package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;

public class QoderProtocolOfflineTest {
    interface Action { void run() throws Exception; }
    static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    static void rejects(Action action) throws Exception {
        try { action.run(); } catch(IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid request accepted");
    }
    static JSONObject base() throws Exception { return new JSONObject("{\"system\":[{\"type\":\"text\",\"text\":\"system once\"}],\"parameters\":{\"enable_thinking\":true,\"max_tokens\":500},\"business\":{\"id\":\"old\"},\"model_config\":{\"key\":\"model-test\"},\"session_id\":\"old\",\"request_set_id\":\"old\",\"request_id\":\"random\",\"chat_record_id\":\"random\"}"); }
    static boolean equalJson(Object a,Object b) {
        if(a instanceof JSONObject && b instanceof JSONObject) { JSONObject x=(JSONObject)a,y=(JSONObject)b; if(x.length()!=y.length()) return false; java.util.Iterator<String> it=x.keys(); while(it.hasNext()){String k=it.next(); if(!y.has(k)||!equalJson(x.opt(k),y.opt(k))) return false;} return true; }
        if(a instanceof JSONArray && b instanceof JSONArray) {JSONArray x=(JSONArray)a,y=(JSONArray)b; if(x.length()!=y.length()) return false; for(int i=0;i<x.length();i++) if(!equalJson(x.opt(i),y.opt(i))) return false; return true;}
        return a==null?b==null:a.equals(b);
    }
    static JSONObject request() throws Exception { return new JSONObject("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"},{\"role\":\"assistant\",\"content\":null,\"reasoning_content\":\"private thinking\",\"tool_calls\":[{\"id\":\"one\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"x\\\":1}\"}},{\"id\":\"two\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"x\\\":2}\"}}]},{\"role\":\"tool\",\"tool_call_id\":\"two\",\"name\":\"read\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,eA==\"}}]},{\"role\":\"tool\",\"tool_call_id\":\"one\",\"content\":\"done\"},{\"role\":\"user\",\"content\":\"next\"}],\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"read\",\"strict\":false,\"parameters\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"}},\"required\":[\"x\"],\"additionalProperties\":false}}}],\"parallel_tool_calls\":true}"); }
    static JSONObject apply(JSONObject r) throws Exception { return QoderProtocol.apply(base(),r,"session","account"); }
    public static void main(String[] args) throws Exception {
        JSONObject r=request(), base=base(); String original=r.toString(), origBase=base.toString();
        JSONObject out=QoderProtocol.apply(base,r,"session","account"); JSONArray m=out.getJSONArray("messages");
        check(original.equals(r.toString()) && origBase.equals(base.toString()),"input mutation");
        check(m.length()==6 && m.getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text").equals("system once"),"system or message layout");
        JSONObject call=m.getJSONObject(2); check(call.getJSONArray("tool_calls").getJSONObject(0).getString("id").equals("one"),"call id");
        check(call.getJSONArray("tool_calls").getJSONObject(1).getJSONObject("function").getString("arguments").equals("{\"x\":2}"),"arguments");
        check(call.getString("reasoning_content").equals("private thinking") && m.getJSONObject(3).getString("tool_call_id").equals("two") && m.getJSONObject(4).getString("tool_call_id").equals("one"),"result pairing");
        JSONArray content=m.getJSONObject(5).getJSONArray("content"); check(content.getJSONObject(1).getJSONObject("image_url").getString("url").equals("data:image/png;base64,eA=="),"image bytes");
        check(content.getJSONObject(2).getString("text").equals("next") && content.getJSONObject(2).getJSONObject("cache_control").getString("type").equals("ephemeral"),"cache marker");
        check(m.getJSONObject(5).getJSONArray("contents").toString().equals(content.toString()),"contents");
        JSONObject schema=out.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getJSONObject("parameters");
        check(equalJson(schema,r.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getJSONObject("parameters")),"schema changed");
        String id=out.getString("session_id"), taskId=out.getString("request_set_id"); check(!id.equals(taskId) && taskId.equals(out.getJSONObject("business").getString("id")),"task envelope");
        check(out.getJSONObject("business").getString("model_key").equals("model-test") && out.getJSONObject("business").getString("stage").equals("start"),"business attribution");
        JSONObject again=apply(r); check(taskId.equals(again.getString("request_set_id")) && again.getJSONObject("business").getLong("begin_at")==out.getJSONObject("business").getLong("begin_at"),"stable task");
        QoderProtocol.answered(taskId); check(apply(r).getJSONObject("business").getString("stage").equals("processing"),"answered stage");
        JSONObject next=request(); next.getJSONArray("messages").put(new JSONObject().put("role","assistant").put("content","reply")).put(new JSONObject().put("role","user").put("content","new prompt"));
        check(!taskId.equals(apply(next).getString("request_set_id")),"new user task");
        JSONObject firstPrompt=new JSONObject().put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hello")));
        String firstTask=apply(firstPrompt).getString("request_set_id");
        JSONObject imageLoop=new JSONObject("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"},{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"im\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}]},{\"role\":\"tool\",\"tool_call_id\":\"im\",\"content\":\"[Images from this tool result follow.]\"},{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"[From the result of tool call im:]\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,eA==\"}}]},{\"role\":\"user\",\"content\":\"more user text\"}]}");
        check(firstTask.equals(apply(imageLoop).getString("request_set_id")),"bridge image and user text remain in tool task");
        imageLoop.getJSONArray("messages").put(new JSONObject().put("role","assistant").put("content","answer")).put(new JSONObject().put("role","user").put("content","new"));
        check(!firstTask.equals(apply(imageLoop).getString("request_set_id")),"user after answer starts task");
        check(!taskId.equals(QoderProtocol.apply(base(),r,"session","other").getString("request_set_id")),"account task isolation");
        JSONObject unsafe=base(); unsafe.getJSONObject("model_config").put("api_key","secret").put("url","https://invalid").put("extra",1);
        JSONObject clean=QoderProtocol.apply(unsafe,r,"s","a").getJSONObject("model_config"); check(clean.getString("api_key").isEmpty() && clean.getString("url").isEmpty() && !clean.has("extra"),"model config allowlist");
        rejects(()->QoderProtocol.apply(base().put("model_config",new JSONObject().put("key","auto")),r,"s","a"));
        JSONObject mismatch=base(); mismatch.getJSONObject("business").put("model_key","other"); rejects(()->QoderProtocol.apply(mismatch,r,"s","a"));
        check(id.equals(apply(r).getString("session_id")),"named session stability");
        check(!id.equals(QoderProtocol.apply(base(),r,"session","other").getString("session_id")),"account isolation");
        check(!QoderProtocol.apply(base(),r,null,"account").getString("session_id").equals(QoderProtocol.apply(base(),r,null,"account").getString("session_id")),"anonymous sessions");
        JSONObject cached=request().put("prompt_cache_key","thread");
        JSONObject c1=QoderProtocol.apply(base(),cached,null,"account"), c2=QoderProtocol.apply(base(),cached,null,"account");
        check(c1.getString("session_id").equals(c2.getString("session_id")) && c1.getString("request_set_id").equals(c2.getString("request_set_id")),"cache session and task stable");
        check(!c1.getString("session_id").equals(QoderProtocol.apply(base(),cached,null,"other").getString("session_id")),"cache account scope");
        check(id.equals(QoderProtocol.apply(base(),cached,"session","account").getString("session_id")),"named session priority");
        for(Object key:new Object[]{" ", 5, new String(new char[1025]).replace('\0','x')}) {
            JSONObject badCache=request().put("prompt_cache_key",key).put("user","same");
            check(!QoderProtocol.apply(base(),badCache,null,"account").getString("session_id").equals(QoderProtocol.apply(base(),badCache,null,"account").getString("session_id")),"invalid cache key ignored");
        }
        JSONObject history=new JSONObject().put("messages",new JSONArray().put(new JSONObject().put("role","assistant").put("content","answer").put("reasoning", "alias")));
        check(apply(history).getJSONArray("messages").getJSONObject(1).getString("reasoning_content").equals("alias"),"reasoning alias");
        JSONObject hm=history.getJSONArray("messages").getJSONObject(0);
        hm.put("reasoning_content","primary").put("reasoning_details",new JSONArray().put(new JSONObject().put("text","detail")));
        check(apply(history).getJSONArray("messages").getJSONObject(1).getString("reasoning_content").equals("primary"),"reasoning precedence");
        hm.remove("reasoning_content"); hm.put("reasoning",42).put("reasoning_details",new JSONArray().put(new JSONObject().put("text",5).put("summary","summary")).put(new JSONObject().put("text"," text")).put("opaque"));
        check(apply(history).getJSONArray("messages").getJSONObject(1).getString("reasoning_content").equals("summary text"),"safe detail strings");
        hm.put("reasoning_content"," "); check(!apply(history).getJSONArray("messages").getJSONObject(1).has("reasoning_content"),"blank ignored");
        hm.put("reasoning_content",123); rejects(()->apply(history));
        hm.remove("reasoning_content"); hm.put("content",JSONObject.NULL).put("reasoning","only thought");
        check(apply(history).getJSONArray("messages").length()==1,"reasoning-only omitted");
        JSONObject none=request().put("tool_choice","none").put("reasoning_effort","none"); JSONObject n=apply(none);
        check(!n.has("tools") && !n.getJSONObject("parameters").getBoolean("enable_thinking"),"none options");
        rejects(()->apply(request().put("tool_choice","required")));
        rejects(()->apply(request().put("tool_choice",new JSONObject().put("type","function"))));
        rejects(()->apply(request().put("parallel_tool_calls",false)));
        check(apply(request().put("reasoning_effort","medium")).getJSONObject("parameters").getBoolean("enable_thinking"),"unknown model default");
        rejects(()->apply(request().put("reasoning_effort",5)));
        JSONObject meta=new JSONObject("{\"key\":\"model-test\",\"is_reasoning\":true,\"thinking_config\":{\"enabled\":{\"efforts\":{\"low\":{},\"high\":{\"is_default\":true},\"max\":{}}},\"disabled\":{}}}");
        JSONObject mb=base().put("model_config",meta); mb.getJSONObject("parameters").put("max_tokens",32000);
        JSONObject mapped=QoderProtocol.apply(mb,request().put("reasoningEffort","medium"),"s","a");
        check(mapped.getJSONObject("parameters").getString("reasoning_effort").equals("high"),"nearest tie goes higher");
        check(QoderProtocol.apply(mb,request(),"s","a").getJSONObject("parameters").getString("reasoning_effort").equals("high"),"model default");
        JSONObject small=new JSONObject(mb.toString()); small.getJSONObject("parameters").put("max_tokens",500);
        check(!QoderProtocol.apply(small,request().put("max_completion_tokens",2000).put("max_tokens",500),"s","a").getJSONObject("parameters").getBoolean("enable_thinking"),"effective small budget");
        check(QoderProtocol.apply(mb,request().put("max_tokens",500),"s","a").getJSONObject("parameters").getBoolean("enable_thinking"),"effective large budget");
        check(QoderProtocol.apply(mb,request().put("reasoning_effort","unknown"),"s","a").getJSONObject("parameters").getString("reasoning_effort").equals("high"),"unknown effort default");
        JSONObject always=new JSONObject(meta.toString()); always.getJSONObject("thinking_config").remove("disabled");
        JSONObject an=QoderProtocol.apply(base().put("model_config",always),request().put("reasoning_effort","none"),"s","a");
        check(an.getJSONObject("parameters").getBoolean("enable_thinking") && an.getJSONObject("parameters").getString("reasoning_effort").equals("low"),"always thinks");
        check(QoderProtocol.hasImages(request()) && QoderProtocol.supportsImages(new JSONObject().put("is_vl",true)) && !QoderProtocol.supportsImages(new JSONObject()),"image capability");
        JSONObject strict=request(); strict.getJSONArray("tools").getJSONObject(0).getJSONObject("function").put("strict",true); rejects(()->apply(strict));
        JSONObject missing=request(); missing.getJSONArray("messages").remove(2); rejects(()->apply(missing));
        JSONObject dup=request(); dup.getJSONArray("messages").getJSONObject(3).put("tool_call_id","two"); rejects(()->apply(dup));
        JSONObject unknown=request(); unknown.getJSONArray("messages").getJSONObject(2).put("tool_call_id","unknown"); rejects(()->apply(unknown));
        JSONObject reorder=request(); reorder.getJSONArray("messages").getJSONObject(2).put("role","user"); rejects(()->apply(reorder));
        JSONObject invalid=request(); invalid.getJSONArray("messages").getJSONObject(2).getJSONArray("content").getJSONObject(1).getJSONObject("image_url").put("url","data:image/png;base64,%%%"); rejects(()->apply(invalid));
        for(String argsText:new String[]{"{\"x\":1,\"x\":2}","{\"x\":1} trailing","[]","{x:1}"}) {
            JSONObject invalidArgs=request(); invalidArgs.getJSONArray("messages").getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").put("arguments",argsText); rejects(()->apply(invalidArgs));
        }
        StringBuilder deep=new StringBuilder(); for(int i=0;i<100;i++) deep.append("{\"a\":"); deep.append("0"); for(int i=0;i<100;i++) deep.append('}');
        JSONObject deepArgs=request(); deepArgs.getJSONArray("messages").getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").put("arguments",deep.toString()); rejects(()->apply(deepArgs));
        JSONObject badName=request(); badName.getJSONArray("messages").getJSONObject(2).put("name",3); rejects(()->apply(badName));
        rejects(()->apply(request().put("n",2))); rejects(()->apply(request().put("n","1"))); rejects(()->apply(request().put("stream","true")));
        check(apply(request().put("n",1).put("stream",true)).getJSONArray("messages").length()==6,"single streamed choice");
        JSONObject unions=request(); JSONObject unionSchema=new JSONObject("{\"properties\":{\"type\":{\"oneOf\":[{\"type\":\"object\"},{\"$ref\":\"#/$defs/item\"}]},\"unchanged\":{\"type\":\"string\"}},\"$defs\":{\"item\":{\"type\":\"object\"}}}");
        unions.getJSONArray("tools").getJSONObject(0).getJSONObject("function").put("parameters",unionSchema);
        JSONObject resultSchema=apply(unions).getJSONArray("tools").getJSONObject(0).getJSONObject("function").getJSONObject("parameters");
        check(resultSchema.getJSONObject("properties").getJSONObject("type").getString("type").equals("object") && resultSchema.getJSONObject("properties").getJSONObject("unchanged").getString("type").equals("string") && !unionSchema.getJSONObject("properties").getJSONObject("type").has("type"),"typed unions preserve schema fields and input");
        QoderProtocol.validateClientHistory(request(),"chat");
        JSONObject badChat=request(); badChat.getJSONArray("messages").remove(2); rejects(()->QoderProtocol.validateClientHistory(badChat,"chat"));
        JSONObject duplicateChat=request(); duplicateChat.getJSONArray("messages").getJSONObject(3).put("tool_call_id","two"); rejects(()->QoderProtocol.validateClientHistory(duplicateChat,"chat"));
        JSONObject earlyChat=request(); earlyChat.getJSONArray("messages").getJSONObject(2).put("role","user"); rejects(()->QoderProtocol.validateClientHistory(earlyChat,"chat"));
        JSONObject anthropic=new JSONObject("{\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"a\",\"name\":\"read\",\"input\":{}},{\"type\":\"tool_use\",\"id\":\"b\",\"name\":\"read\",\"input\":{}}]},{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"b\",\"content\":\"ok\"},{\"type\":\"tool_result\",\"tool_use_id\":\"a\",\"content\":\"ok\"},{\"type\":\"text\",\"text\":\"next\"}]}]}");
        QoderProtocol.validateClientHistory(anthropic,"messages");
        JSONObject am=new JSONObject(anthropic.toString()); am.getJSONArray("messages").getJSONObject(1).getJSONArray("content").remove(0); rejects(()->QoderProtocol.validateClientHistory(am,"messages"));
        JSONObject ad=new JSONObject(anthropic.toString()); ad.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0).put("tool_use_id","a"); rejects(()->QoderProtocol.validateClientHistory(ad,"messages"));
        JSONObject ao=new JSONObject(anthropic.toString()); ao.getJSONArray("messages").getJSONObject(1).put("role","assistant"); rejects(()->QoderProtocol.validateClientHistory(ao,"messages"));
        JSONObject responses=new JSONObject("{\"input\":[{\"type\":\"function_call\",\"call_id\":\"a\",\"name\":\"read\",\"arguments\":\"{}\"},{\"type\":\"function_call\",\"call_id\":\"b\",\"name\":\"read\",\"arguments\":\"{}\"},{\"type\":\"function_call_output\",\"call_id\":\"b\",\"output\":\"ok\"},{\"type\":\"function_call_output\",\"call_id\":\"a\",\"output\":\"ok\"},{\"type\":\"message\",\"role\":\"user\",\"content\":\"next\"}]}");
        QoderProtocol.validateClientHistory(responses,"responses"); QoderProtocol.validateClientHistory(new JSONObject().put("input","hello"),"responses");
        JSONObject rm=new JSONObject(responses.toString()); rm.getJSONArray("input").remove(2); rejects(()->QoderProtocol.validateClientHistory(rm,"responses"));
        JSONObject rd=new JSONObject(responses.toString()); rd.getJSONArray("input").getJSONObject(2).put("call_id","a"); rejects(()->QoderProtocol.validateClientHistory(rd,"responses"));
        JSONObject rr=new JSONObject(responses.toString()); rr.getJSONArray("input").getJSONObject(3).put("type","function_call").put("name","read").put("arguments","{}").put("call_id","c"); rejects(()->QoderProtocol.validateClientHistory(rr,"responses"));
        if(args.length>0) {
            String data=new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[0])),java.nio.charset.StandardCharsets.UTF_8);
            Object fixture=data.trim().startsWith("[")?new JSONArray(data):new JSONObject(data).getJSONArray("tools");
            JSONArray list=(JSONArray)fixture; check(list.length()==122,"fixture tool count");
            JSONObject fixtureRequest=new JSONObject().put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","fixture"))).put("tools",list).put("reasoning_effort","medium");
            String before=fixtureRequest.toString(); JSONObject fixtureOut=apply(fixtureRequest);
            check(fixtureOut.getJSONArray("tools").length()==122 && before.equals(fixtureRequest.toString()),"122 tool fixture");
            System.out.println("122-tool fixture OK");
        }
        System.out.println("QoderProtocolOfflineTest OK");
    }
}
