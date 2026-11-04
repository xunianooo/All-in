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
        check(chat.contains("\"name\":\"up\""),"name suffix lost: "+chat);
    }
    static void prematureDone() throws Exception {
        String raw=sse("{\"choices\":[{\"delta\":{\"content\":\"你好\"},\"finish_reason\":\"stop\"}]}");
        String out=read(ResponsesBridge.chatStreamToResponses(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)),"m"));
        check(out.contains("你好"),"utf8 corrupted");
        check(out.contains("response.completed") && !out.contains("response.failed"),"finish then EOF must complete");
    }
    static void requestValidation() throws Exception {
        String[] bad={"{\"input\":\"x\",\"stream\":\"true\"}","{\"input\":\"x\",\"max_output_tokens\":\"10\"}","{\"input\":\"x\",\"text\":null}","{\"input\":\"x\",\"max_output_tokens\":0}"};
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
        Class<?> c=Class.forName("com.gaeul.allin.GatewayServer$AnthropicSseToOpenAI"); java.lang.reflect.Constructor<?> ctor=c.getDeclaredConstructors()[0]; ctor.setAccessible(true);
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
        check(!valid.has("store") && valid.getInt("max_tokens")==Integer.MAX_VALUE && valid.isNull("temperature"),"valid projection changed");
    }
    static void invalidFinalCalls() throws Exception {
        String[] items={"{\"type\":\"function_call\",\"name\":\"f\",\"arguments\":\"{}\"}","{\"type\":\"function_call\",\"call_id\":\"c\",\"arguments\":\"{}\"}"};
        for(String item:items) {
            String raw=sse("{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":["+item+"]}}");
            String out=read(ResponsesBridge.responsesStreamToChat(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)),"m"));
            check(out.contains("\"error\"") && !out.contains("[DONE]"),"missing call id/name accepted: "+out);
        }
    }
    public static void main(String[] args) throws Exception { int failures=0; try { invalidChat(); } catch(AssertionError e){System.err.println(e); failures++;} try {lateToolMetadata();} catch(AssertionError e){System.err.println(e);failures++;} try {fragmentedName();} catch(AssertionError e){System.err.println(e);failures++;} try {requestValidation();} catch(AssertionError e){System.err.println(e);failures++;} try {finalOnly();} catch(AssertionError e){System.err.println(e);failures++;} try {argumentsDone();} catch(AssertionError e){System.err.println(e);failures++;} try {lifecycleAndInterleave();} catch(AssertionError e){System.err.println(e);failures++;} try {gatewayFixtures();} catch(AssertionError e){System.err.println(e);failures++;} try {projectionBoundaries();} catch(AssertionError e){System.err.println(e);failures++;} try {invalidFinalCalls();} catch(AssertionError e){System.err.println(e);failures++;} try {prematureDone();} catch(AssertionError e){System.err.println(e);failures++;} if(failures>0)throw new AssertionError(failures+" failures"); }
}
