package com.gaeul.allin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Contracts for publishing an engine 2 WorkBuddy rule onto the real native Messages POST
 * ({@code https://copilot.tencent.com/v1/messages}).
 *
 * <p>What is pinned here is the whole path from a signed rules document to the bytes the vendor
 * actually receives:
 *
 * <ul>
 *   <li>a matched rule changes the outbound {@code User-Agent} (and any other static fingerprint
 *       value it names) on the single physical POST, while the credential, account, conversation
 *       and the client's own {@code anthropic-version} header stay exactly as the gateway set
 *       them;</li>
 *   <li>with no rule for this binding the outbound request keeps the shipped static fingerprint —
 *       including when the document only styles the {@code /v2/chat/completions} destination;</li>
 *   <li>a document naming a credential, account, framing or Anthropic protocol header is refused
 *       whole, and the transport itself still refuses to let such a value reach a connection;</li>
 *   <li>the values come from the snapshot the logical request was admitted with, so the one resend
 *       WorkBuddy's signature refusal earns carries the same fingerprint as the send before it even
 *       when a newer document is published mid-request.</li>
 * </ul>
 *
 * <p>Every exchange is loopback: the gateway talks to a synthetic upstream and no real host is
 * contacted.
 */
public final class WorkBuddyNativeQuirksHttpTest {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}

    static final String RULE_UA="WorkBuddy/9.9.9 WorkBuddy/9.9.9 CLI/2.999.0";
    static final String LATER_UA="WorkBuddy/8.8.8 WorkBuddy/8.8.8 CLI/2.888.0";
    static final String SESSION="fixture-session";
    static final String REFUSAL="{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
            +"\"message\":\"Invalid `signature` in `thinking` block. The block is bound to a different conversation\","
            +"\"internal_code\":\"11159\"}}";
    static final String FOREIGN_SIGNATURE="signature-from-another-vendor";

    // ------------------------------------------------------------------ documents

    /** One rule for one exact destination, expressed the way a signed document is. */
    static JSONObject rule(String id,String provider,String protocol,String path,JSONObject headers){
        return new JSONObject().put("id",id).put("provider",provider).put("protocol",protocol)
                .put("origin","https://copilot.tencent.com").put("path",path).put("headers",headers);
    }
    static ProviderQuirks.Snapshot document(String version,JSONObject... rules)throws Exception{
        JSONArray published=new JSONArray();
        for(JSONObject r:rules)published.put(r);
        JSONObject root=new JSONObject().put("schemaVersion",1).put("minimumEngineVersion",2)
                .put("ruleVersion",version).put("rules",published);
        return ProviderQuirks.parse(root.toString().getBytes(StandardCharsets.UTF_8));
    }
    /** The native Messages binding, restyling the client fingerprint it is allowed to restyle. */
    static ProviderQuirks.Snapshot nativeRule(String version,String userAgent,String ideVersion)throws Exception{
        JSONObject headers=new JSONObject().put("User-Agent",userAgent);
        if(ideVersion!=null)headers.put("X-IDE-Version",ideVersion);
        return document(version,rule("workbuddy-native-headers","workbuddy","anthropic","/v1/messages",headers));
    }
    /** The shipped destination only: the chat route, which must never reach the native POST. */
    static ProviderQuirks.Snapshot chatOnlyRule(String version)throws Exception{
        return document(version,rule("workbuddy-chat-headers","workbuddy","openai","/v2/chat/completions",
                new JSONObject().put("User-Agent",LATER_UA).put("X-Product","SaaS")));
    }
    static String defaultNativeUserAgent(){
        return WorkBuddyPolicy.nativeHeaders("workbuddy",WorkBuddyNativePolicy.URL,"ck_fixture_workbuddy_token",
                null,"s",new JSONObject().put("messages",new JSONArray())).get("User-Agent");
    }

    // ------------------------------------------------------------------ harness helpers

    static final class Pinned {
        static WorkBuddyNativeGatewayHttpTest.GatewayHarness harness;
        static void snapshot(ProviderQuirks.Snapshot snapshot)throws Exception{
            ResponsesGatewayOfflineTest.set(harness.gateway,"quirksSnapshot",snapshot);
        }
    }
    static WorkBuddyNativeGatewayHttpTest.GatewayHarness gateway(WorkBuddyNativeGatewayHttpTest.MockUpstream upstream)throws Exception{
        return new WorkBuddyNativeGatewayHttpTest.GatewayHarness(WorkBuddyNativeGatewayHttpTest.config("workbuddy",
                "openai","https://copilot.tencent.com/v2","claude-opus-5.5"),upstream);
    }
    /** Same client contract as the shared helper, plus the session header the identity is scoped by. */
    static WorkBuddyNativeGatewayHttpTest.Result post(int port,String path,JSONObject body)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+path).openConnection();
        c.setConnectTimeout(3000);c.setReadTimeout(15000);c.setRequestMethod("POST");c.setDoOutput(true);
        c.setRequestProperty("Authorization","Bearer gateway-fixture");
        c.setRequestProperty("Content-Type","application/json");
        c.setRequestProperty("Accept-Encoding","identity");
        c.setRequestProperty("x-magpie-session",SESSION);
        try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}
        WorkBuddyNativeGatewayHttpTest.Result result=new WorkBuddyNativeGatewayHttpTest.Result();
        result.status=c.getResponseCode();
        result.headers=c.getHeaderFields();
        InputStream in=result.status>=400?c.getErrorStream():c.getInputStream();
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        if(in!=null)try(InputStream bodyStream=in){byte[] buffer=new byte[4096];int n;while((n=bodyStream.read(buffer))!=-1)out.write(buffer,0,n);}
        result.body=out.toString("UTF-8");
        c.disconnect();
        return result;
    }
    /** A request whose assistant history carries a signature WorkBuddy's signing did not make. */
    static JSONObject signedHistory(boolean stream)throws Exception{
        JSONObject input=WorkBuddyNativeGatewayHttpTest.anthropic(stream);
        JSONArray messages=input.getJSONArray("messages");
        messages.put(new JSONObject().put("role","assistant").put("content",new JSONArray()
                .put(new JSONObject().put("type","thinking").put("thinking","another vendor's thought")
                        .put("signature",FOREIGN_SIGNATURE))
                .put(new JSONObject().put("type","text").put("text","earlier answer"))));
        messages.put(new JSONObject().put("role","user").put("content","and now?"));
        return input;
    }
    static void verifyIdentity(WorkBuddyNativeGatewayHttpTest.Request sent,String credential){
        check(("/v1/messages").equals(sent.path),"native destination only: "+sent.path);
        check(("Bearer "+credential).equals(sent.headers.get("Authorization")),"the gateway credential is the only bearer: "+sent.headers.get("Authorization"));
        check(credential.equals(sent.headers.get("X-API-Key")),"the WorkBuddy key header keeps the gateway credential");
        check("2023-06-01".equals(sent.headers.get("anthropic-version")),"the client protocol version is unchanged");
        check("text/event-stream".equalsIgnoreCase(sent.headers.get("Accept"))||sent.headers.get("Accept")!=null,"an Accept header is present");
        check(sent.headers.get("X-User-Id")!=null||sent.headers.get("X-API-Key")!=null,"account identity header present");
        check(sent.headers.get("X-Conversation-ID")!=null,"conversation identity header present");
        check(sent.headers.get("X-Conversation-Request-ID")!=null,"turn identity header present");
        check("application/json".equals(sent.headers.get("Content-Type")),"native body stays JSON");
    }

    // ------------------------------------------------------------------ end-to-end rules

    /** A published native rule is what the vendor receives, once, on the real destination. */
    static void ruleChangesTheOutboundRequest()throws Exception{
        try(WorkBuddyNativeGatewayHttpTest.MockUpstream upstream=new WorkBuddyNativeGatewayHttpTest.MockUpstream(
                (request,count)->new WorkBuddyNativeGatewayHttpTest.Reply(200,"text/event-stream",
                        WorkBuddyNativeGatewayHttpTest.BASE));
            WorkBuddyNativeGatewayHttpTest.GatewayHarness gateway=gateway(upstream)){
            Pinned.harness=gateway;
            Pinned.snapshot(nativeRule("2.0.0",RULE_UA,"9.9.9"));
            WorkBuddyNativeGatewayHttpTest.Result response=post(gateway.port(),"/v1/messages",
                    WorkBuddyNativeGatewayHttpTest.anthropic(false));
            check(response.status==200,"native request succeeds: "+response.status+" "+response.body);
            check(upstream.requests.size()==1,"one physical send: "+upstream.requests.size());
            WorkBuddyNativeGatewayHttpTest.Request sent=upstream.requests.get(0);
            check(RULE_UA.equals(sent.headers.get("User-Agent")),"the published rule is the outbound User-Agent: "+sent.headers.get("User-Agent"));
            check("9.9.9".equals(sent.headers.get("X-IDE-Version")),"a second named static value is published too: "+sent.headers.get("X-IDE-Version"));
            verifyIdentity(sent,WorkBuddyNativeGatewayHttpTest.KEY);
            check("claude-opus-5.5".equals(sent.body.optString("model")),"the resolved upstream model is unchanged");
        }
    }

    /** No matching rule: the shipped static fingerprint, never a value from another binding. */
    static void withoutARuleTheStaticFingerprintStays()throws Exception{
        String shipped=defaultNativeUserAgent();
        check(!shipped.equals(RULE_UA),"the fixture expectation is a real change");
        String[][] scenarios=new String[][]{{"empty","2.0.0"},{"chat-only","2.0.1"}};
        for(String[] scenario:scenarios){
            try(WorkBuddyNativeGatewayHttpTest.MockUpstream upstream=new WorkBuddyNativeGatewayHttpTest.MockUpstream(
                    (request,count)->new WorkBuddyNativeGatewayHttpTest.Reply(200,"text/event-stream",
                            WorkBuddyNativeGatewayHttpTest.BASE));
                WorkBuddyNativeGatewayHttpTest.GatewayHarness gateway=gateway(upstream)){
                Pinned.harness=gateway;
                Pinned.snapshot("empty".equals(scenario[0])?document(scenario[1]):chatOnlyRule(scenario[1]));
                WorkBuddyNativeGatewayHttpTest.Result response=post(gateway.port(),"/v1/messages",
                        WorkBuddyNativeGatewayHttpTest.anthropic(false));
                check(response.status==200,scenario[0]+": native request succeeds: "+response.status+" "+response.body);
                check(upstream.requests.size()==1,scenario[0]+": one physical send");
                WorkBuddyNativeGatewayHttpTest.Request sent=upstream.requests.get(0);
                check(shipped.equals(sent.headers.get("User-Agent")),
                        scenario[0]+": the shipped fingerprint is kept, got "+sent.headers.get("User-Agent"));
                check(!"9.9.9".equals(sent.headers.get("X-IDE-Version")),
                        scenario[0]+": no value from the native rule leaks in");
                verifyIdentity(sent,WorkBuddyNativeGatewayHttpTest.KEY);
            }
        }
    }

    // ------------------------------------------------------------------ credentials

    /** A document may not name a credential, identity, framing or protocol header at all. */
    static void documentsCannotNameProtectedHeaders()throws Exception{
        String[][] forbidden=new String[][]{{"Authorization","Bearer stolen"},{"X-API-Key","stolen"},
                {"X-User-Id","someone-else"},{"X-Domain","elsewhere.example"},{"anthropic-version","1999-01-01"},
                {"Content-Type","text/plain"},{"Content-Encoding","gzip"},{"Host","attacker.example"},
                {"X-Conversation-ID","bound-to-another-turn"}};
        for(String[] header:forbidden){
            boolean rejected=false;
            try{
                document("2.0.2",rule("workbuddy-native-headers","workbuddy","anthropic","/v1/messages",
                        new JSONObject().put("User-Agent",RULE_UA).put(header[0],header[1])));
            }catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"a document naming "+header[0]+" is refused whole");
        }
        // The destination itself is exact: no widened origin, no other path, no other protocol row.
        boolean widened=false;
        try{document("2.0.3",rule("wrong-origin","workbuddy","anthropic","/v1/messages",
                new JSONObject().put("User-Agent",RULE_UA)).put("origin","https://attacker.example"));}
        catch(IllegalArgumentException expected){widened=true;}
        check(widened,"a rule cannot move the native destination to another origin");
    }

    /** Even if such a value were handed to the transport, identity and framing still win. */
    static void transportRefusesToReplaceProtectedHeaders()throws Exception{
        Map<String,String> handed=new LinkedHashMap<>();
        handed.put("User-Agent",RULE_UA);
        handed.put("X-IDE-Version","9.9.9");
        handed.put("Authorization","Bearer attacker");
        handed.put("X-API-Key","attacker-key");
        handed.put("X-User-Id","attacker-user");
        handed.put("anthropic-version","1999-01-01");
        handed.put("Content-Type","text/plain");
        handed.put("Content-Encoding","gzip");
        handed.put("Accept-Encoding","gzip");
        WorkBuddyNativeTransport.Request request=new WorkBuddyNativeTransport.Request("workbuddy",
                "claude-opus-5.5","ck_fixture_workbuddy_token",new JSONObject().put("uid","u1"),SESSION,
                WorkBuddyNativeGatewayHttpTest.anthropic(false),null,false,false,1000,1000,
                new WorkBuddyHarnessPolicy.Report(),Collections.<String,String>emptyMap(),handed,null);
        handed.put("User-Agent","mutated-after-construction");
        check(RULE_UA.equals(request.ruleHeaders.get("User-Agent")),"the rules are copied into the request");
        FakeConnection connection=new FakeConnection(WorkBuddyNativeGatewayHttpTest.BASE,200);
        WorkBuddyNativeTransport.Response response=WorkBuddyNativeTransport.execute(request,url->connection);
        check("application/json".equals(connection.requestHeaders.get("Content-Type")),"framing stays JSON: "+connection.requestHeaders.get("Content-Type"));
        check(("Bearer ck_fixture_workbuddy_token").equals(connection.requestHeaders.get("Authorization")),
                "the credential cannot be replaced: "+connection.requestHeaders.get("Authorization"));
        check("ck_fixture_workbuddy_token".equals(connection.requestHeaders.get("X-API-Key")),
                "the key header cannot be replaced: "+connection.requestHeaders.get("X-API-Key"));
        check("u1".equals(connection.requestHeaders.get("X-User-Id")),"the account header cannot be replaced");
        check("2023-06-01".equals(connection.requestHeaders.get("anthropic-version")),
                "the Messages protocol version cannot be replaced: "+connection.requestHeaders.get("anthropic-version"));
        check("identity".equals(connection.requestHeaders.get("Accept-Encoding")),"the transport encoding cannot be replaced");
        check(connection.requestHeaders.get("Content-Encoding")==null,"no body encoding is invented by a rule");
        check(RULE_UA.equals(connection.requestHeaders.get("User-Agent")),"the static fingerprint is restyled: "+connection.requestHeaders.get("User-Agent"));
        check("9.9.9".equals(connection.requestHeaders.get("X-IDE-Version")),"the second static value is restyled");
        check(response.statusCode()==200,"the request is still sent");
        response.close();
    }

    // ------------------------------------------------------------------ snapshot lifetime

    /**
     * The rule values come from the snapshot the logical request was admitted with. The resend that
     * a signature refusal earns is the same request, so it must not pick up a document published
     * while it was in flight; a later request must.
     */
    static void oneSnapshotPerRequestAcrossTheResend()throws Exception{
        final java.util.List<WorkBuddyNativeGatewayHttpTest.Request> seen=new java.util.ArrayList<>();
        try(WorkBuddyNativeGatewayHttpTest.MockUpstream upstream=new WorkBuddyNativeGatewayHttpTest.MockUpstream(
                (request,count)->{
                    synchronized(seen){seen.add(request);}
                    if(count==1){
                        try{Pinned.snapshot(nativeRule("2.1.0",LATER_UA,null));}
                        catch(Exception reflection){throw new IllegalStateException(reflection);}
                        return new WorkBuddyNativeGatewayHttpTest.Reply(400,"application/json",REFUSAL);
                    }
                    return new WorkBuddyNativeGatewayHttpTest.Reply(200,"text/event-stream",
                            WorkBuddyNativeGatewayHttpTest.BASE);
                });
            WorkBuddyNativeGatewayHttpTest.GatewayHarness gateway=gateway(upstream)){
            Pinned.harness=gateway;
            Pinned.snapshot(nativeRule("2.0.4",RULE_UA,"9.9.9"));
            WorkBuddyNativeGatewayHttpTest.Result response=post(gateway.port(),"/v1/messages",signedHistory(false));
            check(response.status==200,"the refusal is recovered: "+response.status+" "+response.body);
            check(upstream.requests.size()==2,"one refusal and one resend: "+upstream.requests.size());
            WorkBuddyNativeGatewayHttpTest.Request first, second;
            synchronized(seen){first=seen.get(0);second=seen.get(1);}
            check(RULE_UA.equals(first.headers.get("User-Agent")),"first send uses the admitted rule: "+first.headers.get("User-Agent"));
            check(RULE_UA.equals(second.headers.get("User-Agent")),
                    "the resend keeps the request-scoped rule, not the newly published one: "+second.headers.get("User-Agent"));
            check("9.9.9".equals(second.headers.get("X-IDE-Version")),"the whole rule set is reused, not re-resolved");
            check(first.headers.get("X-Conversation-ID")!=null
                            && first.headers.get("X-Conversation-ID").equals(second.headers.get("X-Conversation-ID")),
                    "conversation identity is stable across the resend");
            verifyIdentity(first,WorkBuddyNativeGatewayHttpTest.KEY);
            verifyIdentity(second,WorkBuddyNativeGatewayHttpTest.KEY);
            check(second.body.toString().contains("earlier answer"),"the resend keeps the visible history");
            check(!second.body.toString().contains(FOREIGN_SIGNATURE),"only the refused signature is dropped");
            java.lang.reflect.Field live=GatewayServer.class.getDeclaredField("quirksSnapshot");
            live.setAccessible(true);
            ProviderQuirks.Snapshot published=(ProviderQuirks.Snapshot)live.get(gateway.gateway);
            check(LATER_UA.equals(published.headers("workbuddy","anthropic",WorkBuddyNativePolicy.URL).get("User-Agent")),
                    "the document really changed while the request was in flight");
            int before=upstream.requests.size();
            WorkBuddyNativeGatewayHttpTest.Result later=post(gateway.port(),"/v1/messages",
                    WorkBuddyNativeGatewayHttpTest.anthropic(false));
            check(later.status==200,"a later request succeeds: "+later.status);
            check(upstream.requests.size()==before+1,"the later request is its own physical send");
            check(LATER_UA.equals(upstream.requests.get(before).headers.get("User-Agent")),
                    "a request admitted after publication uses the new document: "+upstream.requests.get(before).headers.get("User-Agent"));
        }
    }

    // ------------------------------------------------------------------ stub connection

    static final class FakeConnection extends HttpURLConnection {
        final Map<String,String> requestHeaders=new LinkedHashMap<>();
        final ByteArrayOutputStream sent=new ByteArrayOutputStream();
        private final String response;
        private final int code;
        FakeConnection(String response,int code)throws Exception{super(new URL(WorkBuddyNativePolicy.URL));this.response=response;this.code=code;}
        @Override public void disconnect(){}
        @Override public boolean usingProxy(){return false;}
        @Override public void connect(){}
        @Override public void setRequestProperty(String key,String value){requestHeaders.put(key,value);}
        @Override public String getRequestProperty(String key){return requestHeaders.get(key);}
        @Override public OutputStream getOutputStream(){return sent;}
        @Override public int getResponseCode(){return code;}
        @Override public String getContentType(){return "text/event-stream";}
        @Override public InputStream getInputStream(){return new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8));}
        @Override public Map<String,List<String>> getHeaderFields(){return new LinkedHashMap<>();}
    }

    public static void main(String[] args)throws Exception{
        ruleChangesTheOutboundRequest();
        withoutARuleTheStaticFingerprintStays();
        documentsCannotNameProtectedHeaders();
        transportRefusesToReplaceProtectedHeaders();
        oneSnapshotPerRequestAcrossTheResend();
        System.out.println("WorkBuddyNativeQuirksHttpTest OK (native rule reaches the wire, absent rules keep the fingerprint, credentials are protected, one snapshot per request)");
    }
}
