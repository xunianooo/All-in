package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Antigravity ←→ OpenAI 协议桥。
 *
 * 上游是 Google Code Assist 的信封协议：
 *   POST {daily-cloudcode-pa}/v1internal:streamGenerateContent?alt=sse
 *   { "model": "...", "project": "...", "userAgent": "antigravity",
 *     "requestType": "agent", "requestId": "agent-<hex>",
 *     "request": { contents:[{role,parts}], systemInstruction, tools, generationConfig, sessionId } }
 * 回复是 SSE，每行 data: {"response":{candidates:[...],usageMetadata:{...}}}
 *
 * 本类只做纯转换（不碰 HTTP）：OpenAI 请求体 → Code Assist 信封；
 * Code Assist SSE → OpenAI chat.completion.chunk 流 / 聚合结果。
 * HTTP 与错误映射在 GatewayServer.forwardAntigravity 里，和 Codex 一样走 forwardAttempt。
 */
public class AntigravityClient {

    /** 请求体里要打散 system prompt（Antigravity 的假 429 规避），配置可关 */
    public static volatile boolean cloakEnabled = true;

    // ------------------------------------------------------------- 请求：OpenAI → 信封

    public static JSONObject buildEnvelope(JSONObject payload, String model, String project) {
        JSONObject req = new JSONObject();
        JSONArray contents = new JSONArray();
        StringBuilder system = new StringBuilder();

        JSONArray msgs = payload.optJSONArray("messages");
        if (msgs != null) {
            for (int i = 0; i < msgs.length(); i++) {
                JSONObject m = msgs.optJSONObject(i);
                if (m == null) continue;
                String role = m.optString("role", "user");
                if ("system".equals(role) || "developer".equals(role)) {
                    String t = textOf(m.opt("content"));
                    if (!t.isEmpty()) {
                        if (system.length() > 0) system.append("\n\n");
                        system.append(t);
                    }
                    continue;
                }
                if ("tool".equals(role) || "function".equals(role)) {
                    JSONObject part = new JSONObject();
                    JSONObject fr = new JSONObject();
                    String name = m.optString("name", "");
                    if (name.isEmpty()) name = "tool";
                    fr.put("name", name);
                    String id = m.optString("tool_call_id", "");
                    if (!id.isEmpty()) fr.put("id", id);
                    JSONObject resp = new JSONObject();
                    resp.put("result", textOf(m.opt("content")));
                    fr.put("response", resp);
                    part.put("functionResponse", fr);
                    appendTurn(contents, "user", part);
                    continue;
                }
                boolean assistant = "assistant".equals(role) || "model".equals(role);
                String text = textOf(m.opt("content"));
                if (!text.isEmpty()) {
                    JSONObject part = new JSONObject();
                    part.put("text", text);
                    appendTurn(contents, assistant ? "model" : "user", part);
                }
                JSONArray calls = m.optJSONArray("tool_calls");
                if (calls != null) {
                    for (int j = 0; j < calls.length(); j++) {
                        JSONObject c = calls.optJSONObject(j);
                        if (c == null) continue;
                        JSONObject fn = c.optJSONObject("function");
                        if (fn == null) continue;
                        JSONObject part = new JSONObject();
                        JSONObject fc = new JSONObject();
                        fc.put("name", fn.optString("name", "tool"));
                        String args = fn.optString("arguments", "");
                        JSONObject parsed = null;
                        if (!args.trim().isEmpty()) {
                            try {
                                parsed = new JSONObject(args);
                            } catch (Exception ignored) {
                            }
                        }
                        fc.put("args", parsed == null ? new JSONObject() : parsed);
                        String id = c.optString("id", "");
                        if (!id.isEmpty()) fc.put("id", id);
                        part.put("functionCall", fc);
                        appendTurn(contents, "model", part);
                    }
                }
            }
        }

        // Gemini 不接受以 model 结尾的历史
        if (contents.length() > 0) {
            JSONObject last = contents.optJSONObject(contents.length() - 1);
            if (last != null && "model".equals(last.optString("role", ""))) {
                JSONObject part = new JSONObject();
                part.put("text", "Continue.");
                appendTurn(contents, "user", part);
            }
        }
        if (contents.length() > 0) {
            JSONObject first = contents.optJSONObject(0);
            if (first != null && "model".equals(first.optString("role", ""))) {
                JSONArray arr = new JSONArray();
                JSONObject pre = new JSONObject();
                pre.put("role", "user");
                JSONArray parts = new JSONArray();
                JSONObject t = new JSONObject();
                t.put("text", "[Continuing from previous AI thoughts...]");
                parts.put(t);
                pre.put("parts", parts);
                arr.put(pre);
                for (int i = 0; i < contents.length(); i++) arr.put(contents.opt(i));
                contents = arr;
            }
        }
        req.put("contents", contents);

        String sysText = system.toString();
        if (cloakEnabled && !sysText.isEmpty()) sysText = AntigravityAuth.cloak(sysText);
        if (!sysText.isEmpty()) {
            JSONObject si = new JSONObject();
            si.put("role", "user");
            JSONArray parts = new JSONArray();
            JSONObject t = new JSONObject();
            t.put("text", sysText);
            parts.put(t);
            si.put("parts", parts);
            req.put("systemInstruction", si);
        }

        boolean claude = model != null && model.toLowerCase().contains("claude");

        // 工具声明
        JSONArray tools = payload.optJSONArray("tools");
        String toolChoice = payload.optString("tool_choice", "");
        if (tools != null && tools.length() > 0 && !"none".equals(toolChoice)) {
            JSONArray decls = new JSONArray();
            for (int i = 0; i < tools.length(); i++) {
                JSONObject t = tools.optJSONObject(i);
                if (t == null) continue;
                JSONObject fn = t.optJSONObject("function");
                if (fn == null) fn = t;
                JSONObject d = new JSONObject();
                d.put("name", fn.optString("name", "tool"));
                String desc = fn.optString("description", "");
                if (!desc.isEmpty()) d.put("description", desc);
                JSONObject schema = fn.optJSONObject("parameters");
                if (schema == null) {
                    schema = new JSONObject();
                    schema.put("type", "object");
                    schema.put("properties", new JSONObject());
                }
                d.put("parameters", plainSchema(schema));
                decls.put(d);
            }
            if (decls.length() > 0) {
                JSONObject wrap = new JSONObject();
                wrap.put("functionDeclarations", decls);
                JSONArray arr = new JSONArray();
                arr.put(wrap);
                req.put("tools", arr);

                JSONObject fcCfg = new JSONObject();
                String mode = "AUTO";
                if ("required".equals(toolChoice)) {
                    mode = "ANY";
                } else if (toolChoice.startsWith("name:")) {
                    mode = "ANY";
                    JSONArray allowed = new JSONArray();
                    allowed.put(toolChoice.substring(5));
                    fcCfg.put("allowedFunctionNames", allowed);
                } else if (claude) {
                    mode = "VALIDATED";
                }
                fcCfg.put("mode", mode);
                JSONObject tc = new JSONObject();
                tc.put("functionCallingConfig", fcCfg);
                req.put("toolConfig", tc);
            }
        }

        JSONObject gen = new JSONObject();
        int maxTokens = payload.optInt("max_tokens", payload.optInt("max_completion_tokens", 0));
        // Antigravity 上 Gemini 系不收 maxOutputTokens，Claude 系要（upstream 同款）
        if (maxTokens > 0 && claude) gen.put("maxOutputTokens", maxTokens);
        if (payload.has("temperature")) gen.put("temperature", payload.optDouble("temperature", 1.0));
        if (payload.has("top_p")) gen.put("topP", payload.optDouble("top_p", 1.0));
        JSONArray stop = payload.optJSONArray("stop");
        if (stop != null && stop.length() > 0) gen.put("stopSequences", stop);
        else if (payload.opt("stop") instanceof String) {
            JSONArray s = new JSONArray();
            s.put(payload.optString("stop", ""));
            gen.put("stopSequences", s);
        }

        // 只有客户端明确要思考时才带 thinkingConfig，避免 Gemini 把 token 全烧在思考上
        String effort = payload.optString("reasoning_effort", "");
        boolean wantThink = !effort.isEmpty() || payload.optBoolean("thinking", false)
                || (payload.optJSONObject("reasoning") != null);
        if (wantThink) {
            JSONObject tc = new JSONObject();
            tc.put("includeThoughts", true);
            String lower = model == null ? "" : model.toLowerCase();
            boolean gemini3 = lower.contains("gemini-3") || lower.contains("gemini-pro-agent");
            if (gemini3 && !claude) {
                String level = "high";
                if ("low".equals(effort) || "minimal".equals(effort)) level = "low";
                else if ("medium".equals(effort)) level = "medium";
                tc.put("thinkingLevel", level);
            } else if (claude) {
                int budget = 4096;
                if ("low".equals(effort) || "minimal".equals(effort)) budget = 2048;
                else if ("high".equals(effort)) budget = 8192;
                if (maxTokens > 0 && budget >= maxTokens) budget = Math.max(1024, maxTokens - 1);
                tc.put("thinkingBudget", budget);
                if (maxTokens <= 0) gen.put("maxOutputTokens", budget + 32000);
            }
            gen.put("thinkingConfig", tc);
        }

        JSONObject fmt = payload.optJSONObject("response_format");
        if (fmt != null && "json_object".equals(fmt.optString("type", ""))) {
            gen.put("responseMimeType", "application/json");
        }
        if (gen.length() > 0) req.put("generationConfig", gen);

        req.put("sessionId", sessionOf(contents));

        JSONObject env = new JSONObject();
        env.put("model", model);
        env.put("project", project);
        env.put("userAgent", "antigravity");
        env.put("requestType", "agent");
        env.put("requestId", "agent-" + randomHex(12));
        env.put("request", req);
        return env;
    }

