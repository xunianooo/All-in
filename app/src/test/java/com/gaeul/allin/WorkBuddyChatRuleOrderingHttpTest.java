package com.gaeul.allin;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Loopback contracts for the order in which a signed rule and {@code WorkBuddyPolicy} write the
 * chat fingerprint of both WorkBuddy sites.
 *
 * <p>The chat path asks {@code WorkBuddyPolicy.headers(...)} for the whole vendor identity — user
 * agent, product, agent intent, conversation ids and the credential — and writes it to the
 * connection. A rule applied before that call is overwritten for every name the policy also writes,
 * so a schema 2 document could be signed, accepted by the parser, and still change nothing on the
 * wire. The fix keys the order on what the document itself declares, {@code minimumEngineVersion}:
 *
 * <ul>
 *   <li>engine 2 — the rule is applied <em>after</em> the identity, for exactly the chat vocabulary:
 *       the v1 names ({@code X-Product}, {@code X-Agent-Intent}, {@code X-Agent-Purpose},
 *       {@code X-Agent-Type}, {@code User-Agent}, {@code X-Requested-With},
 *       {@code X-Auth-Refresh-Source}) plus the static client fingerprint the policy also writes
 *       for this route ({@code X-IDE-Type/Name/Version}, {@code X-Agent-Runtime}, the
 *       {@code X-Stainless-*} block and {@code Accept}). Every name it omits keeps the policy
 *       value, and credentials, session values and the content framing are not in the vocabulary at
 *       all;</li>
 *   <li>engine 1 — the original order is kept verbatim, including its one carve-out (a rule user
 *       agent is only kept when it does not start with {@code CLI/2.}), because those documents
 *       were signed against that behaviour and are already published.</li>
 * </ul>
 *
 * <p>Every assertion reads the bytes the mock vendor actually received, over a real
 * {@code NanoHTTPD} listener; no real host is contacted.
 */
public final class WorkBuddyChatRuleOrderingHttpTest {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static final String KEY=VendorPolicyGatewayHttpTest.KEY;

    /**
     * One site: the provider id, the fixed origin the binding names, and the policy fingerprint the
     * gateway ships for it. {@code policyUserAgent} is the value {@code WorkBuddyPolicy} writes, so
     * "the rule did not win" and "the policy default survived" are distinguishable.
     */
    static final class Site {
        final String provider,origin,policyUserAgent;
        Site(String provider,String origin,String policyUserAgent){
            this.provider=provider; this.origin=origin; this.policyUserAgent=policyUserAgent;
        }
        String base(){return origin+"/v2";}
        String route(){return provider+"/fixture-model";}
    }
    static final Site[] SITES={
            new Site("workbuddy","https://copilot.tencent.com","WorkBuddy/5.9.3 WorkBuddy/5.9.3 CLI/2.165.0"),
            new Site("workbuddy-ai","https://www.workbuddy.ai","WorkBuddy/5.9.3 workbuddy-ai/5.9.3 CLI/2.165.0")
    };

    // ------------------------------------------------------------------ documents

    /** The engine's chat vocabulary, as a rule body. */
    static JSONObject chatHeaders(String userAgent,String product,String intent,String purpose,String type,
            String refreshSource){
        JSONObject h=new JSONObject();
        if(userAgent!=null)h.put("User-Agent",userAgent);
        if(product!=null)h.put("X-Product",product);
        if(intent!=null)h.put("X-Agent-Intent",intent);
        if(purpose!=null)h.put("X-Agent-Purpose",purpose);
        if(type!=null)h.put("X-Agent-Type",type);
        if(refreshSource!=null)h.put("X-Auth-Refresh-Source",refreshSource);
        return h;
    }
    /** The whole engine 1 chat set: engine 1 documents must stay exact, so a subset is not allowed. */
    static JSONObject fullChatHeaders(String userAgent,String product,String intent,String purpose,
            String type,String requestedWith,String refreshSource){
        JSONObject h=chatHeaders(userAgent,product,intent,purpose,type,refreshSource);
        h.put("X-Requested-With",requestedWith);
        return h;
    }
    static JSONObject rule(Site site,JSONObject headers){
        return new JSONObject().put("id",site.provider+"-chat-headers").put("provider",site.provider)
                .put("protocol","openai").put("origin",site.origin)
                .put("path","/v2/chat/completions").put("headers",headers);
    }
    /** Schema 1 / engine 1: the order this build has always used, and must not change. */
    static ProviderQuirks.Snapshot legacy(Site site,JSONObject headers)throws Exception{
        JSONObject root=new JSONObject().put("schemaVersion",1).put("minimumEngineVersion",1)
                .put("ruleVersion","5.0.0").put("rules",new JSONArray().put(rule(site,headers)));
        return ProviderQuirks.parse(root.toString().getBytes(StandardCharsets.UTF_8));
    }
    /** Schema 1 / engine 2: declares the newer order without needing an outputDefaults table. */
    static ProviderQuirks.Snapshot engineTwo(JSONObject... rules)throws Exception{
        JSONArray list=new JSONArray();
        for(JSONObject r:rules)list.put(r);
        JSONObject root=new JSONObject().put("schemaVersion",1).put("minimumEngineVersion",2)
                .put("ruleVersion","5.1.0").put("rules",list);
        return ProviderQuirks.parse(root.toString().getBytes(StandardCharsets.UTF_8));
    }
    static ProviderQuirks.Snapshot engineTwo(Site site,JSONObject headers)throws Exception{
        return engineTwo(rule(site,headers));
    }

