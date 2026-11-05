package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Anthropic Messages ↔ OpenAI Chat 双向桥。
 *
 * 为什么需要：Claude Code 这类客户端连的是 /v1/messages，发的是 Anthropic 格式
 * （顶层 system、content 是 block 数组、tools 用 input_schema）。
 * 而绝大多数上游（WorkBuddy / 小浣熊 / DeepSeek / OpenAI 兼容…）只认 OpenAI 格式。
 *
 * 之前这些字段被原样塞给 OpenAI 上游，字段名对不上就被上游忽略 ——
 * system 指令和工具定义**静默丢失**，模型照常回答，只是完全不听话。
 *
 * 现在：请求先转成 OpenAI 走统一管线（这样上下文保护、路由、回退全都正常），
 * 响应再转回 Anthropic 交给客户端。
 */
final class AnthropicBridge {

    private AnthropicBridge() {}

    private static final int DEFAULT_MAX_TOKENS = 4096;

    // References are opaque strings. Never infer an inbound identity from position
    // or coerce null/numeric IDs: there is no safe way to pair a later tool result.
    private static String toolString(JSONObject object, String field) {
        Object value = object.opt(field);
        if (!(value instanceof String) || ((String)value).trim().isEmpty())
            throw new IllegalArgumentException("tool call requires a nonempty string " + field);
        return (String)value;
    }

    private static String toolFragment(JSONObject object, String field) {
        Object value = object == null ? null : object.opt(field);
        if (value == null || value == JSONObject.NULL) return "";
        if (!(value instanceof String)) throw new IllegalArgumentException("tool call " + field + " must be a string");
        return (String)value;
    }

    private static int toolIndex(JSONObject call) {
        Object value = call.opt("index");
        return ToolContract.index(value);
    }

    private static JSONObject toolInput(JSONObject function) {
        Object args = function.opt("arguments");
        if (!(args instanceof String)) throw new IllegalArgumentException("tool call arguments must be a JSON object string for Messages");
        try {
            return ToolContract.input((String)args);
        } catch (Exception invalid) {
            // Parser exceptions may quote argument text; keep diagnostics structural.
            throw new IllegalArgumentException("tool call arguments must be a JSON object string for Messages");
        }
    }

    // ===================== 请求：Anthropic → OpenAI =====================

