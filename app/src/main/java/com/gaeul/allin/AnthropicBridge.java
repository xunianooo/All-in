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

    /** Copies an optional sampling parameter. null/absent means "not set"; any
     * non-number is an explicit error rather than a coerced guess. */
    private static void copyFiniteNumber(JSONObject out, JSONObject source, String key) {
        if (!source.has(key)) return;
        Object value = source.opt(key);
        if (value == null || JSONObject.NULL.equals(value)) return;
        if (!(value instanceof Number)) throw new IllegalArgumentException(key + " must be a number");
        double number = ((Number)value).doubleValue();
        if (Double.isNaN(number) || Double.isInfinite(number))
            throw new IllegalArgumentException(key + " must be a finite number");
        out.put(key, number);
    }

    /** Native-only content blocks that carry provider-side payload Chat cannot represent:
     * a hosted document (attached file) or a server search result. The native projection
     * omits them, because the body actually relayed upstream is the untouched original
     * (GatewayServer.nativeAnthropicRequest rebuilds it from that original) and
     * NativeAnthropicPolicy.checkNativeRequired refuses the request outright whenever the
     * native relay is not possible - so the omission can never be delivered as a converted
     * success. Strict Chat conversion still rejects them: a converted request would lose
     * real content without a trace. Types not listed here are unknown and stay an error in
     * both modes; unknown blocks are never guessed. */
    private static boolean nativePayloadOnly(String type) {
        return "document".equals(type) || "search_result".equals(type);
    }

    /** Reasoning blocks: a signature only exists natively and the visible answer lives in
     * the text blocks, so both modes may skip these. A turn left with nothing else is
     * still rejected unless it is the native projection. */
    private static boolean reasoningBlock(String type) {
        return "thinking".equals(type) || "redacted_thinking".equals(type);
    }

    // ===================== 请求：Anthropic → OpenAI =====================

    static JSONObject requestToOpenAI(JSONObject a) throws Exception { return requestToOpenAI(a, false); }
    // Only for native pass-through pipelines that enforce NativeAnthropicPolicy before dispatch.
    static JSONObject projectNativeRequest(JSONObject a) throws Exception { return requestToOpenAI(a, true); }
    private static JSONObject requestToOpenAI(JSONObject a, boolean projection) throws Exception {
        JSONObject out = new JSONObject();
        out.put("model", a.optString("model", ""));

        int maxTokens = 0;
        Object rawMaxTokens = a.has("max_tokens") ? a.opt("max_tokens") : null;
        if (rawMaxTokens != null && !JSONObject.NULL.equals(rawMaxTokens)) {
            if (!(rawMaxTokens instanceof Number)) throw new IllegalArgumentException("max_tokens must be a number");
            maxTokens = ((Number)rawMaxTokens).intValue();
        }
        out.put("max_tokens", maxTokens > 0 ? maxTokens : DEFAULT_MAX_TOKENS);

        // "temperature": null means the value is unset. Copying it through produced
        // a NaN that broke request serialisation, and string/bool values were
        // silently coerced. null and absent both stay absent; any other type is an
        // explicit error instead of a guess.
        copyFiniteNumber(out, a, "temperature");
        copyFiniteNumber(out, a, "top_p");
        if (a.has("stream")) {
            Object rawStream = a.opt("stream");
            if (rawStream != null && !JSONObject.NULL.equals(rawStream)) {
                if (!(rawStream instanceof Boolean)) throw new IllegalArgumentException("stream must be a boolean");
                out.put("stream", ((Boolean)rawStream).booleanValue());
            }
        }
        if (a.has("stop_sequences")) {
            Object rawStop = a.opt("stop_sequences");
            if (rawStop != null && !JSONObject.NULL.equals(rawStop)) {
                JSONArray stop = a.optJSONArray("stop_sequences");
                if (stop == null) throw new IllegalArgumentException("stop_sequences must be an array");
                for (int i = 0; i < stop.length(); i++)
                    if (!(stop.opt(i) instanceof String))
                        throw new IllegalArgumentException("stop_sequences[" + i + "] must be a string");
                if (stop.length() > 0) out.put("stop", stop);
            }
        }
        if (a.has("metadata")) {
            Object metadata = a.opt("metadata");
            if (metadata != null && !JSONObject.NULL.equals(metadata)) out.put("metadata", metadata);
        }

        // Project Anthropic reasoning controls into the internal OpenAI request. This
        // lets provider policies recreate adaptive effort when native relay is unavailable.
        if (a.has("thinking") && !JSONObject.NULL.equals(a.opt("thinking"))) {
            Object rawThinking = a.opt("thinking");
            if (!(rawThinking instanceof JSONObject)) throw new IllegalArgumentException("thinking must be an object");
            JSONObject thinking = (JSONObject) rawThinking;
            String type = thinking.optString("type", "");
            if ("disabled".equals(type)) out.put("reasoning_effort", "none");
            else if ("enabled".equals(type)) {
                Object rawBudget = thinking.opt("budget_tokens");
                if (rawBudget != null && !JSONObject.NULL.equals(rawBudget) && !(rawBudget instanceof Number))
                    throw new IllegalArgumentException("thinking.budget_tokens must be a number");
                int budget = thinking.optInt("budget_tokens", 0);
                out.put("reasoning_effort", budget >= 31999 ? "max"
                        : budget >= 16000 ? "high" : budget >= 8000 ? "medium" : "low");
            } else if ("adaptive".equals(type)) {
                JSONObject config = a.optJSONObject("output_config");
                String effort = config == null ? "high" : config.optString("effort", "high");
                if (!effort.isEmpty()) out.put("reasoning_effort", effort);
            } else throw new IllegalArgumentException("Unsupported thinking type");
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
                if (content.length() == 0)
                    throw new IllegalArgumentException("messages[" + i + "].content must not be empty");
                for (int j = 0; j < content.length(); j++)
                    if (content.optJSONObject(j) == null)
                        throw new IllegalArgumentException("messages[" + i + "].content[" + j + "] must be an object");
            }
            if (!assistant) {
                addUserContent(messages, c, projection, i);
                continue;
            }
            JSONObject msg = new JSONObject().put("role", "assistant");
            if (c instanceof String) { msg.put("content", c); messages.put(msg); continue; }
            JSONArray calls = new JSONArray();
            StringBuilder text = new StringBuilder();
            JSONArray blocks = (JSONArray)c;
            for (int k = 0; k < blocks.length(); k++) {
                JSONObject b = blocks.optJSONObject(k);
                String type = b.optString("type", "");
                if ("image".equals(type)) {
                    if (!projection) throw new IllegalArgumentException("Assistant image cannot be represented by Chat");
                    ImageContent.projectNative(b);
                } else if ("text".equals(type)) {
                    Object value = b.opt("text");
                    if (!(value instanceof String))
                        throw new IllegalArgumentException("messages[" + i + "].content[" + k + "].text must be a string");
                    text.append((String)value);
                } else if ("tool_use".equals(type)) {
                    String id = toolString(b, "id"), name = toolString(b, "name");
                    JSONObject input = b.optJSONObject("input");
                    if (input == null) throw new IllegalArgumentException("tool_use input must be an object");
                    JSONObject fn = new JSONObject().put("name", name).put("arguments", input.toString());
                    calls.put(new JSONObject().put("id", id).put("type", "function").put("function", fn));
                } else if (reasoningBlock(type)) {
                    // Native-only reasoning blocks have no Chat field to carry their
                    // signature, and they are not part of the visible answer.
                } else if (projection && nativePayloadOnly(type)) {
                    // Hosted document / server search result: only the native relay can
                    // forward it; the projection omits it and the request still requires
                    // the native path, so no conversion success is claimed.
                } else throw new IllegalArgumentException("messages[" + i + "].content[" + k + "] unsupported block type: " + type);
            }
            if (text.length() == 0 && calls.length() == 0) {
                if (!projection)
                    throw new IllegalArgumentException("messages[" + i + "].content has no representable block");
                // 原生直通请求（projectNativeRequest）允许只有推理块的 assistant 回合：thinking /
                // redacted_thinking 块在 Chat 里没有对应字段，真正的上游请求用的是未被改动的
                // NATIVE_REQUEST，这里的投影只是路由与治理用的载体。此前该回合投影成空内容，不能
                // 因为投影无法表达就拒绝整条合法请求（Claude Code 会带 signature 回传这类历史回合）。
                // 保留回合而不是删除，是为了不改变角色交替与消息序号；一旦原生直通不可用，
                // NativeAnthropicPolicy.checkNativeRequired 会直接拒绝请求，不会把这个空投影当成
                // 转换成功发出去。
                msg.put("content", "");
                messages.put(msg);
                continue;
            }
            msg.put("content", text.length() > 0 ? text.toString() : JSONObject.NULL);
            if (calls.length() > 0) msg.put("tool_calls", calls);
            messages.put(msg);
        }
        messages = pairToolMessages(joinSplitCalls(messages), true);

        out.put("messages", messages);

        // tools：input_schema → parameters
        JSONArray tools = null;
        if (a.has("tools")) {
            Object rawTools = a.opt("tools");
            if (rawTools != null && !JSONObject.NULL.equals(rawTools)) {
                tools = a.optJSONArray("tools");
                if (tools == null) throw new IllegalArgumentException("tools must be an array");
            }
        }
        if (tools != null && tools.length() > 0) {
            JSONArray ot = new JSONArray();
            for (int i = 0; i < tools.length(); i++) {
                JSONObject t = tools.optJSONObject(i);
                if (t == null) throw new IllegalArgumentException("tools[" + i + "] must be an object");
                Object rawName = t.opt("name");
                if (!(rawName instanceof String) || ((String)rawName).trim().isEmpty())
                    throw new IllegalArgumentException("tools[" + i + "].name must be a nonempty string");
                JSONObject fn = new JSONObject();
                fn.put("name", (String)rawName);
                fn.put("description", t.optString("description", ""));
                JSONObject schema = null;
                if (t.has("input_schema")) {
                    Object rawSchema = t.opt("input_schema");
                    if (rawSchema != null && !JSONObject.NULL.equals(rawSchema)) {
                        if (!(rawSchema instanceof JSONObject))
                            throw new IllegalArgumentException("tools[" + i + "].input_schema must be an object");
                        schema = (JSONObject)rawSchema;
                    }
                }
                fn.put("parameters", schema == null ? new JSONObject().put("type", "object") : schema);
                JSONObject wrap = new JSONObject();
                wrap.put("type", "function");
                wrap.put("function", fn);
                ot.put(wrap);
            }
            if (ot.length() > 0) out.put("tools", ot);
        }

        if (a.has("tool_choice")) {
            Object rawChoice = a.opt("tool_choice");
            if (rawChoice != null && !JSONObject.NULL.equals(rawChoice)) {
                if (!(rawChoice instanceof JSONObject)) throw new IllegalArgumentException("tool_choice must be an object");
                JSONObject o = (JSONObject) rawChoice;
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
                } else throw new IllegalArgumentException("Unsupported tool_choice type");
                if (choice.has("type")) out.put("tool_choice", choice);
            }
        }

        return out;
    }

    private static void addUserContent(JSONArray messages, Object content, boolean projection, int messageIndex) throws Exception {
        if (content instanceof String) { messages.put(new JSONObject().put("role", "user").put("content", content)); return; }
        JSONArray blocks = (JSONArray)content;
        JSONArray parts = new JSONArray();
        JSONArray results = new JSONArray();
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject b = blocks.optJSONObject(i);
            String type = b.optString("type");
            if ("tool_result".equals(type)) {
                JSONObject tool = new JSONObject().put("role", "tool").put("tool_call_id", toolString(b, "tool_use_id"));
                String body = flattenToolResult(b.opt("content"), projection);
                if (b.optBoolean("is_error", false)) body = "[Tool error] " + body;
                JSONArray images = new JSONArray();
                JSONArray result = b.optJSONArray("content");
                for (int j = 0; result != null && j < result.length(); j++) {
                    JSONObject item = result.optJSONObject(j);
                    if (item != null && "image".equals(item.optString("type"))) {
                        JSONObject image = projection ? ImageContent.projectNative(item) : imageBlockToOpenAI(item);
                        if (image == null) throw new IllegalArgumentException("invalid tool result image");
                        images.put(image);
                    }
                }
                if (images.length() > 0) {
                    if (!projection) ImageContent.keys(b, "type", "tool_use_id", "content", "is_error");
                    for(int j=0;j<result.length();j++) {
                        JSONObject part=result.optJSONObject(j);
                        if(part==null) throw new IllegalArgumentException("Unsupported tool result content");
                        String partType=part.optString("type");
                        if("text".equals(partType)) { if(!projection) ImageContent.keys(part,"type","text"); if(!(part.opt("text") instanceof String)) throw new IllegalArgumentException("Invalid tool result text"); }
                        else if(!"image".equals(partType) && !(projection && nativePayloadOnly(partType))) throw new IllegalArgumentException("Unsupported tool result content");
                    }
                    body += "\n[Images from this tool result follow.]"; tool.put("_images", images); }
                tool.put("content", body); results.put(tool);
            } else if ("text".equals(type)) {
                Object value = b.opt("text");
                if (!(value instanceof String))
                    throw new IllegalArgumentException("messages[" + messageIndex + "].content[" + i + "].text must be a string");
                parts.put(new JSONObject().put("type", "text").put("text", value));
            } else if ("image".equals(type)) {
                JSONObject image = projection ? ImageContent.projectNative(b) : imageBlockToOpenAI(b);
                if (image == null) throw new IllegalArgumentException("invalid user image");
                parts.put(image);
            } else if (projection && nativePayloadOnly(type)) {
                // Hosted document / server search result inside a user turn: same rule as
                // the assistant side. Strict conversion refuses it below instead of
                // pretending the block was converted.
            } else throw new IllegalArgumentException("messages[" + messageIndex + "].content[" + i + "] unsupported block type: " + type);
        }
        if (results.length() == 0 && parts.length() == 0) {
            if (!projection)
                throw new IllegalArgumentException("messages[" + messageIndex + "].content has no representable block");
            // The whole turn is native payload (a lone document, for example). The turn is
            // kept with empty text so roles and indices stay aligned, and the request still
            // requires the native relay, which forwards the untouched original block.
            messages.put(new JSONObject().put("role", "user").put("content", ""));
            return;
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

    // ============ Qoder 客户端历史修复（opt-in，只有 Qoder 适配器能消费） ============
    //
    // 为什么需要：0.2.64 的 qoderMessages 自己修「调用丢了 id」的历史（index.mjs:1156-1200、
    // :1250-1285，#290）：没带 id 的调用按「消息下标 + 调用下标 + 名称 + 参数」算出一个每次请求
    // 都一样的 id，丢了 id 的结果去回答本回合最早还没被回答、也没被别的结果点名的那个调用。
    // QoderProtocol.apply 逐条照做，所以网关这边只差一件事：历史要原样走到它手上。
    //
    // 而本类上面的 pairToolMessages 是所有上游共用的严格规范化，它在 apply 之前就把这类历史改掉
    // 了：丢 id 的调用直接抛错（tool call requires a nonempty string id）、没人回应的调用补一条
    // 「结果不可用」的凭据、重复的回答连同它的文字和图片一起丢掉。严格行为本身是对的（绝不能让
    // 上游读到客户端没说过的话），但它同时让 Qoder 永远收不到它自己能修的历史，请求在
    // normalizeCoreMessages 这一步就变成 400 了。
    //
    // 所以这里给 Qoder 一个独立入口：只补 id，顺序、配对、文字、图片、reasoning_content 一律
    // 原样不动，也绝不补「不存在的结果」。默认路径完全不经过它 —— normalizeOpenAiMessages 和
    // 所有非 Qoder 上游的行为一字未改。

    /** 只补 id 的 Qoder 历史修复（index.mjs:1166-1179 的 callID 语义）。
     *
     *  <p>对每条 assistant 回合的 tool_calls：id 缺失、为 null 或只有空白时，补上
     *  QoderProtocol.callID(消息下标, 调用下标, 调用) —— 与插件同一个算法、同一组入参，也与
     *  apply 自己对「没有 id 的调用」用的算法相同，所以两边不会各算一个 id。已经带 id 的调用、
     *  tool 回合、role 顺序、content（含 image_url 块）、reasoning_content 全部保持原样。
     *
     *  <p>不修改入参（返回深拷贝）。形状不对就抛错，绝不默默吞掉：消息不是对象、role 不是字符串、
     *  tool_calls 不是数组、tool_calls 的某一项不是对象 —— 这些都是调用方要看到的错误，不是
     *  可以绕过的事情。
     *
     *  <p>只有 QoderProtocol.apply 能消费返回值：非 Qoder 上游必须继续走
     *  normalizeOpenAiMessages 的严格规范化。 */
    static JSONArray repairQoderHistory(JSONArray input) throws Exception {
        if (input == null) throw new IllegalArgumentException("messages must be an array");
        JSONArray out = new JSONArray(input.toString());
        for (int i = 0; i < out.length(); i++) {
            JSONObject message = out.optJSONObject(i);
            if (message == null)
                throw new IllegalArgumentException("messages[" + i + "] must be an object");
            Object role = message.opt("role");
            if (!(role instanceof String) || ((String) role).trim().isEmpty())
                throw new IllegalArgumentException("messages[" + i + "].role must be a string");
            if (!message.has("tool_calls") || message.isNull("tool_calls")) continue;
            Object raw = message.opt("tool_calls");
            if (!(raw instanceof JSONArray))
                throw new IllegalArgumentException("tool_calls must be an array");
            JSONArray calls = (JSONArray) raw;
            for (int k = 0; k < calls.length(); k++) {
                JSONObject call = calls.optJSONObject(k);
                if (call == null)
                    throw new IllegalArgumentException("messages[" + i + "].tool_calls[" + k + "] must be an object");
                // 插件写的是 String(c.id ?? "").trim() || callID(...)：空白 id 等同于没有 id。
                Object id = call.opt("id");
                if (id instanceof String && !((String) id).trim().isEmpty()) continue;
                // out 是深拷贝，改这一项只改副本；callID 只用 function.name / function.arguments，
                // 和自己刚补上的 id 无关。
                call.put("id", QoderProtocol.callID(i, k, call));
            }
        }
        return out;
    }

    /** 接线用（本类不调用）：Qoder 适配器能不能按原样（只补 id）吃下这份历史。
     *
     *  <p>true 表示 repairQoderHistory 不会对形状挑毛病：messages 是数组、每条都是对象、role 是
     *  非空字符串、tool_calls 是对象数组。false 表示这份历史连「补完 id 交给 apply」都做不到，
     *  那就保持今天的拒绝，不要把它当可修的历史放行。
     *
     *  <p>为什么判据不是 QoderProtocol.validateClientHistory：它是原始历史校验，而且比插件严格。
     *  它不只要求调用有 id（addCall 里 required(id)），还要求每条 tool 回合自己带一个能对上的
     *  tool_call_id（answer 里 required(id)）——只把调用 id 补上并不能让它接受一份「结果也丢了
     *  id」的历史，所以它根本当不了「这份历史能不能修」的判据。插件 0.2.64 对这类历史的态度是
     *  修（index.mjs:1255-1266），Qoder 上游要的也只是「tool 回合必须回答前面某个调用」，而这两点
     *  都由 apply 保证。所以放宽的是这份原始校验，不是把坏形状也放进来。 */
    static boolean qoderHistoryRepairable(JSONObject request) {
        if (request == null || request.optJSONArray("messages") == null) return false;
        try {
            repairQoderHistory(request.getJSONArray("messages"));
            return true;
        } catch (Exception shape) {
            return false;
        }
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
        if (system == null || JSONObject.NULL.equals(system)) return "";
        if (system instanceof String) return (String) system;
        if (system instanceof JSONArray) {
            StringBuilder sb = new StringBuilder();
            JSONArray arr = (JSONArray) system;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject b = arr.optJSONObject(i);
                if (b == null) throw new IllegalArgumentException("system[" + i + "] must be an object");
                String type = b.optString("type", "text");
                if (!"text".equals(type)) throw new IllegalArgumentException("Unsupported system content");
                Object value = b.opt("text");
                if (!(value instanceof String)) throw new IllegalArgumentException("system[" + i + "].text must be a string");
                String t = (String)value;
                if (t.trim().isEmpty()) continue;
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(t);
            }
            return sb.toString();
        }
        // Anything else (object/number/bool) used to become a JSON blob prompt.
        throw new IllegalArgumentException("system must be a string or an array of text blocks");
    }

    private static String flattenToolResult(Object content, boolean projection) {
        if (content == null || JSONObject.NULL.equals(content)) return "";
        if (content instanceof String) return (String) content;
        if (content instanceof JSONArray) {
            StringBuilder sb = new StringBuilder();
            JSONArray arr = (JSONArray) content;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject b = arr.optJSONObject(i);
                if (b == null) throw new IllegalArgumentException("Unsupported tool result content");
                String type = b.optString("type", "text");
                if ("text".equals(type)) {
                    Object value = b.opt("text");
                    if (!(value instanceof String)) throw new IllegalArgumentException("Invalid tool result text");
                    sb.append((String)value);
                } else if (!"image".equals(type) && !(projection && nativePayloadOnly(type))) {
                    // Images are carried separately as _images; any other block
                    // would otherwise be dropped without a trace. Native payload blocks
                    // are the exception: they travel in the untouched native body.
                    throw new IllegalArgumentException("Unsupported tool result content");
                }
            }
            return sb.toString();
        }
        throw new IllegalArgumentException("tool_result content must be a string or an array");
    }

    private static JSONObject imageBlockToOpenAI(JSONObject block) throws Exception {
        return ImageContent.fromAnthropic(block);
    }

    // ===================== 响应：OpenAI → Anthropic =====================

    static String stopReasonOf(String finishReason) {
        if ("stop".equals(finishReason)) return "end_turn";
        if ("length".equals(finishReason)) return "max_tokens";
        if ("tool_calls".equals(finishReason) || "function_call".equals(finishReason)) return "tool_use";
        if ("content_filter".equals(finishReason)) return "refusal";
        throw new IllegalArgumentException("Missing or unsupported upstream finish reason");
    }

    /** Upstream message content: a string, null/absent (empty), or an array of
     * text parts. Anything else is an explicit error instead of a silent empty
     * "successful" answer. */
    private static String responseText(Object content) {
        if (content == null || JSONObject.NULL.equals(content)) return "";
        if (content instanceof String) return (String) content;
        if (content instanceof JSONArray) {
            JSONArray parts = (JSONArray) content;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part == null || !"text".equals(part.optString("type", "")))
                    throw new IllegalArgumentException("Unsupported response content");
                Object value = part.opt("text");
                if (!(value instanceof String)) throw new IllegalArgumentException("Unsupported response content");
                sb.append((String)value);
            }
            return sb.toString();
        }
        throw new IllegalArgumentException("Unsupported response content");
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
            if (openai.has("error")) throw new IllegalArgumentException("Upstream returned an error");
            String finish;

            JSONArray choices = openai.optJSONArray("choices");
            if (choices == null || choices.length() == 0 || choices.optJSONObject(0) == null)
                throw new IllegalArgumentException("Missing upstream choice");
            {
                JSONObject choice = choices.optJSONObject(0);
                Object rawFinish = choice.opt("finish_reason");
                finish = rawFinish instanceof String ? (String) rawFinish : null;
                stopReasonOf(finish);
                JSONObject msg = choice.optJSONObject("message");
                if (msg == null) throw new IllegalArgumentException("Missing upstream message");
                {
                    Object rawReasoning = msg.has("reasoning_content") ? msg.opt("reasoning_content") : null;
                    String reasoning = "";
                    if (rawReasoning != null && !JSONObject.NULL.equals(rawReasoning)) {
                        if (!(rawReasoning instanceof String)) throw new IllegalArgumentException("Invalid reasoning_content");
                        reasoning = (String)rawReasoning;
                    }
                    if (!reasoning.isEmpty()) {
                        JSONObject th = new JSONObject();
                        th.put("type", "thinking");
                        th.put("thinking", reasoning);
                        th.put("signature", "");
                        content.put(th);
                    }
                    Object c = msg.opt("content");
                    String text = responseText(c);
                    if (!text.isEmpty()) {
                        JSONObject t = new JSONObject();
                        t.put("type", "text");
                        t.put("text", text);
                        content.put(t);
                    }
                    if (msg.has("refusal") && !JSONObject.NULL.equals(msg.opt("refusal"))) {
                        Object refusalValue = msg.opt("refusal");
                        if (!(refusalValue instanceof String))
                            throw new IllegalArgumentException("Invalid upstream refusal");
                        // Providers commonly send "refusal": "" when there is none.
                        if (!((String)refusalValue).isEmpty()) {
                            JSONObject refusalText = new JSONObject(); refusalText.put("type", "text"); refusalText.put("text", (String)refusalValue); content.put(refusalText);
                            if ("stop".equals(finish)) finish = "content_filter";
                        }
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
        private boolean refusal;
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
                        if (fr != null && !fr.isEmpty() && !"null".equals(fr)) finish = fr;

                        if (delta != null) {
                            // 注意：Android 自带的 org.json 对 JSONObject.NULL 调 optString 会返回字符串
                            // "null"（桌面版 json.jar 返回 ""）。上游最后一个 chunk 常带 "content": null，
                            // 不判 NULL 就会多出一个内容为 "null" 的文本块。
                            Object rawReason = delta.opt("reasoning_content");
                            String reasoning = deltaString(rawReason, "reasoning_content");
                            if (!reasoning.isEmpty()) {
                                outs.addAll(thinkingDelta(reasoning));
                            }
                            Object rawText = delta.opt("content");
                            String text = deltaString(rawText, "content");
                            if (!text.isEmpty()) {
                                outs.addAll(textDelta(text));
                            }
                            if (delta.has("refusal") && !JSONObject.NULL.equals(delta.opt("refusal"))) {
                                Object refusalValue = delta.opt("refusal");
                                if (!(refusalValue instanceof String)) throw new IllegalArgumentException("stream refusal must be a string");
                                String refusalText = (String)refusalValue;
                                if (!refusalText.isEmpty()) { refusal = true; outs.addAll(textDelta(refusalText)); }
                            }
                            JSONArray calls = delta.optJSONArray("tool_calls");
                            if (delta.has("tool_calls") && !delta.isNull("tool_calls") && calls == null)
                                throw new IllegalArgumentException("tool_calls must be an array");
                            for (int i = 0; calls != null && i < calls.length(); i++) {
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

        // A non-string delta field used to be stringified into the answer (a JSON
        // blob became the model reply). Only null/absent means "no content".
        private static String deltaString(Object value, String field) {
            if (value == null || JSONObject.NULL.equals(value)) return "";
            if (!(value instanceof String)) throw new IllegalArgumentException("stream delta " + field + " must be a string");
            return (String)value;
        }

        /** Chat streams identity as fragments. If an upstream re-sends the complete
         * id/name in every delta instead, appending it duplicates the identity
         * ("call_1call_1"); a fragment identical to the value accumulated so far is
         * therefore treated as a repeated complete snapshot. Every other fragment
         * is concatenated verbatim: repeated but non-equal text and different
         * values are never deduplicated or overwritten, so a real fragment cannot
         * be lost (see ToolCallContractOfflineTest:repeated_fragment_text_not_guessed). */
        private static String joinIdentity(String current, String fragment) {
            if (fragment.isEmpty()) return current;
            if (current.isEmpty()) return fragment;
            if (fragment.equals(current)) return current; // repeated complete snapshot
            return current + fragment;                    // genuine fragment
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
            block.id = joinIdentity(block.id, id);
            block.name = joinIdentity(block.name, name);
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
                stopReasonOf(finish);
                if (refusal && "stop".equals(finish)) finish = "content_filter";
                java.util.Set<String> ids = new java.util.HashSet<String>();
                for (DeferredBlock block : deferred) {
                    if (!"tool_use".equals(block.type)) continue;
                    if (block.id.trim().isEmpty() || block.name.trim().isEmpty())
                        return onError("Upstream tool call is missing an id or name");
                    if (!ids.add(block.id)) return onError("Upstream tool calls have duplicate ids");
                    // Messages requires an object input. Validate the assembled
                    // string without rewriting the original argument fragments.
                    // Every shape is distinguished instead of guessed: no argument
                    // text, truncated text and non-object text all fail explicitly,
                    // because zero bytes cannot be told apart from a dropped or
                    // truncated payload. Only a complete object is published.
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