    // ------------------------------------------------------------------ harness

    static final class Session implements AutoCloseable {
        final Site site;
        final VendorPolicyGatewayHttpTest.Gateway gateway;
        final VendorPolicyGatewayHttpTest.Upstream upstream;
        Session(Site site,VendorPolicyGatewayHttpTest.Upstream upstream,ProviderQuirks.Snapshot snapshot)throws Exception{
            this.site=site;
            this.upstream=upstream;
            this.gateway=new VendorPolicyGatewayHttpTest.Gateway(
                    VendorPolicyGatewayHttpTest.config(VendorPolicyGatewayHttpTest.provider(site.provider,
                            "openai",site.base(),"fixture-model")),upstream,false);
            ResponsesGatewayOfflineTest.set(gateway.gateway,"quirksSnapshot",snapshot);
        }
        VendorPolicyGatewayHttpTest.Result chat()throws Exception{
            return chat(null);
        }
        VendorPolicyGatewayHttpTest.Result chat(String session)throws Exception{
            return VendorPolicyGatewayHttpTest.post(gateway,"/v1/chat/completions",
                    VendorPolicyGatewayHttpTest.body(site.route(),false),session);
        }
        public void close()throws Exception{gateway.close();}
    }

    static VendorPolicyGatewayHttpTest.Upstream sseUpstream()throws Exception{
        return new VendorPolicyGatewayHttpTest.Upstream(
                (request,count)->new VendorPolicyGatewayHttpTest.Reply(200,"text/event-stream",
                        VendorPolicyGatewayHttpTest.SSE));
    }
    static VendorPolicyGatewayHttpTest.Request only(VendorPolicyGatewayHttpTest.Upstream upstream,String why){
        check(upstream.requests.size()==1,why+": exactly one physical send, got "+upstream.requests.size()
                +(upstream.failure==null?"":" failure="+upstream.failure));
        return upstream.requests.get(0);
    }
    static String header(Map<String,String> headers,String name){
        return headers.get(name);
    }

    // ------------------------------------------------------------------ engine 2

