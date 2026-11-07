package com.gaeul.allin;
import org.json.*;
import java.util.*;
import java.io.IOException;

public class CatPawPolicyOfflineTest {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    static JSONArray msgs(String json) { return new JSONArray(json); }
    static class Fake implements CatPawAuth.ChatTransport {
        int calls; int failAt; int status=200; boolean io; List<String> paths=new ArrayList<>();
        public CatPawAuth.ChatResponse post(String url,String body,Map<String,String> h,int timeout,String accept) throws Exception {
            paths.add(url.substring(url.lastIndexOf('/'))); calls++;
            if(calls==failAt) { if(io) throw new IOException("secret 401"); return new CatPawAuth.ChatResponse(status, null); }
            String result=calls==1?"{\"code\":0}":calls==2?"{}":"data: {\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}}\n\ndata: [DONE]\n\n";
            return new CatPawAuth.ChatResponse(200,result.getBytes("UTF-8"));
        }
    }
    public static void main(String[] args) throws Exception {
        JSONArray normal=msgs("[{\"role\":\"developer\",\"content\":\"important\"},{\"role\":\"user\",\"content\":\"hello\"}]");
        check(CatPawAuth.unsupportedReason(normal,null)==null);
        check(CatPawAuth.buildPrompt(normal).contains("[System Instructions]\nimportant"));
        String[] invalid={"[{\"role\":\"unknown\",\"content\":\"x\"}]","[{\"role\":\"assistant\",\"content\":\"x\",\"tool_calls\":[]}]","[{\"role\":\"assistant\",\"content\":\"x\",\"function_call\":{}}]","[{\"role\":\"user\",\"content\":\"x\",\"tool_call_id\":\"x\"}]","[{\"role\":\"user\",\"content\":[{\"type\":\"input_audio\"}]}]","[{\"role\":\"user\",\"content\":[{\"type\":\"file\"}]}]"};
        for(String text:invalid) { JSONArray m=msgs(text); check(CatPawAuth.unsupportedReason(m,null)!=null); Fake fake=new Fake(); try {CatPawAuth.chat("t","auto",m,fake);throw new AssertionError();} catch(CatPawAuth.Failure e){check(e.httpStatus==400&&!e.sentOrUnknown&&fake.calls==0);} }
        Fake ok=new Fake();check(CatPawAuth.chat("t","auto",normal,ok).getString("text").equals("ok")); check(ok.paths.equals(Arrays.asList("/round","/event","/turn")));
        for(int stage=1;stage<=3;stage++) for(int code:new int[]{401,403,429,500}) {Fake f=new Fake();f.failAt=stage;f.status=code;try{CatPawAuth.chat("t","auto",normal,f);throw new AssertionError();}catch(CatPawAuth.Failure e){check(f.calls==stage&&e.sentOrUnknown&&CatPawAuth.failureStatus(e)==(code==500?502:code));}}
        Fake io=new Fake();io.failAt=1;io.io=true;try{CatPawAuth.chat("t","auto",normal,io);throw new AssertionError();}catch(CatPawAuth.Failure e){check(CatPawAuth.failureStatus(e)==502&&e.sentOrUnknown&&!e.getMessage().contains("secret"));}
        String sse="data: {\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"abcdef\",\"reasoningContent\":\"123\"}]}}\n\ndata: {\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"abc\",\"reasoningContent\":\"4\"}]}}\n\ndata: {\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"xyz\",\"reasoningContent\":\"5\"}]}}\n\n";
        JSONObject result=CatPawAuth.parseTurn(sse.getBytes("UTF-8"));check(result.getString("text").equals("xyz")&&result.getString("reasoning").equals("5"));
        try {CatPawAuth.parseTurn("data: {\"error\":{\"code\":429,\"message\":\"secret\"}}\n\n".getBytes("UTF-8"));throw new AssertionError();}catch(CatPawAuth.Failure e){check(e.businessCode==429&&CatPawAuth.failureStatus(e)==429&&!e.getMessage().contains("secret"));}
        System.out.println("CatPawPolicyOfflineTest passed");
    }
}
