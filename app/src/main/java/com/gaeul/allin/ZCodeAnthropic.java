package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OpenAI Chat ↔ Anthropic Messages 的最小必要转换。
 *
 * 为什么需要：ZCode 的赠送额度只有 Anthropic Messages 端点（/api/v1/zcode-plan/anthropic），
 * 而 Cursor / OpenAI SDK 这类客户端发的是 chat.completions 形态。
 * Claude Code 走 /v1/messages 是原生 Anthropic，不需要经过这里。
 *
 * 只做「能正确表达语义」的映射，不做无损往返：
 *  - system 消息与外层 system 合并成一个顶层 system 字符串
 *  - assistant.tool_calls → tool_use block；role:tool → user 消息里的 tool_result block
 *  - tools[].function.parameters → tools[].input_schema
 *  - image_url(data:/http) 原样转成 image block（base64 拆分 media_type）
 */
final class ZCodeAnthropic {

    private ZCodeAnthropic() {}

    private static final int DEFAULT_MAX_TOKENS = 16384;

    // ===================== OpenAI → Anthropic =====================

    static JSONObject toAnthropic(JSONObject openai) throws Exception {
        return toAnthropic(openai, false);
    }

    static JSONObject toAnthropic(JSONObject openai, boolean autoThinking) throws Exception {
        return toAnthropic(openai, autoThinking, AnthropicReasoningPolicy.Provider.ZCODE);
    }

    static JSONObject toAnthropic(JSONObject openai, boolean autoThinking,
                                  AnthropicReasoningPolicy.Provider reasoningProvider) throws Exception {
        return toAnthropic(openai, autoThinking, reasoningProvider, null);
    }

    static JSONObject toAnthropic(JSONObject openai, boolean autoThinking,
                                  AnthropicReasoningPolicy.Provider reasoningProvider,
                                  AnthropicReasoningPolicy.ModelCapabilities capabilities) throws Exception {
        return toAnthropic(openai, autoThinking, reasoningProvider, capabilities, 0, 0);
    }

    static JSONObject toAnthropic(JSONObject openai, boolean autoThinking,
                                  AnthropicReasoningPolicy.Provider reasoningProvider,
                                  AnthropicReasoningPolicy.ModelCapabilities capabilities,
                                  int catalogOutputDefault, int guardOutputCap) throws Exception {
        return convert(openai, autoThinking, reasoningProvider, capabilities,
                catalogOutputDefault, guardOutputCap, true);
    }

    /**
     * Chat → Anthropic 结构投影，供其他厂商的 Claude 端点使用（WorkBuddy 国内原生 Messages）。
     * 这里不套用 ZCode/MiniMax 的推理策略：客户端的 reasoning_effort 由调用方按自己的厂商语义处理，
     * Claude 模型名也不是 ZCode 的插件 variant，套用会得到错误预算或误拒。
     */
    static JSONObject toAnthropicStructural(JSONObject openai) throws Exception {
        return toAnthropicStructural(openai, 0);
    }

    /**
     * catalogOutputDefault is the model's own output limit from the vendor catalog; when the client
     * names none, that (not the 16384 translation fallback) is what the request asks for, as the
     * vendor's own client does.
     */
    static JSONObject toAnthropicStructural(JSONObject openai, int catalogOutputDefault) throws Exception {
        return convert(openai, false, AnthropicReasoningPolicy.Provider.ZCODE, null, catalogOutputDefault, 0, false);
    }

