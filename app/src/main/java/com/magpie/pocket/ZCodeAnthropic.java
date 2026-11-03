package com.magpie.pocket;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
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

    private static final int DEFAULT_MAX_TOKENS = 4096;

    // ===================== OpenAI → Anthropic =====================

    static JSONObject toAnthropic(JSONObject openai) throws Exception {
        JSONObject out = new JSONObject();
        out.put("model", openai.optString("model", ""));

        int maxTokens = openai.optInt("max_completion_tokens", 0);
        if (maxTokens <= 0) maxTokens = openai.optInt("max_tokens", 0);
        out.put("max_tokens", maxTokens > 0 ? maxTokens : DEFAULT_MAX_TOKENS);

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
            String t = flattenContent(m.opt("content"));
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
                block.put("tool_use_id", m.optString("tool_call_id", ""));
                block.put("content", flattenContent(m.opt("content")));
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
                for (int k = 0; k < arr.length(); k++) {
                    JSONObject part = arr.optJSONObject(k);
                    if (part == null) continue;
                    String type = part.optString("type", "text");
                    if ("text".equals(type)) {
                        JSONObject t = new JSONObject();
                        t.put("type", "text");
                        t.put("text", part.optString("text", ""));
                        content.put(t);
                    } else if ("image_url".equals(type)) {
                        JSONObject url = part.optJSONObject("image_url");
                        String u = url == null ? part.optString("image_url", "") : url.optString("url", "");
                        JSONObject img = toImageBlock(u);
                        if (img != null) content.put(img);
                    }
                }
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

        // 推理强度：OpenAI 的 reasoning_effort → Anthropic 的 thinking 预算
        String effort = openai.optString("reasoning_effort", "");
        if (!effort.isEmpty() && !"none".equals(effort) && out.optInt("max_tokens", DEFAULT_MAX_TOKENS) > 1024) {
            int budget = "low".equals(effort) ? 2048 : "medium".equals(effort) ? 8192 : 16384;
            int mt = out.optInt("max_tokens", DEFAULT_MAX_TOKENS);
            if (budget >= mt) budget = Math.max(1024, mt - 512);
            JSONObject thinking = new JSONObject();
            thinking.put("type", "enabled");
            thinking.put("budget_tokens", budget);
            out.put("thinking", thinking);
        }
        return out;
    }

    private static JSONObject toImageBlock(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            JSONObject src = new JSONObject();
            if (url.startsWith("data:")) {
                int comma = url.indexOf(',');
                if (comma < 0) return null;
                String head = url.substring(5, comma);
                String mediaType = head.split(";")[0];
                src.put("type", "base64");
                src.put("media_type", mediaType.isEmpty() ? "image/png" : mediaType);
                src.put("data", url.substring(comma + 1));
            } else {
                src.put("type", "url");
                src.put("url", url);
            }
            JSONObject img = new JSONObject();
            img.put("type", "image");
            img.put("source", src);
            return img;
        } catch (Exception e) {
            return null;
        }
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

    static JSONObject toOpenAI(JSONObject msg, String requestedModel) {
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

            String stopReason = msg.optString("stop_reason", "end_turn");
            String finish;
            if ("max_tokens".equals(stopReason)) finish = "length";
            else if ("tool_use".equals(stopReason)) finish = "tool_calls";
            else finish = "stop";

            JSONArray choices = new JSONArray();
            JSONObject ch = new JSONObject();
            ch.put("index", 0);
            ch.put("message", message);
            ch.put("finish_reason", finish);
            choices.put(ch);
            out.put("choices", choices);

            JSONObject usage = msg.optJSONObject("usage");
            if (usage != null) {
                out.put("usage", openAIUsage(usage));
            }
        } catch (Exception e) {
            try { out.put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) {}
        }
        return out;
    }

    // Anthropic input_tokens excludes cache reads and writes; OpenAI prompt_tokens includes both.
    private static JSONObject openAIUsage(JSONObject usage) throws Exception {
        long input = usage.optLong("input_tokens", 0);
        long read = usage.optLong("cache_read_input_tokens", 0);
        long write = usage.optLong("cache_creation_input_tokens", 0);
        long output = usage.optLong("output_tokens", 0);
        JSONObject u = new JSONObject();
        u.put("prompt_tokens", input + read + write);
        u.put("completion_tokens", output);
        u.put("total_tokens", input + read + write + output);
        if (usage.has("cache_read_input_tokens") || usage.has("cache_creation_input_tokens")) {
            JSONObject details = new JSONObject();
            if (usage.has("cache_read_input_tokens")) details.put("cached_tokens", read);
            if (usage.has("cache_creation_input_tokens")) details.put("cache_write_tokens", write);
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

        StreamState(String model) {
            this.id = "chatcmpl-" + System.currentTimeMillis();
            this.model = model;
            this.created = System.currentTimeMillis() / 1000L;
        }

        /** 一条 Anthropic 事件 → 一到多条 OpenAI data 行（不含 "data: " 前缀与空行） */
        List<String> onEvent(String eventName, JSONObject data) {
            List<String> outs = new ArrayList<String>();
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
                        outs.add(deltaChunk("content", d.optString("text", "")));
                    } else if ("thinking_delta".equals(dt)) {
                        outs.add(deltaChunk("reasoning_content", d.optString("thinking", "")));
                    } else if ("input_json_delta".equals(dt)) {
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
                    }
                } else if ("message_delta".equals(type)) {
                    JSONObject d = data.optJSONObject("delta");
                    String stop = d == null ? "" : d.optString("stop_reason", "");
                    String finish = "max_tokens".equals(stop) ? "length"
                            : "tool_use".equals(stop) ? "tool_calls" : "stop";
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
