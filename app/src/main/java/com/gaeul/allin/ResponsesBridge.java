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
            "tools", "tool_choice", "parallel_tool_calls", "reasoning", "text", "store", "metadata", "include", "prompt_cache_key", "service_tier", "truncation", "user", "stream_options", "background", "previous_response_id", "conversation", "prompt", "max_tool_calls"));

    /** Validate and copy before either native forwarding or Chat projection. */
    static JSONObject normalize(JSONObject request) throws Exception {
        JSONObject copy = new JSONObject(request.toString());
        java.util.List<String> unknown = new java.util.ArrayList<String>();
        for (Iterator<String> it=copy.keys(); it.hasNext();) { String key=it.next(); if(!FIELDS.contains(key)) unknown.add(key); }
        for(String key:unknown) copy.remove(key);
        for(String key:new String[]{"user"}) if(copy.has(key) && !copy.isNull(key) && !(copy.opt(key) instanceof String)) throw new IllegalArgumentException(key+" must be a string or null");
        if(copy.has("metadata") && !copy.isNull("metadata") && !(copy.opt("metadata") instanceof JSONObject)) throw new IllegalArgumentException("metadata must be an object or null");
        if(copy.has("stream_options") && !copy.isNull("stream_options")) {
            JSONObject options=copy.optJSONObject("stream_options"); if(options==null) throw new IllegalArgumentException("stream_options must be an object or null");
            checkKeys(options,new String[]{"include_usage","include_obfuscation"});
            for(String key:new String[]{"include_usage","include_obfuscation"}) if(options.has(key) && !(options.opt(key) instanceof Boolean)) throw new IllegalArgumentException("stream_options."+key+" must be boolean");
        }
        if(copy.has("background") && !copy.isNull("background") && !(copy.opt("background") instanceof Boolean)) throw new IllegalArgumentException("background must be boolean or null");
        if(copy.has("previous_response_id") && !copy.isNull("previous_response_id") && !(copy.opt("previous_response_id") instanceof String)) throw new IllegalArgumentException("previous_response_id must be a string or null");
        if(copy.has("conversation") && !copy.isNull("conversation") && !(copy.opt("conversation") instanceof String) && !(copy.opt("conversation") instanceof JSONObject)) throw new IllegalArgumentException("conversation must be a string, object or null");
        if(copy.has("prompt") && !copy.isNull("prompt") && !(copy.opt("prompt") instanceof JSONObject)) throw new IllegalArgumentException("prompt must be an object or null");
        if(copy.has("max_tool_calls") && !copy.isNull("max_tool_calls")) { Object v=copy.opt("max_tool_calls"); if(!(v instanceof Number) || !Double.isFinite(((Number)v).doubleValue()) || ((Number)v).doubleValue()<=0 || ((Number)v).doubleValue()>Integer.MAX_VALUE || ((Number)v).doubleValue()!=Math.floor(((Number)v).doubleValue())) throw new IllegalArgumentException("max_tool_calls must be a positive integer or null"); }
        if(copy.has("previous_response_id") && !copy.isNull("previous_response_id") && !copy.optString("previous_response_id").isEmpty()) throw new IllegalArgumentException("previous_response_id requires complete input");
        if(copy.has("conversation") && !copy.isNull("conversation")) throw new IllegalArgumentException("conversation requires complete input");
        if(copy.has("prompt") && !copy.isNull("prompt")) throw new IllegalArgumentException("prompt requires complete input");
        if(copy.optBoolean("background",false)) throw new IllegalArgumentException("background=true is unsupported; use a synchronous call");
        JSONObject text=copy.optJSONObject("text");
        if(text!=null && text.has("verbosity") && !text.isNull("verbosity") && (!(text.opt("verbosity") instanceof String) || !Arrays.asList("low","medium","high").contains(text.opt("verbosity")))) throw new IllegalArgumentException("text.verbosity must be low, medium, high or null");
        JSONObject reasoning=copy.optJSONObject("reasoning");
        if(reasoning!=null && reasoning.has("summary") && !reasoning.isNull("summary") && (!(reasoning.opt("summary") instanceof String) || !Arrays.asList("auto","concise","detailed").contains(reasoning.opt("summary")))) throw new IllegalArgumentException("reasoning.summary must be auto, concise, detailed or null");
        return copy;
    }

    /** A conservative projection: native-only fields are never sent through Chat. */
    static JSONObject projection(JSONObject request) throws Exception {
        JSONObject copy = normalize(request);
        for(String key:new String[]{"previous_response_id","conversation","prompt","background","max_tool_calls"}) copy.remove(key);
        if(copy.has("include") && !copy.isNull("include") && copy.optJSONArray("include")==null) throw new IllegalArgumentException("include must be an array");
        JSONArray includes=copy.optJSONArray("include");
        for(int i=0;includes!=null && i<includes.length();i++) if(!(includes.opt(i) instanceof String)) throw new IllegalArgumentException("include entries must be strings");
        copy.remove("include");
        if(copy.has("store") && !(copy.opt("store") instanceof Boolean)) throw new IllegalArgumentException("store must be boolean");
        if(copy.optBoolean("store",false)) copy.remove("store");
        JSONObject reasoning=copy.optJSONObject("reasoning");
        if(reasoning!=null && reasoning.has("summary")) {
            Object summary=reasoning.opt("summary");
            if(summary!=JSONObject.NULL && !(summary instanceof String)) throw new IllegalArgumentException("reasoning.summary must be a string");
            reasoning.remove("summary");
        }
        JSONArray input=copy.optJSONArray("input");
        if(input!=null) {
            JSONArray projected=new JSONArray();
            for(int i=0;i<input.length();i++) {
                Object raw=input.get(i);
                if(raw instanceof JSONObject) {
                    JSONObject item=(JSONObject)raw;
                    if("reasoning".equals(item.optString("type"))) {
                        checkKeys(item,new String[]{"type","id","summary","content","encrypted_content","status"});
                        if(item.has("encrypted_content") && !item.isNull("encrypted_content") && !(item.opt("encrypted_content") instanceof String)) throw new IllegalArgumentException("encrypted_content must be a string");
                        for(String field:new String[]{"summary","content"}) if(item.has(field) && !item.isNull(field)) { JSONArray parts=item.optJSONArray(field); if(parts==null) throw new IllegalArgumentException("reasoning "+field+" must be an array"); for(int j=0;j<parts.length();j++) { JSONObject part=parts.optJSONObject(j); if(part==null || !(part.opt("text") instanceof String) || !("summary_text".equals(part.optString("type")) || "reasoning_text".equals(part.optString("type")))) throw new IllegalArgumentException("Unsupported reasoning part"); checkKeys(part,new String[]{"type","text"}); } }
                        item.remove("id"); item.remove("status"); item.remove("encrypted_content");
                    }
                    JSONArray content=item.optJSONArray("content");
                    if(content!=null) {
                        JSONArray parts=new JSONArray();
                        for(int j=0;j<content.length();j++) {
                            Object part=content.get(j);
                            if(part instanceof JSONObject && "refusal".equals(((JSONObject)part).optString("type"))) {
                                JSONObject refusal=(JSONObject)part; checkKeys(refusal,new String[]{"type","refusal"});
                                if(!(refusal.opt("refusal") instanceof String)) throw new IllegalArgumentException("refusal must be a string");
                                continue;
                            }
                            parts.put(part);
                        }
                        item.put("content",parts);
                    }
                }
                projected.put(raw);
            }
            copy.put("input",projected);
        }
        return toChatInternal(copy);
    }

    static String nativeOnlyReason(JSONObject request) {
        try {
            JSONObject r=normalize(request);
            if(!r.optString("previous_response_id", "").isEmpty() && !r.isNull("previous_response_id")) return "previous_response_id requires full input for this route";
            if(r.has("conversation") && !r.isNull("conversation")) return "conversation requires full input for this route";
            if(r.has("prompt") && !r.isNull("prompt")) return "prompt requires full input for this route";
            if(r.optBoolean("background",false)) return "background=true requires a synchronous call (background=false)";
            if(r.has("max_tool_calls") && !r.isNull("max_tool_calls")) return "max_tool_calls requires a native Responses route";
            JSONArray items=r.optJSONArray("input"); for(int i=0;items!=null && i<items.length();i++) { JSONObject item=items.optJSONObject(i); if(item!=null && "reasoning".equals(item.optString("type")) && !item.isNull("encrypted_content") && !item.optString("encrypted_content", "").isEmpty()) return "encrypted reasoning cannot be represented by Chat; use a native Responses route"; }
            for(int i=0;items!=null && i<items.length();i++) { JSONObject item=items.optJSONObject(i); JSONArray parts=item==null?null:item.optJSONArray("content"); for(int j=0;parts!=null && j<parts.length();j++) { JSONObject part=parts.optJSONObject(j); if(part!=null && "refusal".equals(part.optString("type"))) return "refusal history requires a native Responses route"; } }
            if(r.has("store") && r.optBoolean("store",false)) return "store=true requires a native Responses route";
            JSONArray includes=r.optJSONArray("include"); for(int i=0;includes!=null && i<includes.length();i++) if(!Arrays.asList("reasoning.encrypted_content", "message.output_text.logprobs", "message.input_image.image_url", "file_search_call.results", "web_search_call.action.sources", "code_interpreter_call.outputs", "computer_call_output.output.image_url").contains(includes.optString(i))) return "Unsupported include value: "+includes.optString(i);
            projection(r); return null;
        } catch (Exception e) { return e.getMessage(); }
    }

    static JSONObject toChat(JSONObject request) throws Exception {
        String unsupported=nativeOnlyReason(request);
        if(unsupported!=null) throw new IllegalArgumentException(unsupported);
        return projection(request);
    }

    static JSONObject toChatInternal(JSONObject request) throws Exception {
        request=normalize(request);
        for (Iterator<String> keys = request.keys(); keys.hasNext();) {
            String key = keys.next();
            if (!FIELDS.contains(key)) throw new IllegalArgumentException("Unsupported Responses field: " + key);
        }
        if (request.has("stream") && !(request.opt("stream") instanceof Boolean)) throw new IllegalArgumentException("stream must be boolean");
        if (request.has("max_output_tokens")) {
            Object value=request.opt("max_output_tokens");
            if (!(value instanceof Number) || !Double.isFinite(((Number)value).doubleValue()) || ((Number)value).doubleValue() <= 0 || ((Number)value).doubleValue() > Integer.MAX_VALUE || ((Number)value).doubleValue() != Math.floor(((Number)value).doubleValue())) throw new IllegalArgumentException("max_output_tokens must be a positive integer");
        }
        if (request.has("model") && !request.isNull("model") && !(request.opt("model") instanceof String)) throw new IllegalArgumentException("model must be a string");
        if (request.has("parallel_tool_calls") && !request.isNull("parallel_tool_calls") && !(request.opt("parallel_tool_calls") instanceof Boolean)) throw new IllegalArgumentException("parallel_tool_calls must be boolean");
        for (String key : new String[]{"temperature", "top_p"}) {
            if (request.has(key) && !request.isNull(key)) {
                Object value=request.opt(key);
                if (!(value instanceof Number) || !Double.isFinite(((Number)value).doubleValue()) || ((Number)value).doubleValue()<0 || ((Number)value).doubleValue()>("temperature".equals(key)?2:1)) throw new IllegalArgumentException(key+" must be a finite number in range");
            }
        }
        if (request.has("text") && request.isNull("text")) throw new IllegalArgumentException("text must be an object");
        if (request.has("truncation") && !"disabled".equals(request.optString("truncation"))) throw new IllegalArgumentException("Unsupported truncation mode");
        if (request.has("include") && (request.optJSONArray("include")==null || request.optJSONArray("include").length()>0)) throw new IllegalArgumentException("Unsupported include values");
        if (request.has("service_tier") && !"auto".equals(request.optString("service_tier")) && !"default".equals(request.optString("service_tier"))) throw new IllegalArgumentException("Unsupported service_tier");
        if (request.has("store") && !(request.opt("store") instanceof Boolean)) throw new IllegalArgumentException("store must be boolean");
        if (request.optBoolean("store", false)) throw new IllegalArgumentException("store=true is not supported");
        JSONObject out = new JSONObject();
        copy(request, out, "model", "model"); copy(request, out, "stream", "stream");
        copy(request, out, "max_output_tokens", "max_tokens"); copy(request, out, "temperature", "temperature");
        copy(request, out, "top_p", "top_p"); copy(request, out, "parallel_tool_calls", "parallel_tool_calls");
        copy(request, out, "user", "user"); copy(request, out, "stream_options", "stream_options");
        copy(request, out, "metadata", "metadata"); copy(request, out, "prompt_cache_key", "prompt_cache_key"); copy(request, out, "service_tier", "service_tier");
        JSONObject reasoning = request.optJSONObject("reasoning");
        if (reasoning != null) {
            for (Iterator<String> it = reasoning.keys(); it.hasNext();) {
                String key = it.next();
                if (!"effort".equals(key)) throw new IllegalArgumentException("Unsupported reasoning field: " + key);
            }
            if(reasoning.has("effort") && !reasoning.isNull("effort") && (!(reasoning.opt("effort") instanceof String) || !Arrays.asList("none","minimal","low","medium","high","xhigh").contains(reasoning.opt("effort")))) throw new IllegalArgumentException("Unsupported reasoning.effort");
            copy(reasoning, out, "effort", "reasoning_effort");
        } else if (request.has("reasoning") && !request.isNull("reasoning")) throw new IllegalArgumentException("reasoning must be an object");
        JSONObject text = request.optJSONObject("text");
        if (text != null) {
            for (Iterator<String> it = text.keys(); it.hasNext();) {
                String key = it.next();
                if (!"format".equals(key) && !"verbosity".equals(key)) throw new IllegalArgumentException("Unsupported text field: " + key);
            }
            JSONObject format = text.optJSONObject("format");
            if (format == null && text.has("format") && !text.isNull("format")) throw new IllegalArgumentException("text.format must be an object");
            if (format != null) {
            String type = format.optString("type");
            if ("json_schema".equals(type)) {
                checkKeys(format,new String[]{"type","name","description","schema","strict"});
                JSONObject schema = new JSONObject(format.toString()); schema.remove("type");
                out.put("response_format", new JSONObject().put("type", type).put("json_schema", schema));
            } else if ("text".equals(type) || "json_object".equals(type)) { checkKeys(format,new String[]{"type"}); out.put("response_format", format); }
            else throw new IllegalArgumentException("Unsupported text.format type: " + type);
            }
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
                if ("reasoning".equals(type)) {
                    checkKeys(item, new String[]{"type", "id", "summary", "content"});
                    Object rawSummary=item.optJSONArray("content") != null && item.optJSONArray("content").length()>0 ? item.opt("content") : item.opt("summary");
                    if (rawSummary==null || rawSummary==JSONObject.NULL) rawSummary=new JSONArray();
                    if (!(rawSummary instanceof JSONArray)) throw new IllegalArgumentException("reasoning summary must be an array");
                    JSONArray summary=(JSONArray)rawSummary; StringBuilder thought=new StringBuilder();
                    for(int j=0;j<summary.length();j++) { JSONObject part=summary.optJSONObject(j); if(part==null || !(part.opt("text") instanceof String) || !("summary_text".equals(part.optString("type")) || "reasoning_text".equals(part.optString("type")))) throw new IllegalArgumentException("Unsupported reasoning part"); checkKeys(part,new String[]{"type","text"}); thought.append(part.getString("text")); }
                    if(thought.length()>0) { JSONObject last=messages.length()==0?null:messages.optJSONObject(messages.length()-1); if(last==null || !"assistant".equals(last.optString("role")) || last.has("tool_calls")) { last=new JSONObject().put("role","assistant").put("content",JSONObject.NULL); messages.put(last); } last.put("reasoning_content",last.optString("reasoning_content", "")+thought.toString()); }
                    continue;
                }
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
                    if (last == null || !"assistant".equals(last.optString("role"))) {
                        last = new JSONObject().put("role", "assistant").put("content", JSONObject.NULL); messages.put(last);
                    }
                    if (!last.has("tool_calls")) last.put("tool_calls",new JSONArray());
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
        if (choice instanceof String) { if (!Arrays.asList("auto","none","required").contains(choice)) throw new IllegalArgumentException("Unsupported tool_choice: "+choice); out.put("tool_choice", choice); }
        else if (choice instanceof JSONObject) {
            JSONObject c = (JSONObject) choice;
            checkKeys(c, new String[]{"type","name"});
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
            if ("refusal".equals(type)) throw new IllegalArgumentException("refusal input cannot be represented by Chat");
            checkKeys(p, "input_image".equals(type) ? new String[]{"type", "image_url", "detail"} : new String[]{"type", "text", "annotations"});
            if (p.has("annotations") && p.optJSONArray("annotations") != null && p.optJSONArray("annotations").length() > 0) throw new IllegalArgumentException("text annotations are not supported");
            if ("input_text".equals(type) || "output_text".equals(type)) {
                if (!(p.opt("text") instanceof String)) throw new IllegalArgumentException("text part requires text");
                result.put(new JSONObject().put("type", "text").put("text", p.getString("text")));
            } else if ("input_image".equals(type)) {
                if (!(p.opt("image_url") instanceof String)) throw new IllegalArgumentException("input_image requires image_url");
                String url=p.getString("image_url");
                if (!(url.startsWith("https://") || url.startsWith("http://") || url.startsWith("data:image/"))) throw new IllegalArgumentException("Unsupported image_url");
                JSONObject img = new JSONObject().put("url", url);
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
    static JSONObject chatToResponses(JSONObject chat, String model) throws Exception {
        try { return chatToResponsesChecked(chat, model); }
        catch (org.json.JSONException e) { throw new IllegalArgumentException("Invalid Chat request: " + e.getMessage(), e); }
    }
    private static JSONObject chatToResponsesChecked(JSONObject chat, String model) throws Exception {
        checkKeys(chat,new String[]{"model","messages","stream","stream_options","user","max_tokens","max_completion_tokens","temperature","top_p","parallel_tool_calls","metadata","prompt_cache_key","service_tier","reasoning_effort","tools","tool_choice","response_format","n"});
        if(chat.has("n") && chat.optInt("n",1)!=1) throw new IllegalArgumentException("Responses supports one choice");
        if(chat.has("stream_options") && !chat.isNull("stream_options")) {
            JSONObject options=chat.optJSONObject("stream_options");
            if(options==null) throw new IllegalArgumentException("invalid stream_options");
            checkKeys(options,new String[]{"include_usage","include_obfuscation"});
        }
        JSONObject out = new JSONObject().put("model", model);
        copy(chat, out, "stream", "stream"); copy(chat, out, "user", "user"); copy(chat, out, "stream_options", "stream_options"); copy(chat, out, "max_tokens", "max_output_tokens");
        int completion=chat.optInt("max_completion_tokens",0), max=chat.optInt("max_tokens",0);
        if(completion>0 && (max<=0 || completion<max)) out.put("max_output_tokens",completion);
        copy(chat, out, "temperature", "temperature"); copy(chat, out, "top_p", "top_p");
        copy(chat, out, "parallel_tool_calls", "parallel_tool_calls");
        copy(chat, out, "metadata", "metadata"); copy(chat, out, "prompt_cache_key", "prompt_cache_key"); copy(chat, out, "service_tier", "service_tier");
        if (chat.has("reasoning_effort")) out.put("reasoning", new JSONObject().put("effort", chat.get("reasoning_effort")));
        JSONArray input = new JSONArray(), messages = chat.optJSONArray("messages");
        StringBuilder instructions = new StringBuilder();
        if (messages == null) throw new IllegalArgumentException("messages required");
        for (int i=0;i<messages.length();i++) {
            JSONObject m = messages.getJSONObject(i); String role=m.optString("role");
            checkKeys(m,new String[]{"role","content","tool_calls","tool_call_id","reasoning_content"});
            if(m.has("reasoning_content") && !m.isNull("reasoning_content")) { if(!"assistant".equals(role) || !(m.opt("reasoning_content") instanceof String)) throw new IllegalArgumentException("Invalid reasoning_content"); input.put(new JSONObject().put("type","reasoning").put("summary",new JSONArray().put(new JSONObject().put("type","summary_text").put("text",m.getString("reasoning_content"))))); }
            if(!Arrays.asList("system","developer","user","assistant","tool").contains(role)) throw new IllegalArgumentException("Unsupported Chat role: "+role);
            if (("system".equals(role) || "developer".equals(role)) && input.length()==0 && m.opt("content") instanceof String) {
                if (instructions.length()>0) instructions.append("\n\n"); instructions.append(m.optString("content")); continue;
            }
            if ("tool".equals(role)) {
                input.put(new JSONObject().put("type", "function_call_output").put("call_id", m.getString("tool_call_id")).put("output", m.optString("content", ""))); continue;
            }
            Object content=m.opt("content");
            if (content != null && content != JSONObject.NULL) {
                JSONArray parts=new JSONArray();
                if (content instanceof String) parts.put(new JSONObject().put("type", "assistant".equals(role)?"output_text":"input_text").put("text", content));
                else if (content instanceof JSONArray) {
                    JSONArray source=(JSONArray)content;
                    for(int j=0;j<source.length();j++) {
                        JSONObject part=source.getJSONObject(j); String type=part.optString("type");
                        checkKeys(part,"image_url".equals(type)?new String[]{"type","image_url"}:new String[]{"type","text"});
                        if ("text".equals(type)) parts.put(new JSONObject().put("type", "assistant".equals(role)?"output_text":"input_text").put("text", part.getString("text")));
                        else if ("image_url".equals(type)) {
                            Object image=part.opt("image_url"); JSONObject item=new JSONObject().put("type", "input_image");
                            if (image instanceof String) item.put("image_url",image);
                            else if (image instanceof JSONObject) { JSONObject im=(JSONObject)image; checkKeys(im,new String[]{"url","detail"}); item.put("image_url", im.getString("url")); copy(im,item,"detail","detail"); }
                            else throw new IllegalArgumentException("invalid image_url");
                            parts.put(item);
                        } else throw new IllegalArgumentException("Unsupported Chat content type: "+type);
                    }
                } else throw new IllegalArgumentException("Invalid Chat content");
                if (parts.length()>0) input.put(new JSONObject().put("type","message").put("role",role).put("content",parts));
            }
            JSONArray calls=m.optJSONArray("tool_calls");
            if (m.has("tool_calls") && !m.isNull("tool_calls") && calls==null) throw new IllegalArgumentException("tool_calls must be an array");
            for (int j=0;calls!=null && j<calls.length();j++) {
                JSONObject c=calls.getJSONObject(j), fn=c.getJSONObject("function");
                checkKeys(c,new String[]{"id","type","function"}); checkKeys(fn,new String[]{"name","arguments"});
                if(!"function".equals(c.optString("type"))) throw new IllegalArgumentException("Unsupported Chat tool call");
                input.put(new JSONObject().put("type","function_call").put("call_id",c.getString("id")).put("name",fn.getString("name")).put("arguments",fn.optString("arguments","{}")));
            }
        }
        out.put("input",input);
        if(instructions.length()>0) out.put("instructions",instructions.toString());
        JSONArray tools=chat.optJSONArray("tools");
        if (chat.has("tools") && tools==null) throw new IllegalArgumentException("tools must be an array");
        if (tools!=null) { JSONArray result=new JSONArray(); for(int i=0;i<tools.length();i++) {
            JSONObject t=tools.getJSONObject(i); if (!"function".equals(t.optString("type"))) throw new IllegalArgumentException("Unsupported Chat tool");
            JSONObject fn=new JSONObject(t.getJSONObject("function").toString()); fn.put("type","function"); result.put(fn);
        } out.put("tools", result); }
        Object choice=chat.opt("tool_choice");
        if (choice instanceof String) out.put("tool_choice",choice);
        else if (choice instanceof JSONObject) { JSONObject c=(JSONObject)choice; out.put("tool_choice",new JSONObject().put("type","function").put("name",c.getJSONObject("function").getString("name"))); }
        JSONObject format=chat.optJSONObject("response_format");
        if (format!=null) { JSONObject f=new JSONObject(format.toString()); if ("json_schema".equals(f.optString("type"))) { f=new JSONObject(format.getJSONObject("json_schema").toString()); f.put("type","json_schema"); } out.put("text",new JSONObject().put("format",f)); }
        return out;
    }

    static JSONObject chatResponseToResponses(JSONObject chat, String requestedModel) throws Exception {
        JSONArray choices=chat.optJSONArray("choices");
        if (choices==null || choices.length()!=1) throw new IllegalArgumentException("expected one Chat choice");
        JSONObject choice=choices.getJSONObject(0), msg=choice.getJSONObject("message");
        JSONArray output=new JSONArray();
        String reasoning=msg.optString("reasoning_content", "");
        if (!reasoning.isEmpty()) output.put(reasoningItem(reasoning));
        Object content=msg.opt("content");
        if (content instanceof String) {
            // Tool-only Chat replies legitimately use content="". Skip the empty
            // text item, then continue converting tool_calls/refusal/usage.
            if (!((String)content).isEmpty()) output.put(messageItem((String)content));
        } else if (content instanceof JSONArray) {
            JSONArray parts=(JSONArray)content; StringBuilder b=new StringBuilder();
            for(int i=0;i<parts.length();i++) { JSONObject p=parts.getJSONObject(i); if (!"text".equals(p.optString("type"))) throw new IllegalArgumentException("Unsupported response content"); b.append(p.optString("text")); }
            if (b.length()>0) output.put(messageItem(b.toString()));
        }
        else if (content != null && content != JSONObject.NULL) throw new IllegalArgumentException("Unsupported response content");
        JSONArray calls=msg.optJSONArray("tool_calls");
        for(int i=0;calls!=null && i<calls.length();i++) { JSONObject c=calls.getJSONObject(i), fn=c.getJSONObject("function"); output.put(functionItem(c.optString("id"),fn.optString("name"),fn.optString("arguments"))); }
        if (msg.has("refusal") && !msg.isNull("refusal")) {
            if (!(msg.opt("refusal") instanceof String)) throw new IllegalArgumentException("invalid refusal");
            output.put(refusalItem(msg.getString("refusal")));
        }
        String finish=choice.optString("finish_reason", "stop");
        if (!Arrays.asList("stop", "tool_calls", "length", "content_filter").contains(finish)) throw new IllegalArgumentException("Unsupported finish reason: "+finish);
        JSONObject result=responseEnvelope(requestedModel!=null?requestedModel:chat.optString("model"),output,finish);
        result.put("id",chat.optString("id", result.optString("id")));
        JSONObject usage=chat.optJSONObject("usage"); if(usage!=null) result.put("usage",responsesUsage(usage));
        return result;
    }
    static JSONObject responsesUsage(JSONObject u) throws Exception {
        JSONObject out=new JSONObject().put("input_tokens",u.optInt("prompt_tokens",0)).put("output_tokens",u.optInt("completion_tokens",0));
        out.put("total_tokens",u.optInt("total_tokens",out.getInt("input_tokens")+out.getInt("output_tokens")));
        JSONObject p=u.optJSONObject("prompt_tokens_details"), c=u.optJSONObject("completion_tokens_details");
        if(p!=null) out.put("input_tokens_details",p); if(c!=null) out.put("output_tokens_details",c); return out;
    }
    static JSONObject messageItem(String text) throws Exception { return new JSONObject().put("id","msg_"+java.util.UUID.randomUUID().toString().replace("-", "")).put("type","message").put("status","completed").put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text",text).put("annotations",new JSONArray()))); }
    static JSONObject refusalItem(String text) throws Exception { return new JSONObject().put("id","msg_"+java.util.UUID.randomUUID().toString().replace("-", "")).put("type","message").put("status","completed").put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","refusal").put("refusal",text))); }
    static JSONObject reasoningItem(String text) throws Exception { return new JSONObject().put("id","rs_"+java.util.UUID.randomUUID().toString().replace("-", "")).put("type","reasoning").put("summary",new JSONArray().put(new JSONObject().put("type","summary_text").put("text",text))); }
    static JSONObject functionItem(String call, String name, String args) throws Exception { return new JSONObject().put("id","fc_"+java.util.UUID.randomUUID().toString().replace("-", "")).put("type","function_call").put("status","completed").put("call_id",call).put("name",name).put("arguments",args); }
    static JSONObject responseEnvelope(String model, JSONArray output, String finish) throws Exception {
        boolean incomplete="length".equals(finish) || "content_filter".equals(finish);
        JSONObject r=new JSONObject().put("id","resp_"+java.util.UUID.randomUUID().toString().replace("-", "")).put("object","response").put("created_at",System.currentTimeMillis()/1000).put("status",incomplete?"incomplete":"completed").put("error",JSONObject.NULL).put("incomplete_details",incomplete?new JSONObject().put("reason","length".equals(finish)?"max_output_tokens":"content_filter"):JSONObject.NULL).put("model",model==null?"":model).put("output",output).put("usage",JSONObject.NULL);
        return r;
    }

    static java.io.InputStream chatStreamToResponses(final java.io.InputStream source, final String model) {
        return new java.io.InputStream() {
            final java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(source, java.nio.charset.StandardCharsets.UTF_8));
            final java.util.ArrayList<JSONObject> items = new java.util.ArrayList<JSONObject>();
            final java.util.Map<Integer,Integer> tools = new java.util.HashMap<Integer,Integer>();
            final java.util.Map<Integer,JSONObject> waitingTools = new java.util.LinkedHashMap<Integer,JSONObject>();
            final StringBuilder pending = new StringBuilder();
            JSONObject response, usage; int textIndex=-1, reasoningIndex=-1, refusalIndex=-1, sequence=0; String finish=null;
            byte[] bytes=new byte[0]; int at=0; boolean ended=false, started=false;
            void event(String type, JSONObject value) throws Exception { value.put("type",type).put("sequence_number",sequence++); pending.append("event: ").append(type).append("\ndata: ").append(value.toString()).append("\n\n"); }
            void start() throws Exception {
                if(started)return; started=true; response=responseEnvelope(model,new JSONArray(),"stop"); response.put("status","in_progress");
                event("response.created",new JSONObject().put("response",new JSONObject(response.toString())));
                event("response.in_progress",new JSONObject().put("response",new JSONObject(response.toString())));
            }
            int add(JSONObject item) throws Exception { int index=items.size(); items.add(item); JSONObject init=new JSONObject(item.toString()); init.put("status","in_progress"); event("response.output_item.added",new JSONObject().put("output_index",index).put("item",init)); return index; }
            void text(String value, boolean reasoning) throws Exception {
                if(value.isEmpty())return;
                int index=reasoning?reasoningIndex:textIndex;
                if(index<0) {
                    JSONObject item=reasoning?reasoningItem(""):messageItem(""); index=add(item);
                    if(reasoning) { reasoningIndex=index; event("response.reasoning_summary_part.added",new JSONObject().put("item_id",item.getString("id")).put("output_index",index).put("summary_index",0).put("part",new JSONObject().put("type","summary_text").put("text",""))); }
                    else { textIndex=index; event("response.content_part.added",new JSONObject().put("item_id",item.getString("id")).put("output_index",index).put("content_index",0).put("part",new JSONObject().put("type","output_text").put("text","").put("annotations",new JSONArray()))); }
                }
                JSONObject item=items.get(index); JSONObject part=reasoning?item.getJSONArray("summary").getJSONObject(0):item.getJSONArray("content").getJSONObject(0); part.put("text",part.optString("text")+value);
                JSONObject data=new JSONObject().put("item_id",item.getString("id")).put("output_index",index).put(reasoning?"summary_index":"content_index",0).put("delta",value);
                event(reasoning?"response.reasoning_summary_text.delta":"response.output_text.delta",data);
            }
            void refusal(String value) throws Exception {
                if(value.isEmpty()) return;
                if(refusalIndex<0) {
                    JSONObject item=refusalItem(""); refusalIndex=add(item);
                    event("response.content_part.added",new JSONObject().put("item_id",item.getString("id")).put("output_index",refusalIndex).put("content_index",0).put("part",new JSONObject().put("type","refusal").put("refusal","")));
                }
                JSONObject item=items.get(refusalIndex), part=item.getJSONArray("content").getJSONObject(0);
                part.put("refusal",part.getString("refusal")+value);
                event("response.refusal.delta",new JSONObject().put("item_id",item.getString("id")).put("output_index",refusalIndex).put("content_index",0).put("delta",value));
            }
            void chunk(JSONObject chunk) throws Exception {
                if(chunk.has("error")) throw new java.io.IOException("upstream stream error");
                if(chunk.optJSONObject("usage")!=null) usage=responsesUsage(chunk.getJSONObject("usage"));
                JSONArray choices=chunk.optJSONArray("choices"); if(choices==null || choices.length()==0)return;
                if(choices.length()!=1) throw new java.io.IOException("multiple choices unsupported");
                JSONObject choice=choices.getJSONObject(0), delta=choice.optJSONObject("delta");
                if(delta!=null) {
                    if (delta.has("refusal") && !delta.isNull("refusal")) { if (!(delta.opt("refusal") instanceof String)) throw new java.io.IOException("invalid refusal"); refusal(delta.getString("refusal")); }
                    text(delta.optString("reasoning_content", ""),true); text(delta.optString("content", ""),false);
                    JSONArray calls=delta.optJSONArray("tool_calls");
                    for(int i=0;calls!=null && i<calls.length();i++) {
                        JSONObject call=calls.getJSONObject(i), fn=call.optJSONObject("function"); int number=call.optInt("index",i); Integer index=tools.get(number);
                        JSONObject item;
                        if(index==null) {
                            item=waitingTools.get(number);
                            if(item==null) { item=functionItem("","",""); waitingTools.put(number,item); }
                        } else item=items.get(index);
                        if(call.has("id") && !call.isNull("id")) item.put("call_id",call.getString("id"));
                        String args="";
                        if(fn!=null) {
                            if(fn.has("name") && !fn.isNull("name")) item.put("name",item.getString("name")+fn.getString("name"));
                            if(fn.has("arguments") && !fn.isNull("arguments")) args=fn.getString("arguments");
                        }
                        if(index==null) {
                            item.put("arguments",item.getString("arguments")+args);
                            if(!item.getString("call_id").isEmpty() && !item.getString("name").isEmpty()) {
                                String buffered=item.getString("arguments"); item.put("arguments","");
                                index=add(item); tools.put(number,index); waitingTools.remove(number);
                                args=buffered;
                            } else continue;
                        }
                        if(!args.isEmpty()) { item.put("arguments",item.getString("arguments")+args); event("response.function_call_arguments.delta",new JSONObject().put("item_id",item.getString("id")).put("output_index",index).put("delta",args)); }
                    }
                }
                if(!choice.isNull("finish_reason") && choice.has("finish_reason")) finish=choice.getString("finish_reason");
            }
            void complete() throws Exception {
                if(finish==null) throw new java.io.IOException("upstream stream ended before finish_reason");
                if(!waitingTools.isEmpty()) throw new java.io.IOException("function call missing id or name");
                if(!Arrays.asList("stop","tool_calls","length","content_filter").contains(finish)) throw new java.io.IOException("Unsupported finish reason: "+finish);
                JSONArray output=new JSONArray();
                for(int i=0;i<items.size();i++) {
                    JSONObject item=items.get(i); String type=item.getString("type"), id=item.getString("id");
                    if("message".equals(type)) { JSONObject part=item.getJSONArray("content").getJSONObject(0); if ("refusal".equals(part.optString("type"))) event("response.refusal.done",new JSONObject().put("item_id",id).put("output_index",i).put("content_index",0).put("refusal",part.getString("refusal"))); else event("response.output_text.done",new JSONObject().put("item_id",id).put("output_index",i).put("content_index",0).put("text",part.getString("text"))); event("response.content_part.done",new JSONObject().put("item_id",id).put("output_index",i).put("content_index",0).put("part",part)); }
                    else if("reasoning".equals(type)) { JSONObject part=item.getJSONArray("summary").getJSONObject(0); event("response.reasoning_summary_text.done",new JSONObject().put("item_id",id).put("output_index",i).put("summary_index",0).put("text",part.getString("text"))); event("response.reasoning_summary_part.done",new JSONObject().put("item_id",id).put("output_index",i).put("summary_index",0).put("part",part)); }
                    else event("response.function_call_arguments.done",new JSONObject().put("item_id",id).put("output_index",i).put("arguments",item.getString("arguments")));
                    event("response.output_item.done",new JSONObject().put("output_index",i).put("item",item)); output.put(item);
                }
                JSONObject finalResponse=responseEnvelope(model,output,finish); finalResponse.put("id",response.getString("id")); finalResponse.put("created_at",response.get("created_at")); if(usage!=null) finalResponse.put("usage",usage);
                event("incomplete".equals(finalResponse.optString("status"))?"response.incomplete":"response.completed",new JSONObject().put("response",finalResponse));
            }
            boolean fill() throws java.io.IOException {
                while(at>=bytes.length) {
                    if(ended)return false;
                    try {
                        start(); String line=in.readLine();
                        if(line==null) { complete(); ended=true; }
                        else if(line.trim().equals("data: [DONE]")) { complete(); ended=true; }
                        else if(line.startsWith("data:")) { String data=line.substring(5).trim(); if(!data.isEmpty()) chunk(new JSONObject(data)); }
                    } catch(Exception e) {
                        ended=true;
                        try { start(); JSONObject error=new JSONObject().put("code","upstream_stream_error").put("message",e.getMessage()==null?"upstream stream failed":e.getMessage()); response.put("status","failed").put("error",error); event("response.failed",new JSONObject().put("response",response)); } catch(Exception ignored) {}
                    }
                    if(pending.length()>0) { bytes=pending.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8); pending.setLength(0); at=0; return true; }
                } return true;
            }
            public int read() throws java.io.IOException { if(!fill())return -1; return bytes[at++]&255; }
            public int read(byte[] b,int off,int len) throws java.io.IOException { if(len==0)return 0; if(!fill())return -1; int n=Math.min(len,bytes.length-at); System.arraycopy(bytes,at,b,off,n); at+=n; return n; }
            public void close() throws java.io.IOException { in.close(); }
        };
    }

    static JSONObject responsesResponseToChat(JSONObject response,String model) throws Exception {
        if (!Arrays.asList("completed", "incomplete").contains(response.optString("status"))) throw new IllegalArgumentException("Responses response is not complete");
        StringBuilder text=new StringBuilder(), reasoning=new StringBuilder(), refusal=new StringBuilder(); JSONArray calls=new JSONArray(), output=response.optJSONArray("output");
        for(int i=0;output!=null && i<output.length();i++) { JSONObject item=output.getJSONObject(i); String type=item.optString("type");
            if("message".equals(type)) { JSONArray parts=item.optJSONArray("content"); for(int j=0;parts!=null && j<parts.length();j++) { JSONObject part=parts.getJSONObject(j); if("output_text".equals(part.optString("type"))) text.append(part.optString("text")); else if("refusal".equals(part.optString("type"))) refusal.append(part.optString("refusal")); else throw new IllegalArgumentException("Unsupported Responses output content: "+part.optString("type")); } }
            else if("reasoning".equals(type)) { JSONArray parts=item.optJSONArray("summary"); for(int j=0;parts!=null && j<parts.length();j++) reasoning.append(parts.getJSONObject(j).optString("text")); }
            else if("function_call".equals(type)) calls.put(new JSONObject().put("id",item.optString("call_id")).put("type","function").put("function",new JSONObject().put("name",item.optString("name")).put("arguments",item.optString("arguments"))));
            else throw new IllegalArgumentException("Unsupported Responses output item: "+type);
        }
        JSONObject msg=new JSONObject().put("role","assistant").put("content",text.length()==0?JSONObject.NULL:text.toString());
        if(refusal.length()>0) msg.put("refusal",refusal.toString()); if(reasoning.length()>0) msg.put("reasoning_content",reasoning.toString()); if(calls.length()>0)msg.put("tool_calls",calls);
        String finish=calls.length()>0?"tool_calls":"stop"; if("incomplete".equals(response.optString("status"))) { JSONObject details=response.optJSONObject("incomplete_details"); finish=details!=null && "content_filter".equals(details.optString("reason"))?"content_filter":"length"; }
        JSONObject out=new JSONObject().put("id",response.optString("id","chatcmpl-"+System.currentTimeMillis())).put("object","chat.completion").put("created",response.optLong("created_at",System.currentTimeMillis()/1000)).put("model",model).put("choices",new JSONArray().put(new JSONObject().put("index",0).put("message",msg).put("finish_reason",finish)));
        JSONObject u=response.optJSONObject("usage"); if(u!=null)out.put("usage",chatUsage(u)); return out;
    }
    static JSONObject chatUsage(JSONObject u) throws Exception { JSONObject out=new JSONObject().put("prompt_tokens",u.optInt("input_tokens",0)).put("completion_tokens",u.optInt("output_tokens",0)).put("total_tokens",u.optInt("total_tokens",u.optInt("input_tokens",0)+u.optInt("output_tokens",0))); if(u.has("input_tokens_details"))out.put("prompt_tokens_details",u.get("input_tokens_details")); if(u.has("output_tokens_details"))out.put("completion_tokens_details",u.get("output_tokens_details")); return out; }
    static java.io.InputStream responsesStreamToChat(final java.io.InputStream source, final String model) {
        return new java.io.InputStream() {
            final java.io.BufferedReader in=new java.io.BufferedReader(new java.io.InputStreamReader(source,java.nio.charset.StandardCharsets.UTF_8));
            final java.util.Map<Integer,Integer> calls=new java.util.HashMap<Integer,Integer>();
            final java.util.Map<Integer,String> callNames=new java.util.HashMap<Integer,String>();
            final java.util.Map<Integer,String> callArgs=new java.util.HashMap<Integer,String>();
            final java.util.Map<String,String> content=new java.util.HashMap<String,String>();
            String id="chatcmpl-"+java.util.UUID.randomUUID().toString().replace("-", ""); byte[] bytes=new byte[0]; int at=0; boolean ended=false, completed=false;
            String chunk(JSONObject delta,Object finish,JSONObject usage) throws Exception { JSONObject choice=new JSONObject().put("index",0).put("delta",delta).put("finish_reason",finish==null?JSONObject.NULL:finish); JSONObject o=new JSONObject().put("id",id).put("object","chat.completion.chunk").put("created",System.currentTimeMillis()/1000).put("model",model).put("choices",new JSONArray().put(choice)); if(usage!=null)o.put("usage",usage); return "data: "+o.toString()+"\n\n"; }
            String key(String kind,int output,int part) { return kind+":"+output+":"+part; }
            String appendContent(String kind,int output,int part,String value) throws Exception {
                String k=key(kind,output,part), old=content.containsKey(k)?content.get(k):"";
                content.put(k,old+value);
                return value.isEmpty()?"":chunk(new JSONObject().put(kind,value),null,null);
            }
            String reconcileContent(String kind,int output,int part,String value) throws Exception {
                String k=key(kind,output,part), old=content.containsKey(k)?content.get(k):"";
                if(!value.startsWith(old)) throw new java.io.IOException("content changed in final response");
                return appendContent(kind,output,part,value.substring(old.length()));
            }
            String addCall(int output,JSONObject item) throws Exception {
                if(calls.containsKey(output)) return "";
                int idx=calls.size(); calls.put(output,idx);
                String name=item.optString("name"), args=item.optString("arguments"), callId=item.optString("call_id");
                callNames.put(output,name); callArgs.put(output,args);
                JSONObject fn=new JSONObject().put("name",name).put("arguments",args);
                JSONObject call=new JSONObject().put("index",idx).put("id",callId).put("type","function").put("function",fn);
                return chunk(new JSONObject().put("tool_calls",new JSONArray().put(call)),null,null);
            }
            String reconcileCall(int output,JSONObject item) throws Exception {
                String result=addCall(output,item); Integer idx=calls.get(output);
                String oldName=callNames.get(output), name=item.optString("name",oldName);
                String oldArgs=callArgs.get(output), args=item.optString("arguments",oldArgs);
                if(!name.startsWith(oldName) || !args.startsWith(oldArgs)) throw new java.io.IOException("function call changed in final response");
                JSONObject fn=new JSONObject();
                if(name.length()>oldName.length()) fn.put("name",name.substring(oldName.length()));
                if(args.length()>oldArgs.length()) fn.put("arguments",args.substring(oldArgs.length()));
                callNames.put(output,name); callArgs.put(output,args);
                if(fn.length()>0) result+=chunk(new JSONObject().put("tool_calls",new JSONArray().put(new JSONObject().put("index",idx).put("function",fn))),null,null);
                return result;
            }
            String reconcileItem(int output,JSONObject item) throws Exception {
                String type=item.optString("type"), result="";
                if("function_call".equals(type)) {
                    if (!(item.opt("call_id") instanceof String) || item.optString("call_id").isEmpty() || !(item.opt("name") instanceof String) || item.optString("name").isEmpty()) throw new java.io.IOException("function call missing id or name");
                    return reconcileCall(output,item);
                }
                if("message".equals(type)) {
                    JSONArray parts=item.optJSONArray("content");
                    for(int j=0;parts!=null && j<parts.length();j++) {
                        JSONObject part=parts.getJSONObject(j); String partType=part.optString("type");
                        if("output_text".equals(partType)) result+=reconcileContent("content",output,j,part.optString("text"));
                        else if("refusal".equals(partType)) result+=reconcileContent("refusal",output,j,part.optString("refusal"));
                        else throw new java.io.IOException("Unsupported output content");
                    }
                } else if("reasoning".equals(type)) {
                    JSONArray parts=item.optJSONArray("summary");
                    for(int j=0;parts!=null && j<parts.length();j++) result+=reconcileContent("reasoning_content",output,j,parts.getJSONObject(j).optString("text"));
                } else throw new java.io.IOException("Unsupported output item");
                return result;
            }
            boolean fill() throws java.io.IOException {
                while(at>=bytes.length) {
                    if(ended)return false; String result="";
                    try { String line=in.readLine(); if(line==null || line.trim().equals("data: [DONE]")) { ended=true; if(!completed) result="data: {\"error\":{\"message\":\"Responses stream ended before completion\"}}\n\n"; }
                        else if(line.startsWith("data:")) { String raw=line.substring(5).trim(); if(raw.isEmpty())continue; JSONObject event=new JSONObject(raw); String type=event.optString("type"); JSONObject delta=new JSONObject();
                            if("response.output_text.delta".equals(type) || "response.text.delta".equals(type)) { result=appendContent("content",event.optInt("output_index"),event.optInt("content_index"),event.optString("delta")); }
                            else if("response.refusal.delta".equals(type)) { result=appendContent("refusal",event.optInt("output_index"),event.optInt("content_index"),event.optString("delta")); }
                            else if("response.reasoning_summary_text.delta".equals(type) || "response.reasoning_text.delta".equals(type)) { result=appendContent("reasoning_content",event.optInt("output_index"),event.optInt("summary_index"),event.optString("delta")); }
                            else if("response.output_item.added".equals(type)) {
                                JSONObject item=event.optJSONObject("item");
                                if(item!=null && "function_call".equals(item.optString("type"))) result=addCall(event.optInt("output_index"),item);
                                else if(item!=null && !"message".equals(item.optString("type")) && !"reasoning".equals(item.optString("type"))) throw new java.io.IOException("Unsupported output item");
                            }
                            else if("response.output_item.done".equals(type)) {
                                JSONObject item=event.optJSONObject("item"); if(item!=null) result=reconcileItem(event.optInt("output_index"),item);
                            }
                            else if("response.function_call_arguments.delta".equals(type)) {
                                int output=event.optInt("output_index"); Integer idx=calls.get(output); if(idx==null) throw new java.io.IOException("function arguments before item");
                                String args=event.optString("delta"); callArgs.put(output,callArgs.get(output)+args);
                                JSONObject call=new JSONObject().put("index",idx).put("function",new JSONObject().put("arguments",args));
                                result=chunk(new JSONObject().put("tool_calls",new JSONArray().put(call)),null,null);
                            }
                            else if("response.function_call_arguments.done".equals(type)) {
                                int output=event.optInt("output_index"); if(!calls.containsKey(output)) throw new java.io.IOException("function arguments before item");
                                result=reconcileCall(output,new JSONObject().put("arguments",event.optString("arguments")));
                            }
                            else if("response.completed".equals(type) || "response.incomplete".equals(type)) { JSONObject r=event.optJSONObject("response"); if(r==null || !type.substring(9).equals(r.optString("status"))) throw new java.io.IOException("invalid Responses terminal status"); JSONArray finalOutput=r.optJSONArray("output"); for(int i=0;finalOutput!=null && i<finalOutput.length();i++) result+=reconcileItem(i,finalOutput.getJSONObject(i)); JSONObject u=r.optJSONObject("usage"); JSONObject details=r.optJSONObject("incomplete_details"); String reason="response.incomplete".equals(type)?(details!=null && "content_filter".equals(details.optString("reason"))?"content_filter":"length"):calls.isEmpty()?"stop":"tool_calls"; result+=chunk(delta,reason,u==null?null:chatUsage(u))+"data: [DONE]\n\n"; completed=true; ended=true; }
                            else if("response.failed".equals(type)||"error".equals(type)) { result="data: "+new JSONObject().put("error",event.opt("error") == null ? event : event.opt("error")).toString()+"\n\n"; ended=true; }
                        }
                    } catch(Exception ex) { ended=true; result="data: {\"error\":{\"message\":\"Invalid Responses stream\"}}\n\n"; }
                    if(!result.isEmpty()) {bytes=result.getBytes(java.nio.charset.StandardCharsets.UTF_8);at=0;return true;}
                }return true;
            }
            public int read() throws java.io.IOException {if(!fill())return -1;return bytes[at++]&255;}
            public int read(byte[] b,int off,int len) throws java.io.IOException {if(len==0)return 0;if(!fill())return -1;int n=Math.min(len,bytes.length-at);System.arraycopy(bytes,at,b,off,n);at+=n;return n;}
            public void close() throws java.io.IOException {in.close();}
        };
    }

}