    /** 同一段对话必须同一个 sessionId：取首条用户消息的 sha256 低 63 位 */
    private static String sessionOf(JSONArray contents) {
        String first = "";
        for (int i = 0; i < contents.length(); i++) {
            JSONObject c = contents.optJSONObject(i);
            if (c == null || !"user".equals(c.optString("role", ""))) continue;
            JSONArray parts = c.optJSONArray("parts");
            if (parts != null) {
                for (int j = 0; j < parts.length(); j++) {
                    JSONObject p = parts.optJSONObject(j);
                    if (p == null) continue;
                    String t = p.optString("text", "");
                    if (!t.isEmpty()) {
                        first = t;
                        break;
                    }
                }
            }
            break;
        }
        if (first.isEmpty()) first = randomHex(16);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(first.getBytes(StandardCharsets.UTF_8));
            long n = 0;
            for (int i = 0; i < 8; i++) n = (n << 8) | (h[i] & 0xffL);
            n &= 0x7fffffffffffffffL;
            return "-" + Long.toString(n);
        } catch (Exception e) {
            return "-" + (System.currentTimeMillis() & 0x7fffffffffffffffL);
        }
    }

    /** 合并连续同角色 turn（Gemini 要求 user/model 交替） */
    private static void appendTurn(JSONArray contents, String role, JSONObject part) {
        if (contents.length() > 0) {
            JSONObject last = contents.optJSONObject(contents.length() - 1);
            if (last != null && role.equals(last.optString("role", ""))) {
                JSONArray parts = last.optJSONArray("parts");
                if (parts == null) {
                    parts = new JSONArray();
                    last.put("parts", parts);
                }
                parts.put(part);
                return;
            }
        }
        JSONObject turn = new JSONObject();
        turn.put("role", role);
        JSONArray parts = new JSONArray();
        parts.put(part);
        turn.put("parts", parts);
        contents.put(turn);
    }

    /** OpenAI 的 JSON Schema → Gemini 认得的那一子集（去掉 $schema/additionalProperties 等） */
    private static JSONObject plainSchema(JSONObject in) {
        JSONObject out = new JSONObject();
        String type = null;
        Object rawType = in.opt("type");
        if (rawType instanceof JSONArray) {
            JSONArray ts = (JSONArray) rawType;
            for (int i = 0; i < ts.length(); i++) {
                String t = ts.optString(i, "");
                if ("null".equals(t)) {
                    out.put("nullable", true);
                } else if (!t.isEmpty()) {
                    type = t;
                }
            }
        } else if (rawType instanceof String) {
            type = (String) rawType;
        }
        if (type != null) out.put("type", type);
        String desc = in.optString("description", "");
        if (!desc.isEmpty()) out.put("description", desc);
        String format = in.optString("format", "");
        if (!format.isEmpty() && ("string".equals(type) || type == null)) out.put("format", format);
        JSONArray en = in.optJSONArray("enum");
        if (en != null) out.put("enum", en);
        JSONObject props = in.optJSONObject("properties");
        if (props != null) {
            JSONObject op = new JSONObject();
            java.util.Iterator<String> it = props.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject v = props.optJSONObject(k);
                op.put(k, v == null ? new JSONObject() : plainSchema(v));
            }
            out.put("properties", op);
        }
        JSONArray req = in.optJSONArray("required");
        if (req != null) out.put("required", req);
        JSONObject items = in.optJSONObject("items");
        if (items != null) out.put("items", plainSchema(items));
        return out;
    }

    private static String textOf(Object content) {
        if (content == null) return "";
        if (content instanceof String) return (String) content;
        if (content instanceof JSONArray) {
            StringBuilder sb = new StringBuilder();
            JSONArray arr = (JSONArray) content;
            for (int i = 0; i < arr.length(); i++) {
                Object o = arr.opt(i);
                if (o instanceof String) {
                    sb.append((String) o);
                } else if (o instanceof JSONObject) {
                    JSONObject part = (JSONObject) o;
                    String t = part.optString("text", "");
                    if (!t.isEmpty()) sb.append(t);
                }
            }
            return sb.toString();
        }
        return String.valueOf(content);
    }

    static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // ------------------------------------------------------------- 响应：Code Assist SSE → OpenAI

    public static class Decoded {
        public String text = "";
        public String reasoning = "";
        public String finishReason = "";
        public int promptTokens;
        public int outputTokens;
        public int thoughtTokens;
        public int cachedTokens;
        public String providerError = "";
        public String modelVersion = "";
        public JSONArray toolCalls = new JSONArray();   // [{id,name,arguments}]
    }

    /**
     * 把 Code Assist 的 SSE 转成 OpenAI chat.completion.chunk 流，
     * 交给 GatewayServer 现有的 streamForwardResponse / Anthropic 桥处理。
     */
    public static InputStream toOpenAiStream(final InputStream upstream, final String model) throws Exception {
        final PipedOutputStream pos = new PipedOutputStream();
        final PipedInputStream pis = new PipedInputStream(pos, 16384);
        final String chunkId = "chatcmpl-ag-" + System.currentTimeMillis();
        new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedReader br = null;
                try {
                    br = new BufferedReader(new InputStreamReader(upstream, StandardCharsets.UTF_8));
                    String line;
                    boolean started = false;
                    boolean stopped = false;
                    boolean saidSomething = false;
                    int toolIndex = -1;
                    int promptTokens = 0, outputTokens = 0, thoughtTokens = 0, cachedTokens = 0;
                    while ((line = br.readLine()) != null) {
                        if (!line.startsWith("data:")) continue;
                        String data = line.substring(5).trim();
                        if (data.isEmpty() || "[DONE]".equals(data)) continue;
                        JSONObject ev;
                        try {
                            ev = new JSONObject(data);
                        } catch (Exception ignored) {
                            continue;
                        }
                        if (ev.has("error")) {
                            JSONObject err = ev.optJSONObject("error");
                            String msg = err == null ? data : err.optString("message", data);
                            pos.write(wrapError(msg).getBytes(StandardCharsets.UTF_8));
                            pos.flush();
                            continue;
                        }
                        JSONObject resp = ev.optJSONObject("response");
                        if (resp == null) resp = ev;
                        JSONObject usage = resp.optJSONObject("usageMetadata");
                        if (usage != null) {
                            promptTokens = usage.optInt("promptTokenCount", promptTokens);
                            cachedTokens = usage.optInt("cachedContentTokenCount", cachedTokens);
                            outputTokens = usage.optInt("candidatesTokenCount", outputTokens);
                            thoughtTokens = usage.optInt("thoughtsTokenCount", thoughtTokens);
                        }
                        if (!started) {
                            started = true;
                            pos.write(wrapRole(model, chunkId).getBytes(StandardCharsets.UTF_8));
                            pos.flush();
                        }
                        JSONArray cands = resp.optJSONArray("candidates");
                        if (cands == null) continue;
                        for (int ci = 0; ci < cands.length(); ci++) {
                            JSONObject cand = cands.optJSONObject(ci);
                            if (cand == null) continue;
                            JSONObject content = cand.optJSONObject("content");
                            JSONArray parts = content == null ? null : content.optJSONArray("parts");
                            if (parts != null) {
                                for (int pi = 0; pi < parts.length(); pi++) {
                                    JSONObject p = parts.optJSONObject(pi);
                                    if (p == null) continue;
                                    JSONObject fc = p.optJSONObject("functionCall");
                                    if (fc != null) {
                                        toolIndex++;
                                        String name = fc.optString("name", "tool");
                                        String id = fc.optString("id", "");
                                        if (id.isEmpty()) id = "call_" + randomHex(6);
                                        JSONObject args = fc.optJSONObject("args");
                                        String argStr = args == null ? "{}" : args.toString();
                                        pos.write(wrapToolCall(toolIndex, id, name, argStr, model, chunkId)
                                                .getBytes(StandardCharsets.UTF_8));
                                        pos.flush();
                                        saidSomething = true;
                                        continue;
                                    }
                                    String t = p.optString("text", "");
                                    if (t.isEmpty()) continue;
                                    boolean thought = p.optBoolean("thought", false);
                                    pos.write(wrapDelta(thought ? "reasoning_content" : "content", t, model, chunkId)
                                            .getBytes(StandardCharsets.UTF_8));
                                    pos.flush();
                                    saidSomething = true;
                                }
                            }
                            String fr = cand.optString("finishReason", "");
                            if (!fr.isEmpty() && !stopped) {
                                stopped = true;
                                String stop = "stop";
                                if ("MAX_TOKENS".equals(fr)) stop = "length";
                                else if ("SAFETY".equals(fr) || "RECITATION".equals(fr) || "PROHIBITED_CONTENT".equals(fr)) stop = "content_filter";
                                if (toolIndex >= 0 && "stop".equals(stop)) stop = "tool_calls";
                                pos.write(wrapFinish(stop, promptTokens, cachedTokens, outputTokens, thoughtTokens, model, chunkId)
                                        .getBytes(StandardCharsets.UTF_8));
                                pos.flush();
                            }
                        }
                    }
                    if (!stopped) {
                        pos.write(wrapFinish(toolIndex >= 0 ? "tool_calls" : "stop",
                                promptTokens, cachedTokens, outputTokens, thoughtTokens, model, chunkId)
                                .getBytes(StandardCharsets.UTF_8));
                        pos.flush();
                    }
                    if (!saidSomething) {
                        pos.write(wrapDelta("content", "", model, chunkId).getBytes(StandardCharsets.UTF_8));
                        pos.flush();
                    }
                    pos.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                    pos.flush();
                } catch (Throwable ignored) {
                } finally {
                    try { pos.close(); } catch (Throwable ignored) {}
                    try { if (br != null) br.close(); } catch (Throwable ignored) {}
                    try { upstream.close(); } catch (Throwable ignored) {}
                }
            }
        }, "Antigravity-SSE").start();
        return pis;
    }

    /** 非流式：把整条 SSE 收成 OpenAI chat.completion */
    public static JSONObject toOpenAiCompletion(InputStream upstream, String model) throws Exception {
        Decoded d = decodeAll(upstream);
        JSONObject out = new JSONObject();
        out.put("id", "chatcmpl-ag-" + System.currentTimeMillis());
        out.put("object", "chat.completion");
        out.put("created", System.currentTimeMillis() / 1000);
        out.put("model", model);
        JSONArray choices = new JSONArray();
        JSONObject c0 = new JSONObject();
        c0.put("index", 0);
        JSONObject msg = new JSONObject();
        msg.put("role", "assistant");
        msg.put("content", d.text);
        if (!d.reasoning.isEmpty()) msg.put("reasoning_content", d.reasoning);
        if (d.toolCalls.length() > 0) msg.put("tool_calls", d.toolCalls);
        c0.put("message", msg);
        String stop = d.finishReason.isEmpty() ? (d.toolCalls.length() > 0 ? "tool_calls" : "stop") : d.finishReason;
        c0.put("finish_reason", stop);
        choices.put(c0);
        out.put("choices", choices);
        JSONObject usage = new JSONObject();
        usage.put("prompt_tokens", Math.max(0, d.promptTokens - d.cachedTokens));
        usage.put("completion_tokens", d.outputTokens + d.thoughtTokens);
        usage.put("total_tokens", Math.max(0, d.promptTokens - d.cachedTokens) + d.outputTokens + d.thoughtTokens);
        out.put("usage", usage);
        return out;
    }

    public static Decoded decodeAll(InputStream upstream) throws Exception {
        Decoded d = new Decoded();
        BufferedReader br = new BufferedReader(new InputStreamReader(upstream, StandardCharsets.UTF_8));
        StringBuilder text = new StringBuilder();
        StringBuilder think = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.isEmpty() || "[DONE]".equals(data)) continue;
            JSONObject ev;
            try {
                ev = new JSONObject(data);
            } catch (Exception ignored) {
                continue;
            }
            if (ev.has("error")) {
                JSONObject err = ev.optJSONObject("error");
                d.providerError = err == null ? data : err.optString("message", data);
                continue;
            }
            JSONObject resp = ev.optJSONObject("response");
            if (resp == null) resp = ev;
            String mv = resp.optString("modelVersion", "");
            if (!mv.isEmpty()) d.modelVersion = mv;
            JSONObject usage = resp.optJSONObject("usageMetadata");
            if (usage != null) {
                d.promptTokens = usage.optInt("promptTokenCount", d.promptTokens);
                d.cachedTokens = usage.optInt("cachedContentTokenCount", d.cachedTokens);
                d.outputTokens = usage.optInt("candidatesTokenCount", d.outputTokens);
                d.thoughtTokens = usage.optInt("thoughtsTokenCount", d.thoughtTokens);
            }
            JSONArray cands = resp.optJSONArray("candidates");
            if (cands == null) continue;
            for (int ci = 0; ci < cands.length(); ci++) {
                JSONObject cand = cands.optJSONObject(ci);
                if (cand == null) continue;
                JSONObject content = cand.optJSONObject("content");
                JSONArray parts = content == null ? null : content.optJSONArray("parts");
                if (parts != null) {
                    for (int pi = 0; pi < parts.length(); pi++) {
                        JSONObject p = parts.optJSONObject(pi);
                        if (p == null) continue;
                        JSONObject fc = p.optJSONObject("functionCall");
                        if (fc != null) {
                            JSONObject call = new JSONObject();
                            String id = fc.optString("id", "");
                            if (id.isEmpty()) id = "call_" + randomHex(6);
                            call.put("id", id);
                            call.put("type", "function");
                            JSONObject fn = new JSONObject();
                            fn.put("name", fc.optString("name", "tool"));
                            JSONObject args = fc.optJSONObject("args");
                            fn.put("arguments", args == null ? "{}" : args.toString());
                            call.put("function", fn);
                            d.toolCalls.put(call);
                            continue;
                        }
                        String t = p.optString("text", "");
                        if (t.isEmpty()) continue;
                        if (p.optBoolean("thought", false)) think.append(t);
                        else text.append(t);
                    }
                }
                String fr = cand.optString("finishReason", "");
                if (!fr.isEmpty()) {
                    if ("MAX_TOKENS".equals(fr)) d.finishReason = "length";
                    else if ("SAFETY".equals(fr) || "RECITATION".equals(fr) || "PROHIBITED_CONTENT".equals(fr)) d.finishReason = "content_filter";
                    else d.finishReason = "stop";
                }
            }
        }
        br.close();
        upstream.close();
        d.text = text.toString();
        d.reasoning = think.toString();
        if (d.toolCalls.length() > 0) d.finishReason = "tool_calls";
        return d;
    }

    // ------------------------------------------------------------- chunk 拼装

    private static String wrapRole(String model, String id) {
        JSONObject o = chunkSkeleton(model, id);
        JSONObject delta = new JSONObject();
        try {
            delta.put("role", "assistant");
            delta.put("content", "");
            o.put("choices", choices(delta, null));
        } catch (Exception ignored) {
        }
        return "data: " + o.toString() + "\n\n";
    }

    private static String wrapDelta(String field, String text, String model, String id) {
        JSONObject o = chunkSkeleton(model, id);
        JSONObject delta = new JSONObject();
        try {
            delta.put(field, text);
            o.put("choices", choices(delta, null));
        } catch (Exception ignored) {
        }
        return "data: " + o.toString() + "\n\n";
    }

    private static String wrapToolCall(int index, String callId, String name, String args, String model, String id) {
        JSONObject o = chunkSkeleton(model, id);
        try {
            JSONObject call = new JSONObject();
            call.put("index", index);
            call.put("id", callId);
            call.put("type", "function");
            JSONObject fn = new JSONObject();
            fn.put("name", name);
            fn.put("arguments", args);
            call.put("function", fn);
            JSONArray calls = new JSONArray();
            calls.put(call);
            JSONObject delta = new JSONObject();
            delta.put("tool_calls", calls);
            o.put("choices", choices(delta, null));
        } catch (Exception ignored) {
        }
        return "data: " + o.toString() + "\n\n";
    }

    private static String wrapFinish(String stop, int prompt, int cached, int out, int thought, String model, String id) {
        JSONObject o = chunkSkeleton(model, id);
        try {
            o.put("choices", choices(new JSONObject(), stop));
            JSONObject usage = new JSONObject();
            usage.put("prompt_tokens", Math.max(0, prompt - cached));
            usage.put("completion_tokens", out + thought);
            usage.put("total_tokens", Math.max(0, prompt - cached) + out + thought);
            o.put("usage", usage);
        } catch (Exception ignored) {
        }
        return "data: " + o.toString() + "\n\n";
    }

    private static String wrapError(String msg) {
        JSONObject o = new JSONObject();
        try {
            JSONObject err = new JSONObject();
            err.put("message", msg);
            err.put("type", "antigravity_stream_error");
            o.put("error", err);
        } catch (Exception ignored) {
        }
        return "data: " + o.toString() + "\n\n";
    }

    private static JSONObject chunkSkeleton(String model, String id) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("object", "chat.completion.chunk");
            o.put("created", System.currentTimeMillis() / 1000);
            o.put("model", model);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static JSONArray choices(JSONObject delta, String finish) {
        JSONArray arr = new JSONArray();
        JSONObject c = new JSONObject();
        try {
            c.put("index", 0);
            c.put("delta", delta);
            if (finish == null) c.put("finish_reason", JSONObject.NULL);
            else c.put("finish_reason", finish);
        } catch (Exception ignored) {
        }
        arr.put(c);
        return arr;
    }

    /** Antigravity 那个假 429 想对客户端说清楚：不是额度问题 */
    public static String turnedAwayHint(String system) {
        if (system != null && system.toLowerCase().contains("codex")) {
            return "Antigravity 对 Codex 的 GPT-5 身份提示词一律回这个 429（额度没动）。换个新对话，或在路由组里把 Antigravity 放最后。";
        }
        return "Antigravity 对 Claude Code / Claude Agent SDK 的 system prompt 一律回这个 429（额度没动），等待无效。给网关打开发送前打散（antigravityCloak，默认开），或改用其他供应商。";
    }
}