    static JSONObject requestToOpenAI(JSONObject a) throws Exception {
        JSONObject out = new JSONObject();
        out.put("model", a.optString("model", ""));

        int maxTokens = a.optInt("max_tokens", 0);
        out.put("max_tokens", maxTokens > 0 ? maxTokens : DEFAULT_MAX_TOKENS);

        if (a.has("temperature")) out.put("temperature", a.optDouble("temperature"));
        if (a.has("top_p")) out.put("top_p", a.optDouble("top_p"));
        if (a.has("stream")) out.put("stream", a.optBoolean("stream"));
        JSONArray stop = a.optJSONArray("stop_sequences");
        if (stop != null && stop.length() > 0) out.put("stop", stop);
        if (a.has("metadata")) out.put("metadata", a.opt("metadata"));

        // 推理预算：Anthropic 的 thinking.budget_tokens → OpenAI 的 reasoning_effort
        JSONObject thinking = a.optJSONObject("thinking");
        if (thinking != null && "enabled".equals(thinking.optString("type"))) {
            int budget = thinking.optInt("budget_tokens", 0);
            out.put("reasoning_effort", budget >= 16000 ? "high" : budget >= 8000 ? "medium" : "low");
        }

        JSONArray messages = new JSONArray();

        String sysText = flattenSystem(a.opt("system"));
        if (!sysText.trim().isEmpty()) {
            JSONObject s = new JSONObject();
            s.put("role", "system");
            s.put("content", sysText);
            messages.put(s);
        }

        JSONArray in = a.optJSONArray("messages");
        if (a.has("messages") && in == null) throw new IllegalArgumentException("messages must be an array");
        for (int i = 0; in != null && i < in.length(); i++) {
            JSONObject m = in.optJSONObject(i);
            if (m == null) throw new IllegalArgumentException("messages[" + i + "] must be an object");
            String role = m.optString("role", "");
            if (!"assistant".equals(role) && !"user".equals(role))
                throw new IllegalArgumentException("messages[" + i + "].role must be user or assistant");
            boolean assistant = "assistant".equals(role);
            Object c = m.opt("content");
            if (!(c instanceof String) && !(c instanceof JSONArray))
                throw new IllegalArgumentException("messages[" + i + "].content must be a string or array");
            if (c instanceof JSONArray) {
                JSONArray content = (JSONArray)c;
                for (int j = 0; j < content.length(); j++)
                    if (content.optJSONObject(j) == null)
                        throw new IllegalArgumentException("messages[" + i + "].content[" + j + "] must be an object");
            }
            if (!assistant) {
                addUserContent(messages, c);
                continue;
            }
            JSONObject msg = new JSONObject().put("role", "assistant");
            if (c instanceof String) { msg.put("content", c); messages.put(msg); continue; }
            JSONArray calls = new JSONArray();
            StringBuilder text = new StringBuilder();
            JSONArray blocks = c instanceof JSONArray ? (JSONArray)c : new JSONArray();
            for (int k = 0; k < blocks.length(); k++) {
                JSONObject b = blocks.optJSONObject(k);
                if (b == null) continue;
                if ("text".equals(b.optString("type"))) text.append(b.optString("text", ""));
                if ("tool_use".equals(b.optString("type"))) {
                    String id = toolString(b, "id"), name = toolString(b, "name");
                    JSONObject input = b.optJSONObject("input");
                    if (input == null) throw new IllegalArgumentException("tool_use input must be an object");
                    JSONObject fn = new JSONObject().put("name", name).put("arguments", input.toString());
                    calls.put(new JSONObject().put("id", id).put("type", "function").put("function", fn));
                }
            }
            msg.put("content", text.length() > 0 ? text.toString() : calls.length() > 0 ? JSONObject.NULL : "");
            if (calls.length() > 0) msg.put("tool_calls", calls);
            messages.put(msg);
        }
        messages = pairToolMessages(joinSplitCalls(messages), true);

        out.put("messages", messages);

        // tools：input_schema → parameters
        JSONArray tools = a.optJSONArray("tools");
        if (tools != null && tools.length() > 0) {
            JSONArray ot = new JSONArray();
            for (int i = 0; i < tools.length(); i++) {
                JSONObject t = tools.optJSONObject(i);
                if (t == null) continue;
                JSONObject fn = new JSONObject();
                fn.put("name", t.optString("name", ""));
                fn.put("description", t.optString("description", ""));
                JSONObject schema = t.optJSONObject("input_schema");
                fn.put("parameters", schema == null ? new JSONObject().put("type", "object") : schema);
                JSONObject wrap = new JSONObject();
                wrap.put("type", "function");
                wrap.put("function", fn);
                ot.put(wrap);
            }
            if (ot.length() > 0) out.put("tools", ot);
        }

        Object tc = a.opt("tool_choice");
        if (tc instanceof JSONObject) {
            JSONObject o = (JSONObject) tc;
            String type = o.optString("type", "");
            JSONObject choice = new JSONObject();
            if (o.optBoolean("disable_parallel_tool_use", false)) out.put("parallel_tool_calls", false);
            if ("auto".equals(type)) out.put("tool_choice", "auto");
            else if ("any".equals(type)) out.put("tool_choice", "required");
            else if ("none".equals(type)) out.put("tool_choice", "none");
            else if ("tool".equals(type)) {
                choice.put("type", "function");
                JSONObject fn = new JSONObject();
                fn.put("name", o.optString("name", ""));
                choice.put("function", fn);
            }
            if (choice.has("type")) out.put("tool_choice", choice);
        }

        return out;
    }