    /**
     * The rule wins for every name it names, on both sites. This is the behaviour the audit found
     * missing: before the fix every one of these was overwritten by {@code WorkBuddyPolicy}.
     */
    static void engineTwoRuleReachesBothSites()throws Exception{
        String ruleUserAgent="WorkBuddy/7.1.0 WorkBuddy/7.1.0 CLI/2.701.0";
        for(Site site:SITES){
            JSONObject h=chatHeaders(ruleUserAgent,"RuleProduct","review","analysis","sub","rule-refresh");
            h.put("X-Requested-With","RuleRequestedWith");
            ProviderQuirks.Snapshot snapshot=engineTwo(site,h);
            // The document really is engine 2 and really matches this binding.
            check(snapshot.minimumEngineVersion==2,"the fixture declares engine 2");
            check(snapshot.headers(site.provider,"openai",site.base()+"/chat/completions").size()==7,
                    "the fixture rule matches the chat binding: "
                    +snapshot.headers(site.provider,"openai",site.base()+"/chat/completions"));
            try(VendorPolicyGatewayHttpTest.Upstream upstream=sseUpstream();
                Session session=new Session(site,upstream,snapshot)){
                VendorPolicyGatewayHttpTest.Result response=session.chat();
                check(response.status==200,site.provider+": chat succeeds "+response.status+" "+response.body);
                VendorPolicyGatewayHttpTest.Request sent=only(upstream,site.provider);
                check("/v2/chat/completions".equals(sent.path),site.provider+": chat destination only: "+sent.path);
                // named by the rule -> the rule's value
                check(ruleUserAgent.equals(header(sent.headers,"User-Agent")),
                        site.provider+": rule user agent reaches the wire: "+header(sent.headers,"User-Agent"));
                check("RuleProduct".equals(header(sent.headers,"X-Product")),
                        site.provider+": rule product reaches the wire: "+header(sent.headers,"X-Product"));
                check("review".equals(header(sent.headers,"X-Agent-Intent")),
                        site.provider+": rule intent reaches the wire: "+header(sent.headers,"X-Agent-Intent"));
                check("analysis".equals(header(sent.headers,"X-Agent-Purpose")),
                        site.provider+": rule purpose reaches the wire: "+header(sent.headers,"X-Agent-Purpose"));
                check("sub".equals(header(sent.headers,"X-Agent-Type")),
                        site.provider+": rule agent type reaches the wire: "+header(sent.headers,"X-Agent-Type"));
                check("rule-refresh".equals(header(sent.headers,"X-Auth-Refresh-Source")),
                        site.provider+": rule refresh source reaches the wire: "+header(sent.headers,"X-Auth-Refresh-Source"));
                check("RuleRequestedWith".equals(header(sent.headers,"X-Requested-With")),
                        site.provider+": rule requested-with reaches the wire: "+header(sent.headers,"X-Requested-With"));
                // credential, account identity and dynamic session are not the rule's to touch
                check(("Bearer "+KEY).equals(header(sent.headers,"Authorization")),
                        site.provider+": the credential survives the rule: "+header(sent.headers,"Authorization"));
                check(KEY.equals(header(sent.headers,"X-API-Key")),
                        site.provider+": the api key header survives the rule: "+header(sent.headers,"X-API-Key"));
                check(header(sent.headers,"X-User-Id")!=null,
                        site.provider+": account identity is still written");
                String conversation=header(sent.headers,"X-Conversation-ID");
                check(conversation!=null && !conversation.isEmpty(),
                        site.provider+": conversation id is still written");
                check(header(sent.headers,"X-Conversation-Request-ID")!=null,
                        site.provider+": turn id is still written");
                check(header(sent.headers,"X-Request-ID")!=null,
                        site.provider+": request id is still written");
                // names the rule omitted keep the policy default
                check("local-agent".equals(header(sent.headers,"X-Agent-Runtime")),
                        site.provider+": an omitted name keeps the policy value: "+header(sent.headers,"X-Agent-Runtime"));
                check("WorkBuddy".equals(header(sent.headers,"X-IDE-Type")),
                        site.provider+": IDE type default kept: "+header(sent.headers,"X-IDE-Type"));
                check("5.9.3".equals(header(sent.headers,"X-IDE-Version")),
                        site.provider+": IDE version default kept: "+header(sent.headers,"X-IDE-Version"));
                check("application/json".equals(header(sent.headers,"Accept")),
                        site.provider+": chat accept default kept: "+header(sent.headers,"Accept"));
                check("identity".equals(header(sent.headers,"Accept-Encoding")),
                        site.provider+": transport encoding default kept: "+header(sent.headers,"Accept-Encoding"));
                check("0".equals(header(sent.headers,"X-Stainless-Retry-Count")),
                        site.provider+": client fingerprint defaults kept: "+header(sent.headers,"X-Stainless-Retry-Count"));
                // the body is untouched by any of this
                check("fixture-model".equals(sent.body.optString("model")),"the model is the resolved one");
                check(sent.body.optBoolean("stream"),"the chat body still forces upstream streaming");
            }
        }
    }

    /**
     * An engine 2 rule whose user agent happens to start with {@code CLI/2.} still wins: the legacy
     * carve-out only ever existed to keep the engine 1 order from dropping the policy user agent,
     * and in the newer order the rule is written last regardless.
     */
    static void engineTwoRuleSurvivesTheLegacyCarveOut()throws Exception{
        String ruleUserAgent="CLI/2.999.0 WorkBuddyAudit";
        for(Site site:SITES){
            ProviderQuirks.Snapshot snapshot=engineTwo(site,chatHeaders(ruleUserAgent,"AuditProduct",null,null,null,null));
            try(VendorPolicyGatewayHttpTest.Upstream upstream=sseUpstream();
                Session session=new Session(site,upstream,snapshot)){
                check(session.chat().status==200,site.provider+": chat succeeds");
                VendorPolicyGatewayHttpTest.Request sent=only(upstream,site.provider);
                check(ruleUserAgent.equals(header(sent.headers,"User-Agent")),
                        site.provider+": a CLI/2.-style rule agent still wins at engine 2: "
                        +header(sent.headers,"User-Agent"));
                check("AuditProduct".equals(header(sent.headers,"X-Product")),
                        site.provider+": its product value wins too: "+header(sent.headers,"X-Product"));
                check("craft".equals(header(sent.headers,"X-Agent-Intent")),
                        site.provider+": an omitted intent keeps the policy value: "
                        +header(sent.headers,"X-Agent-Intent"));
            }
        }
    }

