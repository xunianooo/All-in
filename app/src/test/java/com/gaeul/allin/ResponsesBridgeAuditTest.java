package com.gaeul.allin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

public class ResponsesBridgeAuditTest {
    static void check(boolean b, String message) { if (!b) throw new AssertionError(message); }
    static String read(InputStream s) throws Exception { ByteArrayOutputStream b=new ByteArrayOutputStream(); byte[] buf=new byte[7]; int n; while((n=s.read(buf))!=-1)b.write(buf,0,n); return new String(b.toByteArray(),StandardCharsets.UTF_8); }
    static String sse(String... values) { StringBuilder b=new StringBuilder(); for(String v:values)b.append("data: ").append(v).append("\n\n"); return b.toString(); }
    static void invalidChat() throws Exception {
        String[] invalid={"{\"messages\":[42]}","{\"messages\":[{\"role\":\"tool\",\"content\":\"x\"}]}","{\"messages\":[],\"tools\":{}}", "{\"messages\":[{\"role\":\"assistant\",\"tool_calls\":{}}]}"};
        for(String json:invalid) { boolean rejected=false; try { ResponsesBridge.chatToResponses(new JSONObject(json),"m"); } catch(IllegalArgumentException ex) { rejected=true; } catch(Exception ex) { throw new AssertionError("wrong exception for "+json,ex); } check(rejected,"accepted "+json); }
    }
    static void lateToolMetadata() throws Exception {
        String raw=sse("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\"}}]},\"finish_reason\":null}]}","{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"lookup\",\"arguments\":\"}\"}}]},\"finish_reason\":null}]}","{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}","[DONE]");
        InputStream in=new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8));
        String responses=read(ResponsesBridge.chatStreamToResponses(in,"m"));
        String chat=read(ResponsesBridge.responsesStreamToChat(new ByteArrayInputStream(responses.getBytes(StandardCharsets.UTF_8)),"m"));
        check(chat.contains("call_1") && chat.contains("lookup"),"late metadata lost in round trip: "+chat);
    }
    static void fragmentedName() throws Exception {
        String raw=sse("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"look\",\"arguments\":\"{\"}}]}}]}","{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"up\",\"arguments\":\"}\"}}]}}]}","{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}","[DONE]");
        String responses=read(ResponsesBridge.chatStreamToResponses(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)),"m"));
        String chat=read(ResponsesBridge.responsesStreamToChat(new ByteArrayInputStream(responses.getBytes(StandardCharsets.UTF_8)),"m"));
        check(chat.contains("\"name\":\"lookup\"") && chat.contains("\"arguments\":\"{}\""),"complete name/arguments lost: "+chat);
    }
    static void prematureDone() throws Exception {
        String raw=sse("{\"choices\":[{\"delta\":{\"content\":\"你好\"},\"finish_reason\":\"stop\"}]}");
        String out=read(ResponsesBridge.chatStreamToResponses(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)),"m"));
        check(out.contains("你好"),"utf8 corrupted");
        check(out.contains("response.completed") && !out.contains("response.failed"),"finish then EOF must complete");
    }
    static void requestValidation() throws Exception {
        String[] bad={"{\"input\":\"x\",\"stream\":\"true\"}","{\"input\":\"x\",\"max_output_tokens\":\"10\"}","{\"input\":\"x\",\"text\":42}","{\"input\":\"x\",\"max_output_tokens\":0}"};
        for(String raw:bad) { boolean rejected=false; try { ResponsesBridge.projection(new JSONObject(raw)); } catch(Exception ex) { rejected=true; } check(rejected,"invalid Responses request accepted: "+raw); }
    }
    static void finalOnly() throws Exception {
        String raw=sse("{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hello\"}]},{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"go\",\"arguments\":\"{}\"}]}}" );
        String chat=read(ResponsesBridge.responsesStreamToChat(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)),"m"));
        check(chat.contains("Hello") && chat.contains("c1") && chat.contains("{}") && chat.contains("tool_calls"),"final response output lost: "+chat);
    }
    static void argumentsDone() throws Exception {
        String raw=sse("{\"type\":\"response.output_item.added\",\"output_index\":3,\"item\":{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"go\",\"arguments\":\"\"}}","{\"type\":\"response.function_call_arguments.done\",\"output_index\":3,\"arguments\":\"{}\"}","{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}");
        String chat=read(ResponsesBridge.responsesStreamToChat(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)),"m"));
        check(chat.contains("\"arguments\":\"{}\""),"done arguments lost: "+chat);
    }
    static void lifecycleAndInterleave() throws Exception {
        String raw=sse("{\"choices\":[{\"delta\":{\"reasoning_content\":\"思\",\"content\":\"答\",\"tool_calls\":[{\"index\":1,\"id\":\"b\",\"function\":{\"name\":\"bee\",\"arguments\":\"B1\"}},{\"index\":0,\"id\":\"a\",\"function\":{\"name\":\"aye\",\"arguments\":\"A1\"}}]}}]}",
            "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"A2\"}},{\"index\":1,\"function\":{\"arguments\":\"B2\"}}]},\"finish_reason\":\"tool_calls\"}]}","{\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":5,\"total_tokens\":8}}");
        String responses=read(ResponsesBridge.chatStreamToResponses(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)),"m"));
        java.util.Map<Integer,String> ids=new java.util.HashMap<Integer,String>(); int seq=0; JSONObject last=null;
        for(String line:responses.split("\n")) if(line.startsWith("data: ")) {
            JSONObject e=new JSONObject(line.substring(6)); check(e.getInt("sequence_number")==seq++,"sequence gap"); String type=e.getString("type");
            if(type.equals("response.output_item.added")) ids.put(e.getInt("output_index"),e.getJSONObject("item").getString("id"));
            if(e.has("item_id")) check(e.getString("item_id").equals(ids.get(e.getInt("output_index"))),"index/id mismatch "+e);
            if(type.equals("response.output_item.done")) check(e.getJSONObject("item").getString("id").equals(ids.get(e.getInt("output_index"))),"done id mismatch");
            if(type.equals("response.completed")) last=e.getJSONObject("response");
        }
        check(last!=null && last.getJSONObject("usage").getInt("total_tokens")==8,"usage/terminal missing");
        JSONArray items=last.getJSONArray("output"); java.util.Map<String,String> args=new java.util.HashMap<String,String>();
        for(int i=0;i<items.length();i++) {JSONObject item=items.getJSONObject(i); if(item.optString("type").equals("function_call"))args.put(item.getString("call_id"),item.getString("arguments"));}
        check("A1A2".equals(args.get("a")) && "B1B2".equals(args.get("b")),"interleaved arguments mixed: "+args);
        String round=read(ResponsesBridge.responsesStreamToChat(new ByteArrayInputStream(responses.getBytes(StandardCharsets.UTF_8)),"m"));
        check(round.contains("思") && round.contains("答") && round.contains("A1") && round.contains("A2") && round.contains("B1") && round.contains("B2") && !round.contains("Invalid Responses stream"),"round trip failed: "+round);
        String truncated=read(ResponsesBridge.chatStreamToResponses(new ByteArrayInputStream(sse("{\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}").getBytes(StandardCharsets.UTF_8)),"m"));
        check(truncated.contains("response.failed") && !truncated.contains("response.completed"),"truncated response completed");
    }
    static void gatewayFixtures() throws Exception {
        String nativeSse=sse("{\"type\":\"message_start\"}","{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"hello\"}}","{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}","{\"type\":\"message_stop\"}");
        Class<?> c=Class.forName("com.gaeul.allin.GatewayServer$AnthropicSseToOpenAI"); java.lang.reflect.Constructor<?> ctor=c.getDeclaredConstructor(InputStream.class,ZCodeAnthropic.StreamState.class); ctor.setAccessible(true);
        InputStream converted=(InputStream)ctor.newInstance(new ByteArrayInputStream(nativeSse.getBytes(StandardCharsets.UTF_8)),new ZCodeAnthropic.StreamState("m"));
        String response=read(ResponsesBridge.chatStreamToResponses(converted,"m"));
        check(response.contains("hello") && response.contains("response.completed"),"Anthropic adapter chain failed: "+response);
        String nativeResponse=sse("{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"codex\"}]}]}}");
        InputStream toChat=ResponsesBridge.responsesStreamToChat(new ByteArrayInputStream(nativeResponse.getBytes(StandardCharsets.UTF_8)),"m");
        response=read(ResponsesBridge.chatStreamToResponses(toChat,"m"));
        check(response.contains("codex") && response.contains("response.completed"),"Codex adapter chain failed: "+response);
    }
    static void projectionBoundaries() throws Exception {
        String[] bad={"\"store\":\"true\"", "\"store\":1", "\"max_output_tokens\":2147483648", "\"max_output_tokens\":9223372036854775808", "\"max_output_tokens\":1e999", "\"max_output_tokens\":1.5", "\"parallel_tool_calls\":\"false\"", "\"temperature\":\"0.5\"", "\"top_p\":{}", "\"model\":42"};
        for(String entry:bad) {
            boolean rejected=false; try {ResponsesBridge.projection(new JSONObject("{\"input\":\"x\","+entry+"}"));} catch(Exception ex) {rejected=true;}
            check(rejected,"projection accepted "+entry);
        }
        JSONObject valid=ResponsesBridge.projection(new JSONObject("{\"input\":\"x\",\"store\":true,\"max_output_tokens\":2147483647,\"parallel_tool_calls\":true,\"temperature\":null,\"top_p\":null}"));
        check(!valid.has("store") && valid.getInt("max_tokens")==Integer.MAX_VALUE && !valid.has("temperature"),"valid projection changed");
    }
    static void invalidFinalCalls() throws Exception {
        String[] items={"{\"type\":\"function_call\",\"name\":\"f\",\"arguments\":\"{}\"}","{\"type\":\"function_call\",\"call_id\":\"c\",\"arguments\":\"{}\"}"};
        for(String item:items) {
            String raw=sse("{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":["+item+"]}}");
            String out=read(ResponsesBridge.responsesStreamToChat(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)),"m"));
            check(out.contains("\"error\"") && !out.contains("[DONE]"),"missing call id/name accepted: "+out);
        }
    }
    static void terminalSemantics() throws Exception {
        for (String finish : new String[]{"", ",\"finish_reason\":null", ",\"finish_reason\":\"other\"", ",\"finish_reason\":42"}) {
            JSONObject chat=new JSONObject("{\"choices\":[{\"message\":{\"content\":\"partial\"}"+finish+"}]}");
            boolean rejected=false; try { ResponsesBridge.chatResponseToResponses(chat,"m"); } catch (IllegalArgumentException expected) { rejected=true; }
            check(rejected,"invalid nonstream Chat finish accepted: "+finish);
        }
        for (String finish : new String[]{"length", "content_filter"}) {
            String reason=finish.equals("length")?"max_output_tokens":"content_filter";
            JSONObject chat=new JSONObject("{\"choices\":[{\"message\":{\"content\":\"\"},\"finish_reason\":\""+finish+"\"}]}");
            JSONObject response=ResponsesBridge.chatResponseToResponses(chat,"m");
            check("incomplete".equals(response.getString("status")) && reason.equals(response.getJSONObject("incomplete_details").getString("reason")),"nonstream mapping");
            check(finish.equals(ResponsesBridge.responsesResponseToChat(response,"m").getJSONArray("choices").getJSONObject(0).getString("finish_reason")),"nonstream roundtrip");
            String chatSse=sse("{\"choices\":[{\"delta\":{},\"finish_reason\":\""+finish+"\"}]}","[DONE]");
            String responses=read(ResponsesBridge.chatStreamToResponses(new ByteArrayInputStream(chatSse.getBytes(StandardCharsets.UTF_8)),"m"));
            check(responses.contains("response.incomplete") && responses.contains(reason) && !responses.contains("response.completed"),"stream mapping: "+responses);
            String back=read(ResponsesBridge.responsesStreamToChat(new ByteArrayInputStream(responses.getBytes(StandardCharsets.UTF_8)),"m"));
            check(back.contains("\"finish_reason\":\""+finish+"\"") && back.contains("[DONE]"),"stream roundtrip: "+back);
        }
        for (String details : new String[]{"", ",\"incomplete_details\":null", ",\"incomplete_details\":{}", ",\"incomplete_details\":{\"reason\":null}", ",\"incomplete_details\":{\"reason\":\"other\"}"}) {
            JSONObject response=new JSONObject("{\"status\":\"incomplete\",\"output\":[]"+details+"}");
            boolean rejected=false; try { ResponsesBridge.responsesResponseToChat(response,"m"); } catch (IllegalArgumentException expected) { rejected=true; }
            check(rejected,"invalid incomplete accepted: "+details);
            String raw=sse("{\"type\":\"response.incomplete\",\"response\":"+response+"}");
            String out=read(ResponsesBridge.responsesStreamToChat(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)),"m"));
            check(out.contains("\"error\"") && !out.contains("[DONE]") && !out.contains("\"finish_reason\":\"length\""),"invalid stream incomplete accepted: "+out);
        }
        for (String msg : new String[]{"{\"content\":null,\"reasoning_content\":\"think\"}", "{\"content\":\"\",\"refusal\":\"no\"}", "{\"content\":null,\"tool_calls\":[{\"id\":\"c\",\"function\":{\"name\":\"f\",\"arguments\":\"{}\"}}]}"}) {
            JSONObject chat=new JSONObject("{\"choices\":[{\"message\":"+msg+",\"finish_reason\":\"stop\"}]}");
            JSONObject r=ResponsesBridge.chatResponseToResponses(chat,"m");
            check(r.getJSONArray("output").length()>0 && "completed".equals(r.getString("status")),"valid content lost");
        }
        for (String raw : new String[]{sse("{\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}"), sse("{\"error\":{\"message\":\"bad\"}}")} ) {
            String out=read(ResponsesBridge.chatStreamToResponses(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)),"m"));
            check(out.contains("response.failed") && !out.contains("response.completed"),"Chat failure lost: "+out);
        }
        String upstreamError=read(ResponsesBridge.responsesStreamToChat(new ByteArrayInputStream(sse("{\"type\":\"error\",\"error\":{\"message\":\"bad\"}}").getBytes(StandardCharsets.UTF_8)),"m"));
        check(upstreamError.contains("bad") && !upstreamError.contains("[DONE]"),"Responses error lost");
    }
    static String sseTight(String... values) { StringBuilder b=new StringBuilder(); for(String v:values)b.append("data:").append(v).append("\n\n"); return b.toString(); }
    static String q(String s) { return s.replace('\'', '"'); }
    static void doneWithoutSpace() throws Exception {
        String raw=sseTight(q("{'choices':[{'delta':{'content':'ok'},'finish_reason':null}]}"),q("{'choices':[{'delta':{},'finish_reason':'stop'}]}"),"[DONE]");
        String out=read(ResponsesBridge.chatStreamToResponses(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)),"m"));
        check(out.contains("ok") && out.contains("response.completed") && !out.contains("response.failed"),"tight data: ended a complete Chat stream: "+out);
        String nativeStream="event: response.output_text.delta\ndata:"+q("{'type':'response.output_text.delta','output_index':0,'content_index':0,'delta':'hi'}")+"\n\n: keepalive\n\nevent: response.completed\ndata:"+q("{'type':'response.completed','response':{'status':'completed','error':null,'output':[{'type':'message','content':[{'type':'output_text','text':'hi'}]}]}}")+"\n\ndata:[DONE]\n\n";
        String chat=read(ResponsesBridge.responsesStreamToChat(new ByteArrayInputStream(nativeStream.getBytes(StandardCharsets.UTF_8)),"m"));
        check(chat.contains("hi") && chat.contains(q("'finish_reason':'stop'")) && chat.contains("[DONE]") && !chat.contains(q("'error'")),"tight data: ended a complete Responses stream: "+chat);
        String bare=read(ResponsesBridge.chatStreamToResponses(new ByteArrayInputStream(sseTight("[DONE]").getBytes(StandardCharsets.UTF_8)),"m"));
        check(bare.contains("response.failed") && !bare.contains("response.completed"),"bare tight [DONE] reported success: "+bare);
        String truncatedChat=read(ResponsesBridge.chatStreamToResponses(new ByteArrayInputStream(sseTight(q("{'choices':[{'delta':{'content':'partial'}}]}"),"[DONE]").getBytes(StandardCharsets.UTF_8)),"m"));
        check(truncatedChat.contains("response.failed") && !truncatedChat.contains("response.completed"),"tight [DONE] without finish_reason reported success: "+truncatedChat);
        String truncatedNative=read(ResponsesBridge.responsesStreamToChat(new ByteArrayInputStream(sseTight(q("{'type':'response.output_text.delta','output_index':0,'content_index':0,'delta':'partial'}"),"[DONE]").getBytes(StandardCharsets.UTF_8)),"m"));
        check(truncatedNative.contains(q("'error'")) && !truncatedNative.contains("[DONE]"),"tight [DONE] without terminal event reported success: "+truncatedNative);
        String garbage=read(ResponsesBridge.chatStreamToResponses(new ByteArrayInputStream(sseTight(q("{'choices':[{'delta':{'content':'x'},'finish_reason':'stop'}]}"),"not-json").getBytes(StandardCharsets.UTF_8)),"m"));
        check(garbage.contains("response.failed"),"malformed Chat payload accepted: "+garbage);
    }
    static void nullErrorFields() throws Exception {
        String raw=sse(q("{'error':null,'choices':[{'delta':{'content':'ok'},'finish_reason':null}]}"),q("{'error':null,'choices':[{'delta':{},'finish_reason':'stop'}]}"),"[DONE]");
        String out=read(ResponsesBridge.chatStreamToResponses(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)),"m"));
        check(out.contains("ok") && out.contains("response.completed") && !out.contains("response.failed"),"error:null ended a complete Chat stream: "+out);
        String failed=read(ResponsesBridge.chatStreamToResponses(new ByteArrayInputStream(sse(q("{'error':{'message':'bad'},'choices':[]}")).getBytes(StandardCharsets.UTF_8)),"m"));
        check(failed.contains("response.failed") && !failed.contains("response.completed"),"upstream Chat error accepted: "+failed);
        String nullEvent=read(ResponsesBridge.responsesStreamToChat(new ByteArrayInputStream(sse(q("{'type':'error','error':null}")).getBytes(StandardCharsets.UTF_8)),"m"));
        check(nullEvent.contains(q("'error'")) && !nullEvent.contains(q("'error':null")) && !nullEvent.contains("[DONE]"),"null error event forwarded as null: "+nullEvent);
        String nested=read(ResponsesBridge.responsesStreamToChat(new ByteArrayInputStream(sse(q("{'type':'response.failed','error':null,'response':{'status':'failed','error':{'code':'boom','message':'boom-msg'}}}")).getBytes(StandardCharsets.UTF_8)),"m"));
        check(nested.contains("boom-msg") && !nested.contains(q("'error':null")),"nested failure lost: "+nested);
        JSONObject nonStream=ResponsesBridge.chatResponseToResponses(new JSONObject(q("{'error':null,'choices':[{'message':{'content':'ok'},'finish_reason':'stop'}]}")),"m");
        check("completed".equals(nonStream.getString("status")) && nonStream.getJSONArray("output").length()>0,"error:null rejected in nonstream Chat");
    }
    static void repeatedToolIdentity() throws Exception {
        // Known limit, not a guess the code can avoid: a fragment identical to the
        // collected value is either a re-sent complete snapshot or a genuine delta
        // of exactly that text, and no stream evidence separates them. The repeat
        // reading is kept, because it is the observed upstream quirk and the only
        // reading that leaves a re-sent identity valid; an exact-identical delta is
        // therefore indistinguishable and cannot be recovered.
        String raw=sse(q("{'choices':[{'delta':{'tool_calls':[{'index':0,'id':'call_abc','function':{'name':'lookup','arguments':'{'}}]},'finish_reason':null}]}"),q("{'choices':[{'delta':{'tool_calls':[{'index':0,'id':'call_abc','function':{'name':'lookup','arguments':'}'}}]},'finish_reason':null}]}"),q("{'choices':[{'delta':{},'finish_reason':'tool_calls'}]}"),"[DONE]");
        String responses=read(ResponsesBridge.chatStreamToResponses(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)),"m"));
        check(!responses.contains("call_abccall_abc") && !responses.contains("lookuplookup"),"repeated identity duplicated: "+responses);
        check(responses.contains("call_abc") && responses.contains("lookup") && responses.contains("{}") && responses.contains("response.completed"),"repeated identity lost: "+responses);
        String chat=read(ResponsesBridge.responsesStreamToChat(new ByteArrayInputStream(responses.getBytes(StandardCharsets.UTF_8)),"m"));
        check(chat.contains("call_abc") && chat.contains("lookup") && !chat.contains("call_abccall_abc") && !chat.contains("lookuplookup"),"round trip duplicated identity: "+chat);
        String cumulative=sse(q("{'choices':[{'delta':{'tool_calls':[{'index':0,'id':'call_a','function':{'name':'go','arguments':'{}'}}]}}]}"),q("{'choices':[{'delta':{'tool_calls':[{'index':0,'id':'call_a1','function':{'name':'goo','arguments':''}}]},'finish_reason':'tool_calls'}]}"),"[DONE]");
        String snapshots=read(ResponsesBridge.chatStreamToResponses(new ByteArrayInputStream(cumulative.getBytes(StandardCharsets.UTF_8)),"m"));
        // Ambiguous growth: "call_a" then "call_a1" also reads as a cumulative
        // snapshot upstream meaning "call_a1". Nothing in the stream decides
        // between that and two genuine fragments, so the bridge must not guess:
        // collected text is kept and the fragment appended. A snapshot style
        // upstream is unsupported and shows a doubled identity instead of
        // silently renaming the tool behind the client's back.
        check(snapshots.contains("\"call_id\":\"call_acall_a1\"") && snapshots.contains("\"name\":\"gogoo\"") && snapshots.contains("response.completed") && !snapshots.contains("response.failed"),"ambiguous identity growth was guessed away: "+snapshots);
    }
    // A real identity fragment must never be rewritten into a replacement, and a
    // repeated complete snapshot must still be absorbed. The removed prefix
    // heuristic swallowed genuine text: "get_"+"g" stayed "get_" so the call went
    // out as "get_it_status", "search_"+"search" stayed "search_", and
    // "list_"+"list_items" stayed "list_items".
    static void identityFragmentsAreNotGuessed() throws Exception {
        String prefixed=sse(q("{'choices':[{'delta':{'tool_calls':[{'index':0,'id':'call_9','function':{'name':'get_','arguments':'{'}}]}}]}"),q("{'choices':[{'delta':{'tool_calls':[{'index':0,'function':{'name':'g','arguments':''}}]}}]}"),q("{'choices':[{'delta':{'tool_calls':[{'index':0,'function':{'name':'it_status','arguments':'}'}}]},'finish_reason':'tool_calls'}]}"),"[DONE]");
        String responses=read(ResponsesBridge.chatStreamToResponses(new ByteArrayInputStream(prefixed.getBytes(StandardCharsets.UTF_8)),"m"));
        check(responses.contains("\"name\":\"get_git_status\"") && !responses.contains("get_it_status") && responses.contains("\"arguments\":\"{}\""),"prefix fragment swallowed: "+responses);
        String chat=read(ResponsesBridge.responsesStreamToChat(new ByteArrayInputStream(responses.getBytes(StandardCharsets.UTF_8)),"m"));
        check(chat.contains("\"name\":\"get_git_status\"") && !chat.contains("get_it_status"),"round trip lost identity fragment: "+chat);
        String stale=sse(q("{'choices':[{'delta':{'tool_calls':[{'index':0,'id':'call_','function':{'name':'search_','arguments':'{'}}]}}]}"),q("{'choices':[{'delta':{'tool_calls':[{'index':0,'id':'tail','function':{'name':'search','arguments':'}'}}]},'finish_reason':'tool_calls'}]}"),"[DONE]");
        String staleOut=read(ResponsesBridge.chatStreamToResponses(new ByteArrayInputStream(stale.getBytes(StandardCharsets.UTF_8)),"m"));
        check(staleOut.contains("\"call_id\":\"call_tail\"") && staleOut.contains("\"name\":\"search_search\"") && staleOut.contains("\"arguments\":\"{}\""),"stale prefix guessed identity: "+staleOut);
        String growing=sse(q("{'choices':[{'delta':{'tool_calls':[{'index':0,'id':'call_','function':{'name':'list_','arguments':'{}'}}]}}]}"),q("{'choices':[{'delta':{'tool_calls':[{'index':0,'id':'tail','function':{'name':'list_items','arguments':''}}]},'finish_reason':'tool_calls'}]}"),"[DONE]");
        String growingOut=read(ResponsesBridge.chatStreamToResponses(new ByteArrayInputStream(growing.getBytes(StandardCharsets.UTF_8)),"m"));
        check(growingOut.contains("\"name\":\"list_list_items\"") && growingOut.contains("\"call_id\":\"call_tail\""),"growing fragment overwrote identity: "+growingOut);
    }
    public static void main(String[] args) throws Exception { int failures=0; terminalSemantics(); try { invalidChat(); } catch(AssertionError e){System.err.println(e); failures++;} try {lateToolMetadata();} catch(AssertionError e){System.err.println(e);failures++;} try {fragmentedName();} catch(AssertionError e){System.err.println(e);failures++;} try {requestValidation();} catch(AssertionError e){System.err.println(e);failures++;} try {finalOnly();} catch(AssertionError e){System.err.println(e);failures++;} try {argumentsDone();} catch(AssertionError e){System.err.println(e);failures++;} try {lifecycleAndInterleave();} catch(AssertionError e){System.err.println(e);failures++;} try {gatewayFixtures();} catch(AssertionError e){System.err.println(e);failures++;} try {projectionBoundaries();} catch(AssertionError e){System.err.println(e);failures++;} try {invalidFinalCalls();} catch(AssertionError e){System.err.println(e);failures++;} try {prematureDone();} catch(AssertionError e){System.err.println(e);failures++;} try {doneWithoutSpace();} catch(AssertionError e){System.err.println(e);failures++;} try {nullErrorFields();} catch(AssertionError e){System.err.println(e);failures++;} try {repeatedToolIdentity();} catch(AssertionError e){System.err.println(e);failures++;} try {identityFragmentsAreNotGuessed();} catch(AssertionError e){System.err.println(e);failures++;} if(failures>0)throw new AssertionError(failures+" failures"); }
}
