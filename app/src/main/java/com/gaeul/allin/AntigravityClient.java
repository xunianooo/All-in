package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
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
        boolean claude = model != null && model.toLowerCase().contains("claude");

        JSONArray msgs = payload.optJSONArray("messages");
        java.util.HashMap<String, String> toolNamesById = new java.util.HashMap<>();
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
                    JSONObject fr = new JSONObject();
                    String id = safeToolId(m.optString("tool_call_id", ""));
                    String name = m.optString("name", "");
                    if (name.isEmpty()) name = toolNamesById.get(id);
                    if (name == null || name.isEmpty()) name = "tool";
                    fr.put("name", name);
                    if (!id.isEmpty()) fr.put("id", id);
                    JSONObject response = new JSONObject();
                    response.put("result", textOf(m.opt("content")));
                    fr.put("response", response);
                    JSONObject part = new JSONObject();
                    part.put("functionResponse", fr);
                    appendTurn(contents, "user", part, true);
                    appendToolImages(contents, m.opt("content"));
                    continue;
                }
                boolean assistant = "assistant".equals(role) || "model".equals(role);
                appendContentParts(contents, assistant ? "model" : "user", m.opt("content"),
                        assistant ? m.optJSONObject("extra_content") : null, claude);
                JSONArray calls = m.optJSONArray("tool_calls");
                if (calls != null) {
                    boolean signed = false;
                    for (int j = 0; j < calls.length(); j++) {
                        JSONObject call = calls.optJSONObject(j);
                        if (call != null && !googleThoughtSignature(call.optJSONObject("extra_content")).isEmpty()) signed = true;
                    }
                    for (int j = 0; j < calls.length(); j++) {
                        JSONObject c = calls.optJSONObject(j);
                        if (c == null) continue;
                        JSONObject fn = c.optJSONObject("function");
                        if (fn == null) continue;
                        JSONObject part = new JSONObject();
                        JSONObject fc = new JSONObject();
                        String callName = fn.optString("name", "tool");
                        fc.put("name", callName);
                        String args = fn.optString("arguments", "");
                        JSONObject parsed = null;
                        if (!args.trim().isEmpty()) {
                            try {
                                parsed = new JSONObject(args);
                            } catch (Exception ignored) {
                            }
                        }
                        fc.put("args", parsed == null ? new JSONObject() : parsed);
                        String id = safeToolId(c.optString("id", ""));
                        if (!id.isEmpty()) {
                            fc.put("id", id);
                            toolNamesById.put(id, callName);
                        }
                        part.put("functionCall", fc);
                        // Magpie sends an Antigravity Claude the skip marker on every call,
                        // whatever signature the client kept from an earlier turn.
                        if (claude) {
                            part.put("thoughtSignature", SKIP_SIGNATURE);
                        } else {
                            String signature = googleThoughtSignature(c.optJSONObject("extra_content"));
                            if (!signature.isEmpty()) part.put("thoughtSignature", signature);
                            else if (!signed) part.put("thoughtSignature", SKIP_SIGNATURE);
                        }
                        appendTurn(contents, "model", part, false);
                    }
                }
            }
        }

        // Google reads a function call turn with an unanswered call as broken for the
        // whole conversation, and turns away a turn mixing functionResponse with text.
        contents = pairFunctionTurns(contents, claude);

        // Gemini 不接受以 model 结尾的历史
        if (contents.length() > 0) {
            JSONObject last = contents.optJSONObject(contents.length() - 1);
            if (last != null && "model".equals(last.optString("role", ""))) {
                JSONObject part = new JSONObject();
                part.put("text", "Continue.");
                appendTurn(contents, "user", part, false);
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

        // 工具声明
        JSONArray tools = payload.optJSONArray("tools");
        Object rawToolChoice = payload.opt("tool_choice");
        String toolChoice = rawToolChoice instanceof String ? (String) rawToolChoice : "";
        String namedTool = "";
        if (rawToolChoice instanceof JSONObject) {
            JSONObject choice = (JSONObject) rawToolChoice;
            JSONObject function = choice.optJSONObject("function");
            if ("function".equals(choice.optString("type", "")) && function != null) {
                namedTool = function.optString("name", "");
            }
        }
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
                } else if (toolChoice.startsWith("name:") || !namedTool.isEmpty()) {
                    mode = "ANY";
                    JSONArray allowed = new JSONArray();
                    allowed.put(!namedTool.isEmpty() ? namedTool : toolChoice.substring(5));
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

        // Match Magpie's normalized effort names and provider budget policy.
        String effort = payload.optString("reasoning_effort", "");
        JSONObject reasoning = payload.optJSONObject("reasoning");
        if (effort.isEmpty() && reasoning != null) effort = reasoning.optString("effort", "");
        JSONObject thinking = payload.optJSONObject("thinking");
        boolean explicitThink = payload.optBoolean("thinking", false)
                || (thinking != null && (thinking.optBoolean("enabled", false)
                || "enabled".equalsIgnoreCase(thinking.optString("type", ""))));
        String lower = model == null ? "" : model.toLowerCase();
        String bareModel = lower.startsWith("models/") ? lower.substring(7) : lower;
        // Magpie sends gpt-oss no thinkingConfig at all.
        boolean gptOss = bareModel.startsWith("gpt-oss");
        boolean claudeThinkingModel = claude && lower.contains("thinking");
        boolean wantThink = !gptOss && (!effort.isEmpty() || explicitThink || claudeThinkingModel);
        if (wantThink) {
            JSONObject tc = new JSONObject();
            tc.put("includeThoughts", true);
            int geminiGeneration = geminiGeneration(lower);
            boolean geminiThinkingModel = geminiGeneration >= 3 || lower.contains("gemini-pro-agent");
            if (geminiThinkingModel && !claude) {
                String normalized = normalizeEffort(effort);
                if (!normalized.isEmpty()) {
                    String level = "high";
                    String raw = effort == null ? "" : effort.trim().toLowerCase();
                    if ("minimal".equals(raw)) {
                        // Magpie asks for a level only for an id that names one; otherwise
                        // minimal goes as high, the lowest level every variant takes.
                        level = "high";
                    } else if ("low".equals(normalized)) level = "low";
                    else if ("medium".equals(normalized)) {
                        level = geminiGeneration == 3 && !lower.contains("flash") ? "high" : "medium";
                    }
                    tc.put("thinkingLevel", level);
                }
            } else if (claude) {
                if (!lower.contains("thinking")) {
                    // Magpie omits thinkingConfig for Claude variants without thinking support.
                    wantThink = false;
                } else {
                    String normalized = normalizeEffort(effort);
                    int budget = "low".equals(normalized) ? 4096
                            : "medium".equals(normalized) ? 10000
                            : "high".equals(normalized) ? 24000
                            : ("xhigh".equals(normalized) || "max".equals(normalized)) ? 32000 : 10000;
                    if (maxTokens > 0 && budget >= maxTokens) {
                        budget = maxTokens - 1;
                        if (budget < 1024) wantThink = false;
                    }
                    if (wantThink) {
                        tc.put("thinkingBudget", budget);
                        if (maxTokens <= 0) gen.put("maxOutputTokens", budget + 32000);
                    }
                }
            }
            if (wantThink) gen.put("thinkingConfig", tc);
        }

        JSONObject fmt = payload.optJSONObject("response_format");
        if (fmt != null) {
            String fmtType = fmt.optString("type", "");
            if ("json_object".equals(fmtType) || "json_schema".equals(fmtType)) {
                gen.put("responseMimeType", "application/json");
            }
            // Magpie forwards the client's schema so the model answers in that shape.
            JSONObject declared = fmt.optJSONObject("json_schema");
            JSONObject schema = declared == null ? null
                    : declared.optJSONObject("schema") != null ? declared.optJSONObject("schema") : declared;
            if (schema != null) gen.put("responseJsonSchema", schema);
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
    private static final String SKIP_SIGNATURE = "skip_thought_signature_validator";
    private static final String INTERRUPTED_TOOL_NOTE =
            "[The result of this tool call is unavailable: the turn was interrupted.]";

    private static boolean hasResponse(JSONArray parts) {
        if (parts == null) return false;
        for (int i = 0; i < parts.length(); i++) {
            JSONObject p = parts.optJSONObject(i);
            if (p != null && p.has("functionResponse")) return true;
        }
        return false;
    }

    /**
     * Answers a model turn's calls that no result ever came back for, and keeps a
     * tool result turn apart from what the user said next: Google turns away a
     * history whose call count differs from its response count, and a user turn
     * mixing a functionResponse with text.
     */
    private static JSONArray pairFunctionTurns(JSONArray contents, boolean claude) {
        java.util.HashSet<String> answeredIds = new java.util.HashSet<String>();
        java.util.HashSet<String> answeredNames = new java.util.HashSet<String>();
        for (int i = 0; i < contents.length(); i++) {
            JSONObject turn = contents.optJSONObject(i);
            JSONArray parts = turn == null ? null : turn.optJSONArray("parts");
            for (int j = 0; parts != null && j < parts.length(); j++) {
                JSONObject p = parts.optJSONObject(j);
                JSONObject fr = p == null ? null : p.optJSONObject("functionResponse");
                if (fr == null) continue;
                String id = fr.optString("id", "");
                if (!id.isEmpty()) answeredIds.add(id);
                else answeredNames.add(fr.optString("name", ""));
            }
        }
        JSONArray out = new JSONArray();
        for (int i = 0; i < contents.length(); i++) {
            JSONObject turn = contents.optJSONObject(i);
            if (turn == null) continue;
            out.put(turn);
            if (!"model".equals(turn.optString("role", ""))) continue;
            JSONArray parts = turn.optJSONArray("parts");
            JSONArray synth = null;
            for (int j = 0; parts != null && j < parts.length(); j++) {
                JSONObject p = parts.optJSONObject(j);
                JSONObject fc = p == null ? null : p.optJSONObject("functionCall");
                if (fc == null) continue;
                String id = fc.optString("id", "");
                String name = fc.optString("name", "tool");
                if (id.isEmpty() ? answeredNames.contains(name) : answeredIds.contains(id)) continue;
                if (synth == null) synth = new JSONArray();
                JSONObject response = new JSONObject().put("name", name)
                        .put("response", new JSONObject().put("result", INTERRUPTED_TOOL_NOTE));
                if (!id.isEmpty()) response.put("id", id);
                synth.put(new JSONObject().put("functionResponse", response));
            }
            if (synth == null) continue;
            JSONObject next = i + 1 < contents.length() ? contents.optJSONObject(i + 1) : null;
            JSONArray nextParts = next == null ? null : next.optJSONArray("parts");
            if (next != null && "user".equals(next.optString("role", ""))
                    && (claude || hasResponse(nextParts))) {
                JSONArray merged = new JSONArray();
                for (int k = 0; k < synth.length(); k++) merged.put(synth.opt(k));
                for (int k = 0; nextParts != null && k < nextParts.length(); k++) merged.put(nextParts.opt(k));
                next.put("parts", merged);
            } else {
                out.put(new JSONObject().put("role", "user").put("parts", synth));
            }
        }
        return out;
    }

    /** 合并连续同角色 turn（Gemini 要求 user/model 交替；工具应答与普通文本不混一个 user turn） */
    private static void appendTurn(JSONArray contents, String role, JSONObject part, boolean followsResponse) {
        if (contents.length() > 0) {
            JSONObject last = contents.optJSONObject(contents.length() - 1);
            if (last != null && role.equals(last.optString("role", ""))
                    && (!"user".equals(role) || hasResponse(last.optJSONArray("parts")) == followsResponse)) {
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

    private static String googleThoughtSignature(JSONObject extraContent) {
        JSONObject google = extraContent == null ? null : extraContent.optJSONObject("google");
        return google == null ? "" : google.optString("thought_signature", "");
    }

    private static String openAiFinishReason(String finishReason) {
        if ("MAX_TOKENS".equals(finishReason)) return "length";
        if ("STOP".equals(finishReason) || "FINISH_REASON_UNSPECIFIED".equals(finishReason)
                || "OTHER".equals(finishReason) || "MALFORMED_FUNCTION_CALL".equals(finishReason)
                || "UNEXPECTED_TOOL_CALL".equals(finishReason)) return "stop";
        return "content_filter";
    }

    private static int geminiGeneration(String model) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|/)gemini-(\\d+)").matcher(model);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static String normalizeEffort(String effort) {
        String value = effort == null ? "" : effort.trim().toLowerCase();
        if ("none".equals(value) || "minimal".equals(value)) return "low";
        if ("low".equals(value) || "medium".equals(value) || "high".equals(value)
                || "max".equals(value) || "xhigh".equals(value)) return value;
        return "";
    }

    private static String safeToolId(String id) {
        return id == null ? "" : id.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static JSONObject googleExtraContent(String signature) {
        return new JSONObject().put("google", new JSONObject().put("thought_signature", signature));
    }

    private static String imageUrl(JSONObject block) {
        Object image = block.opt("image_url");
        if (image instanceof String) return (String) image;
        if (image instanceof JSONObject) return ((JSONObject) image).optString("url", "");
        String type = block.optString("type", "");
        return "image".equals(type) || "input_image".equals(type) ? block.optString("url", "") : "";
    }

    private static void appendContentParts(JSONArray contents, String role, Object content,
                                           JSONObject extraContent, boolean claude) {
        if (content instanceof JSONArray) {
            JSONArray blocks = (JSONArray) content;
            for (int i = 0; i < blocks.length(); i++) {
                Object item = blocks.opt(i);
                if (!(item instanceof JSONObject)) continue;
                JSONObject block = (JSONObject) item;
                String url = imageUrl(block);
                if (!url.isEmpty()) {
                    JSONObject part = imagePart(url);
                    if (part != null) appendTurn(contents, role, part, false);
                } else {
                    String text = block.optString("text", "");
                    // Magpie keeps Google's text signature on Gemini only; an Antigravity
                    // Claude gets none of its own thinking signatures back.
                    String signature = "model".equals(role) && !claude
                            ? googleThoughtSignature(block.optJSONObject("extra_content")) : "";
                    if (!text.isEmpty() || !signature.isEmpty()) {
                        JSONObject part = new JSONObject();
                        part.put("text", text);
                        if (!signature.isEmpty()) part.put("thoughtSignature", signature);
                        appendTurn(contents, role, part, false);
                    }
                }
            }
            return;
        }
        String text = textOf(content);
        String signature = claude ? "" : googleThoughtSignature(extraContent);
        if (!text.isEmpty() || !signature.isEmpty()) {
            JSONObject part = new JSONObject();
            part.put("text", text);
            if (!signature.isEmpty()) part.put("thoughtSignature", signature);
            appendTurn(contents, role, part, false);
        }
    }

    private static void appendToolImages(JSONArray contents, Object content) {
        if (!(content instanceof JSONArray)) return;
        JSONArray blocks = (JSONArray) content;
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject block = blocks.optJSONObject(i);
            if (block == null) continue;
            String url = imageUrl(block);
            if (!url.isEmpty()) {
                JSONObject part = imagePart(url);
                // A tool's images follow its response in the same turn, as Magpie sends them.
                if (part != null) appendTurn(contents, "user", part, true);
            }
        }
    }

    private static JSONObject imagePart(String value) {
        try {
            JSONObject part = new JSONObject();
            if (value.startsWith("data:")) {
                int comma = value.indexOf(',');
                if (comma < 0) return null;
                String meta = value.substring(5, comma);
                int semi = meta.indexOf(';');
                String mime = semi >= 0 ? meta.substring(0, semi) : meta;
                String data = value.substring(comma + 1);
                if (meta.contains(";base64")) {
                    part.put("inlineData", new JSONObject().put("mimeType", mime).put("data", data));
                } else {
                    byte[] decoded = java.net.URLDecoder.decode(data.replace("+", "%2B"), "UTF-8")
                            .getBytes(StandardCharsets.UTF_8);
                    part.put("inlineData", new JSONObject().put("mimeType", mime)
                            .put("data", android.util.Base64.encodeToString(decoded, android.util.Base64.NO_WRAP)));
                }
            } else {
                part.put("fileData", new JSONObject().put("mimeType", "image/*").put("fileUri", value));
            }
            return part;
        } catch (Exception ignored) {
            return null;
        }
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
        public String thoughtSignature = "";
        public String finishReason = "";
        public int promptTokens;
        public int outputTokens;
        public int thoughtTokens;
        public int cachedTokens;
        public String providerError = "";
        public boolean complete;
        public String modelVersion = "";
        public JSONArray toolCalls = new JSONArray();   // [{id,name,arguments}]
        /** 上游回了一张图：OpenAI 的 message.content 只有字符串，装不下它 */
        public boolean sawImage;
        public String imageMime = "";
    }

    /**
     * 把 Code Assist 的 SSE 转成 OpenAI chat.completion.chunk 流，
     * 交给 GatewayServer 现有的 streamForwardResponse / Anthropic 桥处理。
     */
    public static InputStream toOpenAiStream(final InputStream upstream, final String model) throws Exception {
        final PipedOutputStream pos = new PipedOutputStream();
        final PipedInputStream pis = new PipedInputStream(pos, 16384);
        final String chunkId = "chatcmpl-ag-" + System.currentTimeMillis();
        final Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedReader br = null;
                try {
                    br = new BufferedReader(new InputStreamReader(upstream, StandardCharsets.UTF_8));
                    String line;
                    boolean started = false;
                    boolean stopped = false;
                    boolean sawFinishReason = false;
                    boolean sawProviderError = false;
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
                        if (ev.has("error") || "error".equals(ev.optString("type", ""))
                                || "response.failed".equals(ev.optString("type", ""))) {
                            sawProviderError = true;
                            JSONObject err = ev.optJSONObject("error");
                            Object rawError = ev.opt("error");
                            String msg = err != null ? err.optString("message", err.optString("type", data))
                                    : rawError instanceof String ? (String) rawError : data;
                            pos.write(wrapError(msg).getBytes(StandardCharsets.UTF_8));
                            pos.flush();
                            continue;
                        }
                        JSONObject resp = ev.optJSONObject("response");
                        if (resp != null && resp.has("error")) {
                            sawProviderError = true;
                            JSONObject err = resp.optJSONObject("error");
                            String msg = err == null ? resp.optString("error", data)
                                    : err.optString("message", err.optString("type", data));
                            pos.write(wrapError(msg).getBytes(StandardCharsets.UTF_8));
                            pos.flush();
                            continue;
                        }
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
                                        pos.write(wrapToolCall(toolIndex, id, name, argStr, p.optString("thoughtSignature", ""), model, chunkId)
                                                .getBytes(StandardCharsets.UTF_8));
                                        pos.flush();
                                        saidSomething = true;
                                        continue;
                                    }
                                    // Magpie gives a picture the model drew its own event (codeAssistDecoder's
                                    // inlineData case); a thought's draft images stay with its reasoning. An
                                    // OpenAI chunk has only text to put it in, so saying so beats losing it.
                                    if (hasOutputImage(p)) {
                                        pos.write(wrapError(imageOutputError(p)).getBytes(StandardCharsets.UTF_8));
                                        pos.flush();
                                        return;
                                    }
                                    String t = p.optString("text", "");
                                    String signature = p.optString("thoughtSignature", "");
                                    if (t.isEmpty() && signature.isEmpty()) continue;
                                    boolean thought = p.optBoolean("thought", false);
                                    pos.write(wrapDelta(thought ? "reasoning_content" : "content", t, signature, model, chunkId)
                                            .getBytes(StandardCharsets.UTF_8));
                                    pos.flush();
                                    saidSomething = true;
                                }
                            }
                            String fr = cand.optString("finishReason", "");
                            if (!fr.isEmpty() && !stopped) {
                                stopped = true;
                                sawFinishReason = true;
                                String stop = openAiFinishReason(fr);
                                if (toolIndex >= 0 && "stop".equals(stop)) stop = "tool_calls";
                                pos.write(wrapFinish(stop, promptTokens, cachedTokens, outputTokens, thoughtTokens, model, chunkId)
                                        .getBytes(StandardCharsets.UTF_8));
                                pos.flush();
                            }
                        }
                    }
                    if (!sawFinishReason && !sawProviderError) {
                        pos.write(wrapError("Antigravity stream ended before a finishReason was received")
                                .getBytes(StandardCharsets.UTF_8));
                        pos.flush();
                        return;
                    }
                    if (sawProviderError) return;
                    if (!saidSomething) {
                        pos.write(wrapDelta("content", "", "", model, chunkId).getBytes(StandardCharsets.UTF_8));
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
        }, "Antigravity-SSE");
        worker.setDaemon(true);worker.start();
        return new FilterInputStream(pis) {
            private final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
            @Override public void close() throws IOException {
                if(!closed.compareAndSet(false,true))return;
                try { upstream.close(); } finally { worker.interrupt();super.close(); }
            }
        };
    }

    /** 非流式：把整条 SSE 收成 OpenAI chat.completion */
    public static JSONObject toOpenAiCompletion(InputStream upstream, String model) throws Exception {
        Decoded d = decodeAll(upstream);
        if (!d.providerError.isEmpty()) throw new java.io.IOException(d.providerError);
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
        if (!d.thoughtSignature.isEmpty()) msg.put("extra_content", googleExtraContent(d.thoughtSignature));
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
            if (ev.has("error") || "error".equals(ev.optString("type", ""))
                    || "response.failed".equals(ev.optString("type", ""))) {
                Object rawError = ev.opt("error");
                JSONObject err = ev.optJSONObject("error");
                if (err != null) d.providerError = err.optString("message", err.optString("type", data));
                else if (rawError instanceof String) d.providerError = (String) rawError;
                else d.providerError = data;
                continue;
            }
            JSONObject resp = ev.optJSONObject("response");
            if (resp != null && resp.has("error")) {
                JSONObject err = resp.optJSONObject("error");
                d.providerError = err == null ? resp.optString("error", data)
                        : err.optString("message", err.optString("type", data));
                continue;
            }
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
                            String signature = p.optString("thoughtSignature", "");
                            if (!signature.isEmpty()) call.put("extra_content", googleExtraContent(signature));
                            d.toolCalls.put(call);
                            continue;
                        }
                        if (hasOutputImage(p)) {
                            d.sawImage = true;
                            if (d.imageMime.isEmpty()) d.imageMime = inlineMime(p);
                            continue;
                        }
                        String signature = p.optString("thoughtSignature", "");
                        if (!signature.isEmpty()) d.thoughtSignature = signature;
                        String t = p.optString("text", "");
                        if (t.isEmpty()) continue;
                        if (p.optBoolean("thought", false)) think.append(t);
                        else text.append(t);
                    }
                }
                String fr = cand.optString("finishReason", "");
                if (!fr.isEmpty()) d.finishReason = openAiFinishReason(fr);
            }
        }
        br.close();
        upstream.close();
        if (!d.providerError.isEmpty()) throw new java.io.IOException(d.providerError);
        // A reply holding a picture is not one OpenAI's message carries: told as the failure it is,
        // rather than handed over with the picture gone and finish_reason "stop".
        if (d.sawImage) throw new java.io.IOException(imageOutputError(d.imageMime));
        if (d.finishReason.isEmpty()) {
            throw new java.io.IOException("Antigravity stream ended before a finishReason was received");
        }
        d.complete = true;
        d.text = text.toString();
        d.reasoning = think.toString();
        if (d.toolCalls.length() > 0 && "stop".equals(d.finishReason)) d.finishReason = "tool_calls";
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

    private static String wrapDelta(String field, String text, String signature, String model, String id) {
        JSONObject o = chunkSkeleton(model, id);
        JSONObject delta = new JSONObject();
        try {
            delta.put(field, text);
            if (!signature.isEmpty()) delta.put("extra_content", googleExtraContent(signature));
            o.put("choices", choices(delta, null));
        } catch (Exception ignored) {
        }
        return "data: " + o.toString() + "\n\n";
    }

    private static String wrapToolCall(int index, String callId, String name, String args, String signature, String model, String id) {
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
            if (!signature.isEmpty()) call.put("extra_content", googleExtraContent(signature));
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

    /**
     * A picture the reply carried, as Magpie's codeAssistDecoder tells it (an inlineData part
     * with data). Its drafts, marked a thought, are the reasoning's and are not one.
     */
    private static boolean hasOutputImage(JSONObject part) {
        if (part == null || part.optBoolean("thought", false)) return false;
        JSONObject inline = part.optJSONObject("inlineData");
        return inline != null && !inline.optString("data", "").isEmpty();
    }

    private static String inlineMime(JSONObject part) {
        JSONObject inline = part == null ? null : part.optJSONObject("inlineData");
        return inline == null ? "" : inline.optString("mimeType", "");
    }

    /**
     * An OpenAI chat reply carries text, reasoning and calls, and no picture: Magpie hands one back
     * to a Gemini client as inline data, which this bridge has nowhere to put. Said out loud, so a
     * reply holding one is not delivered with the picture silently gone.
     */
    private static String imageOutputError(JSONObject part) {
        return imageOutputError(inlineMime(part));
    }

    /** An inline image's type is the upstream's word, and only a well-formed media type is repeated. */
    private static String imageOutputError(String mimeType) {
        String type = safeImageType(mimeType);
        return (type.isEmpty()
                ? "Antigravity returned an image in its reply"
                : "Antigravity returned an image (" + type + ") in its reply")
                + "; an OpenAI chat reply carries text only, so the picture would be lost";
    }

    /** The longest media type repeated in an error: a reply's own type is not trusted with more. */
    private static final int MAX_IMAGE_TYPE = 64;

    /**
     * An upstream's image type as it may be said back: the image/ prefix and a bounded RFC token
     * after it, or "" for anything else — a type carries no newline, no quote and no other
     * client's words into this bridge's error, however long the upstream makes it.
     */
    private static String safeImageType(String mimeType) {
        String type = mimeType == null ? "" : mimeType.trim();
        if (type.length() <= 6 || type.length() > MAX_IMAGE_TYPE) return "";
        if (!type.regionMatches(true, 0, "image/", 0, 6)) return "";
        for (int i = 6; i < type.length(); i++) {
            char c = type.charAt(i);
            boolean token = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "!#$&^_.+-".indexOf(c) >= 0;
            if (!token) return "";
        }
        return type;
    }

    /** Antigravity 那个假 429 想对客户端说清楚：不是额度问题 */
    public static String turnedAwayHint(String system) {
        if (system != null && system.toLowerCase().contains("codex")) {
            return "Antigravity 对 Codex 的 GPT-5 身份提示词一律回这个 429（额度没动）。换个新对话，或在路由组里把 Antigravity 放最后。";
        }
        return "Antigravity 对 Claude Code / Claude Agent SDK 的 system prompt 一律回这个 429（额度没动），等待无效。给网关打开发送前打散（antigravityCloak，默认开），或改用其他供应商。";
    }
}