    /** Two rules in one document: each site matches only its own row. */
    static void oneDocumentServesBothSitesIndependently()throws Exception{
        JSONObject first=rule(SITES[0],chatHeaders(null,"ProductOne",null,null,null,null));
        JSONObject second=rule(SITES[1],chatHeaders(null,"ProductTwo",null,null,null,null));
        ProviderQuirks.Snapshot both=engineTwo(first,second);
        check(both.ruleCount()==2,"both rows are in one document");
        for(int i=0;i<SITES.length;i++){
            Site site=SITES[i];
            String expected=i==0?"ProductOne":"ProductTwo";
            try(VendorPolicyGatewayHttpTest.Upstream upstream=sseUpstream();
                Session session=new Session(site,upstream,both)){
                check(session.chat().status==200,site.provider+": chat succeeds");
                VendorPolicyGatewayHttpTest.Request sent=only(upstream,site.provider);
                check(expected.equals(header(sent.headers,"X-Product")),
                        site.provider+": only its own row applies: "+header(sent.headers,"X-Product"));
                check(site.policyUserAgent.equals(header(sent.headers,"User-Agent")),
                        site.provider+": the other site's user agent does not leak: "
                        +header(sent.headers,"User-Agent"));
            }
        }
    }

    /**
     * The engine 1 order is kept byte for byte: a published document keeps restyling only what the
     * policy does not write (plus the user agent carve-out). Changing this would change what an
     * already signed document does.
     */
    static void legacyEngineOneOrderIsUnchanged()throws Exception{
        String legacyUserAgent="WorkBuddy/1.0 Legacy/1.0";
        for(Site site:SITES){
            JSONObject h=fullChatHeaders(legacyUserAgent,"LegacyProduct","legacy-intent","legacy-purpose",
                    "legacy-type","LegacyRequestedWith","legacy-refresh");
            ProviderQuirks.Snapshot snapshot=legacy(site,h);
            check(snapshot.minimumEngineVersion==1,"the fixture declares engine 1");
            try(VendorPolicyGatewayHttpTest.Upstream upstream=sseUpstream();
                Session session=new Session(site,upstream,snapshot)){
                check(session.chat().status==200,site.provider+": legacy chat succeeds");
                VendorPolicyGatewayHttpTest.Request sent=only(upstream,site.provider);
                // The carve-out keeps a non-CLI/2. rule agent, exactly as it always did.
                check(legacyUserAgent.equals(header(sent.headers,"User-Agent")),
                        site.provider+": engine 1 keeps the rule agent: "+header(sent.headers,"User-Agent"));
                // Everything else the policy writes still wins — the behaviour this fix leaves alone.
                check("SaaS".equals(header(sent.headers,"X-Product")),
                        site.provider+": engine 1 still lets the policy product win: "
                        +header(sent.headers,"X-Product"));
                check("craft".equals(header(sent.headers,"X-Agent-Intent")),
                        site.provider+": engine 1 still lets the policy intent win: "
                        +header(sent.headers,"X-Agent-Intent"));
                check("conversation".equals(header(sent.headers,"X-Agent-Purpose")),
                        site.provider+": engine 1 still lets the policy purpose win: "
                        +header(sent.headers,"X-Agent-Purpose"));
                check("main".equals(header(sent.headers,"X-Agent-Type")),
                        site.provider+": engine 1 still lets the policy type win: "
                        +header(sent.headers,"X-Agent-Type"));
                check("XMLHttpRequest".equals(header(sent.headers,"X-Requested-With")),
                        site.provider+": engine 1 still lets the policy requested-with win: "
                        +header(sent.headers,"X-Requested-With"));
                // A name the policy never writes was, and still is, the rule's.
                check("legacy-refresh".equals(header(sent.headers,"X-Auth-Refresh-Source")),
                        site.provider+": engine 1 keeps the field the policy does not write: "
                        +header(sent.headers,"X-Auth-Refresh-Source"));
                check(("Bearer "+KEY).equals(header(sent.headers,"Authorization")),
                        site.provider+": engine 1 credential unchanged");
            }
        }
    }

