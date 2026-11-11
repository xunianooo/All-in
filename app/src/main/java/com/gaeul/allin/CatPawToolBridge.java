package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Adapts the CatPaw plugin's text-only JSON tool protocol to OpenAI tool calls.
 * This is a textual convention: CatPaw does not execute or acknowledge tools natively, so the bridge can only
 * recognize a whole JSON reply and validate the schema subset it understands. A valid envelope does not prove
 * that a tool ran. A new reply's calls are checked against the declared type and required keys (including nested
 * object/array values); keywords such as enum, format, pattern, numeric bounds, and conditional schemas are
 * outside this validator. A past call in the history is only rendered as prompt text and is not schema-checked.
 * Tool extraction requires the npm reader's [DONE] terminal; legacy text EOF fallback remains available
 * when tools are disabled. A complete-looking cumulative JSON snapshot is not a confirmed turn.
 */
public final class CatPawToolBridge {
    private CatPawToolBridge() {}

    private static final class Choice {
        String mode = "auto";
        String name;
    }

    private static Choice choice(Object value) {
        Choice c = new Choice();
        if (value == null || value == JSONObject.NULL) return c;
        if (value instanceof String) {
            String s = (String) value;
            if ("none".equals(s) || "auto".equals(s) || "required".equals(s) || "any".equals(s)) c.mode = s;
            else { c.mode = "named"; c.name = s; }
        } else if (value instanceof JSONObject) {
            JSONObject o = (JSONObject) value;
            c.mode = o.optString("type", "auto");
            if ("tool".equals(c.mode) || "function".equals(c.mode)) {
                JSONObject f = o.optJSONObject("function");
                c.name = f == null ? o.optString("name", "") : f.optString("name", "");
                c.mode = "named";
            }
        } else throw new IllegalArgumentException("invalid tool_choice");
        if ("any".equals(c.mode)) c.mode = "required";
        if (!"auto".equals(c.mode) && !"none".equals(c.mode)
                && !"required".equals(c.mode) && !"named".equals(c.mode))
            throw new IllegalArgumentException("invalid tool_choice");
        if ("named".equals(c.mode) && (c.name == null || c.name.trim().isEmpty()))
            throw new IllegalArgumentException("missing tool name");
        return c;
    }

    private static JSONObject functionOf(JSONObject tool) {
        JSONObject function = tool.optJSONObject("function");
        return function == null ? tool : function;
    }

    private static String nameOf(JSONObject tool) { return functionOf(tool).optString("name", ""); }

    private static JSONObject schemaOf(JSONObject tool) {
        JSONObject function = functionOf(tool);
        JSONObject schema = function.optJSONObject("parameters");
        if (schema == null) schema = tool.optJSONObject("input_schema");
        if (schema == null) schema = tool.optJSONObject("parameters");
        return schema == null ? new JSONObject() : schema;
    }

