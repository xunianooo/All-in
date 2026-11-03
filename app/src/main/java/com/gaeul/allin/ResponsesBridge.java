package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Stateless request translation between Responses and the gateway's Chat representation. */
final class ResponsesBridge {
    private static final Set<String> FIELDS = new HashSet<String>(Arrays.asList(
            "model", "input", "instructions", "stream", "max_output_tokens", "temperature", "top_p",
            "tools", "tool_choice", "parallel_tool_calls", "reasoning", "text", "store"));

    static JSONObject toChat(JSONObject request) throws Exception {
        for (Iterator<String> keys = request.keys(); keys.hasNext();) {
            String key = keys.next();
            if (!FIELDS.contains(key)) throw new IllegalArgumentException("Unsupported Responses field: " + key);
        }
        if (request.optBoolean("store", false)) throw new IllegalArgumentException("store=true is not supported");
        JSONObject out = new JSONObject();
        copy(request, out, "model", "model"); copy(request, out, "stream", "stream");
        copy(request, out, "max_output_tokens", "max_tokens"); copy(request, out, "temperature", "temperature");
        copy(request, out, "top_p", "top_p"); copy(request, out, "parallel_tool_calls", "parallel_tool_calls");
        JSONObject reasoning = request.optJSONObject("reasoning");
        if (reasoning != null) {
            for (Iterator<String> it = reasoning.keys(); it.hasNext();) {
                String key = it.next();
                if (!"effort".equals(key)) throw new IllegalArgumentException("Unsupported reasoning field: " + key);
            }
            copy(reasoning, out, "effort", "reasoning_effort");
        } else if (request.has("reasoning") && !request.isNull("reasoning")) throw new IllegalArgumentException("reasoning must be an object");
        JSONObject text = request.optJSONObject("text");
        if (text != null) {
            for (Iterator<String> it = text.keys(); it.hasNext();) {
                String key = it.next();
                if (!"format".equals(key)) throw new IllegalArgumentException("Unsupported text field: " + key);
            }
            JSONObject format = text.optJSONObject("format");
            if (format == null) throw new IllegalArgumentException("text.format must be an object");
            String type = format.optString("type");
            if ("json_schema".equals(type)) {
                JSONObject schema = new JSONObject(format.toString()); schema.remove("type");
                out.put("response_format", new JSONObject().put("type", type).put("json_schema", schema));
            } else if ("text".equals(type) || "json_object".equals(type)) out.put("response_format", format);
            else throw new IllegalArgumentException("Unsupported text.format type: " + type);
        } else if (request.has("text") && !request.isNull("text")) throw new IllegalArgumentException("text must be an object");
        JSONArray messages = new JSONArray();
        if (request.has("instructions") && !request.isNull("instructions")) {
            if (!(request.opt("instructions") instanceof String)) throw new IllegalArgumentException("instructions must be a string");
            messages.put(new JSONObject().put("role", "system").put("content", request.getString("instructions")));
        }
        Object input = request.opt("input");
        if (input instanceof String) messages.put(new JSONObject().put("role", "user").put("content", input));
        else if (input instanceof JSONArray) {
            JSONArray items = (JSONArray) input;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) throw new IllegalArgumentException("input item must be an object");
                String type = item.optString("type", "message");
                checkKeys(item, "message".equals(type) ? new String[]{"type", "role", "content", "id", "status"} : "function_call".equals(type) ? new String[]{"type", "id", "call_id", "name", "arguments", "status"} : new String[]{"type", "id", "call_id", "output", "status"});
                if ("message".equals(type)) {
                    String role = item.optString("role");
                    if (!Arrays.asList("user", "assistant", "system", "developer").contains(role)) throw new IllegalArgumentException("Unsupported message role: " + role);
                    messages.put(new JSONObject().put("role", role).put("content", chatContent(item.opt("content"))));
                } else if ("function_call".equals(type)) {
                    String id = item.optString("call_id"), name = item.optString("name");
                    if (id.isEmpty() || name.isEmpty() || !(item.opt("arguments") instanceof String)) throw new IllegalArgumentException("function_call requires call_id, name and arguments");
                    JSONObject fn = new JSONObject().put("name", name).put("arguments", item.getString("arguments"));
                    JSONObject call = new JSONObject().put("id", id).put("type", "function").put("function", fn);
                    JSONObject last = messages.length() == 0 ? null : messages.optJSONObject(messages.length()-1);
                    if (last == null || !"assistant".equals(last.optString("role")) || !last.has("tool_calls")) {
                        last = new JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", new JSONArray()); messages.put(last);
                    }
                    last.getJSONArray("tool_calls").put(call);
                } else if ("function_call_output".equals(type)) {
                    String id = item.optString("call_id");
                    if (id.isEmpty() || !(item.opt("output") instanceof String)) throw new IllegalArgumentException("function_call_output requires call_id and string output");
                    messages.put(new JSONObject().put("role", "tool").put("tool_call_id", id).put("content", item.getString("output")));
                } else throw new IllegalArgumentException("Unsupported input item type: " + type);
            }
        } else throw new IllegalArgumentException("input must be a string or array");
        out.put("messages", messages);
        if (request.has("tools")) {
            JSONArray tools = request.optJSONArray("tools"), converted = new JSONArray();
            if (tools == null) throw new IllegalArgumentException("tools must be an array");
            for (int i = 0; i < tools.length(); i++) {
                JSONObject t = tools.optJSONObject(i);
                if (t == null || !"function".equals(t.optString("type"))) throw new IllegalArgumentException("Only function tools are supported");
                checkKeys(t, new String[]{"type", "name", "description", "parameters", "strict"});
                if (t.optString("name").isEmpty()) throw new IllegalArgumentException("function tool requires name");
                JSONObject fn = new JSONObject(t.toString()); fn.remove("type");
                converted.put(new JSONObject().put("type", "function").put("function", fn));
            }
            out.put("tools", converted);
        }
        Object choice = request.opt("tool_choice");
        if (choice instanceof String) out.put("tool_choice", choice);
        else if (choice instanceof JSONObject) {
            JSONObject c = (JSONObject) choice;
            if (!"function".equals(c.optString("type")) || c.optString("name").isEmpty()) throw new IllegalArgumentException("Unsupported tool_choice");
            out.put("tool_choice", new JSONObject().put("type", "function").put("function", new JSONObject().put("name", c.getString("name"))));
        } else if (choice != null && choice != JSONObject.NULL) throw new IllegalArgumentException("Invalid tool_choice");
        return out;
    }
    private static Object chatContent(Object content) throws Exception {
        if (content instanceof String) return content;
        if (!(content instanceof JSONArray)) throw new IllegalArgumentException("message content must be a string or array");
        JSONArray parts = (JSONArray) content, result = new JSONArray();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject p = parts.optJSONObject(i);
            if (p == null) throw new IllegalArgumentException("content part must be an object");
            String type = p.optString("type");
            checkKeys(p, "input_image".equals(type) ? new String[]{"type", "image_url", "detail"} : new String[]{"type", "text", "annotations"});
            if (p.has("annotations") && p.optJSONArray("annotations") != null && p.optJSONArray("annotations").length() > 0) throw new IllegalArgumentException("text annotations are not supported");
            if ("input_text".equals(type) || "output_text".equals(type)) {
                if (!(p.opt("text") instanceof String)) throw new IllegalArgumentException("text part requires text");
                result.put(new JSONObject().put("type", "text").put("text", p.getString("text")));
            } else if ("input_image".equals(type)) {
                if (!(p.opt("image_url") instanceof String)) throw new IllegalArgumentException("input_image requires image_url");
                JSONObject img = new JSONObject().put("url", p.getString("image_url"));
                copy(p, img, "detail", "detail");
                result.put(new JSONObject().put("type", "image_url").put("image_url", img));
            } else throw new IllegalArgumentException("Unsupported content type: " + type);
        }
        return result;
    }
    private static void checkKeys(JSONObject object, String[] allowed) {
        Set<String> keys = new HashSet<String>(Arrays.asList(allowed));
        for (Iterator<String> it = object.keys(); it.hasNext();) {
            String key = it.next();
            if (!keys.contains(key)) throw new IllegalArgumentException("Unsupported field: " + key);
        }
    }
    private static void copy(JSONObject from, JSONObject to, String key, String dest) throws Exception {
        if (from.has(key)) to.put(dest, from.get(key));
    }
}
