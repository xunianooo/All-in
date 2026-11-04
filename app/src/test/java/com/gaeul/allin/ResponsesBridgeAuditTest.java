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
        check(out.contains("response.failed") && !out.contains("response.completed"),"EOF without DONE falsely completed");
    }
    public static void main(String[] args) throws Exception { int failures=0; try { invalidChat(); } catch(AssertionError e){System.err.println(e); failures++;} try {lateToolMetadata();} catch(AssertionError e){System.err.println(e);failures++;} try {fragmentedName();} catch(AssertionError e){System.err.println(e);failures++;} try {prematureDone();} catch(AssertionError e){System.err.println(e);failures++;} if(failures>0)throw new AssertionError(failures+" failures"); }
}