    /** The engine 1 carve-out itself is still there: a CLI/2.-style legacy agent is replaced. */
    static void legacyEngineOneStillDropsACliStyleAgent()throws Exception{
        for(Site site:SITES){
            ProviderQuirks.Snapshot snapshot=legacy(site,fullChatHeaders("CLI/2.165.0 legacy","LegacyProduct",
                    "legacy-intent","legacy-purpose","legacy-type","LegacyRequestedWith","legacy-refresh"));
            try(VendorPolicyGatewayHttpTest.Upstream upstream=sseUpstream();
                Session session=new Session(site,upstream,snapshot)){
                check(session.chat().status==200,site.provider+": legacy chat succeeds");
                VendorPolicyGatewayHttpTest.Request sent=only(upstream,site.provider);
                check(site.policyUserAgent.equals(header(sent.headers,"User-Agent")),
                        site.provider+": engine 1 replaces a CLI/2. agent as it always did: "
                        +header(sent.headers,"User-Agent"));
                check("SaaS".equals(header(sent.headers,"X-Product")),
                        site.provider+": and its product value is still overridden: "
                        +header(sent.headers,"X-Product"));
            }
        }
    }

    // ------------------------------------------------------------------ client fingerprint

    /**
     * The whole engine 2 chat vocabulary, one distinctive value per name: the seven v1 names plus
     * the static client fingerprint {@code WorkBuddyPolicy} writes for this route. Every value is
     * printable and short, which is all the writer asks of a rule.
     */
    static JSONObject fullFingerprintHeaders(){
        JSONObject h=chatHeaders("WorkBuddy/8.0.0 WorkBuddy/8.0.0 CLI/2.800.0","RuleProduct","review",
                "analysis","sub","rule-refresh");
        h.put("X-Requested-With","RuleRequestedWith");
        h.put("X-IDE-Type","RuleIDE");
        h.put("X-IDE-Name","RuleIDEName");
        h.put("X-IDE-Version","9.9.9");
        h.put("X-Agent-Runtime","rule-runtime");
        h.put("Accept","application/vnd.rule+json");
        h.put("X-Stainless-Retry-Count","7");
        h.put("X-Stainless-Timeout","99");
        h.put("X-Stainless-Lang","rust");
        h.put("X-Stainless-Package-Version","1.2.3");
        h.put("X-Stainless-OS","Android");
        h.put("X-Stainless-Arch","x86_64");
        h.put("X-Stainless-Runtime","bun");
        h.put("X-Stainless-Runtime-Version","v1.0.0");
        return h;
    }

    /**
     * Every name a rule may restyle now arrives at the vendor with the rule's value, on both sites;
     * the credential, the session identity and the content framing keep the gateway's own values.
     * This is the contract the writer's allowlist ({@code CHAT_RULE_HEADERS}) has to match: a name
     * the parser accepts but the writer drops would be a signed rule that silently does nothing.
     */
    static void engineTwoFullClientFingerprintReachesTheWire()throws Exception{
        for(Site site:SITES){
            JSONObject h=fullFingerprintHeaders();
            ProviderQuirks.Snapshot snapshot=engineTwo(site,h);
            check(snapshot.minimumEngineVersion==2,"the fixture declares engine 2");
            check(snapshot.headers(site.provider,"openai",site.base()+"/chat/completions").size()==h.length(),
                    site.provider+": the whole fingerprint rule matches the chat binding: "
                    +snapshot.headers(site.provider,"openai",site.base()+"/chat/completions"));
            try(VendorPolicyGatewayHttpTest.Upstream upstream=sseUpstream();
                Session session=new Session(site,upstream,snapshot)){
                VendorPolicyGatewayHttpTest.Result response=session.chat();
                check(response.status==200,site.provider+": chat succeeds "+response.status+" "+response.body);
                VendorPolicyGatewayHttpTest.Request sent=only(upstream,site.provider);
                java.util.Iterator<String> names=h.keys();
                while(names.hasNext()){
                    String name=names.next();
                    check(h.getString(name).equals(header(sent.headers,name)),
                            site.provider+": rule "+name+" reaches the wire: "+header(sent.headers,name)
                            +" (wanted "+h.getString(name)+")");
                }
                // Everything below is outside the vocabulary, so it is still the gateway's to write.
                check(("Bearer "+KEY).equals(header(sent.headers,"Authorization")),
                        site.provider+": the credential is still the key: "+header(sent.headers,"Authorization"));
                check(KEY.equals(header(sent.headers,"X-API-Key")),
                        site.provider+": the api key header is still the key: "+header(sent.headers,"X-API-Key"));
                check(header(sent.headers,"X-User-Id")!=null,site.provider+": account identity is still written");
                String conversation=header(sent.headers,"X-Conversation-ID");
                check(conversation!=null && !conversation.isEmpty(),
                        site.provider+": conversation id is still written");
                check(header(sent.headers,"X-Conversation-Request-ID")!=null,
                        site.provider+": turn id is still written");
                check(header(sent.headers,"X-Request-ID")!=null,site.provider+": request id is still written");
                check("application/json".equals(header(sent.headers,"Content-Type")),
                        site.provider+": content type stays the transport's: "+header(sent.headers,"Content-Type"));
                check("gzip".equals(header(sent.headers,"Content-Encoding")),
                        site.provider+": the body is still the gateway's gzip: "
                        +header(sent.headers,"Content-Encoding"));
                check("identity".equals(header(sent.headers,"Accept-Encoding")),
                        site.provider+": accept-encoding stays the policy's: "
                        +header(sent.headers,"Accept-Encoding"));
                check(!sent.headers.containsKey("Transfer-Encoding"),
                        site.provider+": framing is still a fixed content length");
                check("fixture-model".equals(sent.body.optString("model")),site.provider+": model unchanged");
                check(sent.body.optBoolean("stream"),site.provider+": upstream streaming still forced");
            }
        }
    }

