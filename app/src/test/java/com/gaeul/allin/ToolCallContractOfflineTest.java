package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** One synthetic fixture replayed through Chat / Responses / Messages, without a provider. */
public final class ToolCallContractOfflineTest {
    interface Checked { void run() throws Exception; }
    static final String MODEL = "offline/contract";
    static final String ID_A = "call_kept-alpha", ID_B = "opaque-beta";
    static final String ARGS_A = "{ \"q\":\"中文\\n雪\", \"nested\":{\"id\":\"arg-id-keep\",\"signature\":\"arg-signature-keep\"}, \"n\":1234567890123456789 }";
    static final String ARGS_B = "{\"timezone\":\"Asia/Shanghai\",\"items\":[1,true,null,\"\\\"quoted\\\"\"],\"signature\":\"other-arg-signature\"}";
    static final Map<String,Call> EXPECTED = new LinkedHashMap<String,Call>();
    static {
        EXPECTED.put(ID_A, new Call(ID_A, "lookup_weather", ARGS_A));
        EXPECTED.put(ID_B, new Call(ID_B, "lookup_time", ARGS_B));
    }
    static final class Call {
        final String id, name, args;
        Call(String id, String name, String args) { this.id=id; this.name=name; this.args=args; }
        JSONObject chat() throws Exception { return new JSONObject().put("id",id).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",args)); }
        String result() { return "result-for-" + id + ": 中文 / " + name; }
    }
    static final class ChatStream {
        final Map<String,Call> calls = new LinkedHashMap<String,Call>();
        boolean error, done;
    }
    static void check(boolean ok, String why) { if(!ok) throw new AssertionError(why); }
    static JSONObject copy(JSONObject o) throws Exception { return new JSONObject(o.toString()); }
    static void set(JSONObject o, String key, Object value) throws Exception { if(value==null)o.remove(key); else o.put(key,value); }
    static void rejects(Checked action, String why) throws Exception {
        try { action.run(); } catch(IllegalArgumentException expected) { return; }
        throw new AssertionError("accepted " + why);
    }
    static InputStream input(String raw) { return new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)); }
    static String read(InputStream in) throws Exception {
        try(InputStream s=in; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] bytes=new byte[7]; int n;
            while((n=s.read(bytes))!=-1)out.write(bytes,0,n);
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    static List<JSONObject> events(String raw) throws Exception {
        List<JSONObject> out=new ArrayList<JSONObject>();
        for(String line:raw.split("\n")) if(line.startsWith("data:")) {
            String data=line.substring(5).trim();
            if(!data.isEmpty() && !"[DONE]".equals(data))out.add(new JSONObject(data));
        }
        return out;
    }
    static String sse(JSONObject... objects) {
        StringBuilder out=new StringBuilder();
        for(JSONObject object:objects)out.append("data: ").append(object).append("\n\n");
        return out.toString();
    }
    static JSONObject chunk(JSONArray calls, String finish) throws Exception {
        JSONObject delta=new JSONObject(); if(calls!=null)delta.put("tool_calls",calls);
        return new JSONObject().put("id","response-scope-fixture").put("choices",new JSONArray().put(new JSONObject().put("index",0).put("delta",delta).put("finish_reason",finish==null?JSONObject.NULL:finish)));
    }
    static JSONObject delta(int index, String id, String name, String args) throws Exception {
        JSONObject call=new JSONObject().put("index",index), fn=new JSONObject();
        if(id!=null)call.put("id",id);
        if(name!=null)fn.put("name",name);
        if(args!=null)fn.put("arguments",args);
        if(fn.length()>0)call.put("function",fn);
        return call;
    }
    static JSONObject chatReply() throws Exception {
        JSONArray calls=new JSONArray(); for(Call c:EXPECTED.values())calls.put(c.chat());
        return new JSONObject().put("id","response-scope-fixture").put("model",MODEL).put("choices",new JSONArray().put(new JSONObject().put("index",0).put("message",new JSONObject().put("role","assistant").put("content",JSONObject.NULL).put("tool_calls",calls)).put("finish_reason","tool_calls")));
    }
    static JSONObject chatMessage(JSONObject reply) throws Exception { return reply.getJSONArray("choices").getJSONObject(0).getJSONObject("message"); }
    static Map<String,Call> chatCalls(JSONObject message) throws Exception {
        Map<String,Call> out=new LinkedHashMap<String,Call>(); JSONArray calls=message.optJSONArray("tool_calls");
        for(int i=0;calls!=null && i<calls.length();i++) {
            JSONObject c=calls.getJSONObject(i), f=c.getJSONObject("function"); String id=c.getString("id");
            check(!out.containsKey(id),"duplicate Chat ID"); out.put(id,new Call(id,f.getString("name"),f.getString("arguments")));
        }
        return out;
    }
    static Map<String,Call> responsesCalls(JSONObject response) throws Exception {
        Map<String,Call> out=new LinkedHashMap<String,Call>(); JSONArray items=response.getJSONArray("output");
        for(int i=0;i<items.length();i++) {
            JSONObject c=items.getJSONObject(i); if(!"function_call".equals(c.optString("type")))continue;
            String id=c.getString("call_id"); check(!out.containsKey(id),"duplicate Responses ID");
            out.put(id,new Call(id,c.getString("name"),c.getString("arguments")));
        }
        return out;
    }
    static Map<String,Call> messagesCalls(JSONObject message) throws Exception {
        check(!"error".equals(message.optString("type")),"Messages conversion returned error");
        Map<String,Call> out=new LinkedHashMap<String,Call>(); JSONArray blocks=message.getJSONArray("content");
        for(int i=0;i<blocks.length();i++) {
            JSONObject c=blocks.getJSONObject(i); if(!"tool_use".equals(c.optString("type")))continue;
            String id=c.getString("id"); check(!out.containsKey(id),"duplicate Messages ID");
            out.put(id,new Call(id,c.getString("name"),c.getJSONObject("input").toString()));
        }
        return out;
    }
    static void mapping(Map<String,Call> actual, boolean exactArgs, String route) throws Exception {
        check(actual.keySet().equals(EXPECTED.keySet()),route+" ID mismatch: "+actual.keySet());
        for(Call expected:EXPECTED.values()) {
            Call c=actual.get(expected.id);
            check(expected.name.equals(c.name),route+" name mismatch for "+expected.id+": "+c.name);
            check(exactArgs?expected.args.equals(c.args):new JSONObject(expected.args).similar(new JSONObject(c.args)),route+" arguments changed for "+expected.id);
        }
    }
    static JSONObject chatHistory(Map<String,Call> calls) throws Exception {
        JSONArray toolCalls=new JSONArray(); for(Call c:calls.values())toolCalls.put(c.chat());
        JSONArray messages=new JSONArray().put(new JSONObject().put("role","user").put("content","fixture"))
                .put(new JSONObject().put("role","assistant").put("content",JSONObject.NULL).put("tool_calls",toolCalls));
        List<Call> reversed=new ArrayList<Call>(calls.values());
        for(int i=reversed.size()-1;i>=0;i--) {
            Call c=reversed.get(i); messages.put(new JSONObject().put("role","tool").put("tool_call_id",c.id).put("content",EXPECTED.get(c.id).result()));
        }
        return new JSONObject().put("model",MODEL).put("messages",messages);
    }
    static void history(JSONObject chat, boolean exactArgs, String route) throws Exception {
        Map<String,Call> calls=new LinkedHashMap<String,Call>(); Map<String,String> results=new LinkedHashMap<String,String>();
        JSONArray messages=chat.getJSONArray("messages");
        for(int i=0;i<messages.length();i++) {
            JSONObject m=messages.getJSONObject(i);
            if(m.has("tool_calls"))calls.putAll(chatCalls(m));
            if("tool".equals(m.optString("role"))) { String id=m.getString("tool_call_id"); check(!results.containsKey(id),route+" duplicate result"); results.put(id,m.getString("content")); }
        }
        mapping(calls,exactArgs,route);
        check(results.keySet().equals(EXPECTED.keySet()),route+" result reference mismatch");
        for(Call c:EXPECTED.values())check(c.result().equals(results.get(c.id)),route+" result paired to wrong call "+c.id);
    }
    static void resultsRoundTrip(Map<String,Call> calls, boolean exactArgs, String route) throws Exception {
        mapping(calls,exactArgs,route);
        JSONObject request=chatHistory(calls); String before=request.toString();
        history(ResponsesBridge.toChat(ResponsesBridge.chatToResponses(request,MODEL)),exactArgs,route+" → Responses history → Chat");
        history(AnthropicBridge.requestToOpenAI(ZCodeAnthropic.toAnthropic(request)),false,route+" → Messages history → Chat");
        check(before.equals(request.toString()),route+" mutated source history / tool args");
    }
    static String plainStream() throws Exception {
        JSONArray calls=new JSONArray(); int i=0; for(Call c:EXPECTED.values())calls.put(c.chat().put("index",i++));
        return sse(chunk(calls,null),chunk(null,"tool_calls"))+"data: [DONE]\n\n";
    }
    static String fragmentedStream() throws Exception {
        int a=ARGS_A.length()/2, b=ARGS_B.length()/2;
        return sse(
            chunk(new JSONArray().put(delta(0,null,null,ARGS_A.substring(0,a))).put(delta(1,null,null,ARGS_B.substring(0,b))),null),
            chunk(new JSONArray().put(delta(1,"opaque-","lookup_",ARGS_B.substring(b))).put(delta(0,null,null,"")),null),
            chunk(new JSONArray().put(delta(0,"call_kept-","lookup_",ARGS_A.substring(a))).put(delta(1,"beta","time","")),null),
            chunk(new JSONArray().put(delta(0,"alpha","weather","")),null),chunk(null,"tool_calls"))+"data: [DONE]\n\n";
    }
    static ChatStream chatStream(String raw) throws Exception {
        ChatStream out=new ChatStream(); out.done=raw.contains("data: [DONE]");
        Map<Integer,StringBuilder[]> assembled=new TreeMap<Integer,StringBuilder[]>();
        for(JSONObject e:events(raw)) {
            if(e.has("error")) { out.error=true; continue; }
            JSONArray choices=e.optJSONArray("choices");
            for(int i=0;choices!=null && i<choices.length();i++) {
                JSONObject d=choices.getJSONObject(i).optJSONObject("delta"); JSONArray calls=d==null?null:d.optJSONArray("tool_calls");
                for(int j=0;calls!=null && j<calls.length();j++) {
                    JSONObject c=calls.getJSONObject(j); int idx=c.getInt("index");
                    if(!assembled.containsKey(idx))assembled.put(idx,new StringBuilder[]{new StringBuilder(),new StringBuilder(),new StringBuilder()});
                    StringBuilder[] p=assembled.get(idx); if(c.opt("id") instanceof String)p[0].append(c.getString("id"));
                    JSONObject f=c.optJSONObject("function");
                    if(f!=null) { if(f.opt("name") instanceof String)p[1].append(f.getString("name")); if(f.opt("arguments") instanceof String)p[2].append(f.getString("arguments")); }
                }
            }
        }
        for(StringBuilder[] p:assembled.values()) { String id=p[0].toString(); check(!out.calls.containsKey(id),"duplicate assembled Chat ID"); out.calls.put(id,new Call(id,p[1].toString(),p[2].toString())); }
        return out;
    }
    static JSONObject responsesTerminal(String raw, boolean stableIdentity) throws Exception {
        JSONObject terminal=null; Map<Integer,JSONObject> added=new LinkedHashMap<Integer,JSONObject>();
        for(JSONObject e:events(raw)) {
            String type=e.optString("type"); check(!"response.failed".equals(type),"Responses stream failed");
            if("response.output_item.added".equals(type))added.put(e.getInt("output_index"),e.getJSONObject("item"));
            if(stableIdentity && "response.output_item.done".equals(type)) {
                JSONObject done=e.getJSONObject("item"), first=added.get(e.getInt("output_index"));
                check(first!=null && first.getString("id").equals(done.getString("id")),"Responses item ID changed");
                if("function_call".equals(done.optString("type"))) {
                    check(first.getString("call_id").equals(done.getString("call_id")),"Responses call_id changed after added");
                    check(first.getString("name").equals(done.getString("name")),"Responses name changed after added");
                }
            }
            if("response.completed".equals(type) || "response.incomplete".equals(type))terminal=e.getJSONObject("response");
        }
        check(terminal!=null,"Responses terminal absent"); return terminal;
    }
    static Map<String,Call> messagesStream(String chatSse) throws Exception {
        AnthropicBridge.StreamState state=new AnthropicBridge.StreamState(MODEL); List<String> raw=new ArrayList<String>();
        for(JSONObject chunk:events(chatSse))raw.addAll(state.onChunk(chunk)); raw.addAll(state.onEnd());
        StringBuilder all=new StringBuilder(); for(String e:raw)all.append(e);
        Map<Integer,JSONObject> blocks=new LinkedHashMap<Integer,JSONObject>(); Map<Integer,StringBuilder> args=new LinkedHashMap<Integer,StringBuilder>();
        int open=-1; boolean stopped=false;
        for(JSONObject e:events(all.toString())) {
            String type=e.optString("type"); check(!"error".equals(type),"Messages stream failed");
            if("content_block_start".equals(type)) { check(open<0,"parallel Messages blocks not serialized"); open=e.getInt("index"); blocks.put(open,e.getJSONObject("content_block")); args.put(open,new StringBuilder()); }
            if("content_block_delta".equals(type)) { check(open==e.getInt("index"),"Messages delta wrong block"); JSONObject d=e.getJSONObject("delta"); if("input_json_delta".equals(d.optString("type")))args.get(open).append(d.getString("partial_json")); }
            if("content_block_stop".equals(type)) { check(open==e.getInt("index"),"Messages stop wrong block"); open=-1; }
            if("message_stop".equals(type)) { check(open<0,"Messages terminal with open block"); stopped=true; }
        }
        check(stopped,"Messages success terminal absent"); Map<String,Call> out=new LinkedHashMap<String,Call>();
        for(Map.Entry<Integer,JSONObject> entry:blocks.entrySet()) {
            JSONObject b=entry.getValue(); if(!"tool_use".equals(b.optString("type")))continue;
            String id=b.getString("id"); check(!out.containsKey(id),"duplicate Messages stream ID");
            String a=args.get(entry.getKey()).toString(); out.put(id,new Call(id,b.getString("name"),a.isEmpty()?b.getJSONObject("input").toString():a));
        }
        return out;
    }
    static JSONObject item(Call c, boolean late) throws Exception {
        return new JSONObject().put("id","fc_"+c.id).put("type","function_call").put("call_id",late?"":c.id).put("name",late?"":c.name).put("arguments","");
    }
    static JSONObject nativeEvent(String type, int output, JSONObject item) throws Exception {
        JSONObject event=new JSONObject().put("type",type).put("output_index",output);
        if(item!=null)event.put("item",item); return event;
    }
    static String nativeResponses(boolean late) throws Exception {
        Call a=EXPECTED.get(ID_A), b=EXPECTED.get(ID_B);
        JSONObject firstA=item(a,late), firstB=item(b,late);
        JSONObject finalA=item(a,false).put("arguments",a.args), finalB=item(b,false).put("arguments",b.args);
        int split=a.args.length()/2;
        return sse(nativeEvent("response.output_item.added",0,firstA),nativeEvent("response.output_item.added",1,firstB),
            nativeEvent("response.function_call_arguments.delta",1,null).put("delta",b.args),
            nativeEvent("response.function_call_arguments.delta",0,null).put("delta",a.args.substring(0,split)),
            nativeEvent("response.function_call_arguments.done",0,null).put("arguments",a.args),
            nativeEvent("response.output_item.done",1,finalB),nativeEvent("response.output_item.done",0,finalA),
            new JSONObject().put("type","response.completed").put("response",new JSONObject().put("id","native-response-fixture").put("status","completed").put("output",new JSONArray().put(finalA).put(finalB))));
    }
    static void nonstreamMatrix() throws Exception {
        JSONObject source=chatReply(); String before=source.toString();
        resultsRoundTrip(chatCalls(chatMessage(source)),true,"Chat nonstream");
        JSONObject responses=ResponsesBridge.chatResponseToResponses(source,MODEL);
        resultsRoundTrip(responsesCalls(responses),true,"Chat → Responses nonstream");
        resultsRoundTrip(chatCalls(chatMessage(ResponsesBridge.responsesResponseToChat(responses,MODEL))),true,"Responses → Chat nonstream");
        JSONObject messages=AnthropicBridge.responseToAnthropic(source,MODEL);
        resultsRoundTrip(messagesCalls(messages),false,"Chat → Messages nonstream");
        resultsRoundTrip(chatCalls(chatMessage(ZCodeAnthropic.toOpenAI(messages,MODEL))),false,"Messages → Chat nonstream");
        check(before.equals(source.toString()),"nonstream source mutated");
    }
    static void streamMatrix(String raw) throws Exception {
        String responseSse=read(ResponsesBridge.chatStreamToResponses(input(raw),MODEL));
        resultsRoundTrip(responsesCalls(responsesTerminal(responseSse,false)),true,"Chat → Responses stream");
        ChatStream round=chatStream(read(ResponsesBridge.responsesStreamToChat(input(responseSse),MODEL)));
        check(round.done && !round.error,"Responses → Chat stream terminal"); resultsRoundTrip(round.calls,true,"Responses → Chat stream");
        resultsRoundTrip(messagesStream(raw),true,"Chat → Messages stream");
        StringBuilder back=new StringBuilder(); AnthropicBridge.StreamState anth=new AnthropicBridge.StreamState(MODEL); List<String> messageEvents=new ArrayList<String>();
        for(JSONObject e:events(raw))messageEvents.addAll(anth.onChunk(e)); messageEvents.addAll(anth.onEnd());
        ZCodeAnthropic.StreamState z=new ZCodeAnthropic.StreamState(MODEL);
        for(String e:messageEvents) for(JSONObject ev:events(e)) for(String c:z.onEvent(ev.getString("type"),ev))back.append("data: ").append(c).append("\n\n");
        resultsRoundTrip(chatStream(back.toString()).calls,true,"Messages → Chat stream");
    }
    static void nativeStream(boolean late) throws Exception {
        ChatStream converted=chatStream(read(ResponsesBridge.responsesStreamToChat(input(nativeResponses(late)),MODEL)));
        check(converted.done && !converted.error,"native Responses terminal"); resultsRoundTrip(converted.calls,true,"native Responses late="+late);
    }
    static Object[] invalidIds() { return new Object[]{null,JSONObject.NULL,""," \t",17,Boolean.TRUE}; }
    static void inboundMessages() throws Exception {
        JSONObject base=ZCodeAnthropic.toAnthropic(chatHistory(EXPECTED));
        JSONArray messages=base.getJSONArray("messages"); JSONObject use=messages.getJSONObject(1).getJSONArray("content").getJSONObject(0);
        JSONObject result=messages.getJSONObject(2).getJSONArray("content").getJSONObject(0);
        for(Object value:invalidIds()) {
            JSONObject req=copy(base); set(req.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0),"id",value);
            rejects(()->AnthropicBridge.requestToOpenAI(req),"Messages tool_use id="+value);
        }
        for(Object value:invalidIds()) {
            JSONObject req=copy(base); set(req.getJSONArray("messages").getJSONObject(2).getJSONArray("content").getJSONObject(0),"tool_use_id",value);
            rejects(()->AnthropicBridge.requestToOpenAI(req),"Messages result reference="+value);
        }
        check(use.getString("id").equals(ID_A) && result.has("tool_use_id"),"inbound fixture mutated");
    }
    static void inboundChat() throws Exception {
        for(Object value:invalidIds()) {
            JSONObject req=chatHistory(EXPECTED); set(req.getJSONArray("messages").getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0),"id",value);
            rejects(()->AnthropicBridge.normalizeOpenAiMessages(req.getJSONArray("messages")),"Chat call id="+value);
        }
        for(Object value:invalidIds()) {
            JSONObject req=chatHistory(EXPECTED); set(req.getJSONArray("messages").getJSONObject(2),"tool_call_id",value);
            rejects(()->AnthropicBridge.normalizeOpenAiMessages(req.getJSONArray("messages")),"Chat result reference="+value);
        }
    }
    static void inboundResponses() throws Exception {
        JSONObject base=ResponsesBridge.chatToResponses(chatHistory(EXPECTED),MODEL);
        for(String type:new String[]{"function_call","function_call_output"})for(Object value:invalidIds()) {
            JSONObject req=copy(base); JSONArray items=req.getJSONArray("input");
            for(int i=0;i<items.length();i++)if(type.equals(items.getJSONObject(i).optString("type"))) { set(items.getJSONObject(i),"call_id",value); break; }
            rejects(()->ResponsesBridge.toChat(req),"Responses "+type+" reference="+value);
        }
    }
    static void inboundChatToResponses() throws Exception {
        for(Object value:invalidIds()) {
            JSONObject req=chatHistory(EXPECTED); set(req.getJSONArray("messages").getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0),"id",value);
            rejects(()->ResponsesBridge.chatToResponses(req,MODEL),"Chat → Responses call id="+value);
        }
        for(Object value:invalidIds()) {
            JSONObject req=chatHistory(EXPECTED); set(req.getJSONArray("messages").getJSONObject(2),"tool_call_id",value);
            rejects(()->ResponsesBridge.chatToResponses(req,MODEL),"Chat → Responses result reference="+value);
        }
    }
    static void missingChatOutput(boolean messages) throws Exception {
        for(Object value:invalidIds()) {
            JSONObject source=chatReply(); set(chatMessage(source).getJSONArray("tool_calls").getJSONObject(0),"id",value);
            if(messages)check("error".equals(AnthropicBridge.responseToAnthropic(source,MODEL).optString("type")),"Messages fabricated/accepted output id="+value);
            else rejects(()->ResponsesBridge.chatResponseToResponses(source,MODEL),"Responses output id="+value);
        }
    }
    static void missingResponsesOutput() throws Exception {
        JSONObject base=ResponsesBridge.chatResponseToResponses(chatReply(),MODEL);
        for(String field:new String[]{"call_id","name"})for(Object value:invalidIds()) {
            JSONObject source=copy(base); set(source.getJSONArray("output").getJSONObject(0),field,value);
            rejects(()->ResponsesBridge.responsesResponseToChat(source,MODEL),"Responses → Chat output "+field+"="+value);
        }
    }
    static void missingStreamIdentity() throws Exception {
        String raw=sse(chunk(new JSONArray().put(delta(0,null,"lookup","{}")),null),chunk(null,"tool_calls"))+"data: [DONE]\n\n";
        String response=read(ResponsesBridge.chatStreamToResponses(input(raw),MODEL));
        check(response.contains("response.failed") && !response.contains("response.completed"),"missing Chat ID completed in Responses");
        AnthropicBridge.StreamState state=new AnthropicBridge.StreamState(MODEL); StringBuilder out=new StringBuilder();
        for(JSONObject c:events(raw))for(String e:state.onChunk(c))out.append(e); for(String e:state.onEnd())out.append(e);
        check(out.toString().contains("event: error") && !out.toString().contains("message_stop"),"missing Chat ID completed in Messages");
        JSONObject first=new JSONObject().put("type","function_call").put("id","fc_missing").put("arguments","");
        String nativeRaw=sse(nativeEvent("response.output_item.added",0,first),nativeEvent("response.function_call_arguments.done",0,null).put("arguments","{}"),new JSONObject().put("type","response.completed").put("response",new JSONObject().put("status","completed")));
        String converted=read(ResponsesBridge.responsesStreamToChat(input(nativeRaw),MODEL));
        check(converted.contains("\"error\"") && !converted.contains("[DONE]"),"missing native ID/name accepted without final output");
    }
    static void changedNativeIdentity() throws Exception {
        Call c=EXPECTED.get(ID_A); JSONObject first=item(c,false), last=item(c,false).put("call_id","other-id").put("arguments",c.args);
        String raw=sse(nativeEvent("response.output_item.added",0,first),nativeEvent("response.output_item.done",0,last),new JSONObject().put("type","response.completed").put("response",new JSONObject().put("status","completed")));
        String converted=read(ResponsesBridge.responsesStreamToChat(input(raw),MODEL));
        check(converted.contains("\"error\"") && !converted.contains("[DONE]"),"native ID silently changed/ignored");
    }
    static void duplicateOutputIds() throws Exception {
        JSONObject source=chatReply(); chatMessage(source).getJSONArray("tool_calls").getJSONObject(1).put("id",ID_A);
        rejects(()->ResponsesBridge.chatResponseToResponses(source,MODEL),"duplicate Chat output IDs");
        check("error".equals(AnthropicBridge.responseToAnthropic(source,MODEL).optString("type")),"duplicate Messages output IDs accepted");
        JSONObject response=ResponsesBridge.chatResponseToResponses(chatReply(),MODEL); response.getJSONArray("output").getJSONObject(1).put("call_id",ID_A);
        rejects(()->ResponsesBridge.responsesResponseToChat(response,MODEL),"duplicate native output IDs");
    }
    static void duplicateStreamIds() throws Exception {
        String raw=sse(chunk(new JSONArray().put(delta(0,ID_A,"a","{}")).put(delta(1,ID_A,"b","{}")),null),chunk(null,"tool_calls"));
        String response=read(ResponsesBridge.chatStreamToResponses(input(raw),MODEL));
        check(response.contains("response.failed") && !response.contains("response.completed"),"duplicate stream IDs completed in Responses");
        AnthropicBridge.StreamState state=new AnthropicBridge.StreamState(MODEL); StringBuilder messages=new StringBuilder();
        for(JSONObject c:events(raw))for(String e:state.onChunk(c))messages.append(e); for(String e:state.onEnd())messages.append(e);
        check(messages.toString().contains("event: error") && !messages.toString().contains("message_stop"),"duplicate stream IDs completed in Messages");
    }
    static void argumentsNotSilentlyReplaced() throws Exception {
        for(Object args:new Object[]{17,new JSONObject().put("x",1),"{broken-json", "[]"}) {
            JSONObject source=chatReply(); chatMessage(source).getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").put("arguments",args);
            check("error".equals(AnthropicBridge.responseToAnthropic(source,MODEL).optString("type")),"Messages replaced unrepresentable arguments with {}: "+args);
            if(!(args instanceof String))rejects(()->ResponsesBridge.chatResponseToResponses(source,MODEL),"non-string Chat output args");
        }
        JSONObject response=ResponsesBridge.chatResponseToResponses(chatReply(),MODEL); response.getJSONArray("output").getJSONObject(0).put("arguments",new JSONObject().put("x",1));
        rejects(()->ResponsesBridge.responsesResponseToChat(response,MODEL),"non-string Responses output args");
        JSONObject source=chatReply(); chatMessage(source).getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").put("arguments","{partial");
        check("{partial".equals(responsesCalls(ResponsesBridge.chatResponseToResponses(source,MODEL)).get(ID_A).args),"Responses rewrote raw incomplete args");
    }
    static void invalidInboundArguments() throws Exception {
        JSONObject messages=ZCodeAnthropic.toAnthropic(chatHistory(EXPECTED)); messages.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0).put("input",new JSONArray().put(1));
        rejects(()->AnthropicBridge.requestToOpenAI(messages),"Messages non-object input silently replaced");
        JSONObject chat=chatHistory(EXPECTED); chat.getJSONArray("messages").getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").put("arguments",new JSONObject().put("x",1));
        rejects(()->ResponsesBridge.chatToResponses(chat,MODEL),"Chat request args coerced to string");
    }
    static void ambiguousIndexesRejected() throws Exception {
        for(Object value:new Object[]{null,-1,0.5,"0"}) {
            JSONObject call=delta(0,ID_A,"lookup_weather","{}"); set(call,"index",value);
            String raw=sse(chunk(new JSONArray().put(call),null),chunk(null,"tool_calls"));
            String response=read(ResponsesBridge.chatStreamToResponses(input(raw),MODEL));
            check(response.contains("response.failed") && !response.contains("response.completed"),"Responses guessed invalid/missing index="+value);
            AnthropicBridge.StreamState state=new AnthropicBridge.StreamState(MODEL); StringBuilder out=new StringBuilder();
            for(JSONObject c:events(raw))for(String e:state.onChunk(c))out.append(e); for(String e:state.onEnd())out.append(e);
            check(out.toString().contains("event: error") && !out.toString().contains("message_stop"),"Messages guessed invalid/missing index="+value);
        }
    }
    static void messagesStreamArgumentsNotReplaced() throws Exception {
        for(Object args:new Object[]{null,"", "{partial", "[]", "{\"x\":1} trailing"}) {
            JSONObject call=delta(0,ID_A,"lookup_weather",null);
            JSONObject fn=call.getJSONObject("function"); set(fn,"arguments",args);
            String raw=sse(chunk(new JSONArray().put(call),null),chunk(null,"tool_calls"));
            AnthropicBridge.StreamState state=new AnthropicBridge.StreamState(MODEL); StringBuilder out=new StringBuilder();
            for(JSONObject c:events(raw))for(String e:state.onChunk(c))out.append(e); for(String e:state.onEnd())out.append(e);
            check(out.toString().contains("event: error") && !out.toString().contains("message_stop"),"Messages stream accepted unrepresentable/missing object arguments="+args);
        }
    }
    static void nativePartialSnapshots() throws Exception {
        Call c=EXPECTED.get(ID_A);
        JSONObject first=item(c,false).put("call_id","call_kept-").put("name","lookup_");
        JSONObject last=item(c,false).put("arguments",c.args);
        String raw=sse(nativeEvent("response.output_item.added",0,first),nativeEvent("response.output_item.done",0,last),new JSONObject().put("type","response.completed").put("response",new JSONObject().put("status","completed")));
        ChatStream stream=chatStream(read(ResponsesBridge.responsesStreamToChat(input(raw),MODEL)));
        check(stream.done && !stream.error && stream.calls.size()==1,"partial native snapshot terminal");
        Call actual=stream.calls.get(c.id);
        check(actual!=null && actual.name.equals(c.name) && actual.args.equals(c.args),"native cumulative ID/name/args snapshots not reconciled");
    }
    static void repeatedFragmentTextNotGuessed() throws Exception {
        String id="call_repeatrepeat", name="same_repeatrepeat";
        String raw=sse(chunk(new JSONArray().put(delta(0,"call_","same_","{")),null),
            chunk(new JSONArray().put(delta(0,"repeat","repeat","")),null),
            chunk(new JSONArray().put(delta(0,"repeat","repeat","}")),null),chunk(null,"tool_calls"));
        Map<String,Call> messages=messagesStream(raw);
        Map<String,Call> responses=responsesCalls(responsesTerminal(read(ResponsesBridge.chatStreamToResponses(input(raw),MODEL)),true));
        for(Map<String,Call> calls:java.util.Arrays.asList(messages,responses)) {
            Call actual=calls.get(id); check(calls.size()==1 && actual!=null && actual.name.equals(name) && actual.args.equals("{}"),"repeated legitimate fragment text was overwritten/deduplicated");
        }
    }
    static void deferredResponseToolsBounded() throws Exception {
        char[] data=new char[1024*1024]; java.util.Arrays.fill(data,'x'); String args="{\"blob\":\""+new String(data)+"\"}";
        String raw=sse(chunk(new JSONArray().put(delta(0,ID_A,"lookup_weather",args)),null),chunk(null,"tool_calls"));
        String out=read(ResponsesBridge.chatStreamToResponses(input(raw),MODEL));
        check(out.contains("response.failed") && out.contains("buffer limit") && !out.contains("response.completed"),"deferred Responses tools not bounded");
        check(!out.contains("response.output_item.added"),"oversized deferred tool published before validation");
    }
    static void run(Map<String,Checked> tests) throws Exception {
        int failures=0;
        for(Map.Entry<String,Checked> t:tests.entrySet()) {
            try { t.getValue().run(); System.out.println("PASS "+t.getKey()); }
            catch(Exception | AssertionError e) { failures++; System.out.println("FAIL "+t.getKey()+" : "+e.getClass().getSimpleName()+" : "+e.getMessage()); }
        }
        System.out.println("ToolCallContractOfflineTest: "+(tests.size()-failures)+" passed, "+failures+" failed, "+tests.size()+" cases");
        if(failures>0)throw new AssertionError(failures+" contract cases failed");
    }
    public static void main(String[] args) throws Exception {
        if(args.length>0) {
            Path dir=Paths.get(args[0]); Files.createDirectories(dir);
            Files.write(dir.resolve("two-tools-chat.json"),chatReply().toString(2).getBytes(StandardCharsets.UTF_8));
            Files.write(dir.resolve("two-tools-history.json"),chatHistory(EXPECTED).toString(2).getBytes(StandardCharsets.UTF_8));
            Files.write(dir.resolve("parallel-fragmented-chat.sse"),fragmentedStream().getBytes(StandardCharsets.UTF_8));
            Files.write(dir.resolve("native-responses-late.sse"),nativeResponses(true).getBytes(StandardCharsets.UTF_8));
        }
        Map<String,Checked> tests=new LinkedHashMap<String,Checked>();
        tests.put("nonstream_complete_id_name_args_result_matrix",()->nonstreamMatrix());
        tests.put("stream_complete_id_name_args_result_matrix",()->streamMatrix(plainStream()));
        tests.put("parallel_late_fragmented_chat_matrix",()->streamMatrix(fragmentedStream()));
        tests.put("responses_added_identity_is_final_identity",()->responsesTerminal(read(ResponsesBridge.chatStreamToResponses(input(fragmentedStream()),MODEL)),true));
        tests.put("messages_parallel_late_fragments_complete",()->resultsRoundTrip(messagesStream(fragmentedStream()),true,"Messages fragmented"));
        tests.put("native_responses_parallel_repeated_done",()->nativeStream(false));
        tests.put("native_responses_late_id_name",()->nativeStream(true));
        tests.put("inbound_messages_missing_invalid_references",()->inboundMessages());
        tests.put("inbound_chat_missing_invalid_references",()->inboundChat());
        tests.put("inbound_responses_missing_invalid_references",()->inboundResponses());
        tests.put("inbound_chat_to_responses_missing_invalid_references",()->inboundChatToResponses());
        tests.put("chat_to_messages_missing_output_id_rejected",()->missingChatOutput(true));
        tests.put("chat_to_responses_missing_output_id_rejected",()->missingChatOutput(false));
        tests.put("responses_to_chat_missing_output_identity_rejected",()->missingResponsesOutput());
        tests.put("streams_missing_identity_no_success_terminal",()->missingStreamIdentity());
        tests.put("native_stream_conflicting_existing_id_rejected",()->changedNativeIdentity());
        tests.put("nonstream_duplicate_output_ids_rejected",()->duplicateOutputIds());
        tests.put("streams_duplicate_output_ids_rejected",()->duplicateStreamIds());
        tests.put("arguments_preserved_or_rejected_never_replaced",()->argumentsNotSilentlyReplaced());
        tests.put("inbound_arguments_not_coerced_or_replaced",()->invalidInboundArguments());
        tests.put("ambiguous_chat_stream_indexes_rejected",()->ambiguousIndexesRejected());
        tests.put("messages_stream_args_preserved_or_rejected",()->messagesStreamArgumentsNotReplaced());
        tests.put("native_partial_identity_snapshots_reconciled",()->nativePartialSnapshots());
        tests.put("repeated_fragment_text_not_guessed",()->repeatedFragmentTextNotGuessed());
        tests.put("deferred_responses_tool_buffer_bounded",()->deferredResponseToolsBounded());
        run(tests);
    }
}
