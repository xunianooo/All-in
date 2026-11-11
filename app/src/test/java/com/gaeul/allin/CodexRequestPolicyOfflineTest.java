package com.gaeul.allin;

import org.json.*;
import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic loopback contracts; no claim of real Codex backend cap support. */
public final class CodexRequestPolicyOfflineTest {
    static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    static JSONObject request() { return new JSONObject().put("model","m").put("input","hello"); }
    static final String EVENTS="data: {\"type\":\"response.completed\",\"response\":{\"id\":\"r\",\"status\":\"completed\",\"model\":\"m\",\"output\":[],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n";
    static final String COMPACT="{\"id\":\"cmp_fixture\",\"object\":\"response.compaction\",\"output\":[]}";
    static String responseFor(String path) {return path.endsWith("/compact")?COMPACT:EVENTS;}
    static GatewayServer gateway(int port) throws Exception {
        GatewayServer g=ResponsesGatewayOfflineTest.gateway(port);
        JSONObject account=new JSONObject().put("id","codex-fixture").put("enabled",true).put("access","fixture-access").put("refresh","").put("expires",0).put("accountId","fixture-account");
        JSONObject p=new JSONObject().put("id","codex").put("enabled",true).put("protocol","codex").put("baseUrl",CodexAuth.CODEX_BASE).put("models",new JSONArray().put("m")).put("keys",new JSONArray().put("fixture-access")).put("accounts",new JSONArray().put(account));
        ResponsesGatewayOfflineTest.config(g).put("providers",new JSONArray().put(p)).put("guard",new JSONObject().put("enabled",true).put("maxBodyBytes",0).put("maxOutputTokens",0));
        g.setVendorConnectionsForTests(url->NetworkPolicy.openHttp(url.startsWith(CodexAuth.CODEX_BASE)?"http://127.0.0.1:"+port+new URL(url).getFile():url));
        return g;
    }
    static void policy() {
        JSONObject original=request().put("temperature",0.2).put("previous_response_id","r").put("service_tier","priority").put("reasoning",new JSONObject().put("effort","ultra"));
        JSONObject body=CodexRequestPolicy.prepareFinalOutbound(original,CodexRequestPolicy.OutputConstraints.none()).body;
        check(original.has("temperature")&&!body.has("temperature")&&!body.has("previous_response_id"),"detached normalization");
        check(body.getBoolean("stream")&&!body.getBoolean("store")&&body.getJSONArray("input").length()==1,"native defaults");
        check(body.getJSONObject("reasoning").getString("effort").equals("max")&&body.getString("service_tier").equals("priority"),"reasoning/tier retained");
        for(String field:new String[]{"max_tokens","max_completion_tokens","max_output_tokens"}) {
            for(CodexRequestPolicy.OutputConstraints sources:new CodexRequestPolicy.OutputConstraints[]{CodexRequestPolicy.OutputConstraints.none(),CodexRequestPolicy.OutputConstraints.of(128,0)}) {
                JSONObject limited=CodexRequestPolicy.prepareFinalOutbound(request().put(field,32),sources).body;
                check(!limited.has("max_output_tokens")&&!limited.has("max_completion_tokens"),"Magpie removes unsupported aliases");
                check(field.equals("max_tokens")?limited.getInt("max_tokens")==32:!limited.has("max_tokens"),"Magpie leaves max_tokens only; never synthesize cap");
            }
            for(CodexRequestPolicy.OutputConstraints sources:new CodexRequestPolicy.OutputConstraints[]{CodexRequestPolicy.OutputConstraints.of(0,64),CodexRequestPolicy.OutputConstraints.of(128,64)}) {
                try { CodexRequestPolicy.prepareFinalOutbound(request().put(field,32),sources);throw new AssertionError("unverified Guard cap accepted"); }
                catch(CodexRequestPolicy.UnpreservableOutputLimitException e) {check(e.getMessage().contains("Context Guard")&&e.guardAppliedLimit==64,"explicit Guard guarantee rejected");}
            }
        }
        try {CodexRequestPolicy.prepareFinalOutbound(request(),CodexRequestPolicy.OutputConstraints.of(0,64));throw new AssertionError("lost guard cap");}
        catch(CodexRequestPolicy.UnpreservableOutputLimitException expected) {check(expected.guardAppliedLimit==64,"missing-body guard cap rejected");}
        for(Object v:new Object[]{0,-1,1.5,"3",JSONObject.NULL}) {
            try {CodexRequestPolicy.prepareFinalOutbound(request().put("max_tokens",v),CodexRequestPolicy.OutputConstraints.none());throw new AssertionError("invalid cap");}
            catch(CodexRequestPolicy.InvalidOutputLimitException expected) {}
        }
        Map<String,String> headers=new HashMap<>();headers.put("session-id","client-session");headers.put("thread-id","client-thread");headers.put("session_id","old");
        check(CodexRequestPolicy.sessionHeaders(request(),headers).get("thread-id").equals("client-thread"),"preserve named client session");
        Map<String,String> keyed=CodexRequestPolicy.sessionHeaders(request().put("prompt_cache_key","cache"),headers);
        check(keyed.size()==2&&keyed.get("session-id").equals("cache")&&keyed.get("thread-id").equals("cache"),"body cache key wins as upstream does");
        Map<String,String> legacy=CodexRequestPolicy.sessionHeaders(request(),Collections.singletonMap("session_id","old"));
        check(legacy.size()==2&&!legacy.containsKey("session_id")&&!legacy.containsValue("old"),"legacy underscored header not forwarded");
        try {CodexRequestPolicy.sessionHeaders(request().put("prompt_cache_key","x\r\ny"),headers);throw new AssertionError("header injection");}catch(IllegalArgumentException expected) {}
    }
    static final String UUID_SHAPE="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    static JSONObject userTurn(String text) {
        return new JSONObject().put("type","message").put("role","user")
                .put("content",new JSONArray().put(new JSONObject().put("type","input_text").put("text",text)));
    }
    static JSONObject conversation() {
        return new JSONObject().put("model","m").put("instructions","You are omp.")
                .put("tools",new JSONArray().put(new JSONObject().put("type","function").put("name","read")));
    }
    static String sessionOf(JSONObject body,Map<String,String> inbound) {
        Map<String,String> session=CodexRequestPolicy.sessionHeaders(body,inbound);
        check(session.get("session-id")!=null&&session.get("session-id").equals(session.get("thread-id")),"paired session headers "+session);
        return session.get("session-id");
    }
    /** Upstream contracts: TestCodexSignSessionHeaders, TestCodexPromptCacheKey and sessionHeaders. */
    static void sessionContract() {
        JSONObject first=new JSONObject(conversation().toString()).put("input",new JSONArray().put(userTurn("fix the bug")));
        JSONObject later=new JSONObject(conversation().toString()).put("input",new JSONArray().put(userTurn("fix the bug"))
                .put(new JSONObject().put("type","message").put("role","assistant")
                        .put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text","done"))))
                .put(userTurn("and the test?")));
        JSONObject other=new JSONObject(conversation().toString()).put("input",new JSONArray().put(userTurn("fix another bug")));
        String key=sessionOf(first,new HashMap<String,String>());
        check(key.matches(UUID_SHAPE),"a keyless conversation is named by a bare hash: "+key);
        check(key.equals(sessionOf(later,new HashMap<String,String>())),"every turn of a conversation keeps its session");
        check(!key.equals(sessionOf(other,new HashMap<String,String>())),"another conversation gets another session");
        check(!key.equals(sessionOf(new JSONObject(conversation().toString()).put("instructions","You are Pi.")
                .put("input",new JSONArray().put(userTurn("fix the bug"))),new HashMap<String,String>())),
                "the client's instructions are part of the conversation");
        check(key.equals(sessionOf(new JSONObject(conversation().toString()).put("input",new JSONArray().put(
                userTurn("fix the bug").put("id","msg_1").put("status","completed"))),new HashMap<String,String>())),
                "volatile ids and statuses don't move the session");
        check(!key.contains("bug")&&!key.contains("omp"),"the session names nothing of the client");

        // a client that names its own session keeps it; nothing is derived beside it
        Map<String,String> named=new HashMap<String,String>();named.put("session-id","client-session");
        Map<String,String> kept=CodexRequestPolicy.sessionHeaders(first,named);
        check(kept.size()==1&&"client-session".equals(kept.get("session-id")),"client-named session is preserved");
        check("thread-1".equals(sessionOf(new JSONObject().put("prompt_cache_key","thread-1")
                .put("input",new JSONArray().put(userTurn("hi"))),named)),"an explicit body key still wins");

        // no user message yet: no conversation to name, and nothing is invented
        check(CodexRequestPolicy.sessionHeaders(new JSONObject().put("input",new JSONArray().put(
                new JSONObject().put("type","reasoning").put("encrypted_content","c"))),new HashMap<String,String>()).isEmpty(),
                "no session without a conversation");
        check(CodexRequestPolicy.sessionHeaders(new JSONObject(),new HashMap<String,String>()).isEmpty(),
                "no session without input");
        check(sessionOf(new JSONObject().put("input","hello"),new HashMap<String,String>()).matches(UUID_SHAPE),
                "a plain string input is a conversation too");

        // a reference dropped before the key is taken must not move the session either
        String referenced=sessionOf(new JSONObject(conversation().toString()).put("input",new JSONArray()
                .put(new JSONObject().put("type","item_reference").put("id","msg_1")).put(userTurn("fix the bug"))),
                new HashMap<String,String>());
        check(referenced.equals(key),"a dropped item reference doesn't move the session");
        // a client header cannot smuggle a second header into the request
        try {CodexRequestPolicy.sessionHeaders(first,Collections.singletonMap("session-id","a\r\nb"));
            throw new AssertionError("client session header injection");}
        catch(IllegalArgumentException expected) {}
    }
    static final class Upstream implements AutoCloseable {
        final ServerSocket server=new ServerSocket(0); final Thread thread; final boolean drop; final int status; final String response;
        final AtomicInteger count=new AtomicInteger(); volatile JSONObject body; volatile Map<String,String> headers; volatile Throwable error;
        Upstream(boolean drop,int status,String response) throws Exception {
            this.drop=drop;this.status=status;this.response=response;
            thread=new Thread(()->{while(!server.isClosed()) try(Socket s=server.accept()) {
                s.setSoTimeout(5000);InputStream in=s.getInputStream();ByteArrayOutputStream head=new ByteArrayOutputStream();int state=0,c;
                while(state<4&&(c=in.read())!=-1){head.write(c);state=state==0&&c==13?1:state==1&&c==10?2:state==2&&c==13?3:state==3&&c==10?4:0;}
                Map<String,String> h=new HashMap<>();for(String line:head.toString("UTF-8").split("\r\n")){int colon=line.indexOf(':');if(colon>0)h.put(line.substring(0,colon).toLowerCase(Locale.ROOT),line.substring(colon+1).trim());}
                int len=Integer.parseInt(h.get("content-length"));byte[] b=new byte[len];int n=0;while(n<len){int r=in.read(b,n,len-n);if(r<0)throw new EOFException();n+=r;}
                body=new JSONObject(new String(b,StandardCharsets.UTF_8));headers=h;count.incrementAndGet();if(drop)continue;
                byte[] out=response.getBytes(StandardCharsets.UTF_8);s.getOutputStream().write(("HTTP/1.1 "+status+" Result\r\nContent-Type: "+(response.startsWith("data:")?"text/event-stream":"application/json")+"\r\nContent-Length: "+out.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));s.getOutputStream().write(out);s.getOutputStream().flush();
            }catch(Throwable e){if(!server.isClosed())error=e;}});thread.setDaemon(true);thread.start();
        }
        public void close() throws Exception {server.close();thread.join(2000);if(error!=null)throw new AssertionError(error);check(!thread.isAlive(),"mock closed");}
    }
    static JSONObject chat() {return new JSONObject().put("model","codex/m").put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hello")));}
    static void legalNative() throws Exception {
        for(String path:new String[]{"/v1/chat/completions","/v1/responses","/backend-api/codex/responses","/backend-api/codex/responses/compact"}) for(boolean stream:new boolean[]{false,true}) {
            try(Upstream u=new Upstream(false,200,responseFor(path))) {
                GatewayServer g=gateway(u.server.getLocalPort());JSONObject b=path.contains("chat/completions")?chat():request().put("model",path.startsWith("/v1")?"codex/m":"m");
                b.put("stream",stream).put("prompt_cache_key","cache-session");
                if(!path.contains("chat/completions"))b.put("input",new JSONArray().put(new JSONObject().put("type","reasoning").put("encrypted_content","cipher").put("summary",new JSONArray())).put(new JSONObject().put("role","user").put("content",new JSONArray().put(new JSONObject().put("type","input_text").put("text","hello")).put(new JSONObject().put("type","input_image").put("image_url","https://example.com/a.png")))));
                NanoHTTPD.IHTTPSession session=ResponsesGatewayOfflineTest.session(b.toString(),"secret",path);session.getHeaders().put("session-id","client-session");
                NanoHTTPD.Response r=g.serve(session);String result=ResponsesGatewayOfflineTest.read(r.getData());
                check(r.getStatus().getRequestStatus()==200,"legal Codex "+path+" "+result);check(u.count.get()==1,"single dispatch");
                check("cache-session".equals(u.headers.get("session-id"))&&"cache-session".equals(u.headers.get("thread-id")),"actual session headers "+path);
                if(!path.contains("chat/completions"))check(u.body.toString().contains("cipher")&&u.body.toString().contains("input_image"),"native reasoning/image retained");
            }
        }
        try(Upstream u=new Upstream(false,200,EVENTS)) {
            GatewayServer g=gateway(u.server.getLocalPort());NanoHTTPD.IHTTPSession s=ResponsesGatewayOfflineTest.session(chat().toString(),"secret","/v1/chat/completions");s.getHeaders().put("session-id","named");s.getHeaders().put("thread-id","thread");s.getHeaders().put("session_id","legacy");
            NanoHTTPD.Response r=g.serve(s);ResponsesGatewayOfflineTest.read(r.getData());check("named".equals(u.headers.get("session-id"))&&"thread".equals(u.headers.get("thread-id"))&&!u.headers.containsKey("session_id"),"actual passthrough without key");
        }
    }
    /** A keyless client's turns reach the backend named by the conversation, not anonymous (#1473). */
    static void derivedSessions() throws Exception {
        try(Upstream u=new Upstream(false,200,EVENTS)) {
            GatewayServer g=gateway(u.server.getLocalPort());
            NanoHTTPD.Response r=g.serve(ResponsesGatewayOfflineTest.session(chat().toString(),"secret","/v1/chat/completions"));
            String result=ResponsesGatewayOfflineTest.read(r.getData());
            check(r.getStatus().getRequestStatus()==200,"derived session request "+result);
            String first=u.headers.get("session-id");
            check(first!=null&&first.equals(u.headers.get("thread-id"))&&first.matches(UUID_SHAPE),"derived session headers "+u.headers);
            check(!u.headers.containsKey("session_id")&&!u.headers.containsKey("conversation_id"),"no legacy underscored session header");
            check(!u.headers.containsKey("x-session-id")&&!u.headers.containsKey("x-conversation-id"),"no client session header invented");
            check(!u.body.has("prompt_cache_key"),"no cache key fabricated in the client's body");
            JSONObject second=chat();
            second.getJSONArray("messages").put(new JSONObject().put("role","assistant").put("content","done"))
                    .put(new JSONObject().put("role","user").put("content","and the test?"));
            r=g.serve(ResponsesGatewayOfflineTest.session(second.toString(),"secret","/v1/chat/completions"));
            ResponsesGatewayOfflineTest.read(r.getData());
            check(r.getStatus().getRequestStatus()==200&&first.equals(u.headers.get("session-id"))&&first.equals(u.headers.get("thread-id")),
                    "the next turn of the conversation keeps its session "+u.headers);
        }
        // no user message yet: the request goes unnamed rather than inventing a session
        try(Upstream u=new Upstream(false,200,EVENTS)) {
            GatewayServer g=gateway(u.server.getLocalPort());
            JSONObject b=request().put("model","codex/m").put("input",new JSONArray().put(
                    new JSONObject().put("type","reasoning").put("encrypted_content","cipher").put("summary",new JSONArray())));
            NanoHTTPD.Response r=g.serve(ResponsesGatewayOfflineTest.session(b.toString(),"secret","/v1/responses"));
            ResponsesGatewayOfflineTest.read(r.getData());
            check(r.getStatus().getRequestStatus()==200&&!u.headers.containsKey("session-id")&&!u.headers.containsKey("thread-id"),
                    "no session invented without a conversation "+u.headers);
        }
    }
    static int clientCaps() throws Exception {
        int cases=0;
        for(String path:new String[]{"/v1/chat/completions","/v1/responses","/backend-api/codex/responses","/backend-api/codex/responses/compact"})
            for(String field:new String[]{"max_tokens","max_completion_tokens","max_output_tokens"})
                for(boolean stream:new boolean[]{false,true}) {
                    // /v1/responses validates its own documented schema: only max_output_tokens.
                    if(path.equals("/v1/responses")&&!field.equals("max_output_tokens")) continue;
                    try(Upstream u=new Upstream(false,200,responseFor(path))) {
                        GatewayServer g=gateway(u.server.getLocalPort());
                        JSONObject b=path.contains("chat/completions")?chat():request().put("model",path.startsWith("/v1")?"codex/m":"m");
                        b.put(field,100).put("stream",stream);
                        NanoHTTPD.Response r=g.serve(ResponsesGatewayOfflineTest.session(b.toString(),"secret",path));
                        String result=ResponsesGatewayOfflineTest.read(r.getData());
                        check(r.getStatus().getRequestStatus()==200&&u.count.get()==1,"ordinary client cap usable "+path+" "+field+" "+result);
                        if(path.endsWith("/compact")) check(u.body.getInt(field)==100,"compact body passthrough");
                        else {
                            check(!u.body.has("max_output_tokens")&&!u.body.has("max_completion_tokens"),"upstream alias deletion "+path);
                            check(path.equals("/backend-api/codex/responses")&&field.equals("max_tokens")?u.body.getInt("max_tokens")==100:!u.body.has("max_tokens"),"no synthesized max_tokens "+path);
                        }
                        check(r.getHeader("X-AllIn-Compatibility-Warning")!=null&&r.getHeader("X-AllIn-Compatibility-Warning").contains("codex-output-limit-not-guaranteed"),"client receives cap advisory "+path);
                        cases++;
                    }
                }
        check(cases==20,"client cap matrix actually exercised: "+cases);
        return cases;
    }
    static int disabledGuard() throws Exception {
        int cases=0;
        for(String path:new String[]{"/v1/chat/completions","/v1/responses","/backend-api/codex/responses","/backend-api/codex/responses/compact"}) {
            try(Upstream u=new Upstream(false,200,responseFor(path))) {
                GatewayServer g=gateway(u.server.getLocalPort());
                ResponsesGatewayOfflineTest.config(g).getJSONObject("guard").put("enabled",false).put("maxOutputTokens",12);
                JSONObject b=path.contains("chat/completions")?chat():request().put("model",path.startsWith("/v1")?"codex/m":"m");
                b.put(path.equals("/v1/responses")?"max_output_tokens":"max_tokens",100);
                NanoHTTPD.Response r=g.serve(ResponsesGatewayOfflineTest.session(b.toString(),"secret",path));
                String result=ResponsesGatewayOfflineTest.read(r.getData());
                check(r.getStatus().getRequestStatus()==200&&u.count.get()==1,"disabled Guard must not block client cap "+path+" "+result);
                cases++;
            }
        }
        check(cases==4,"disabled Guard matrix actually exercised: "+cases);
        return cases;
    }
    static void rejectionMatrix() throws Exception {
        for(String path:new String[]{"/v1/chat/completions","/v1/responses","/backend-api/codex/responses","/backend-api/codex/responses/compact"}) for(int mode=1;mode<6;mode++) {
            GatewayServer g=gateway(1);JSONObject cfg=ResponsesGatewayOfflineTest.config(g);JSONObject b=path.contains("chat/completions")?chat():request().put("model",path.startsWith("/v1")?"codex/m":"m");
            if(mode==1)cfg.getJSONObject("guard").put("maxOutputTokens",12);
            if(mode==2)cfg.getJSONObject("guard").put("maxBodyBytes",10);
            if(mode==3)cfg.getJSONObject("guard").put("maxContextTokens",1);
            if(mode==4)b.put("max_tokens",0);
            if(mode==5){cfg.getJSONObject("guard").put("maxOutputTokens",12);b.put(path.equals("/v1/responses")?"max_output_tokens":"max_tokens",4);}
            AtomicInteger opens=new AtomicInteger();g.setVendorConnectionsForTests(url->{opens.incrementAndGet();throw new IOException("must not send");});
            NanoHTTPD.Response r=g.serve(ResponsesGatewayOfflineTest.session(b.toString(),"secret",path));String result=ResponsesGatewayOfflineTest.read(r.getData());
            check((r.getStatus().getRequestStatus()==400 || mode>=2&&mode<=3&&r.getStatus().getRequestStatus()==413)&&opens.get()==0,"local cap/guard rejection "+path+" mode="+mode+" "+result);
            if(mode==1||mode==5)check(result.contains("Context Guard")&&result.contains("not verified"),"explicit Guard limitation clear");
        }
    }
    static void clearFixtureRest() throws Exception {
        java.lang.reflect.Field field=GatewayServer.class.getDeclaredField("RESTS");field.setAccessible(true);
        ((Map<?,?>)field.get(null)).remove("codex/m");
    }
    static void replayBoundary(boolean drop,int status,String response,int expectedSecond) throws Exception {
        clearFixtureRest();
        try(Upstream first=new Upstream(drop,status,response);Upstream second=new Upstream(false,200,ResponsesAgentRequestOfflineTest.CHAT)) {
            GatewayServer g=gateway(first.server.getLocalPort());JSONObject cfg=ResponsesGatewayOfflineTest.config(g);
            cfg.getJSONArray("providers").put(new JSONObject().put("id","other").put("protocol","openai").put("baseUrl","http://127.0.0.1:"+second.server.getLocalPort()+"/v1").put("keys",new JSONArray().put("fixture")).put("models",new JSONArray().put("m")));
            cfg.put("routing",new JSONObject().put("groupsEnabled",true)).put("groups",new JSONArray().put(new JSONObject().put("id","g").put("strategy","order").put("targets",new JSONArray().put("codex/m").put("other/m")))).put("failover",new JSONObject().put("enabled",true).put("maxTargets",2).put("maxAttempts",4));
            NanoHTTPD.Response r=g.serve(ResponsesGatewayOfflineTest.session(chat().put("model","group/g").toString(),"secret","/v1/chat/completions"));String result=ResponsesGatewayOfflineTest.read(r.getData());
            check(first.count.get()==1,"first mock read exactly one complete body count="+first.count.get()+" second="+second.count.get()+" status="+r.getStatus().getRequestStatus()+" result="+result);check(second.count.get()==expectedSecond,"unknown execution must not replay: second="+second.count.get()+" "+result);
            check(r.getStatus().getRequestStatus()==(expectedSecond==1?200:drop||status==200?502:status),"replay boundary status "+result);
        }
    }
    static void nativeHiddenGuard() throws Exception {
        for(String path:new String[]{"/v1/responses","/backend-api/codex/responses","/backend-api/codex/responses/compact"}) {
            GatewayServer g=gateway(1);ResponsesGatewayOfflineTest.config(g).getJSONObject("guard").put("maxContextTokens",100);
            char[] hidden=new char[4000];Arrays.fill(hidden,'x');
            JSONObject b=request().put("model",path.startsWith("/v1")?"codex/m":"m").put("input",new JSONArray().put(new JSONObject().put("type","reasoning").put("encrypted_content",new String(hidden)).put("summary",new JSONArray())).put(new JSONObject().put("role","user").put("content","hi")));
            AtomicInteger opens=new AtomicInteger();g.setVendorConnectionsForTests(url->{opens.incrementAndGet();throw new IOException("must not send");});
            NanoHTTPD.Response r=g.serve(ResponsesGatewayOfflineTest.session(b.toString(),"secret",path));ResponsesGatewayOfflineTest.read(r.getData());
            check(r.getStatus().getRequestStatus()>=400&&r.getStatus().getRequestStatus()<500&&opens.get()==0,"native hidden material cannot bypass Guard "+path);
        }
    }
    static void preSendFallback() throws Exception {
        clearFixtureRest();
        try(Upstream second=new Upstream(false,200,ResponsesAgentRequestOfflineTest.CHAT)) {
            GatewayServer g=gateway(1);JSONObject cfg=ResponsesGatewayOfflineTest.config(g);
            cfg.getJSONArray("providers").put(new JSONObject().put("id","other-before-send").put("protocol","openai").put("baseUrl","http://127.0.0.1:"+second.server.getLocalPort()+"/v1").put("keys",new JSONArray().put("fixture")).put("models",new JSONArray().put("m")));
            cfg.put("routing",new JSONObject().put("groupsEnabled",true)).put("groups",new JSONArray().put(new JSONObject().put("id","before").put("strategy","order").put("targets",new JSONArray().put("codex/m").put("other-before-send/m")))).put("failover",new JSONObject().put("enabled",true).put("maxTargets",2).put("maxAttempts",4));
            AtomicInteger codexOpens=new AtomicInteger();g.setVendorConnectionsForTests(url->{if(url.startsWith(CodexAuth.CODEX_BASE)){codexOpens.incrementAndGet();throw new ConnectException("synthetic pre-send refusal");}return NetworkPolicy.openHttp(url);});
            NanoHTTPD.Response r=g.serve(ResponsesGatewayOfflineTest.session(chat().put("model","group/before").toString(),"secret","/v1/chat/completions"));String result=ResponsesGatewayOfflineTest.read(r.getData());
            check(r.getStatus().getRequestStatus()==200&&codexOpens.get()==1&&second.count.get()==1,"proven pre-send failure can fallback "+result);
        }
    }
    public static void main(String[] args) throws Exception {
        policy();sessionContract();rejectionMatrix();nativeHiddenGuard();legalNative();derivedSessions();
        int clientCases=clientCaps();int guardCases=disabledGuard();
        replayBoundary(true,200,"",0);
        replayBoundary(false,200,"data: {\"type\":\"response.created\"}\n\n",0);
        replayBoundary(false,200,"data: {invalid-json}\n\n",0);
        replayBoundary(false,502,"{\"error\":\"unknown\"}",0);
        replayBoundary(false,429,"{\"error\":{\"type\":\"rate_limit_error\",\"message\":\"rate limited\"}}",1);
        replayBoundary(false,503,"{\"error\":{\"message\":\"unknown\"}}",0);
        replayBoundary(false,503,"{\"error\":{\"type\":\"overloaded_error\",\"message\":\"overloaded\"}}",1);
        preSendFallback();
        System.out.println("CodexRequestPolicyOfflineTest PASS (client caps compatible "+clientCases+" cases, explicit Guard rejected, native preserved, keyless conversations named by session headers, drop/truncation/502 no replay, known model rejection fallback, disabled Guard "+guardCases+" cases)");
    }
}
