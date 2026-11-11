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
    /* A second process is what tells a plain hash from a per-process salt: the same conversation
     * has to come out as the same session in both (0.2.64's sessionOf keeps no salt). */
    static String sessionProbe() throws Exception {
        ProcessBuilder builder=new ProcessBuilder(System.getProperty("java.home")+"/bin/java","-cp",
                System.getProperty("java.class.path"),QoderProtocolOfflineTest.class.getName(),"--session-probe");
        builder.redirectErrorStream(true);
        Process process=builder.start();
        java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream(),"UTF-8"));
        String line=reader.readLine(); while(reader.readLine()!=null) {} reader.close();
        process.waitFor();
        return line==null?"":line.trim();
    }
    public static void main(String[] args) throws Exception {
        if(args.length==1 && "--session-probe".equals(args[0])) {
            System.out.println(QoderProtocol.apply(base(),request(),null,"account").getString("session_id"));
            return;
        }
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
        /* Without a named session the tool loop still repeats one session and one task: the
         * session is the conversation's now, so the task key is too. */
        JSONObject loopPrompt=new JSONObject().put("messages",new JSONArray()
                .put(new JSONObject().put("role","user").put("content","hello")));
        JSONObject loopFirst=QoderProtocol.apply(base(),loopPrompt,null,"account");
        JSONObject loopNext=new JSONObject(loopPrompt.toString());
        loopNext.getJSONArray("messages")
                .put(new JSONObject().put("role","assistant").put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(new JSONObject().put("id","t1").put("type","function").put("function",new JSONObject().put("name","read").put("arguments","{}")))))
                .put(new JSONObject().put("role","tool").put("tool_call_id","t1").put("content","ok"));
        JSONObject loopSecond=QoderProtocol.apply(base(),loopNext,null,"account");
        check(loopFirst.getString("session_id").equals(loopSecond.getString("session_id")),"an anonymous tool loop keeps its session");
        check(loopFirst.getString("request_set_id").equals(loopSecond.getString("request_set_id")),"an anonymous tool loop keeps its task");
        QoderProtocol.answered(taskId); check(apply(r).getJSONObject("business").getString("stage").equals("processing"),"answered stage");
        JSONObject next=request(); next.getJSONArray("messages").put(new JSONObject().put("role","assistant").put("content","reply")).put(new JSONObject().put("role","user").put("content","new prompt"));
        check(!taskId.equals(apply(next).getString("request_set_id")),"new user task");
        JSONObject firstPrompt=new JSONObject().put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hello")));
        JSONObject firstPromptOut=apply(firstPrompt);
        String firstTask=firstPromptOut.getString("request_set_id");
        check(firstPromptOut.getJSONObject("business").getString("name").equals("hello"),"the task is named after its prompt");
        JSONObject longPrompt=new JSONObject().put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","0123456789abcdef")));
        check(apply(longPrompt).getJSONObject("business").getString("name").equals("0123456789"),"a task name is the prompt's first ten characters");
        /* 0.2.64's taskOf is the chat's last user turn, taken as it came (index.mjs:1393-1412).
         * A turn the gateway itself synthesized for a tool's images is no user turn of the chat
         * (the plugin synthesizes it inside qoderMessages, after taskOf has looked), so a history
         * that ends on one keeps the tool loop's task ... */
        JSONObject imageLoop=new JSONObject("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"},{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"im\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}]},{\"role\":\"tool\",\"tool_call_id\":\"im\",\"content\":\"[Images from this tool result follow.]\"},{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"[From the result of tool call im:]\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,eA==\"}}]}]}");
        check(firstTask.equals(apply(imageLoop).getString("request_set_id")),"a synthesized tool image turn is no new user turn");
        /* ... while the user's own words after those results are one, as they are upstream:
         * the bridge puts the image turn between the tool result and that message. */
        JSONObject imageThenText=new JSONObject(imageLoop.toString());
        imageThenText.getJSONArray("messages").put(new JSONObject().put("role","user").put("content","more user text"));
        JSONObject imageThenTextOut=apply(imageThenText);
        check(!firstTask.equals(imageThenTextOut.getString("request_set_id")),"user text after tool results starts a task");
        check(imageThenTextOut.getJSONObject("business").getString("name").equals("more user "),"that task is named after the text that starts it");
        check(imageThenTextOut.getString("request_set_id").equals(apply(imageThenText).getString("request_set_id")),"the task that text starts is stable");
        /* The split is the task's, not the conversation's: an anonymous turn of the same chat
         * still goes out under the session its opening names (index.mjs:1339-1350). */
        JSONObject anonFirst=QoderProtocol.apply(base(),firstPrompt,null,"account");
        JSONObject anonAfter=QoderProtocol.apply(base(),imageThenText,null,"account");
        check(anonFirst.getString("session_id").equals(anonAfter.getString("session_id")),"the tool loop's session is not split with its task");
        check(!anonFirst.getString("request_set_id").equals(anonAfter.getString("request_set_id")),"an anonymous task follows the new user turn too");
        /* The same history reaches here over the chat protocol too: a client that answers the
         * tool results and then speaks again, with no assistant turn in between. The history is
         * one validateClientHistory accepts, so the misjudged turn was reachable from a request
         * a client sends, not only from the bridge's shape of one. */
        JSONObject toolThenText=new JSONObject("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"},{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"t1\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}]},{\"role\":\"tool\",\"tool_call_id\":\"t1\",\"content\":\"ok\"},{\"role\":\"user\",\"content\":\"new plan\"}]}");
        QoderProtocol.validateClientHistory(toolThenText,"chat");
        JSONObject toolThenTextOut=apply(toolThenText);
        check(!firstTask.equals(toolThenTextOut.getString("request_set_id")),"a user turn after tool results starts a task");
        check(toolThenTextOut.getJSONObject("business").getString("name").equals("new plan"),"that task is named after its prompt");
        check(!taskId.equals(toolThenTextOut.getString("request_set_id")),"an answered task is not the one that turn starts");
        imageLoop.getJSONArray("messages").put(new JSONObject().put("role","assistant").put("content","answer")).put(new JSONObject().put("role","user").put("content","new"));
        check(!firstTask.equals(apply(imageLoop).getString("request_set_id")),"user after answer starts task");
        check(!taskId.equals(QoderProtocol.apply(base(),r,"session","other").getString("request_set_id")),"account task isolation");
        JSONObject unsafe=base(); unsafe.getJSONObject("model_config").put("api_key","secret").put("url","https://invalid").put("extra",1);
        JSONObject clean=QoderProtocol.apply(unsafe,r,"s","a").getJSONObject("model_config"); check(clean.getString("api_key").isEmpty() && clean.getString("url").isEmpty() && !clean.has("extra"),"model config allowlist");
        rejects(()->QoderProtocol.apply(base().put("model_config",new JSONObject().put("key","auto")),r,"s","a"));
        JSONObject mismatch=base(); mismatch.getJSONObject("business").put("model_key","other"); rejects(()->QoderProtocol.apply(mismatch,r,"s","a"));
        check(id.equals(apply(r).getString("session_id")),"named session stability");
        check(!id.equals(QoderProtocol.apply(base(),r,"session","other").getString("session_id")),"account isolation");
        /* 0.2.64 sessionOf hashes the conversation's own opening when the client names no
         * session (index.mjs:1339-1350). A turn of one conversation repeats it; another
         * conversation, or another system prompt, is another session. A random session per
         * request was the deviation this covers. */
        String anonymous=QoderProtocol.apply(base(),r,null,"account").getString("session_id");
        check(anonymous.equals(QoderProtocol.apply(base(),r,null,"account").getString("session_id")),"a conversation repeats its session without a named one");
        check(anonymous.equals(QoderProtocol.digestSession("account","chat","[[],\"hello\"]")),"the conversation's opening is the seed, as sessionOf does it");
        JSONObject otherChat=new JSONObject().put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","another opening")));
        check(!anonymous.equals(QoderProtocol.apply(base(),otherChat,null,"account").getString("session_id")),"another conversation is another session");
        JSONObject laterTurn=new JSONObject(r.toString());
        laterTurn.getJSONArray("messages").put(new JSONObject().put("role","assistant").put("content","reply")).put(new JSONObject().put("role","user").put("content","new prompt"));
        JSONObject laterOut=QoderProtocol.apply(base(),laterTurn,null,"account");
        check(anonymous.equals(laterOut.getString("session_id")),"the next turn keeps the conversation's session");
        check(!anonymous.equals(laterOut.getString("request_set_id")),"a new prompt in the same session is a new task");
        JSONObject otherSystem=new JSONObject(r.toString());
        otherSystem.getJSONArray("messages").put(0,new JSONObject().put("role","system").put("content","another system"));
        check(!anonymous.equals(QoderProtocol.apply(base(),otherSystem,null,"account").getString("session_id")),"another system prompt is another session");
        JSONObject userFallback=request().put("user","same");
        String byUser=QoderProtocol.apply(base(),userFallback,null,"account").getString("session_id");
        check(byUser.equals(QoderProtocol.apply(base(),userFallback,null,"account").getString("session_id")),"the client user names the session");
        check(!byUser.equals(anonymous) && !byUser.equals(id),"the user fallback is its own session");
        JSONObject cached=request().put("prompt_cache_key","thread");
        JSONObject c1=QoderProtocol.apply(base(),cached,null,"account"), c2=QoderProtocol.apply(base(),cached,null,"account");
        check(c1.getString("session_id").equals(c2.getString("session_id")) && c1.getString("request_set_id").equals(c2.getString("request_set_id")),"cache session and task stable");
        check(!c1.getString("session_id").equals(QoderProtocol.apply(base(),cached,null,"other").getString("session_id")),"cache account scope");
        check(!c1.getString("session_id").equals(byUser),"prompt_cache_key wins over the user");
        check(id.equals(QoderProtocol.apply(base(),cached,"session","account").getString("session_id")),"named session priority");
        for(Object key:new Object[]{" ", 5, new String(new char[1025]).replace('\0','x')}) {
            JSONObject badCache=request().put("prompt_cache_key",key).put("user","same");
            check(byUser.equals(QoderProtocol.apply(base(),badCache,null,"account").getString("session_id")),"an unusable prompt_cache_key falls back to the user: "+key);
        }
        /* A magpie-made placeholder session names no session (index.mjs:1358-1364): the
         * conversation's opening (or the client's key/user) is the seed instead. */
        check(anonymous.equals(QoderProtocol.apply(base(),r,"magpie-0123456789abcdef01234567","account").getString("session_id")),"a magpie placeholder session is ignored");
        check(c1.getString("session_id").equals(QoderProtocol.apply(base(),cached,"magpie-0123456789abcdef01234567","account").getString("session_id")),"a placeholder falls through to prompt_cache_key");
        String head=new String(new char[128]).replace('\0','c');
        check(QoderProtocol.apply(base(),r,head+"aaa","account").getString("session_id").equals(QoderProtocol.apply(base(),r,head+"bbb","account").getString("session_id")),"namedSession keeps 128 characters");
        check(id.length()==36 && id.charAt(8)=='-' && id.charAt(13)=='-' && id.charAt(18)=='-' && id.charAt(23)=='-' && !id.contains("session") && !id.contains("hello"),"a session is an opaque 8-4-4-4-12 id");
        check(sessionProbe().equals(anonymous),"the same conversation is the same session in another process");
        /* Upstream anchors, taken by running 0.2.64 itself (node: _internal.sessionOf on the
         * package's own index.mjs). digestSession with no scope writes the plugin's own bytes
         * (label, NUL, value) before sha256, so these are the plugin's values to the character. */
        check(QoderProtocol.digestSession("","session","sess-abc").equals("cfc506f4-188b-132b-d635-0795dfd085b8"),"anchor: named session");
        check(QoderProtocol.digestSession("","key","thread").equals("a08e7985-0f14-58fe-8574-305078fe55eb"),"anchor: prompt_cache_key");
        check(QoderProtocol.digestSession("","user","u1").equals("433f5a03-1e86-909a-46b5-e2bac60e2452"),"anchor: user");
        check(QoderProtocol.digestSession("","chat","[[\"sys\"],\"hello\"]").equals("059e9544-d186-fd05-7b4e-b9695fa99fb5"),"anchor: conversation opening");
        check(!QoderProtocol.digestSession("account","session","sess-abc").equals("cfc506f4-188b-132b-d635-0795dfd085b8"),"the account scope changes the bytes");
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
        JSONObject required=apply(request().put("tool_choice","required")); check(required.getJSONArray("tools").length()==1 && required.getJSONArray("system").getJSONObject(0).getString("text").contains("You must call an available function in this response."),"required tool instruction");
        rejects(()->apply(new JSONObject().put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hello"))).put("tool_choice","required")));
        // qoderBody names no parallel knob (index.mjs:1495-1527). With tools forwarded the answer
        // may carry several calls at once and this adapter cannot make them serial, so the flag is
        // refused; with no tool forwarded nothing can run in parallel, so it is accepted as vacuous.
        rejects(()->apply(request().put("parallel_tool_calls",false)));
        check(!apply(new JSONObject().put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hello")))
                .put("parallel_tool_calls",false)).has("tools"),"parallel off with no tools is accepted");
        check(!apply(request().put("tool_choice","none").put("parallel_tool_calls",false)).has("tools"),"parallel off with tool_choice none is accepted");
        rejects(()->apply(request().put("tool_choice",new JSONObject().put("type","function"))));
        JSONObject windows=base(); windows.getJSONObject("parameters").put("max_tokens",32000); windows.getJSONObject("model_config").put("max_input_tokens",200000).put("available_context_windows",new JSONArray().put(200000).put(400000).put(1000000)).put("default_context_window",200000);
        JSONObject largePrompt=new JSONObject().put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content",new String(new char[550000]).replace('\0','x'))));
        check(QoderProtocol.apply(windows,largePrompt,"s","a").getJSONObject("parameters").getInt("context_length")==400000,"select next fitting context window");
        check(QoderProtocol.apply(windows,request(),"s","a").getJSONObject("parameters").getInt("context_length")==200000,"retain default context window");
        JSONObject noDefault=base(); noDefault.getJSONObject("parameters").put("max_tokens",32000); noDefault.getJSONObject("model_config").put("available_context_windows",new JSONArray().put(200000).put(400000)).put("max_input_tokens",0);
        check(QoderProtocol.apply(noDefault,request(),"s","a").getJSONObject("parameters").getInt("context_length")==400000,"no default uses largest advertised base window");
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
        // Only a malformed value is a local rejection: 0.2.64 sends a positive budget as asked
        // whatever its size (index.mjs:1502-1506), so 63, 16 and 1 are no longer refused here.
        for(String malformed:new String[]{"{\"max_tokens\":\"64\"}","{\"max_tokens\":64.5}","{\"max_completion_tokens\":\"64\"}","{\"max_tokens\":0}"}) {
            boolean rejected=false; try { QoderProtocol.validateClientHistory(new JSONObject(malformed).put("messages",new JSONArray()),"chat"); }
            catch(IllegalArgumentException expected) { rejected=true; } check(rejected,"invalid client output budget rejected before normalization: "+malformed);
        }
        for(int budget:new int[]{1,16,63,64}) QoderProtocol.validateClientHistory(new JSONObject().put("max_tokens",budget).put("messages",new JSONArray()),"chat");
        JSONObject strict=request(); strict.getJSONArray("tools").getJSONObject(0).getJSONObject("function").put("strict",true); rejects(()->apply(strict));
        /* A client's tool history reaches Qoder with ids missing or lost, and 0.2.64 repairs it
         * instead of turning the request away (index.mjs:1156-1200, :1250-1285): a call sent with
         * no id goes out with one (:1171-1179, :1256-1259), a result naming no call of its turn
         * answers the earliest call still open that no other result names (:1255-1266), and a
         * result with no call left to answer becomes text in the user's own turn (:1255-1258).
         * Each expectation below is the plugin's own output for the same history, and the ids are
         * the ones its callID produced (sha256 of "at\0k\0name\0arguments", :1175-1179).
         * The words and images the client sent are kept as they came; nothing is invented.
         * apply() is the projection itself, so it repairs these histories the way the plugin does;
         * the pre-flight validateClientHistory still refuses them, because the gateway's own
         * normalizer would rewrite the result — see the validate section below. */
        JSONObject noIds=new JSONObject("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"},{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}]},{\"role\":\"tool\",\"content\":\"ok\"}]}");
        JSONArray noIdsOut=apply(noIds).getJSONArray("messages");
        String madeCall="call_c30754fb663ad5db7e141a74dd0c96b8";
        check(noIdsOut.length()==4,"a call without an id is no reason to drop a turn");
        check(noIdsOut.getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0).getString("id").equals(madeCall),"the synthesized call id is 0.2.64's, not a local one");
        check(noIdsOut.getJSONObject(3).getString("tool_call_id").equals(madeCall),"a result without an id answers the call that has none");
        check(noIdsOut.getJSONObject(3).getString("content").equals("ok"),"the result's words are kept as sent");
        check(apply(noIds).getJSONArray("messages").getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0).getString("id").equals(madeCall),"the synthesized id repeats on the next request");
        JSONObject noIdsTwo=new JSONObject("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"},{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"x\\\":1}\"}},{\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"x\\\":2}\"}}]},{\"role\":\"tool\",\"content\":\"first\"},{\"role\":\"tool\",\"content\":\"second\"}]}");
        JSONArray noIdsTwoOut=apply(noIdsTwo).getJSONArray("messages");
        JSONArray twoCalls=noIdsTwoOut.getJSONObject(2).getJSONArray("tool_calls");
        check(twoCalls.getJSONObject(0).getString("id").equals("call_6b41bb068d910234a4cc06aebc323ba5") && twoCalls.getJSONObject(1).getString("id").equals("call_ced07d48f8eaec0545dc6796cff0c4f0"),"each call without an id gets its own id from where it is");
        check(noIdsTwoOut.getJSONObject(3).getString("tool_call_id").equals(twoCalls.getJSONObject(0).getString("id")) && noIdsTwoOut.getJSONObject(4).getString("tool_call_id").equals(twoCalls.getJSONObject(1).getString("id")),"results without ids answer the calls in the order they were made");
        JSONObject orphan=new JSONObject("{\"messages\":[{\"role\":\"tool\",\"tool_call_id\":\"missing\",\"content\":\"x\"}]}");
        JSONArray orphanOut=apply(orphan).getJSONArray("messages");
        check(orphanOut.length()==2 && "user".equals(orphanOut.getJSONObject(1).getString("role")),"a result with no call to answer becomes the user's own turn");
        check(orphanOut.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("text").equals("x"),"an orphan result keeps its words as the user's text");
        /* The call one result was dropped for is still sent unanswered, as the plugin sends it:
         * nothing is invented to answer a call the client left open (:1263-1275). */
        JSONObject missing=request(); missing.getJSONArray("messages").remove(2);
        JSONArray missingOut=apply(missing).getJSONArray("messages");
        check(missingOut.length()==5,"a dropped result does not drop the turn");
        check(missingOut.getJSONObject(2).getJSONArray("tool_calls").length()==2,"the unanswered call is still declared");
        check(missingOut.getJSONObject(3).getString("tool_call_id").equals("one") && missingOut.getJSONObject(3).getString("content").equals("done"),"the result that named its call keeps it");
        /* The earliest open call no other result names is the one a lost or unmatched id answers,
         * so two results naming the same call pair in the order they arrived (:1263-1266). */
        JSONObject dup=request(); dup.getJSONArray("messages").getJSONObject(3).put("tool_call_id","two");
        JSONArray dupOut=apply(dup).getJSONArray("messages");
        check(dupOut.getJSONObject(3).getString("tool_call_id").equals("two") && dupOut.getJSONObject(4).getString("tool_call_id").equals("one"),"a repeated result id answers the call still open");
        check(dupOut.getJSONObject(4).getString("content").equals("done"),"the order-paired result keeps its words");
        JSONObject unknown=request(); unknown.getJSONArray("messages").getJSONObject(2).put("tool_call_id","unknown");
        JSONArray unknownOut=apply(unknown).getJSONArray("messages");
        check(unknownOut.getJSONObject(3).getString("tool_call_id").equals("two"),"a result naming no call answers the earliest one no other result names");
        check(unknownOut.getJSONObject(4).getString("tool_call_id").equals("one"),"the result that named its call keeps it");
        check(unknownOut.getJSONObject(5).getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("url").equals("data:image/png;base64,eA=="),"the tool's image is kept with the order-paired result");
        /* A result after the user's own words has no call of its turn to answer, so its verdict
         * joins the user's next turn as text — with the images it carried (:1191-1200). */
        JSONObject reorder=request(); reorder.getJSONArray("messages").getJSONObject(2).put("role","user");
        JSONArray reorderOut=apply(reorder).getJSONArray("messages");
        check("user".equals(reorderOut.getJSONObject(3).getString("role")) && reorderOut.getJSONObject(3).getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("url").equals("data:image/png;base64,eA=="),"the user's own image turn is kept");
        check(reorderOut.getJSONObject(4).getJSONArray("content").getJSONObject(0).getString("text").equals("done") && reorderOut.getJSONObject(4).getJSONArray("content").getJSONObject(1).getString("text").equals("next"),"the isolated result's text joins the user's next turn");
        JSONObject nonStringId=request(); nonStringId.getJSONArray("messages").getJSONObject(2).put("tool_call_id",5); rejects(()->apply(nonStringId));
        JSONObject duplicateCall=request(); duplicateCall.getJSONArray("messages").getJSONObject(1).getJSONArray("tool_calls").getJSONObject(1).put("id","one"); rejects(()->apply(duplicateCall));
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
        /* The pre-flight check on the raw client payload still names a lost tool id instead of
         * repairing it. The repair lives in apply() (above) and matches the plugin exactly there,
         * but a repaired history does not survive the gateway's own normalizer unchanged — it
         * invents an answer for a call no result answered and drops a repeated result id with its
         * content (AnthropicBridge.pairToolMessages:411-460) — so telling Qoder a history the
         * client never sent stays a refusal until that normalizer is fixed. */
        for(JSONObject refused:new JSONObject[]{noIds,noIdsTwo,orphan,missing,dup,unknown,reorder}) rejects(()->QoderProtocol.validateClientHistory(refused,"chat"));
        /* An empty tool_calls array is no lost history: the plugin reads it as a turn with no calls
         * (index.mjs:1251) and so does the gateway normalizer, so it is accepted and the turn goes
         * out as the assistant's own words. */
        JSONObject emptyCalls=new JSONObject("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"},{\"role\":\"assistant\",\"content\":\"x\",\"tool_calls\":[]}]}");
        QoderProtocol.validateClientHistory(emptyCalls,"chat");
        JSONArray emptyOut=apply(emptyCalls).getJSONArray("messages");
        check(emptyOut.length()==3 && "assistant".equals(emptyOut.getJSONObject(2).getString("role")) && !emptyOut.getJSONObject(2).has("tool_calls"),"an empty tool_calls array is a turn with no calls");
        JSONObject badChat=request(); badChat.getJSONArray("messages").getJSONObject(2).put("tool_call_id",5); rejects(()->QoderProtocol.validateClientHistory(badChat,"chat"));
        JSONObject duplicateChat=request(); duplicateChat.getJSONArray("messages").getJSONObject(1).getJSONArray("tool_calls").getJSONObject(1).put("id","one"); rejects(()->QoderProtocol.validateClientHistory(duplicateChat,"chat"));
        JSONObject earlyChat=request(); earlyChat.getJSONArray("messages").getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0).remove("function"); rejects(()->QoderProtocol.validateClientHistory(earlyChat,"chat"));
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
        // A named choice cannot be enforced by Qoder, so it is accepted as "the tools are
        // available" (as the plugin does); an undeclared name stays a local rejection.
        JSONObject named=apply(request().put("tool_choice",new JSONObject().put("type","function").put("function",new JSONObject().put("name","read"))));
        check(named.getJSONArray("tools").length()==1 && !named.getJSONArray("system").getJSONObject(0).getString("text").contains("You must call an available function"),"named choice forwards tools without forcing a call");
        check(apply(request().put("tool_choice","read")).getJSONArray("tools").length()==1,"a string naming a declared tool is forwarded too");
        rejects(()->apply(request().put("tool_choice",new JSONObject().put("type","function").put("function",new JSONObject().put("name","other")))));
        rejects(()->apply(request().put("tool_choice","other")));
        rejects(()->apply(request().put("tool_choice",new JSONObject().put("type","bogus"))));
        rejects(()->apply(request().put("tool_choice",7)));
        JSONObject noTools=new JSONObject().put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hi")))
                .put("tool_choice",new JSONObject().put("type","function").put("function",new JSONObject().put("name","read")));
        rejects(()->apply(noTools));
        check(!apply(request().put("tool_choice","none")).has("tools"),"none still suppresses the tool declarations");
        System.out.println("QoderProtocolOfflineTest OK");
    }
}