    // ------------------------------------------------------------------ retry

    /**
     * The refusal retry keeps working under the newer order: the second physical send is the same
     * logical request, so it carries the same rule values and the same conversation identity.
     */
    static void retryKeepsTheRuleAndTheConversation()throws Exception{
        Site site=SITES[0];
        String ruleUserAgent="WorkBuddy/7.2.0 WorkBuddy/7.2.0 CLI/2.702.0";
        ProviderQuirks.Snapshot snapshot=engineTwo(site,chatHeaders(ruleUserAgent,"RetryProduct",
                "retry-intent",null,null,null));
        final List<VendorPolicyGatewayHttpTest.Request> seen=Collections.synchronizedList(
                new ArrayList<VendorPolicyGatewayHttpTest.Request>());
        try(VendorPolicyGatewayHttpTest.Upstream upstream=new VendorPolicyGatewayHttpTest.Upstream(
                (request,count)->{
                    seen.add(request);
                    // The vendor's "unapproved channel" refusal is a known rejection, so asking again
                    // with the comma-normalised history cannot duplicate an executed turn.
                    if(count==1)return new VendorPolicyGatewayHttpTest.Reply(400,"application/json",
                            "{\"error\":{\"message\":\"unapproved channel\",\"code\":\"40001\"}}");
                    return new VendorPolicyGatewayHttpTest.Reply(200,"text/event-stream",
                            VendorPolicyGatewayHttpTest.SSE);
                })) {
            try(Session session=new Session(site,upstream,snapshot)){
                // A comma in the history is what the compatibility rewrite has to act on.
                JSONObject body=new JSONObject().put("model","workbuddy/fixture-model").put("stream",false)
                        .put("max_tokens",100)
                        .put("messages",new JSONArray()
                                .put(new JSONObject().put("role","system")
                                        .put("content","Answer politely, and briefly."))
                                .put(new JSONObject().put("role","user").put("content","hello")));
                VendorPolicyGatewayHttpTest.Result response=VendorPolicyGatewayHttpTest.post(
                        session.gateway,"/v1/chat/completions",body,null);
                check(response.status==200,"the refusal is recovered: "+response.status+" "+response.body);
                check(seen.size()==2,"one refusal and one resend, got "+seen.size());
                VendorPolicyGatewayHttpTest.Request first=seen.get(0), second=seen.get(1);
                for(int i=0;i<2;i++){
                    VendorPolicyGatewayHttpTest.Request sent=i==0?first:second;
                    check(ruleUserAgent.equals(header(sent.headers,"User-Agent")),
                            "send "+(i+1)+" carries the rule agent: "+header(sent.headers,"User-Agent"));
                    check("RetryProduct".equals(header(sent.headers,"X-Product")),
                            "send "+(i+1)+" carries the rule product: "+header(sent.headers,"X-Product"));
                    check("retry-intent".equals(header(sent.headers,"X-Agent-Intent")),
                            "send "+(i+1)+" carries the rule intent: "+header(sent.headers,"X-Agent-Intent"));
                    check(("Bearer "+KEY).equals(header(sent.headers,"Authorization")),
                            "send "+(i+1)+" keeps the credential");
                    check("local-agent".equals(header(sent.headers,"X-Agent-Runtime")),
                            "send "+(i+1)+" keeps the omitted default");
                }
                String firstConversation=header(first.headers,"X-Conversation-ID");
                String secondConversation=header(second.headers,"X-Conversation-ID");
                check(firstConversation!=null && firstConversation.equals(secondConversation),
                        "the resend keeps the conversation identity: "+firstConversation+" vs "+secondConversation);
                String firstTurn=header(first.headers,"X-Conversation-Request-ID");
                String secondTurn=header(second.headers,"X-Conversation-Request-ID");
                check(firstTurn!=null && firstTurn.equals(secondTurn),
                        "the resend keeps the turn identity: "+firstTurn+" vs "+secondTurn);
                check(!first.body.toString().equals(second.body.toString()),
                        "the resend really is the rewritten body, so the retry path ran");
            }
        }
    }