    private static void addUserContent(JSONArray messages, Object content) throws Exception {
        if (content instanceof String) { messages.put(new JSONObject().put("role", "user").put("content", content)); return; }
        JSONArray blocks = content instanceof JSONArray ? (JSONArray)content : new JSONArray();
        JSONArray parts = new JSONArray();
        JSONArray results = new JSONArray();
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject b = blocks.optJSONObject(i);
            if (b == null) continue;
            String type = b.optString("type");
            if ("tool_result".equals(type)) {
                JSONObject tool = new JSONObject().put("role", "tool").put("tool_call_id", toolString(b, "tool_use_id"));
                String body = flattenToolResult(b.opt("content"));
                if (b.optBoolean("is_error", false)) body = "[Tool error] " + body;
                JSONArray images = new JSONArray();
                JSONArray result = b.optJSONArray("content");
                for (int j = 0; result != null && j < result.length(); j++) {
                    JSONObject item = result.optJSONObject(j);
                    if (item != null && "image".equals(item.optString("type"))) {
                        JSONObject image = imageBlockToOpenAI(item);
                        if (image == null) throw new IllegalArgumentException("invalid tool result image");
                        images.put(image);
                    }
                }
                if (images.length() > 0) { body += "\n[Images from this tool result follow.]"; tool.put("_images", images); }
                tool.put("content", body); results.put(tool);
            } else if ("text".equals(type)) parts.put(new JSONObject().put("type", "text").put("text", b.optString("text", "")));
            else if ("image".equals(type)) {
                JSONObject image = imageBlockToOpenAI(b);
                if (image == null) throw new IllegalArgumentException("invalid user image");
                parts.put(image);
            }
        }
        for (int i = 0; i < results.length(); i++) messages.put(results.get(i));
        if (parts.length() > 0) messages.put(userParts(parts));
    }

    private static JSONObject userParts(JSONArray parts) throws Exception {
        boolean plain = true; StringBuilder text = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject p = parts.getJSONObject(i);
            if (!"text".equals(p.optString("type"))) plain = false;
            else text.append(p.optString("text", ""));
        }
        return new JSONObject().put("role", "user").put("content", plain ? (Object)text.toString() : parts);
    }

    // Messages treats consecutive assistant messages as one turn once the
    // first contains a tool call; keep earlier text-only turns separate.
    private static JSONArray joinSplitCalls(JSONArray input) throws Exception {
        JSONArray out = new JSONArray();
        for (int i = 0; i < input.length(); i++) {
            JSONObject current = input.getJSONObject(i);
            JSONObject previous = out.length() == 0 ? null : out.getJSONObject(out.length() - 1);
            JSONArray priorCalls = previous == null ? null : previous.optJSONArray("tool_calls");
            if (previous == null || !"assistant".equals(current.optString("role")) ||
                    !"assistant".equals(previous.optString("role")) || priorCalls == null || priorCalls.length() == 0) {
                out.put(current); continue;
            }
            JSONArray more = current.optJSONArray("tool_calls");
            for (int j = 0; more != null && j < more.length(); j++) priorCalls.put(more.get(j));
            Object oldText = previous.opt("content");
            Object newText = current.opt("content");
            String a = oldText instanceof String ? (String)oldText : "";
            String b = newText instanceof String ? (String)newText : "";
            previous.put("content", a.isEmpty() && b.isEmpty() ? JSONObject.NULL : a + b);
        }
        return out;
    }

    /** Normalize direct Chat messages without changing the caller's JSON. Unlike
     * Messages conversion this must not join consecutive assistant messages. */
    static JSONArray normalizeOpenAiMessages(JSONArray input) throws Exception {
        if (input == null) throw new IllegalArgumentException("messages must be an array");
        JSONArray copy = new JSONArray(input.toString());
        for (int i = 0; i < copy.length(); i++)
            if (copy.optJSONObject(i) == null)
                throw new IllegalArgumentException("messages[" + i + "] must be an object");
        return pairToolMessages(copy, false);
    }

    // Pending calls end at the first nonmatching tool or non-tool message.
    // Only the first answer to a pending call is kept.
    private static JSONArray pairToolMessages(JSONArray input, boolean translated) throws Exception {
        JSONArray out = new JSONArray();
        for (int i = 0; i < input.length();) {
            JSONObject m = input.getJSONObject(i++);
            JSONArray calls = m.optJSONArray("tool_calls");
            if (m.has("tool_calls") && !m.isNull("tool_calls") && calls == null)
                throw new IllegalArgumentException("tool_calls must be an array");
            if ("tool".equals(m.optString("role"))) toolString(m, "tool_call_id");
            if (calls == null || calls.length() == 0) {
                if ("tool".equals(m.optString("role"))) appendOrphan(out, m, translated);
                else out.put(m);
                continue;
            }
            out.put(m);
            java.util.LinkedHashMap<String, JSONObject> answers = new java.util.LinkedHashMap<String, JSONObject>();
            for (int k = 0; k < calls.length(); k++) {
                String id = toolString(calls.getJSONObject(k), "id");
                if (answers.containsKey(id)) throw new IllegalArgumentException("duplicate tool_use id: " + id);
                answers.put(id, null);
            }
            while (i < input.length()) {
                JSONObject item = input.getJSONObject(i);
                if (!"tool".equals(item.optString("role"))) break;
                String id = toolString(item, "tool_call_id");
                if (!answers.containsKey(id)) break;
                i++;
                if (answers.get(id) == null) answers.put(id, item);
            }
            JSONArray images = new JSONArray();
            // Upstream emits answered calls in call order, then the missing calls.
            for (int pass = 0; pass < 2; pass++) {
                for (java.util.Map.Entry<String, JSONObject> entry : answers.entrySet()) {
                    JSONObject answer = entry.getValue();
                    if ((answer == null) != (pass == 1)) continue;
                    if (answer == null) answer = new JSONObject().put("role", "tool").put("tool_call_id", entry.getKey())
                            .put("content", "[The result of this tool call is unavailable: the turn was interrupted.]");
                    JSONArray im = translated ? answer.optJSONArray("_images") : null;
                    if (translated) answer.remove("_images");
                    out.put(answer);
                    if (im != null) {
                        images.put(new JSONObject().put("type", "text").put("text", "[From the result of tool call " + entry.getKey() + ":]"));
                        for (int k = 0; k < im.length(); k++) images.put(im.get(k));
                    }
                }
            }
            if (images.length() > 0) out.put(userParts(images));
        }
        return out;
    }

    private static void appendOrphan(JSONArray out, JSONObject tool, boolean translated) throws Exception {
        Object content = tool.opt("content");
        if (!translated && content instanceof JSONArray) {
            out.put(new JSONObject().put("role", "user").put("content", content));
            return;
        }
        String body = tool.optString("content", "");
        if (body.trim().isEmpty()) body = "Tool result received.";
        JSONArray parts = new JSONArray().put(new JSONObject().put("type", "text").put("text", body));
        JSONArray images = translated ? tool.optJSONArray("_images") : null;
        for (int i = 0; images != null && i < images.length(); i++) parts.put(images.get(i));
        out.put(userParts(parts));
    }

    private static String flattenSystem(Object system) {
        if (system == null) return "";
        if (system instanceof String) return (String) system;
        if (system instanceof JSONArray) {
            StringBuilder sb = new StringBuilder();
            JSONArray arr = (JSONArray) system;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject b = arr.optJSONObject(i);
                if (b == null) continue;
                if (!"text".equals(b.optString("type", "text"))) continue;
                String t = b.optString("text", "");
                if (t.trim().isEmpty()) continue;
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(t);
            }
            return sb.toString();
        }
        return String.valueOf(system);
    }

    private static String flattenToolResult(Object content) {
        if (content == null) return "";
        if (content instanceof String) return (String) content;
        if (content instanceof JSONArray) {
            StringBuilder sb = new StringBuilder();
            JSONArray arr = (JSONArray) content;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject b = arr.optJSONObject(i);
                if (b == null) {
                    Object o = arr.opt(i);
                    if (o instanceof String) sb.append(o);
                    continue;
                }
                if ("text".equals(b.optString("type", "text"))) sb.append(b.optString("text", ""));
            }
            return sb.toString();
        }
        return String.valueOf(content);
    }

    private static JSONObject imageBlockToOpenAI(JSONObject block) {
        try {
            JSONObject src = block.optJSONObject("source");
            if (src == null) return null;
            String url;
            if ("base64".equals(src.optString("type"))) {
                String media = src.optString("media_type", "image/png");
                url = "data:" + media + ";base64," + src.optString("data", "");
            } else {
                url = src.optString("url", "");
            }
            if (url.isEmpty()) return null;
            JSONObject imgUrl = new JSONObject();
            imgUrl.put("url", url);
            JSONObject part = new JSONObject();
            part.put("type", "image_url");
            part.put("image_url", imgUrl);
            return part;
        } catch (Exception e) {
            return null;
        }
    }

    // ===================== 响应：OpenAI → Anthropic =====================

    static String stopReasonOf(String finishReason) {
        if (finishReason == null) return "end_turn";
        if ("length".equals(finishReason)) return "max_tokens";
        if ("tool_calls".equals(finishReason) || "function_call".equals(finishReason)) return "tool_use";
        if ("content_filter".equals(finishReason)) return "refusal";
        return "end_turn";
    }

    static JSONObject responseToAnthropic(JSONObject openai, String model) {
        JSONObject out = new JSONObject();
        try {
            String id = openai.optString("id", "msg_" + System.currentTimeMillis());
            out.put("id", id.startsWith("msg_") ? id : "msg_" + id);
            out.put("type", "message");
            out.put("role", "assistant");
            out.put("model", model);

            JSONArray content = new JSONArray();
            String finish = "stop";

            JSONArray choices = openai.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject choice = choices.optJSONObject(0);
                finish = choice.optString("finish_reason", "stop");
                JSONObject msg = choice.optJSONObject("message");
                if (msg != null) {
                    String reasoning = msg.optString("reasoning_content", "");
                    if (!reasoning.isEmpty()) {
                        JSONObject th = new JSONObject();
                        th.put("type", "thinking");
                        th.put("thinking", reasoning);
                        th.put("signature", "");
                        content.put(th);
                    }
                    Object c = msg.opt("content");
                    String text = c instanceof String ? (String) c : "";
                    if (!text.isEmpty()) {
                        JSONObject t = new JSONObject();
                        t.put("type", "text");
                        t.put("text", text);
                        content.put(t);
                    }
                    if (msg.opt("refusal") instanceof String && !msg.optString("refusal").isEmpty()) {
                        JSONObject refusalText = new JSONObject(); refusalText.put("type", "text"); refusalText.put("text", msg.getString("refusal")); content.put(refusalText);
                        if ("stop".equals(finish)) finish = "content_filter";
                    }
                    JSONArray calls = msg.optJSONArray("tool_calls");
                    if (msg.has("tool_calls") && !msg.isNull("tool_calls") && calls == null)
                        throw new IllegalArgumentException("tool_calls must be an array");
                    java.util.Set<String> ids = new java.util.HashSet<String>();
                    for (int i = 0; calls != null && i < calls.length(); i++) {
                        JSONObject call = calls.getJSONObject(i), fn = call.getJSONObject("function");
                        String callId = toolString(call, "id");
                        if (!ids.add(callId)) throw new IllegalArgumentException("duplicate tool call id");
                        JSONObject use = new JSONObject();
                        use.put("type", "tool_use");
                        use.put("id", callId);
                        use.put("name", toolString(fn, "name"));
                        use.put("input", toolInput(fn));
                        content.put(use);
                    }
                }
            }
            out.put("content", content);
            out.put("stop_reason", stopReasonOf(finish));
            out.put("stop_sequence", JSONObject.NULL);

            UsageCounts counts = new UsageCounts();
            counts.merge(openai.optJSONObject("usage"));
            out.put("usage", counts.toAnthropic());
        } catch (Exception e) {
            // Never turn an invalid tool identity/argument into a successful empty
            // message. Do not expose parser exceptions that can contain tool args.
            return errorBody(502, e instanceof IllegalArgumentException ? e.getMessage() : "Invalid upstream tool response");
        }
        return out;
    }

    // OpenAI prompt_tokens includes cached reads and, when supplied by the
    // bridge, cache writes. Keep absent cache fields absent, not invented.
    private static final class UsageCounts {
        long prompt, output, read, write;
        boolean hasRead, hasWrite;

        void merge(JSONObject usage) {
            if (usage == null) return;
            if (usage.has("prompt_tokens") && !usage.isNull("prompt_tokens"))
                prompt = Math.max(0L, usage.optLong("prompt_tokens", 0));
            if (usage.has("completion_tokens") && !usage.isNull("completion_tokens"))
                output = Math.max(0L, usage.optLong("completion_tokens", 0));
            JSONObject details = usage.optJSONObject("prompt_tokens_details");
            if (details == null) return;
            if (details.has("cached_tokens") && !details.isNull("cached_tokens")) {
                hasRead = true; read = Math.max(0L, details.optLong("cached_tokens", 0));
            }
            if (details.has("cache_write_tokens") && !details.isNull("cache_write_tokens")) {
                hasWrite = true; write = Math.max(0L, details.optLong("cache_write_tokens", 0));
            }
        }

        JSONObject toAnthropic() throws Exception {
            // Sequential subtraction prevents overflow even for corrupt counts.
            long input = Math.max(0L, prompt - read);
            input = Math.max(0L, input - write);
            JSONObject out = new JSONObject().put("input_tokens", input).put("output_tokens", output);
            if (hasRead) out.put("cache_read_input_tokens", read);
            if (hasWrite) out.put("cache_creation_input_tokens", write);
            return out;
        }
    }

    /** Anthropic 风格错误体（Claude Code 认这个形状） */
    static JSONObject errorBody(int status, String message) {
        JSONObject out = new JSONObject();
        try {
            String type = status == 401 ? "authentication_error"
                    : status == 403 ? "permission_error"
                    : status == 429 ? "rate_limit_error"
                    : status >= 500 ? "api_error"
                    : status == 400 ? "invalid_request_error"
                    : "api_error";
            JSONObject err = new JSONObject();
            err.put("type", type);
            err.put("message", message);
            out.put("type", "error");
            out.put("error", err);
        } catch (Exception ignored) {}
        return out;
    }

    // ===================== 流式：OpenAI chunk → Anthropic SSE =====================

    /**
     * 把 OpenAI 的 SSE chunk 逐条转成 Anthropic 的 SSE 事件。
     * Anthropic 流的事件序：message_start → (content_block_start → delta* → content_block_stop)*
     * → message_delta → message_stop
     */
    static final class StreamState {
        private final String model;
        private final String messageId;
        private boolean started;
        private int nextIndex = 0;
        private int textIndex = -1;
        private int thinkIndex = -1;
        private int openIndex = -1;          // 当前打开着的块
        private String finish = "";
        private final UsageCounts usageCounts = new UsageCounts();
        private int bufferedBytes;
        private static final int MAX_BUFFERED_CHARS = 1024 * 1024;
        private static final int MAX_BLOCKS = 1024;
        private boolean failed;
        private boolean hasContent;
        private boolean closed;
        // Chat may interleave tools and fragment their ID/name until finish. Hold
        // tool blocks until identity is complete, then emit sequential Messages blocks.
        private final List<DeferredBlock> deferred = new ArrayList<DeferredBlock>();
        private final java.util.Map<Integer, DeferredBlock> tools = new java.util.HashMap<Integer, DeferredBlock>();

        private static final class DeferredBlock {
            int index;
            String type;
            JSONObject start;
            final List<JSONObject> deltas = new ArrayList<JSONObject>();
            String id = "";
            String name = "";
            boolean emitted;
        }

        StreamState(String model) {
            this.model = model;
            this.messageId = "msg_" + System.currentTimeMillis();
        }

        String messageId() { return messageId; }
        boolean isClosed() { return closed; }
        boolean isFailed() { return failed; }
        boolean hasFinishReason() { return !finish.isEmpty(); }

        List<String> onError(String message) {
            List<String> outs = new ArrayList<String>();
            if (closed) return outs;
            closed = true; failed = true;
            deferred.clear(); tools.clear();
            try {
                JSONObject error = new JSONObject().put("type", "error").put("error",
                        new JSONObject().put("type", "api_error").put("message", message));
                outs.add(event("error", error));
            } catch (Exception e) { throw new IllegalStateException(e); }
            return outs;
        }

        private void reserve(int size) {
            if (size < 0 || size > MAX_BUFFERED_CHARS - bufferedBytes)
                throw new IllegalStateException("upstream stream exceeded deferred buffer limit");
            bufferedBytes += size;
        }

        /** 每条上游 chunk → 若干条完整的 SSE 事件文本（含 "event:" 与 "data:"） */
        List<String> onChunk(JSONObject chunk) {
            List<String> outs = new ArrayList<String>();
            if (closed) return outs;
            try {
                JSONObject upstreamError = chunk.optJSONObject("error");
                if (upstreamError != null) return onError(upstreamError.optString("message", "Upstream stream error"));
                usageCounts.merge(chunk.optJSONObject("usage"));
                if (!started) {
                    started = true;
                    JSONObject msg = new JSONObject();
                    msg.put("id", messageId);
                    msg.put("type", "message");
                    msg.put("role", "assistant");
                    msg.put("model", model);
                    msg.put("content", new JSONArray());
                    msg.put("stop_reason", JSONObject.NULL);
                    msg.put("stop_sequence", JSONObject.NULL);
                    JSONObject usage = usageCounts.toAnthropic();
                    usage.put("output_tokens", 0);
                    msg.put("usage", usage);
                    JSONObject data = new JSONObject();
                    data.put("type", "message_start");
                    data.put("message", msg);
                    outs.add(event("message_start", data));
                }

                JSONArray choices = chunk.optJSONArray("choices");
                if (choices != null && choices.length() > 0) {
                    JSONObject choice = null;
                    for (int ci = 0; ci < choices.length(); ci++) {
                        JSONObject candidate = choices.optJSONObject(ci);
                        if (candidate != null && candidate.optInt("index", ci) == 0) { choice = candidate; break; }
                    }
                    if (choice != null) {
                        JSONObject delta = choice.optJSONObject("delta");
                        String fr = choice.optString("finish_reason", "");
                        if (fr != null && !fr.isEmpty() && !"null".equals(fr) && !("content_filter".equals(finish) && "stop".equals(fr))) finish = fr;

                        if (delta != null) {
                            // 注意：Android 自带的 org.json 对 JSONObject.NULL 调 optString 会返回字符串
                            // "null"（桌面版 json.jar 返回 ""）。上游最后一个 chunk 常带 "content": null，
                            // 不判 NULL 就会多出一个内容为 "null" 的文本块。
                            Object rawReason = delta.opt("reasoning_content");
                            String reasoning = (rawReason == null || JSONObject.NULL.equals(rawReason))
                                    ? "" : String.valueOf(rawReason);
                            if (!reasoning.isEmpty()) {
                                hasContent = true;
                                outs.addAll(thinkingDelta(reasoning));
                            }
                            Object rawText = delta.opt("content");
                            String text = (rawText == null || JSONObject.NULL.equals(rawText))
                                    ? "" : String.valueOf(rawText);
                            if (!text.isEmpty()) {
                                hasContent = true;
                                outs.addAll(textDelta(text));
                            }
                            if (delta.opt("refusal") instanceof String && !delta.optString("refusal").isEmpty()) {
                                hasContent = true; finish = "content_filter"; outs.addAll(textDelta(delta.optString("refusal")));
                            }
                            JSONArray calls = delta.optJSONArray("tool_calls");
                            if (delta.has("tool_calls") && !delta.isNull("tool_calls") && calls == null)
                                throw new IllegalArgumentException("tool_calls must be an array");
                            for (int i = 0; calls != null && i < calls.length(); i++) {
                                hasContent = true;
                                outs.addAll(toolDelta(calls.optJSONObject(i)));
                            }
                        }
                    }
                }

                // 有些上游在最后一个 chunk 里给 finish_reason 但不给 [DONE]，
                // 这里不在 chunk 里收尾，交给 onEnd()。
            } catch (Exception e) {
                outs.addAll(onError("Invalid upstream stream: " + e.getMessage()));
            }
            return outs;
        }

        private List<String> thinkingDelta(String text) throws Exception {
            List<String> outs = new ArrayList<String>();
            if (!deferred.isEmpty()) return deferText("thinking", text);
            if (thinkIndex < 0) {
                closeOpen(outs);
                thinkIndex = nextIndex++;
                JSONObject block = new JSONObject();
                block.put("type", "thinking");
                block.put("thinking", "");
                block.put("signature", "");
                JSONObject data = new JSONObject();
                data.put("type", "content_block_start");
                data.put("index", thinkIndex);
                data.put("content_block", block);
                outs.add(event("content_block_start", data));
                openIndex = thinkIndex;
            }
            JSONObject d = new JSONObject();
            d.put("type", "thinking_delta");
            d.put("thinking", text);
            JSONObject data = new JSONObject();
            data.put("type", "content_block_delta");
            data.put("index", thinkIndex);
            data.put("delta", d);
            outs.add(event("content_block_delta", data));
            return outs;
        }

        private List<String> textDelta(String text) throws Exception {
            List<String> outs = new ArrayList<String>();
            if (!deferred.isEmpty()) return deferText("text", text);
            if (textIndex < 0) {
                closeOpen(outs);
                textIndex = nextIndex++;
                JSONObject block = new JSONObject();
                block.put("type", "text");
                block.put("text", "");
                JSONObject data = new JSONObject();
                data.put("type", "content_block_start");
                data.put("index", textIndex);
                data.put("content_block", block);
                outs.add(event("content_block_start", data));
                openIndex = textIndex;
            }
            JSONObject d = new JSONObject();
            d.put("type", "text_delta");
            d.put("text", text);
            JSONObject data = new JSONObject();
            data.put("type", "content_block_delta");
            data.put("index", textIndex);
            data.put("delta", d);
            outs.add(event("content_block_delta", data));
            return outs;
        }

        private List<String> deferText(String type, String text) throws Exception {
            DeferredBlock block = deferred.get(deferred.size() - 1);
            if (!type.equals(block.type)) {
                if (deferred.size() >= MAX_BLOCKS) throw new IllegalStateException("too many deferred blocks");
                block = new DeferredBlock();
                block.index = nextIndex++;
                block.type = type;
                block.start = new JSONObject().put("type", type).put(type, "");
                if ("thinking".equals(type)) block.start.put("signature", "");
                deferred.add(block);
            }
            reserve(text.length() + 128);
            block.deltas.add(new JSONObject().put("type", type + "_delta").put(type, text));
            return new ArrayList<String>();
        }

        private List<String> toolDelta(JSONObject call) throws Exception {
            List<String> outs = new ArrayList<String>();
            if (call == null) throw new IllegalStateException("invalid tool call delta");
            int idx = toolIndex(call);
            DeferredBlock block = tools.get(Integer.valueOf(idx));
            JSONObject fn = call.optJSONObject("function");
            if (call.has("function") && !call.isNull("function") && fn == null)
                throw new IllegalArgumentException("tool call function must be an object");
            if (block == null) {
                if (deferred.size() >= MAX_BLOCKS) throw new IllegalStateException("too many deferred blocks");
                closeOpen(outs);
                block = new DeferredBlock(); block.index = nextIndex++; block.type = "tool_use";
                tools.put(Integer.valueOf(idx), block); deferred.add(block);
            }
            String id = toolFragment(call, "id");
            String name = toolFragment(fn, "name");
            reserve(id.length() + name.length());
            if (!id.isEmpty()) block.id += id;
            if (!name.isEmpty()) block.name += name;
            String args = toolFragment(fn, "arguments");
            if (!args.isEmpty()) {
                reserve(args.length() + 128);
                block.deltas.add(new JSONObject().put("type", "input_json_delta").put("partial_json", args));
            }
            return outs;
        }

        private static String blockEvent(String type, int index, String key, JSONObject value) throws Exception {
            JSONObject data = new JSONObject().put("type", type).put("index", index);
            if (key != null) data.put(key, value);
            return event(type, data);
        }

        private void closeOpen(List<String> outs) throws Exception {
            if (openIndex < 0) return;
            JSONObject data = new JSONObject();
            data.put("type", "content_block_stop");
            data.put("index", openIndex);
            outs.add(event("content_block_stop", data));
            if (openIndex == textIndex) textIndex = -1;
            if (openIndex == thinkIndex) thinkIndex = -1;
            openIndex = -1;
        }

        /** 收尾：关块 + message_delta + message_stop */
        List<String> onEnd() {
            List<String> outs = new ArrayList<String>();
            if (closed) return outs;
            try {
                if (!started || finish.isEmpty()) return onError("Upstream stream ended before a finish reason");
                if (!hasContent) return onError("Upstream stream returned an empty response");
                java.util.Set<String> ids = new java.util.HashSet<String>();
                for (DeferredBlock block : deferred) {
                    if (!"tool_use".equals(block.type)) continue;
                    if (block.id.trim().isEmpty() || block.name.trim().isEmpty())
                        return onError("Upstream tool call is missing an id or name");
                    if (!ids.add(block.id)) return onError("Upstream tool calls have duplicate ids");
                    // Messages requires an object input. Validate the assembled
                    // string without rewriting the original argument fragments.
                    // Missing/partial/non-object args must not become implicit {}.
                    StringBuilder args = new StringBuilder();
                    for (JSONObject delta : block.deltas) args.append(delta.getString("partial_json"));
                    toolInput(new JSONObject().put("arguments", args.toString()));
                }
                closeOpen(outs);
                for (int i = 0; i < deferred.size(); i++) {
                    DeferredBlock block = deferred.get(i);
                    if ("tool_use".equals(block.type) && block.start == null)
                        block.start = new JSONObject().put("type", "tool_use").put("id", block.id).put("name", block.name).put("input", new JSONObject());
                    if (!block.emitted) outs.add(blockEvent("content_block_start", block.index, "content_block", block.start));
                    for (JSONObject delta : block.deltas) {
                        outs.add(blockEvent("content_block_delta", block.index, "delta", delta));
                    }
                    outs.add(blockEvent("content_block_stop", block.index, null, null));
                }
                deferred.clear();
                tools.clear();
                JSONObject d = new JSONObject();
                d.put("type", "message_delta");
                JSONObject delta = new JSONObject();
                delta.put("stop_reason", stopReasonOf(finish));
                delta.put("stop_sequence", JSONObject.NULL);
                d.put("delta", delta);
                d.put("usage", usageCounts.toAnthropic());
                outs.add(event("message_delta", d));

                JSONObject stop = new JSONObject();
                stop.put("type", "message_stop");
                outs.add(event("message_stop", stop));
                closed = true;
            } catch (Exception e) {
                outs.addAll(onError("Invalid upstream stream: " + e.getMessage()));
            }
            return outs;
        }

        private static String event(String name, JSONObject data) {
            return "event: " + name + "\ndata: " + data.toString() + "\n\n";
        }
    }
}