    private static JSONObject convert(JSONObject openai, boolean autoThinking,
                                  AnthropicReasoningPolicy.Provider reasoningProvider,
                                  AnthropicReasoningPolicy.ModelCapabilities capabilities,
                                  int catalogOutputDefault, int guardOutputCap,
                                  boolean applyReasoningPolicy) throws Exception {
        JSONObject out = new JSONObject();
        out.put("model", openai.optString("model", ""));
        AnthropicReasoningPolicy.Provider provider = reasoningProvider == null
                ? AnthropicReasoningPolicy.Provider.ZCODE : reasoningProvider;

        int maxTokens = openai.optInt("max_completion_tokens", 0);
        int legacyMaxTokens = openai.optInt("max_tokens", 0);
        if (maxTokens <= 0) maxTokens = legacyMaxTokens;
        else if (legacyMaxTokens > 0) maxTokens = Math.min(maxTokens, legacyMaxTokens);
        int effectiveLimit = AnthropicReasoningPolicy.effectiveOutputLimit(
                provider, openai.optString("model", ""), maxTokens, catalogOutputDefault, guardOutputCap);
        // A limit from the client, the catalog or Guard is final; only the translation fallback
        // may be raised so that a reasoning budget keeps room for the answer.
        boolean outputLimitKnown = effectiveLimit > 0;
        out.put("max_tokens", effectiveLimit > 0 ? effectiveLimit : DEFAULT_MAX_TOKENS);

        if (openai.has("temperature")) out.put("temperature", openai.optDouble("temperature"));
        if (openai.has("top_p")) out.put("top_p", openai.optDouble("top_p"));
        if (openai.has("stream")) out.put("stream", openai.optBoolean("stream"));

        Object stop = openai.opt("stop");
        if (stop instanceof String) {
            JSONArray sa = new JSONArray();
            sa.put(stop);
            out.put("stop_sequences", sa);
        } else if (stop instanceof JSONArray) {
            out.put("stop_sequences", stop);
        }

        // system：所有 role=system 的内容合并
        StringBuilder system = new StringBuilder();
        JSONArray msgs = openai.optJSONArray("messages");
        for (int i = 0; msgs != null && i < msgs.length(); i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null) continue;
            if (!"system".equals(m.optString("role"))) continue;
            Object sys=m.opt("content");
            if (sys instanceof JSONArray) { JSONArray parts=(JSONArray)sys; for(int k=0;k<parts.length();k++) {JSONObject part=parts.optJSONObject(k); if(part==null || !"text".equals(part.optString("type"))) throw new IllegalArgumentException("Unsupported system content"); ImageContent.keys(part,"type","text"); if(!(part.opt("text") instanceof String)) throw new IllegalArgumentException("Invalid system text");} }
            else if(!(sys instanceof String)) throw new IllegalArgumentException("Invalid system content");
            String t = flattenContent(sys);
            if (t.trim().isEmpty()) continue;
            if (system.length() > 0) system.append("\n\n");
            system.append(t);
        }
        if (system.length() > 0) out.put("system", system.toString());

