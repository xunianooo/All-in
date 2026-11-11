package com.gaeul.allin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

public class WorkBuddyNativeTransportOfflineTest {
    static void check(boolean ok, String why) { if(!ok)throw new AssertionError(why); }
    static final String SSE = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"m1\",\"role\":\"assistant\",\"model\":\"claude-opus-5.5\",\"content\":[],\"usage\":{\"input_tokens\":12}}}\n\n"
          + "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
          + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}\n\n"
          + "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
          + "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}\n\n"
          + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";

    static class FakeConnection extends java.net.HttpURLConnection {
        final ByteArrayOutputStream sent=new ByteArrayOutputStream();
        final Map<String,String> requestHeaders=new HashMap<>();
        final String response;
        final int code;
        boolean failStatus, disconnected;
        FakeConnection(String response,int code) throws Exception { super(new URL(WorkBuddyNativePolicy.URL));this.response=response;this.code=code; }
        @Override public void disconnect(){disconnected=true;}
        @Override public boolean usingProxy(){return false;}
        @Override public void connect(){}
        @Override public void setRequestProperty(String k,String v){requestHeaders.put(k,v);}
        @Override public void setInstanceFollowRedirects(boolean follow){super.setInstanceFollowRedirects(follow);}
        @Override public String getRequestProperty(String k){return requestHeaders.get(k);}
        @Override public OutputStream getOutputStream(){return sent;}
        @Override public int getResponseCode() throws IOException {if(failStatus)throw new IOException("socket broke after send");return code;}
        @Override public String getContentType(){return code>=400?"application/json":"text/event-stream";}
        @Override public long getContentLengthLong(){return response.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;}
        @Override public java.io.InputStream getInputStream() throws IOException {if(code>=400)throw new IOException("http error");return new ByteArrayInputStream(response.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        @Override public java.io.InputStream getErrorStream(){return code>=400?new ByteArrayInputStream(response.getBytes(java.nio.charset.StandardCharsets.UTF_8)):null;}
        @Override public Map<String,List<String>> getHeaderFields(){Map<String,List<String>> h=new HashMap<>();h.put("content-type",java.util.Collections.singletonList("text/event-stream"));return h;}
    }
    static WorkBuddyNativeTransport.Request request(boolean stream, JSONObject body, boolean hide) {
        return request(stream,body,hide,null);
    }
    static WorkBuddyNativeTransport.Request request(boolean stream, JSONObject body, boolean hide, JSONObject metadata) {
        return new WorkBuddyNativeTransport.Request("workbuddy","claude-opus-5.5","personal-token",
                new JSONObject().put("uid","u1"),"session-1",body,metadata,hide,stream,1000,1000,new WorkBuddyHarnessPolicy.Report());
    }
    static final String SIG_11159="{\"error\":{\"code\":\"400001\",\"internal_code\":\"11159\",\"message\":\"Invalid `signature` in `thinking` block. The block is bound to a different conversation\"}}";
    /**
     * A vendor signature refusal is passed through as-is after exactly one POST.
     *
     * Magpie resends the history once without thinking blocks, but that resend cannot be made safe
     * from this class: the caller's BeforeDispatch hook is the gateway's single outbound admission
     * (rate-limit ticket + markSent, cancellation registration, timeout clamp), and one hook call
     * can only serve one connection. Re-running it would take a second ticket and mark the send
     * twice; skipping it would leave the resend outside cancellation and rate pacing. This test
     * pins the safe behaviour so a retry cannot be reintroduced without also fixing that seam.
     */
    static void signatureRefusalIsNotReplayed() throws Exception {
        JSONObject signedHistory=new JSONObject("{\"model\":\"claude-opus-5.5\",\"system\":\"sys\","
                +"\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"signed prior\",\"signature\":\"sig-prior\"},{\"type\":\"text\",\"text\":\"answer\"}]},"
                +"{\"role\":\"user\",\"content\":\"continue\"}]}");
        String original=signedHistory.toString();
        FakeConnection refused=new FakeConnection(SIG_11159,400);
        int[] opens={0}; int[] dispatched={0};
        WorkBuddyNativeTransport.Request hooked=new WorkBuddyNativeTransport.Request(
                "workbuddy","claude-opus-5.5","personal-token",new JSONObject().put("uid","u1"),"session-1",
                signedHistory,null,false,false,1000,1000,new WorkBuddyHarnessPolicy.Report(),
                java.util.Collections.<String,String>emptyMap(),connection->dispatched[0]++);
        WorkBuddyNativeTransport.Response response=WorkBuddyNativeTransport.execute(hooked,url->{
            if(opens[0]++>0)throw new IOException("a signature refusal must not be resent");
            return refused;});
        check(opens[0]==1,"signature refusal produces exactly one physical POST, opens="+opens[0]);
        check(dispatched[0]==1,"one admission for one POST: no second ticket and no second markSent");
        check(response.statusCode()==400,"the vendor refusal status is preserved for the client");
        check(SIG_11159.equals(response.readErrorBody()),"the refusal body reaches the gateway error mapper unchanged");
        check(refused.disconnected,"the single connection is closed by the gateway error path");
        JSONObject sent=new JSONObject(refused.sent.toString("UTF-8"));
        check(sent.toString().contains("sig-prior"),"the only request keeps the signed history it was given");
        check("answer".equals(sent.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(1).optString("text")),
                "assistant text is untouched");
        check(original.equals(signedHistory.toString()),"caller body remains immutable");
    }
    public static void main(String[] args) throws Exception {
        signatureRefusalIsNotReplayed();

        JSONObject source=new JSONObject("{\"model\":\"claude-opus-5.5\",\"stream\":false,\"thinking\":{\"type\":\"adaptive\"},\"messages\":[{\"role\":\"system\",\"content\":\"The DeepSeek Harness should not appear.\"},{\"role\":\"user\",\"content\":\"hello\"}]}");
        FakeConnection nonstream=new FakeConnection(SSE,200); int[] opens={0};
        WorkBuddyNativeTransport.Response response=WorkBuddyNativeTransport.execute(request(false,source,true),url->{check(WorkBuddyNativePolicy.URL.equals(url),"fixed domestic native URL");opens[0]++;return nonstream;});
        check(opens[0]==1 && "POST".equals(nonstream.getRequestMethod()),"single POST");
        check("Bearer personal-token".equals(nonstream.getRequestProperty("Authorization")),"bearer header");
        check("text/event-stream".equals(nonstream.getRequestProperty("Accept")),"SSE accept header");
        check("2023-06-01".equals(nonstream.getRequestProperty("anthropic-version")),"Anthropic version header");
        check(!nonstream.getInstanceFollowRedirects(),"redirects disabled for single attempt");
        check("u1".equals(nonstream.getRequestProperty("X-User-Id")),"account header");
        JSONObject sent=new JSONObject(nonstream.sent.toString("UTF-8"));
        check(sent.optBoolean("stream") && !sent.toString().contains("DeepSeek Harness"),"forces upstream SSE and applies DSH");
        check(source.toString().contains("DeepSeek Harness"),"input body remains immutable");
        JSONObject aggregated=response.aggregate();
        check("ok".equals(aggregated.getJSONArray("content").getJSONObject(0).optString("text")),"nonstream response aggregation");
        check(aggregated.getJSONObject("usage").optInt("input_tokens")==12 && aggregated.getJSONObject("usage").optInt("output_tokens")==2,"real Anthropic usage retained");
        check(nonstream.disconnected,"aggregate closes connection");

        FakeConnection stream=new FakeConnection(SSE,200);
        WorkBuddyNativeTransport.Response streamResponse=WorkBuddyNativeTransport.execute(request(true,source,false),url->stream);
        check(streamResponse.clientWantsStream(),"stream client marker");
        check(streamResponse.stream().read()=='e',"stream client receives raw SSE");
        streamResponse.close(); check(stream.disconnected,"stream close disconnects");

        JSONObject protectedRequest=new JSONObject("{\"model\":\"claude-opus-5.5\",\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"signed thought\",\"signature\":\"signature-opaque\"},{\"type\":\"text\",\"text\":\"ordinary text\"}]},{\"role\":\"user\",\"content\":\"continue\"}]}");
        FakeConnection denied=new FakeConnection("{\"error\":\"rejected\"}",400);
        int[] deniedOpens={0};
        WorkBuddyNativeTransport.Response deniedResponse=WorkBuddyNativeTransport.execute(request(false,protectedRequest,true),url->{deniedOpens[0]++;return denied;});
        check(deniedOpens[0]==1 && deniedResponse.statusCode()==400,"HTTP rejection is returned once without retry");
        boolean noErrorStream=false; try { deniedResponse.stream(); } catch(IllegalStateException expected) { noErrorStream=true; }
        check(noErrorStream,"error status cannot be passed through as successful SSE");
        String deniedBody=denied.sent.toString("UTF-8");
        check(deniedBody.contains("signature-opaque") && deniedBody.contains("signed thought") && deniedBody.contains("ordinary text"),"signed assistant history is preserved by DSH preprocessing");
        check("{\"error\":\"rejected\"}".equals(deniedResponse.readErrorBody()),"error body available to gateway mapper");
        deniedResponse.close();

        JSONObject rewriteProtected=new JSONObject("{\"model\":\"claude-opus-5.5\",\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"signed\",\"signature\":\"sig\"},{\"type\":\"text\",\"text\":\"You are an AI agent, powered by DeepSeek Harness.\"}]}]}");
        String protectedOriginal=rewriteProtected.toString();
        int[] protectedOpens={0}; boolean protectedRejected=false;
        try { WorkBuddyNativeTransport.execute(request(false,rewriteProtected,true),url->{protectedOpens[0]++;return denied;}); }
        catch(IOException expected) { protectedRejected=true; }
        check(protectedRejected && protectedOpens[0]==0 && protectedOriginal.equals(rewriteProtected.toString()),"DSH cannot rewrite signed history and rejects before network dispatch");

        FakeConnection unknown=new FakeConnection("",200); unknown.failStatus=true; int[] unknownOpens={0};
        boolean outcomeUnknown=false;
        try { WorkBuddyNativeTransport.execute(request(false,new JSONObject("{\"model\":\"claude-opus-5.5\",\"messages\":[{\"role\":\"user\",\"content\":\"once\"}]}"),false),url->{unknownOpens[0]++;return unknown;}); }
        catch(WorkBuddyNativeTransport.OutcomeUnknownException expected) { outcomeUnknown=expected.mayHaveReachedUpstream(); }
        check(outcomeUnknown && unknownOpens[0]==1 && unknown.sent.size()>0,"unknown execution is explicit and never replayed");
        check(unknown.disconnected,"unknown connection closed");

        JSONObject adaptive=new JSONObject("{\"thinking\":{\"type\":\"adaptive\"},\"messages\":[{\"role\":\"user\",\"content\":\"effort\"}]}");
        JSONObject meta=new JSONObject("{\"reasoning\":{\"defaultEffort\":\"high\",\"supportedEfforts\":[\"low\",\"high\"]}}");
        FakeConnection effortConn=new FakeConnection(SSE,200);
        WorkBuddyNativeTransport.Response effortResponse=WorkBuddyNativeTransport.execute(request(false,adaptive,false,meta),url->effortConn);
        JSONObject effortSent=new JSONObject(effortConn.sent.toString("UTF-8"));
        check("claude-opus-5.5".equals(effortSent.optString("model")),"selected model is sent");
        // Messages is not a chat: a catalog defaultEffort belongs to the chat route only, so an
        // adaptive thinking block without a client-named level travels without one.
        check(!effortSent.has("output_config") && "adaptive".equals(effortSent.getJSONObject("thinking").optString("type")),
                "no catalog default effort is invented on the native route");
        JSONObject asked=new JSONObject("{\"thinking\":{\"type\":\"adaptive\"},\"output_config\":{\"effort\":\"low\"},\"messages\":[{\"role\":\"user\",\"content\":\"effort\"}]}");
        FakeConnection askedConn=new FakeConnection(SSE,200);
        WorkBuddyNativeTransport.Response askedResponse=WorkBuddyNativeTransport.execute(request(false,asked,false,meta),url->askedConn);
        JSONObject askedSent=new JSONObject(askedConn.sent.toString("UTF-8"));
        check("low".equals(askedSent.getJSONObject("output_config").optString("effort")),"a client-named level is sent as given");
        askedResponse.close();
        effortResponse.close();

        boolean wrongRoute=false;
        try { WorkBuddyNativeTransport.execute(new WorkBuddyNativeTransport.Request("workbuddy-ai","claude-opus-5.5","key",null,"",source,null,false,true,0,0,null),url->{throw new AssertionError("must not open wrong route");}); }
        catch(IllegalArgumentException expected){wrongRoute=true;}
        check(wrongRoute,"international WorkBuddy excluded locally");
        FakeConnection wrongMime=new FakeConnection(SSE,200) { @Override public String getContentType(){return "application/json";} };
        boolean knownNoReplay=false;
        try { WorkBuddyNativeTransport.execute(request(false,source,false),url->wrongMime); }
        catch(WorkBuddyNativeTransport.ResponseProtocolException expected) { knownNoReplay=!expected.outcomeUnknown() && expected.mayHaveReachedUpstream() && expected.status==200; }
        check(knownNoReplay,"known successful non-SSE response is terminal and non-replayable");
        FakeConnection malformed=new FakeConnection("data: [DONE]\n\n",200); int[] malformedOpens={0};
        WorkBuddyNativeTransport.Response malformedResponse=WorkBuddyNativeTransport.execute(request(false,source,false),url->{malformedOpens[0]++;return malformed;});
        boolean malformedNoReplay=false;
        try { malformedResponse.aggregate(); } catch(WorkBuddyNativeTransport.NoReplayException expected) { malformedNoReplay=!expected.outcomeUnknown(); }
        check(malformedNoReplay && malformedOpens[0]==1,"accepted but malformed response remains non-replayable");
        int[] invalidOpens={0}; boolean invalidRequest=false;
        FakeConnection invalidConnection=new FakeConnection(SSE,200);
        try { WorkBuddyNativeTransport.execute(request(false,new JSONObject("{\"model\":\"claude-opus-5.5\"}"),false),url->{invalidOpens[0]++;return invalidConnection;}); }
        catch(IOException expected){invalidRequest=true;}
        check(invalidRequest && invalidOpens[0]==0,"invalid native body rejected before dispatch");
        System.out.println("WorkBuddyNativeTransportOfflineTest OK");
    }
}