    private static Map<String, JSONObject> declarations(JSONArray tools) {
        Map<String, JSONObject> known = new HashMap<>();
        if (tools == null) return known;
        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.optJSONObject(i);
            if (tool == null || (tool.has("type") && !"function".equals(tool.optString("type"))))
                throw new IllegalArgumentException("invalid tool declaration");
            String name = nameOf(tool);
            if (name.trim().isEmpty() || known.put(name.toLowerCase(Locale.ROOT), tool) != null)
                throw new IllegalArgumentException("invalid or duplicate tool name");
            validateSchemaDefinition(schemaOf(tool), 0);
        }
        return known;
    }

    private static Choice validate(JSONArray tools, Object rawChoice) {
        Choice c = choice(rawChoice);
        Map<String, JSONObject> known = declarations(tools);
        if (("required".equals(c.mode) || "named".equals(c.mode)) && known.isEmpty())
            throw new IllegalArgumentException("tool choice requires tools");
        if ("named".equals(c.mode)) {
            JSONObject declaration = known.get(c.name.toLowerCase(Locale.ROOT));
            if (declaration == null) throw new IllegalArgumentException("unknown forced tool");
            c.name = nameOf(declaration);
        }
        return c;
    }

    /** Reject schema constructs whose declared type/required constraints cannot be checked here. */
    private static void validateSchemaDefinition(JSONObject schema, int depth) {
        if (depth > 32) throw new IllegalArgumentException("tool schema too deep");
        Object type = schema.opt("type");
        if (type != null && type != JSONObject.NULL) {
            if (type instanceof String) validateTypeName((String) type);
            else if (type instanceof JSONArray) {
                JSONArray types = (JSONArray) type;
                if (types.length() == 0) throw new IllegalArgumentException("empty schema type");
                for (int i = 0; i < types.length(); i++) {
                    Object item = types.opt(i);
                    if (!(item instanceof String)) throw new IllegalArgumentException("invalid schema type");
                    validateTypeName((String) item);
                }
            } else throw new IllegalArgumentException("invalid schema type");
        }
        Object requiredValue = schema.opt("required");
        if (requiredValue != null && requiredValue != JSONObject.NULL) {
            if (!(requiredValue instanceof JSONArray)) throw new IllegalArgumentException("invalid schema required");
            Set<String> required = new HashSet<>();
            JSONArray list = (JSONArray) requiredValue;
            for (int i = 0; i < list.length(); i++) {
                Object item = list.opt(i);
                if (!(item instanceof String) || !required.add((String) item))
                    throw new IllegalArgumentException("invalid schema required");
            }
        }
        Object propertiesValue = schema.opt("properties");
        if (propertiesValue != null && propertiesValue != JSONObject.NULL) {
            if (!(propertiesValue instanceof JSONObject)) throw new IllegalArgumentException("invalid schema properties");
            JSONObject properties = (JSONObject) propertiesValue;
            Iterator<String> keys = properties.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject child = properties.optJSONObject(key);
                if (child == null) throw new IllegalArgumentException("invalid property schema");
                validateSchemaDefinition(child, depth + 1);
            }
        }
        Object itemsValue = schema.opt("items");
        if (itemsValue != null && itemsValue != JSONObject.NULL) {
            if (!(itemsValue instanceof JSONObject)) throw new IllegalArgumentException("invalid schema items");
            validateSchemaDefinition((JSONObject) itemsValue, depth + 1);
        }
        Object additional = schema.opt("additionalProperties");
        if (additional instanceof JSONObject) validateSchemaDefinition((JSONObject) additional, depth + 1);
        else if (additional != null && additional != JSONObject.NULL && !(additional instanceof Boolean))
            throw new IllegalArgumentException("invalid additionalProperties");
    }

    private static void validateTypeName(String type) {
        if (!"object".equals(type) && !"array".equals(type) && !"string".equals(type)
                && !"number".equals(type) && !"integer".equals(type)
                && !"boolean".equals(type) && !"null".equals(type))
            throw new IllegalArgumentException("unsupported schema type");
    }

    private static String textContent(Object value) {
        if (value instanceof String) return (String) value;
        if (!(value instanceof JSONArray)) throw new IllegalArgumentException("CatPaw supports text content only");
        JSONArray parts = (JSONArray) value;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part == null || !"text".equals(part.optString("type")) || !(part.opt("text") instanceof String))
                throw new IllegalArgumentException("CatPaw supports text content only");
            text.append(part.optString("text"));
        }
        return text.toString();
    }

    private static void requireCallId(Object id) {
        if (!(id instanceof String) || ((String) id).trim().isEmpty())
            throw new IllegalArgumentException("missing tool call id");
    }

    /**
     * The arguments of a past call as the prompt shows them: the text the model wrote,
     * or the JSON value it produced. No schema check — a history entry is prompt text,
     * not a call anything executes.
     */
    private static String renderArguments(Object raw) {
        if (raw == null || raw == JSONObject.NULL) return "{}";
        if (raw instanceof String) {
            String text = ((String) raw).trim();
            return text.isEmpty() ? "{}" : text;
        }
        if (raw instanceof JSONObject || raw instanceof JSONArray) return raw.toString();
        if (raw instanceof Number || raw instanceof Boolean) return String.valueOf(raw);
        throw new IllegalArgumentException("invalid tool arguments");
    }

    private static JSONObject parseArguments(Object raw) {
        if (raw instanceof JSONObject) return (JSONObject) raw;
        if (raw instanceof String) {
            try { new BoundedStrictJson((String) raw).run(); return new JSONObject((String) raw); }
            catch (Exception e) { throw new IllegalArgumentException("invalid tool arguments JSON"); }
        }
        throw new IllegalArgumentException("tool arguments must be an object");
    }

    private static boolean typeMatches(Object value, String type) {
        if ("null".equals(type)) return value == null || value == JSONObject.NULL;
        if (value == null || value == JSONObject.NULL) return false;
        if ("object".equals(type)) return value instanceof JSONObject;
        if ("array".equals(type)) return value instanceof JSONArray;
        if ("string".equals(type)) return value instanceof String;
        if ("boolean".equals(type)) return value instanceof Boolean;
        if ("number".equals(type)) return value instanceof Number && Double.isFinite(((Number) value).doubleValue());
        if ("integer".equals(type)) return value instanceof Number
                && Double.isFinite(((Number) value).doubleValue())
                && ((Number) value).doubleValue() == Math.rint(((Number) value).doubleValue());
        return false;
    }

    private static boolean matchesDeclaredType(Object value, Object declared) {
        if (declared instanceof String) return typeMatches(value, (String) declared);
        JSONArray types = (JSONArray) declared;
        for (int i = 0; i < types.length(); i++) if (typeMatches(value, types.optString(i))) return true;
        return false;
    }

    private static void validateValue(Object value, JSONObject schema, int depth) {
        if (depth > 32) throw new IllegalArgumentException("tool arguments too deep");
        Object declared = schema.opt("type");
        if ((declared == null || declared == JSONObject.NULL) && (schema.has("properties") || schema.has("required"))) declared = "object";
        if (declared != null && declared != JSONObject.NULL && !matchesDeclaredType(value, declared))
            throw new IllegalArgumentException("tool argument type mismatch");

        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONArray required = schema.optJSONArray("required");
            if (required != null) for (int i = 0; i < required.length(); i++) {
                String key = required.optString(i, "");
                if (key.isEmpty() || !object.has(key)) throw new IllegalArgumentException("missing required tool argument");
            }
            JSONObject properties = schema.optJSONObject("properties");
            Object additional = schema.opt("additionalProperties");
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject property = properties == null ? null : properties.optJSONObject(key);
                if (property != null) validateValue(object.opt(key), property, depth + 1);
                else if (Boolean.FALSE.equals(additional)) throw new IllegalArgumentException("unexpected tool argument");
                else if (additional instanceof JSONObject) validateValue(object.opt(key), (JSONObject) additional, depth + 1);
            }
        } else if (value instanceof JSONArray) {
            JSONObject items = schema.optJSONObject("items");
            if (items != null) {
                JSONArray array = (JSONArray) value;
                for (int i = 0; i < array.length(); i++) validateValue(array.opt(i), items, depth + 1);
            }
        }
    }

    private static void validateArguments(JSONObject args, JSONObject declaration) {
        validateValue(args, schemaOf(declaration), 0);
    }

    private static String instruction(JSONArray tools, Choice choice) {
        StringBuilder out = new StringBuilder();
        out.append("You can call tools using a strict JSON text response. When calling tools, return JSON only, without prose. ")
                .append("Use exactly: {\"tool_calls\":[{\"name\":\"ToolName\",\"arguments\":{\"arg\":\"value\"}}]}.\n");
        if ("required".equals(choice.mode)) out.append("tool_choice=required: a tool call is required now; plain text is invalid.\n");
        else if ("named".equals(choice.mode)) out.append("tool_choice forces tool \"").append(choice.name)
                .append("\": call ONLY this tool; plain text is invalid.\n");
        else out.append("Call tools when the request needs them; answer normally for pure conversation.\n");
        out.append("Available tools:\n");
        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.getJSONObject(i);
            JSONObject function = functionOf(tool);
            out.append("- ").append(nameOf(tool)).append(": ")
                    .append(function.optString("description", tool.optString("description", "No description")))
                    .append("; parameters ").append(schemaOf(tool).toString()).append('\n');
        }
        return out.toString();
    }

    /** Validate and flatten the text-only message history before any upstream request. */
    public static JSONArray prepare(JSONArray messages, JSONArray tools, Object toolChoice) throws Exception {
        Choice selected = validate(tools, toolChoice);
        if (messages == null || messages.length() == 0) throw new IllegalArgumentException("missing messages");
        Map<String, JSONObject> known = declarations(tools);
        Set<String> pending = new HashSet<>();
        Set<String> seenIds = new HashSet<>();
        JSONArray flattened = new JSONArray();
        boolean addInstruction = tools != null && tools.length() > 0 && !"none".equals(selected.mode);
        /* The plugin brackets the history with the instruction: once before it and once
         * after, so the model reads the protocol again right before it answers. */
        String toolInstruction = addInstruction ? instruction(tools, selected) : "";
        if (addInstruction)
            flattened.put(new JSONObject().put("role", "system").put("content", toolInstruction));

        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) throw new IllegalArgumentException("invalid message");
            String role = message.optString("role", "");
            if ("tool".equals(role)) {
                Object id = message.opt("tool_call_id");
                requireCallId(id);
                String key = (String) id;
                if (!pending.remove(key)) throw new IllegalArgumentException("orphan or duplicate tool result");
                flattened.put(new JSONObject().put("role", "user")
                        .put("content", "Tool result for " + key + ":\n" + textContent(message.opt("content"))));
                continue;
            }
            if (message.has("tool_call_id")) throw new IllegalArgumentException("tool_call_id requires tool role");
            if (message.has("function_call")) throw new IllegalArgumentException("legacy function history unsupported");
            JSONArray calls = message.optJSONArray("tool_calls");
            if (message.has("tool_calls") && calls == null) throw new IllegalArgumentException("invalid tool_calls");
            if (calls != null && calls.length() > 0) {
                if (!"assistant".equals(role)) throw new IllegalArgumentException("tool calls require assistant role");
                if (!pending.isEmpty()) throw new IllegalArgumentException("unanswered tool call");
                String assistantText = "";
                Object content = message.opt("content");
                if (content != null && content != JSONObject.NULL) assistantText = textContent(content);
                StringBuilder historyText = new StringBuilder(assistantText);
                for (int j = 0; j < calls.length(); j++) {
                    JSONObject call = calls.optJSONObject(j);
                    if (call == null) throw new IllegalArgumentException("invalid tool call");
                    Object id = call.opt("id");
                    requireCallId(id);
                    String key = (String) id;
                    if (!seenIds.add(key) || !pending.add(key)) throw new IllegalArgumentException("duplicate tool call id");
                    if (call.has("type") && !"function".equals(call.optString("type")))
                        throw new IllegalArgumentException("invalid tool call type");
                    JSONObject function = call.optJSONObject("function");
                    if (function == null) throw new IllegalArgumentException("invalid function call");
                    String name = function.optString("name", "");
                    JSONObject declaration = known.get(name.toLowerCase(Locale.ROOT));
                    if (declaration == null) throw new IllegalArgumentException("unknown tool in history");
                    // The history is flattened to prompt text, never executed: the plugin renders
                    // the arguments the model wrote and only a new reply is schema-checked, so one
                    // past call that missed its schema does not fail every later turn.
                    String rendered = renderArguments(function.has("arguments")
                            ? function.opt("arguments") : function.opt("input"));
                    if (historyText.length() > 0) historyText.append('\n');
                    historyText.append("Tool call requested: ").append(nameOf(declaration)).append(' ').append(rendered);
                }
                flattened.put(new JSONObject().put("role", "assistant").put("content", historyText.toString()));
                continue;
            }
            if (!pending.isEmpty()) throw new IllegalArgumentException("unanswered tool call");
            if (!"system".equals(role) && !"developer".equals(role)
                    && !"user".equals(role) && !"assistant".equals(role))
                throw new IllegalArgumentException("unsupported role");
            String content = textContent(message.opt("content"));
            String normalizedRole = "developer".equals(role) ? "system" : role;
            flattened.put(new JSONObject().put("role", normalizedRole).put("content", content));
        }
        if (!pending.isEmpty()) throw new IllegalArgumentException("unanswered tool call");
        if (addInstruction)
            flattened.put(new JSONObject().put("role", "system").put("content", toolInstruction));
        if (CatPawAuth.unsupportedReason(flattened, null) != null)
            throw new IllegalArgumentException("unsupported message");
        return flattened;
    }

    private static String callName(JSONObject call) {
        JSONObject function = call.optJSONObject("function");
        if (function != null && !function.optString("name", "").isEmpty()) return function.optString("name", "");
        String name = call.optString("name", "");
        return name.isEmpty() ? call.optString("tool", "") : name;
    }

    private static Object callArguments(JSONObject call) {
        Object args = call.opt("arguments");
        if (args == null || args == JSONObject.NULL) args = call.opt("input");
        if (args == null || args == JSONObject.NULL) args = call.opt("parameters");
        if (args == null || args == JSONObject.NULL) {
            JSONObject function = call.optJSONObject("function");
            if (function != null) args = function.opt("arguments");
        }
        return args;
    }

    private static JSONArray envelopeCalls(Object parsed) {
        if (parsed instanceof JSONArray) return (JSONArray) parsed;
        if (!(parsed instanceof JSONObject)) return null;
        JSONObject object = (JSONObject) parsed;
        JSONArray calls = object.optJSONArray("tool_calls");
        if (calls != null) return calls;
        calls = object.optJSONArray("tool_uses");
        if (calls != null) return calls;
        String name = callName(object);
        Object arguments = callArguments(object);
        if (!name.isEmpty() && arguments != null && arguments != JSONObject.NULL) return new JSONArray().put(object);
        return null;
    }

    /**
     * Parse a whole JSON envelope (or one fenced JSON block); reject the entire batch on any invalid call.
     * Checks declared types and required keys, including nested properties/items. It does not implement every
     * JSON Schema assertion (for example enum, format, pattern, or numeric bounds).
     */
    public static JSONArray parse(String response, JSONArray tools, Object toolChoice) throws Exception {
        Choice selected = validate(tools, toolChoice);
        JSONArray result = new JSONArray();
        if ("none".equals(selected.mode) || tools == null || tools.length() == 0) return result;
        String raw = response == null ? "" : response.trim();
        if (raw.isEmpty()) return result;
        if (raw.matches("(?is)^```(?:json)?\\s*[\\s\\S]*?\\s*```$"))
            raw = raw.replaceFirst("(?is)^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
        Object parsed;
        try {
            new BoundedStrictJson(raw).run();
            org.json.JSONTokener tokener = new org.json.JSONTokener(raw);
            parsed = tokener.nextValue();
            if (tokener.nextClean() != 0) return result;
        } catch (Exception e) { return result; }
        JSONArray calls = envelopeCalls(parsed);
        if (calls == null || calls.length() == 0) return result;
        Map<String, JSONObject> known = declarations(tools);
        JSONArray pending = new JSONArray();
        for (int i = 0; i < calls.length(); i++) {
            JSONObject call = calls.optJSONObject(i);
            if (call == null) return new JSONArray();
            String rawName = callName(call);
            JSONObject declaration = known.get(rawName.toLowerCase(Locale.ROOT));
            if (declaration == null) return new JSONArray();
            String name = nameOf(declaration);
            if ("named".equals(selected.mode) && !name.equals(selected.name)) return new JSONArray();
            Object rawArgs = callArguments(call);
            if (rawArgs == null || rawArgs == JSONObject.NULL) return new JSONArray();
            JSONObject args;
            try {
                args = parseArguments(rawArgs);
                validateArguments(args, declaration);
            } catch (Exception invalid) { return new JSONArray(); }
            pending.put(new JSONObject().put("id", "call_" + UUID.randomUUID().toString().replace("-", ""))
                    .put("type", "function")
                    .put("function", new JSONObject().put("name", name).put("arguments", args.toString())));
        }
        return pending;
    }

    public static boolean required(Object value) { String mode = choice(value).mode; return "required".equals(mode) || "named".equals(mode); }

    private static String forcedRetryMessage(Choice choice) {
        StringBuilder message = new StringBuilder("Your previous reply did not include a valid tool call.\n");
        if ("named".equals(choice.mode)) message.append("Return ONLY JSON tool_calls that invoke \"")
                .append(choice.name).append("\". Use arguments satisfying that tool's schema above. No markdown, no explanation.");
        else message.append("Return ONLY a JSON tool_calls payload now, with arguments satisfying the declared schema. No markdown, no explanation.");
        return message.toString();
    }

    private static void requireCompleteToolTurn(JSONObject response, JSONArray tools, Choice selected)
            throws CatPawAuth.Failure {
        if (!"none".equals(selected.mode) && tools != null && tools.length() > 0
                && !Boolean.TRUE.equals(response.opt("turnComplete")))
            throw new CatPawAuth.Failure(502, null, true);
    }

    public static JSONObject complete(String token, String model, JSONArray messages, JSONArray tools,
                                      Object toolChoice, Integer modelType, CatPawAuth.ChatTransport transport) throws Exception {
        final JSONArray prepared;
        final Choice selected;
        try {
            selected = validate(tools, toolChoice);
            prepared = prepare(messages, tools, toolChoice);
        } catch (Exception e) { throw new CatPawAuth.Failure(400, null, false); }

        JSONObject response = transport == null
                ? CatPawAuth.chatWithCatalogMetadata(token, model, prepared, modelType)
                : CatPawAuth.chatWithCatalogMetadata(token, model, prepared, modelType, transport);
        requireCompleteToolTurn(response, tools, selected);
        JSONArray calls = parse(response.optString("text", ""), tools, toolChoice);
        if (calls.length() == 0 && ("required".equals(selected.mode) || "named".equals(selected.mode))) {
            // Retry only after the first chat call returned normally. A transport failure is ambiguous and is never replayed.
            JSONArray retryMessages = new JSONArray(prepared.toString());
            retryMessages.put(new JSONObject().put("role", "assistant").put("content", response.optString("text", "")));
            retryMessages.put(new JSONObject().put("role", "user").put("content", forcedRetryMessage(selected)));
            response = transport == null
                    ? CatPawAuth.chatWithCatalogMetadata(token, model, retryMessages, modelType)
                    : CatPawAuth.chatWithCatalogMetadata(token, model, retryMessages, modelType, transport);
            requireCompleteToolTurn(response, tools, selected);
            calls = parse(response.optString("text", ""), tools, toolChoice);
            if (calls.length() == 0) throw new CatPawAuth.Failure(502, null, true);
        }
        response.put("tool_calls", calls);
        return response;
    }

    /** Buffered completion: emit OpenAI tool-call deltas only after the text turn has returned. */
    public static String bufferedStream(JSONObject response, String model, String id, long created) throws Exception {
        StringBuilder out = new StringBuilder();
        appendChunk(out, model, id, created, new JSONObject().put("role", "assistant"), null);
        JSONArray calls = response.optJSONArray("tool_calls");
        if (calls != null && calls.length() > 0) {
            for (int i = 0; i < calls.length(); i++) {
                JSONObject call = new JSONObject(calls.getJSONObject(i).toString());
                call.put("index", i);
                appendChunk(out, model, id, created, new JSONObject().put("tool_calls", new JSONArray().put(call)), null);
            }
        } else {
            JSONObject delta = new JSONObject().put("content", response.optString("text", ""));
            if (!response.optString("reasoning", "").isEmpty()) delta.put("reasoning_content", response.optString("reasoning"));
            appendChunk(out, model, id, created, delta, null);
        }
        appendChunk(out, model, id, created, new JSONObject(), calls != null && calls.length() > 0 ? "tool_calls" : "stop");
        return out.append("data: [DONE]\n\n").toString();
    }

    private static void appendChunk(StringBuilder out, String model, String id, long created,
                                    JSONObject delta, String finish) throws Exception {
        JSONObject choice = new JSONObject().put("index", 0).put("delta", delta)
                .put("finish_reason", finish == null ? JSONObject.NULL : finish);
        JSONObject frame = new JSONObject().put("id", id).put("object", "chat.completion.chunk")
                .put("created", created).put("model", model).put("choices", new JSONArray().put(choice));
        out.append("data: ").append(frame.toString()).append("\n\n");
    }
}