        JSONArray conv = new JSONArray();
        for (int i = 0; msgs != null && i < msgs.length(); i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role", "user");
            if ("system".equals(role)) continue;

            if ("tool".equals(role)) {
                // 工具结果必须落在 user 消息里；同一 turn 的多个结果合并进同一条
                JSONObject block = new JSONObject();
                block.put("type", "tool_result");
                if (!(m.opt("tool_call_id") instanceof String) || m.optString("tool_call_id").isEmpty()) throw new IllegalArgumentException("Tool call ID required");
                ImageContent.keys(m, "role", "tool_call_id", "content", "is_error");
                block.put("tool_use_id", m.optString("tool_call_id"));
                Object toolContent=m.opt("content");
                if(toolContent instanceof String) block.put("content",toolContent);
                else if(toolContent instanceof JSONArray) block.put("content",convertParts((JSONArray)toolContent));
                else throw new IllegalArgumentException("Invalid tool content");
                if(m.has("is_error") && !(m.opt("is_error") instanceof Boolean)) throw new IllegalArgumentException("Invalid tool is_error");
                if (m.has("is_error") && !m.isNull("is_error")) block.put("is_error", m.optBoolean("is_error"));

                JSONObject last = conv.length() > 0 ? conv.optJSONObject(conv.length() - 1) : null;
                if (last != null && "user".equals(last.optString("role")) && last.optBoolean("_toolResults")) {
                    last.optJSONArray("content").put(block);
                } else {
                    JSONArray content = new JSONArray();
                    content.put(block);
                    JSONObject um = new JSONObject();
                    um.put("role", "user");
                    um.put("content", content);
                    um.put("_toolResults", true);
                    conv.put(um);
                }
                continue;
            }

            JSONObject am = new JSONObject();
            am.put("role", "assistant".equals(role) ? "assistant" : "user");
            JSONArray content = new JSONArray();

            Object c = m.opt("content");
            if("assistant".equals(role) && c instanceof JSONArray) { JSONArray parts=(JSONArray)c; for(int k=0;k<parts.length();k++) {JSONObject part=parts.optJSONObject(k); if(part!=null && "image_url".equals(part.optString("type"))) throw new IllegalArgumentException("Assistant image cannot be represented by Anthropic");} }
            if (c instanceof String) {
                String s = (String) c;
                if (!s.isEmpty()) {
                    JSONObject t = new JSONObject();
                    t.put("type", "text");
                    t.put("text", s);
                    content.put(t);
                }
            } else if (c instanceof JSONArray) {
                JSONArray arr = (JSONArray) c;
                JSONArray converted=convertParts(arr);
                for(int k=0;k<converted.length();k++) content.put(converted.get(k));
            }

            JSONArray calls = m.optJSONArray("tool_calls");
            for (int k = 0; calls != null && k < calls.length(); k++) {
                JSONObject call = calls.optJSONObject(k);
                if (call == null) continue;
                JSONObject fn = call.optJSONObject("function");
                if (fn == null) continue;
                JSONObject use = new JSONObject();
                use.put("type", "tool_use");
                use.put("id", call.optString("id", "call_" + k));
                use.put("name", fn.optString("name", ""));
                use.put("input", parseArgs(fn.optString("arguments", "{}")));
                content.put(use);
            }

            if (content.length() == 0) {
                JSONObject t = new JSONObject();
                t.put("type", "text");
                t.put("text", "");
                content.put(t);
            }
            am.put("content", content);
            conv.put(am);
        }

        // Anthropic 要求 user 起头；纯 assistant 起头的对话前面补一个空的 user
        if (conv.length() == 0 || !"user".equals(conv.optJSONObject(0).optString("role"))) {
            JSONArray c0 = new JSONArray();
            JSONObject t = new JSONObject();
            t.put("type", "text");
            t.put("text", "(continue)");
            c0.put(t);
            JSONObject um = new JSONObject();
            um.put("role", "user");
            um.put("content", c0);
            JSONArray shifted = new JSONArray();
            shifted.put(um);
            for (int i = 0; i < conv.length(); i++) shifted.put(conv.opt(i));
            conv = shifted;
        }
        for (int i = 0; i < conv.length(); i++) conv.optJSONObject(i).remove("_toolResults");
        out.put("messages", conv);

        // tools
        JSONArray tools = openai.optJSONArray("tools");
        if (tools != null && tools.length() > 0) {
            JSONArray at = new JSONArray();
            for (int i = 0; i < tools.length(); i++) {
                JSONObject t = tools.optJSONObject(i);
                if (t == null) continue;
                JSONObject fn = t.optJSONObject("function");
                if (fn == null) continue;
                JSONObject at1 = new JSONObject();
                at1.put("name", fn.optString("name", ""));
                at1.put("description", fn.optString("description", ""));
                JSONObject params = fn.optJSONObject("parameters");
                at1.put("input_schema", params == null ? new JSONObject().put("type", "object") : params);
                at.put(at1);
            }
            if (at.length() > 0) out.put("tools", at);
        }