    // ------------------------------------------------------------------ vocabulary guard

    /**
     * A chat rule cannot name a credential, a dynamic session value or a content framing header at
     * all, so the newer write order has nothing sensitive to overwrite. The parser is the first
     * half of that guarantee; {@link #writerAllowlistMatchesTheChatVocabulary()} and
     * {@link #writerRefusesSensitiveAndMalformedHeaders()} are the other half.
     */
    static void chatRuleVocabularyExcludesCredentialsAndSession()throws Exception{
        for(String name:new String[]{"Authorization","Proxy-Authorization","Cookie","Set-Cookie",
                "X-API-Key","X-User-Id","X-Domain","X-Conversation-ID","X-Conversation-Request-ID",
                "X-Request-ID","Host","Content-Type","Content-Length","Content-Encoding",
                "Transfer-Encoding","Accept-Encoding","anthropic-version","X-Forwarded-For"}){
            boolean rejected=false;
            try{
                for(Site site:SITES){
                    JSONObject h=chatHeaders("WorkBuddy/7.0.0 CLI/2.700.0","Product",null,null,null,null);
                    h.put(name,"attacker");
                    engineTwo(site,h);
                }
            }catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"an engine 2 chat rule naming "+name+" is refused whole");
        }
    }

    /**
     * Engine 1 keeps the historic exact set. A published document restyles the seven names it was
     * signed for and only those, so naming a newer name is refused whole: the older order can never
     * silently gain a name it did not have when it shipped.
     */
    static void engineOneVocabularyStaysTheHistoricSet()throws Exception{
        for(Site site:SITES)
            for(String name:new String[]{"X-IDE-Type","X-IDE-Name","X-IDE-Version","X-Agent-Runtime",
                    "Accept","X-Stainless-Retry-Count","X-Stainless-Lang"}){
                boolean rejected=false;
                try{
                    JSONObject h=fullChatHeaders("WorkBuddy/1.0 Legacy/1.0","LegacyProduct","legacy-intent",
                            "legacy-purpose","legacy-type","LegacyRequestedWith","legacy-refresh");
                    h.put(name,"attacker");
                    legacy(site,h);
                }catch(IllegalArgumentException expected){rejected=true;}
                check(rejected,site.provider+": engine 1 refuses a document naming "+name);
            }
    }

    /** The writer's ceiling, spelled out: exactly the chat vocabulary and not one name more. */
    static void writerAllowlistMatchesTheChatVocabulary()throws Exception{
        java.lang.reflect.Field field=GatewayServer.class.getDeclaredField("CHAT_RULE_HEADERS");
        field.setAccessible(true);
        java.util.Set<?> allowed=(java.util.Set<?>)field.get(null);
        java.util.Set<String> expected=new java.util.LinkedHashSet<>(java.util.Arrays.asList(
                "accept","user-agent","x-requested-with","x-product","x-ide-type","x-ide-name",
                "x-ide-version","x-agent-intent","x-agent-purpose","x-agent-type","x-agent-runtime",
                "x-auth-refresh-source","x-stainless-retry-count","x-stainless-timeout","x-stainless-lang",
                "x-stainless-package-version","x-stainless-os","x-stainless-arch","x-stainless-runtime",
                "x-stainless-runtime-version"));
        check(allowed.equals(expected),"the writer vocabulary is exactly the chat vocabulary: "+allowed);
        for(String name:new String[]{"authorization","proxy-authorization","cookie","set-cookie","x-api-key",
                "x-user-id","x-domain","x-conversation-id","x-conversation-request-id","x-request-id",
                "anthropic-version","x-forwarded-for","host","connection","content-type","content-length",
                "content-encoding","transfer-encoding","accept-encoding"})
            check(!allowed.contains(name),"the writer vocabulary never carries "+name);
    }

    /**
     * The second half of the contract, exercised on a real {@code HttpURLConnection}: a map that
     * somehow names a credential, a session value or a framing header writes none of them, and a
     * value the wire could not carry is dropped rather than truncated or escaped.
     */
    static void writerRefusesSensitiveAndMalformedHeaders()throws Exception{
        java.lang.reflect.Method apply=GatewayServer.class.getDeclaredMethod("applyChatRuleHeaders",
                java.net.HttpURLConnection.class,java.util.Map.class);
        apply.setAccessible(true);
        java.net.HttpURLConnection conn=(java.net.HttpURLConnection)
                new java.net.URL("http://127.0.0.1:1/fixture").openConnection();
        java.util.Map<String,String> wanted=new java.util.LinkedHashMap<>();
        wanted.put("X-IDE-Type","RuleIDE");
        wanted.put("X-Stainless-Lang","rust");
        wanted.put("Accept","application/vnd.rule+json");
        // Sensitive: none of these is in the vocabulary, so none may be written even if a document
        // ever managed to carry one.
        wanted.put("Authorization","Bearer attacker");
        wanted.put("Proxy-Authorization","Basic attacker");
        wanted.put("Cookie","session=attacker");
        wanted.put("X-API-Key","attacker");
        wanted.put("X-User-Id","attacker");
        wanted.put("X-Domain","attacker");
        wanted.put("X-Conversation-ID","attacker");
        wanted.put("X-Conversation-Request-ID","attacker");
        wanted.put("X-Request-ID","attacker");
        wanted.put("anthropic-version","1999-01-01");
        wanted.put("Accept-Encoding","gzip");
        wanted.put("X-Forwarded-For","attacker");
        wanted.put("Content-Type","text/plain");
        wanted.put("Content-Encoding","identity");
        apply.invoke(null,conn,wanted);
        for(String name:new String[]{"Authorization","Proxy-Authorization","Cookie","X-API-Key","X-User-Id",
                "X-Domain","X-Conversation-ID","X-Conversation-Request-ID","X-Request-ID","anthropic-version",
                "Accept-Encoding","X-Forwarded-For","Content-Type","Content-Encoding"})
            check(conn.getRequestProperty(name)==null,
                    "the writer refuses "+name+": "+conn.getRequestProperty(name));
        check("RuleIDE".equals(conn.getRequestProperty("X-IDE-Type")),
                "an allowed name is written: "+conn.getRequestProperty("X-IDE-Type"));
        check("rust".equals(conn.getRequestProperty("X-Stainless-Lang")),
                "an allowed name is written: "+conn.getRequestProperty("X-Stainless-Lang"));
        check("application/vnd.rule+json".equals(conn.getRequestProperty("Accept")),
                "an allowed name is written: "+conn.getRequestProperty("Accept"));
        java.util.Map<String,String> broken=new java.util.LinkedHashMap<>();
        broken.put("User-Agent","");
        broken.put("X-Product","line\r\nbreak");
        StringBuilder longValue=new StringBuilder();
        for(int i=0;i<300;i++)longValue.append('a');
        broken.put("X-Agent-Type",longValue.toString());
        java.net.HttpURLConnection second=(java.net.HttpURLConnection)
                new java.net.URL("http://127.0.0.1:1/fixture").openConnection();
        apply.invoke(null,second,broken);
        check(second.getRequestProperty("User-Agent")==null,"an empty value is not written");
        check(second.getRequestProperty("X-Product")==null,"a value with control characters is not written");
        check(second.getRequestProperty("X-Agent-Type")==null,"an over-long value is not written");
    }

    public static void main(String[] args)throws Exception{
        engineTwoRuleReachesBothSites();
        engineTwoRuleSurvivesTheLegacyCarveOut();
        engineTwoFullClientFingerprintReachesTheWire();
        oneDocumentServesBothSitesIndependently();
        legacyEngineOneOrderIsUnchanged();
        legacyEngineOneStillDropsACliStyleAgent();
        engineOneVocabularyStaysTheHistoricSet();
        retryKeepsTheRuleAndTheConversation();
        chatRuleVocabularyExcludesCredentialsAndSession();
        writerAllowlistMatchesTheChatVocabulary();
        writerRefusesSensitiveAndMalformedHeaders();
        System.out.println("WorkBuddyChatRuleOrderingHttpTest OK (engine 2 rules reach both sites with the "
                +"full client fingerprint, omitted names and credentials keep the policy values, the writer "
                +"refuses sensitive and malformed headers, engine 1 order and vocabulary unchanged, retry intact)");
    }
}