        Object tc = openai.opt("tool_choice");
        if (tc instanceof String) {
            String s = (String) tc;
            JSONObject choice = new JSONObject();
            if ("auto".equals(s)) choice.put("type", "auto");
            else if ("required".equals(s)) choice.put("type", "any");
            else if ("none".equals(s)) choice.put("type", "none");
            if (choice.has("type")) out.put("tool_choice", choice);
        } else if (tc instanceof JSONObject) {
            JSONObject o = (JSONObject) tc;
            JSONObject fn = o.optJSONObject("function");
            if (fn != null) {
                JSONObject choice = new JSONObject();
                choice.put("type", "tool");
                choice.put("name", fn.optString("name", ""));
                out.put("tool_choice", choice);
            }
        }

        if (out.has("tools") && openai.has("parallel_tool_calls")
                && !openai.isNull("parallel_tool_calls") && !openai.optBoolean("parallel_tool_calls", true)) {
            JSONObject choice = out.optJSONObject("tool_choice");
            if (choice == null) {
                choice = new JSONObject().put("type", "auto");
                out.put("tool_choice", choice);
            }
            if (!"none".equals(choice.optString("type"))) choice.put("disable_parallel_tool_use", true);
        }

        if (applyReasoningPolicy) {
            // Preserve explicit Anthropic controls; policy validates them against the final output cap.
            AnthropicReasoningPolicy.apply(openai, out, provider, capabilities, outputLimitKnown, guardOutputCap);
            if (autoThinking && !openai.has("reasoning_effort") && !openai.has("thinking")) {
                AnthropicReasoningPolicy.ModelCapabilities effectiveCapabilities = capabilities;
                if (provider == AnthropicReasoningPolicy.Provider.MINIMAX && effectiveCapabilities == null)
                    effectiveCapabilities = AnthropicReasoningPolicy.ModelCapabilities.miniMax(
                            Collections.<String>emptySet(), false, false);
                AnthropicReasoningPolicy.applyAutomatic(openai, out, provider, effectiveCapabilities);
            }
        }
        AnthropicReasoningPolicy.validateOutputCompatibility(out);
        return out;
    }

    private static JSONArray convertParts(JSONArray arr) throws Exception {
        JSONArray result=new JSONArray();
        for(int i=0;i<arr.length();i++) {
            JSONObject part=arr.optJSONObject(i); if(part==null) throw new IllegalArgumentException("Invalid content part");
            String type=part.optString("type");
            if("text".equals(type)) {ImageContent.keys(part,"type","text"); if(!(part.opt("text") instanceof String)) throw new IllegalArgumentException("Text content required"); result.put(new JSONObject().put("type","text").put("text",part.getString("text")));}
            else if("image_url".equals(type)) result.put(ImageContent.toAnthropic(part));
            else throw new IllegalArgumentException("Unsupported content part: "+type);
        }
        return result;
    }

    private static JSONObject parseArgs(String args) {
        try {
            if (args == null || args.trim().isEmpty()) return new JSONObject();
            return new JSONObject(args);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String flattenContent(Object content) {
        if (content == null) return "";
        if (content instanceof String) return (String) content;
        if (content instanceof JSONArray) {
            StringBuilder sb = new StringBuilder();
            JSONArray arr = (JSONArray) content;
            for (int i = 0; i < arr.length(); i++) {
                Object o = arr.opt(i);
                if (o instanceof String) sb.append(o);
                else if (o instanceof JSONObject) sb.append(((JSONObject) o).optString("text", ""));
            }
            return sb.toString();
        }
        return String.valueOf(content);
    }

    // ===================== Anthropic → OpenAI =====================

    /** Upstream stopped because the model context window was exhausted, not because the answer ended. */
    private static final String STOP_CONTEXT_WINDOW = "model_context_window_exceeded";
    /** Upstream paused a long-running turn (server-side tool loop); Chat has no equivalent. */
    private static final String STOP_PAUSE_TURN = "pause_turn";

    /**
     * A paused turn has no Chat projection at all, so the Chat conversion must fail loudly rather than
     * pick a lossy answer. stop would call an unfinished turn complete (silent success) and, when the
     * paused message also carries tool_use blocks, would place tool_calls beside a stop finish - the
     * client cannot then tell whether to run those tools or treat the answer as final. tool_calls is
     * just as false: the upstream never asked for those tools to run, it asked to continue the turn.
     */
    static final String PAUSE_TURN_UNSUPPORTED =
            "Anthropic stop_reason pause_turn cannot be represented in a Chat completion; use the native Messages endpoint";

    /**
     * Native Messages clients get the upstream stop_reason verbatim (see NativeAnthropicStream), so the
     * only job here is to prove the value is a stop reason Anthropic defines. The Chat projection losses
     * are decided in finishReason and must not be applied to the native stream: pause_turn stays
     * pause_turn there, even though the Chat projection refuses it.
     */
    static void validateStopReason(JSONObject message) {
        Object reason = message == null ? null : message.opt("stop_reason");
        if (!(reason instanceof String)) throw new IllegalArgumentException("Invalid or missing Anthropic stop_reason");
        if (!isAnthropicStopReason((String) reason))
            throw new IllegalArgumentException("Invalid or missing Anthropic stop_reason: " + reason);
    }

    private static boolean isAnthropicStopReason(String stop) {
        return "end_turn".equals(stop) || "stop_sequence".equals(stop) || "refusal".equals(stop)
                || "max_tokens".equals(stop) || "tool_use".equals(stop)
                || STOP_CONTEXT_WINDOW.equals(stop) || STOP_PAUSE_TURN.equals(stop);
    }

    private static String finishReason(String stop) {
        if ("end_turn".equals(stop) || "stop_sequence".equals(stop)) return "stop";
        if ("refusal".equals(stop)) return "content_filter";
        if ("max_tokens".equals(stop)) return "length";
        if ("tool_use".equals(stop)) return "tool_calls";
        // Chat has no "context window exhausted" finish reason. It is a truncation of the answer, so
        // length is the honest projection: claiming stop would hide that the turn was cut off.
        if (STOP_CONTEXT_WINDOW.equals(stop)) return "length";
        // A paused turn is neither a completed answer nor a completed tool request: the model stopped
        // mid turn and the upstream expects the same turn to be continued. Chat cannot express a pause,
        // so both available answers would be fabricated (stop = silent success, tool_calls = tools the
        // upstream never asked for). Fail explicitly and leave the choice to the caller.
        if (STOP_PAUSE_TURN.equals(stop)) throw new IllegalArgumentException(PAUSE_TURN_UNSUPPORTED);
        throw new IllegalArgumentException("Invalid or missing Anthropic stop_reason: " + stop);
    }

    static JSONObject toOpenAI(JSONObject msg, String requestedModel) {
        // Resolved before anything is built: a stop reason Chat cannot represent must abort the whole
        // conversion (the callers turn the throw into an error response) instead of surfacing a
        // well-formed looking 200 whose body only carries an "error" field.
        String finish = finishReason(msg.isNull("stop_reason") ? "" : msg.optString("stop_reason", ""));
        JSONObject out = new JSONObject();
        try {
            out.put("id", msg.optString("id", "chatcmpl-" + System.currentTimeMillis()));
            out.put("object", "chat.completion");
            out.put("created", System.currentTimeMillis() / 1000L);
            out.put("model", requestedModel);

            StringBuilder text = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            JSONArray toolCalls = new JSONArray();

            JSONArray content = msg.optJSONArray("content");
            for (int i = 0; content != null && i < content.length(); i++) {
                JSONObject c = content.optJSONObject(i);
                if (c == null) continue;
                String type = c.optString("type");
                if ("text".equals(type)) {
                    text.append(c.optString("text", ""));
                } else if ("thinking".equals(type)) {
                    reasoning.append(c.optString("thinking", ""));
                } else if ("tool_use".equals(type)) {
                    JSONObject call = new JSONObject();
                    call.put("id", c.optString("id", ""));
                    call.put("type", "function");
                    JSONObject fn = new JSONObject();
                    fn.put("name", c.optString("name", ""));
                    JSONObject input = c.optJSONObject("input");
                    fn.put("arguments", input == null ? "{}" : input.toString());
                    call.put("function", fn);
                    toolCalls.put(call);
                }
            }

            JSONObject message = new JSONObject();
            message.put("role", "assistant");
            message.put("content", text.length() == 0 && toolCalls.length() > 0 ? JSONObject.NULL : text.toString());
            if (reasoning.length() > 0) message.put("reasoning_content", reasoning.toString());
            if (toolCalls.length() > 0) message.put("tool_calls", toolCalls);

            JSONArray choices = new JSONArray();
            JSONObject ch = new JSONObject();
            ch.put("index", 0);
            ch.put("message", message);
            ch.put("finish_reason", finish);
            choices.put(ch);
            out.put("choices", choices);

            JSONObject usage = msg.optJSONObject("usage");
            if (usage != null) {
                JSONObject convertedUsage = openAIUsage(usage);
                if (convertedUsage.length() > 0) out.put("usage", convertedUsage);
            }
        } catch (Exception e) {
            try { out.put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) {}
        }
        return out;
    }

    // Anthropic input_tokens excludes cache reads and writes; OpenAI prompt_tokens includes both.
    private static JSONObject openAIUsage(JSONObject usage) throws Exception {
        boolean hasInput = usage.has("input_tokens") && !usage.isNull("input_tokens");
        boolean hasOutput = usage.has("output_tokens") && !usage.isNull("output_tokens");
        boolean hasRead = usage.has("cache_read_input_tokens") && !usage.isNull("cache_read_input_tokens");
        boolean hasWrite = usage.has("cache_creation_input_tokens") && !usage.isNull("cache_creation_input_tokens");
        long input = hasInput ? usage.optLong("input_tokens", 0) : 0;
        long read = hasRead ? usage.optLong("cache_read_input_tokens", 0) : 0;
        long write = hasWrite ? usage.optLong("cache_creation_input_tokens", 0) : 0;
        long output = hasOutput ? usage.optLong("output_tokens", 0) : 0;
        JSONObject u = new JSONObject();
        if (hasInput) u.put("prompt_tokens", input + read + write);
        if (hasOutput) u.put("completion_tokens", output);
        if (hasInput && hasOutput) u.put("total_tokens", input + read + write + output);
        if (hasRead || hasWrite) {
            JSONObject details = new JSONObject();
            if (hasRead) details.put("cached_tokens", read);
            if (hasWrite) details.put("cache_write_tokens", write);
            u.put("prompt_tokens_details", details);
        }
        return u;
    }

    private static void mergeUsage(JSONObject target, JSONObject source) throws Exception {
        if (source == null) return;
        String[] keys = {"input_tokens", "cache_read_input_tokens", "cache_creation_input_tokens", "output_tokens"};
        for (String key : keys) if (source.has(key) && !source.isNull(key)) target.put(key, source.optLong(key, 0));
    }

    // ===================== 流式状态机 =====================

    /** 把 Anthropic 的 SSE 事件逐条转成 OpenAI 的 chunk；返回 null 表示这条事件不产生输出 */
    static final class StreamState {
        private final String id;
        private final String model;
        private final long created;
        private int toolIndex = -1;
        private final List<Integer> openToolBlocks = new ArrayList<Integer>();
        private final JSONObject usage = new JSONObject();
        private boolean started;
        private final boolean thinkingOnlyTruncationCompat;
        private boolean hasThinking, hasText, hasToolOrRefusal;

        StreamState(String model) { this(model, false); }

        StreamState(String model, boolean thinkingOnlyTruncationCompat) {
            this.thinkingOnlyTruncationCompat = thinkingOnlyTruncationCompat;
            this.id = "chatcmpl-" + System.currentTimeMillis();
            this.model = model;
            this.created = System.currentTimeMillis() / 1000L;
        }

        /** 一条 Anthropic 事件 → 一到多条 OpenAI data 行（不含 "data: " 前缀与空行） */
        List<String> onEvent(String eventName, JSONObject data) {
            List<String> outs = new ArrayList<String>();
            String eventType = data == null ? "" : data.optString("type", eventName);
            JSONObject stopDelta = data == null ? null : data.optJSONObject("delta");
            String finish = null;
            String rawStop = null;
            // Deliberately resolved before the event is processed and outside the catch below: a stop
            // reason Chat cannot represent (pause_turn) aborts the stream here, so no finish chunk is
            // ever emitted for it and the SSE layer reports the failure instead of a fake completion.
            if ("message_delta".equals(eventType) && stopDelta != null
                    && stopDelta.has("stop_reason") && !stopDelta.isNull("stop_reason")) {
                rawStop = stopDelta.optString("stop_reason", "");
                finish = finishReason(rawStop);
            }
            try {
                String type = data == null ? "" : data.optString("type", eventName);
                if ("message_start".equals(type) && data != null) {
                    JSONObject message = data.optJSONObject("message");
                    if (message != null) mergeUsage(usage, message.optJSONObject("usage"));
                } else if ("message_delta".equals(type) && data != null) {
                    mergeUsage(usage, data.optJSONObject("usage"));
                }
                boolean wasStarted = started;
                if (!started) {
                    started = true;
                    JSONObject chunk = base();
                    JSONArray choices = new JSONArray();
                    JSONObject ch = new JSONObject();
                    ch.put("index", 0);
                    JSONObject delta = new JSONObject();
                    delta.put("role", "assistant");
                    delta.put("content", "");
                    ch.put("delta", delta);
                    ch.put("finish_reason", JSONObject.NULL);
                    choices.put(ch);
                    chunk.put("choices", choices);
                    if (usage.length() > 0) chunk.put("usage", openAIUsage(usage));
                    outs.add(chunk.toString());
                }
                if (data == null) return outs;
                if (wasStarted && "message_start".equals(type) && usage.length() > 0) {
                    JSONObject chunk = base();
                    chunk.put("choices", new JSONArray());
                    chunk.put("usage", openAIUsage(usage));
                    outs.add(chunk.toString());
                }

                if ("content_block_start".equals(type)) {
                    JSONObject block = data.optJSONObject("content_block");
                    if (block != null) {
                        String kind = block.optString("type", "");
                        if ("text".equals(kind)) hasText |= !block.optString("text", "").isEmpty();
                        else if (!"thinking".equals(kind)) hasToolOrRefusal = true;
                    }
                    if (block != null && "tool_use".equals(block.optString("type"))) {
                        toolIndex++;
                        openToolBlocks.add(Integer.valueOf(data.optInt("index", 0)));
                        JSONObject chunk = base();
                        JSONArray choices = new JSONArray();
                        JSONObject ch = new JSONObject();
                        ch.put("index", 0);
                        JSONObject delta = new JSONObject();
                        JSONArray calls = new JSONArray();
                        JSONObject call = new JSONObject();
                        call.put("index", toolIndex);
                        call.put("id", block.optString("id", ""));
                        call.put("type", "function");
                        JSONObject fn = new JSONObject();
                        fn.put("name", block.optString("name", ""));
                        fn.put("arguments", "");
                        call.put("function", fn);
                        calls.put(call);
                        delta.put("tool_calls", calls);
                        ch.put("delta", delta);
                        ch.put("finish_reason", JSONObject.NULL);
                        choices.put(ch);
                        chunk.put("choices", choices);
                        outs.add(chunk.toString());
                    }
                } else if ("content_block_delta".equals(type)) {
                    JSONObject d = data.optJSONObject("delta");
                    if (d == null) return outs;
                    String dt = d.optString("type");
                    if ("text_delta".equals(dt)) {
                        String text = d.optString("text", "");
                        if (!text.isEmpty()) hasText = true;
                        outs.add(deltaChunk("content", text));
                    } else if ("thinking_delta".equals(dt)) {
                        String thinking = d.optString("thinking", "");
                        if (!thinking.isEmpty()) hasThinking = true;
                        outs.add(deltaChunk("reasoning_content", thinking));
                    } else if ("input_json_delta".equals(dt)) {
                        hasToolOrRefusal = true;
                        JSONObject chunk = base();
                        JSONArray choices = new JSONArray();
                        JSONObject ch = new JSONObject();
                        ch.put("index", 0);
                        JSONObject delta = new JSONObject();
                        JSONArray calls = new JSONArray();
                        JSONObject call = new JSONObject();
                        int callIndex = openToolBlocks.indexOf(Integer.valueOf(data.optInt("index", 0)));
                        if (callIndex < 0) return outs;
                        call.put("index", callIndex);
                        JSONObject fn = new JSONObject();
                        fn.put("arguments", d.optString("partial_json", ""));
                        call.put("function", fn);
                        calls.put(call);
                        delta.put("tool_calls", calls);
                        ch.put("delta", delta);
                        ch.put("finish_reason", JSONObject.NULL);
                        choices.put(ch);
                        chunk.put("choices", choices);
                        outs.add(chunk.toString());
                    } else if (!"signature_delta".equals(dt)) {
                        hasToolOrRefusal = true;
                    }
                } else if ("message_delta".equals(type)) {
                    JSONObject d = data.optJSONObject("delta");
                    if (finish == null) {
                        if (usage.length() > 0) {
                            JSONObject chunk = base();
                            chunk.put("choices", new JSONArray());
                            chunk.put("usage", openAIUsage(usage));
                            outs.add(chunk.toString());
                        }
                        return outs;
                    }
                    // The thinking-only compat guess is only defensible for a real max_tokens cut: a
                    // context-window overflow must stay visible as length instead of being masked to stop.
                    if (thinkingOnlyTruncationCompat && "max_tokens".equals(rawStop)
                            && hasThinking && !hasText && !hasToolOrRefusal) {
                        outs.add(deltaChunk("content", ThinkingTruncationStream.NOTICE));
                        finish = "stop";
                    }
                    JSONObject chunk = base();
                    JSONArray choices = new JSONArray();
                    JSONObject ch = new JSONObject();
                    ch.put("index", 0);
                    ch.put("delta", new JSONObject());
                    ch.put("finish_reason", finish);
                    choices.put(ch);
                    chunk.put("choices", choices);
                    if (usage.length() > 0) chunk.put("usage", openAIUsage(usage));
                    outs.add(chunk.toString());
                }
            } catch (Exception ignored) {
            }
            return outs;
        }

        private JSONObject base() {
            JSONObject chunk = new JSONObject();
            try {
                chunk.put("id", id);
                chunk.put("object", "chat.completion.chunk");
                chunk.put("created", created);
                chunk.put("model", model);
            } catch (Exception ignored) {}
            return chunk;
        }

        private String deltaChunk(String field, String text) {
            JSONObject chunk = base();
            try {
                JSONArray choices = new JSONArray();
                JSONObject ch = new JSONObject();
                ch.put("index", 0);
                JSONObject delta = new JSONObject();
                delta.put(field, text);
                ch.put("delta", delta);
                ch.put("finish_reason", JSONObject.NULL);
                choices.put(ch);
                chunk.put("choices", choices);
            } catch (Exception ignored) {}
            return chunk.toString();
        }
    }
}
