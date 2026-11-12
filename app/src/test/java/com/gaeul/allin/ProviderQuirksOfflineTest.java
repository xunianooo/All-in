package com.gaeul.allin;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public class ProviderQuirksOfflineTest {
    static void check(boolean x){if(!x)throw new AssertionError();}
    static void reject(String s)throws Exception{try{ProviderQuirks.parse(s.getBytes(StandardCharsets.UTF_8));throw new AssertionError("accepted: "+s);}catch(IllegalArgumentException|com.gaeul.allin.json.JSONException expected){}}
    static final String WB_CHAT="https://copilot.tencent.com/v2/chat/completions";
    static final String WB_NATIVE="https://copilot.tencent.com/v1/messages";
    static final String CHAT_ALL="{\"User-Agent\":\"A\",\"X-Requested-With\":\"XMLHttpRequest\",\"X-Product\":\"SaaS\","
            +"\"X-Agent-Intent\":\"craft\",\"X-Agent-Purpose\":\"conversation\",\"X-Agent-Type\":\"main\",\"X-Auth-Refresh-Source\":\"workbuddy\"}";
    static final String NATIVE_ALL="{\"User-Agent\":\"WorkBuddy/5.9.3 CLI/2.165.0\",\"X-Requested-With\":\"XMLHttpRequest\","
            +"\"X-Product\":\"SaaS\",\"X-IDE-Type\":\"WorkBuddy\",\"X-IDE-Name\":\"WorkBuddy\",\"X-IDE-Version\":\"5.9.3\","
            +"\"X-Agent-Intent\":\"craft\",\"X-Agent-Purpose\":\"conversation\",\"X-Agent-Type\":\"main\","
            +"\"X-Agent-Runtime\":\"local-agent\",\"Accept\":\"text/event-stream\"}";
    /** The engine 2 addition: the static SDK block, exactly as WorkBuddyPolicy writes it. */
    static final String FINGERPRINT_STATIC="\"X-Stainless-Lang\":\"js\",\"X-Stainless-Package-Version\":\"6.25.0\","
            +"\"X-Stainless-OS\":\"Linux\",\"X-Stainless-Arch\":\"arm64\",\"X-Stainless-Runtime\":\"node\","
            +"\"X-Stainless-Runtime-Version\":\"v22.21.1\",\"X-Stainless-Retry-Count\":\"0\","
            +"\"X-Stainless-Timeout\":\"1200\"";
    /** The full nineteen-name native rule: the shipped eleven plus the static SDK block. */
    static final String NATIVE_ALL_V2=NATIVE_ALL.replace("\"Accept\":\"text/event-stream\"",
            "\"Accept\":\"text/event-stream\","+FINGERPRINT_STATIC);
    /** The chat additions: the IDE block, the agent runtime and the response type. */
    static final String CHAT_FINGERPRINT_EXTRA="\"X-IDE-Type\":\"WorkBuddy\",\"X-IDE-Name\":\"WorkBuddy\","
            +"\"X-IDE-Version\":\"5.9.3\",\"X-Agent-Runtime\":\"local-agent\",\"Accept\":\"application/json\"";
    /** The full twenty-name chat rule: the frozen seven plus every static name the writer applies. */
    static final String CHAT_ALL_V2=CHAT_ALL.replace("\"X-Auth-Refresh-Source\":\"workbuddy\"",
            "\"X-Auth-Refresh-Source\":\"workbuddy\","+CHAT_FINGERPRINT_EXTRA+","+FINGERPRINT_STATIC);
    static String rule(String id,String provider,String protocol,String origin,String path,String headers){
        return "{\"id\":\""+id+"\",\"provider\":\""+provider+"\",\"protocol\":\""+protocol+"\",\"origin\":\""+origin
                +"\",\"path\":\""+path+"\",\"headers\":"+headers+"}";
    }
    static String chat(String id){return rule(id,"workbuddy","openai","https://copilot.tencent.com","/v2/chat/completions",CHAT_ALL);}
    static String chat(String id,String headers){return rule(id,"workbuddy","openai","https://copilot.tencent.com","/v2/chat/completions",headers);}
    static String nativeRule(String id,String headers){return rule(id,"workbuddy","anthropic","https://copilot.tencent.com","/v1/messages",headers);}
    static String doc(String engineToken,String rules){
        return "{\"schemaVersion\":1,\"minimumEngineVersion\":"+engineToken+",\"ruleVersion\":\"2.0.0\",\"rules\":["+rules+"]}";
    }
    /** Schema 2 document: rules plus outputDefaults, always at engine 2. */
    static String doc2(String rules,String defaults){
        return "{\"schemaVersion\":2,\"minimumEngineVersion\":2,\"ruleVersion\":\"2.1.0\",\"rules\":["+rules+"],\"outputDefaults\":["+defaults+"]}";
    }
    static String out(String provider,String model,String maxOutput){
        return "{\"provider\":\""+provider+"\",\"model\":\""+model+"\",\"maxOutput\":"+maxOutput+"}";
    }
    public static void main(String[] args)throws Exception {
        byte[] b=Files.readAllBytes(Paths.get(args.length==0?(Files.exists(Paths.get("src/main/assets/default_quirks.json"))?"src/main/assets/default_quirks.json":"app/src/main/assets/default_quirks.json"):args[0])); String s=new String(b,StandardCharsets.UTF_8);
        // ---- v1 baseline: the shipped asset and every previously signed shape ----
        ProviderQuirks.Snapshot q=ProviderQuirks.parse(b); check(q.ruleCount()==3 && q.ruleVersion.equals("1.0.2"));
        check(q.schemaVersion==1 && q.engineVersion==2 && q.minimumEngineVersion==1);
        String url=WB_CHAT;
        Map<String,String> h=q.headers("workbuddy","openai",url); check(h.size()==7 && h.get("X-Product").equals("SaaS"));
        check(!h.containsKey("X-Conversation-ID"));
        check("WorkBuddy/5.9.3 CLI/2.165.0".equals(h.get("User-Agent")));
        check("WorkBuddy/5.9.3 workbuddy-ai/5.9.3 CLI/2.165.0".equals(q.headers("workbuddy-ai","openai","https://www.workbuddy.ai/v2/chat/completions").get("User-Agent")));
        String legacy=s.replace("1.0.2","1.0.1").replace("\"X-Agent-Type\": \"main\"", "\"X-Conversation-ID\": \"00000000-0000-0000-0000-000000000000\", \"X-Agent-Type\": \"main\"");
        check(ProviderQuirks.parse(legacy.getBytes(StandardCharsets.UTF_8)).headers("workbuddy","openai",url).containsKey("X-Conversation-ID"));
        reject(s.replace("\"X-Product\": \"SaaS\",", ""));
        reject(s.replace("\"X-Product\": \"SaaS\"", "\"X-Product\": \"SaaS\", \"Accept\": \"application/json\""));
        try{h.put("X-Product","bad");throw new AssertionError();}catch(UnsupportedOperationException expected){}
        ProviderQuirks.Snapshot changed=ProviderQuirks.parse(s.replace("WorkBuddy/5.9.3 CLI/2.165.0","CLI/test-static").getBytes(StandardCharsets.UTF_8));
        check("CLI/test-static".equals(changed.headers("workbuddy","openai",url).get("User-Agent")));
        reject(s.replace("1.0.2","made-up"));
        check(q.headers("workbuddy-ai","openai","https://www.workbuddy.ai/v2/chat/completions").size()==7);
        check(q.headers("raccoon","openai","https://xiaohuanxiong.com/api/web/llm/v2/chat/completions").size()==2);
        for(String u:Arrays.asList(url+"?a=1",url+"/", "http://copilot.tencent.com/v2/chat/completions", "https://copilot.tencent.com.evil/v2/chat/completions", "https://user@copilot.tencent.com/v2/chat/completions", "https://copilot.tencent.com:444/v2/chat/completions")) check(q.headers("workbuddy","openai",u).isEmpty());
        check(q.headers("workbuddy-extra","openai",url).isEmpty()); check(q.headers("workbuddy","anthropic",url).isEmpty());
        reject(s.replace("\"schemaVersion\": 1","\"schemaVersion\": 2"));
        reject(s.replace("\"X-Product\": \"SaaS\"","\"Authorization\": \"secret\""));
        reject(s.replace("\"SaaS\"","\"${key}\""));
        reject(s.replace("\"schemaVersion\": 1,","\"schemaVersion\": 1, \"schemaVersion\": 1,"));
        reject(s.replace("\"schemaVersion\": 1,","\"schemaVersion\": 1, \"unknown\": 1,"));
        reject(s.replace("https://copilot.tencent.com","https://evil.example"));
        reject(s.replace("\"schemaVersion\"", "'schemaVersion'"));
        reject(s+" false"); reject(new String(new char[33000]).replace('\0',' '));
        // ---- engine version: 1..2 accepted, everything else refused ----
        check(ProviderQuirks.parse(doc("1",chat("c1")).getBytes(StandardCharsets.UTF_8)).minimumEngineVersion==1);
        check(ProviderQuirks.parse(doc("2",chat("c1")).getBytes(StandardCharsets.UTF_8)).minimumEngineVersion==2);
        for(String bad:new String[]{"0","3","1.0","2.0","\"2\"","\"one\"","true","null","-1"})
            reject(doc(bad,chat("c1")));
        // ---- v2: the native Messages tuple is a v2 feature only ----
        reject(doc("1",nativeRule("wb-native",NATIVE_ALL)));
        ProviderQuirks.Snapshot v2=ProviderQuirks.parse(doc("2",nativeRule("wb-native",NATIVE_ALL)).getBytes(StandardCharsets.UTF_8));
        check(v2.minimumEngineVersion==2 && v2.engineVersion==2 && v2.ruleCount()==1);
        Map<String,String> nat=v2.headers("workbuddy","anthropic",WB_NATIVE);
        check(nat.size()==11 && "text/event-stream".equals(nat.get("Accept")));
        check("WorkBuddy/5.9.3 CLI/2.165.0".equals(nat.get("User-Agent")));
        check("WorkBuddy".equals(nat.get("X-IDE-Type")));
        check(v2.headers("workbuddy","openai",url).isEmpty());
        check(v2.headers("workbuddy","anthropic",url).isEmpty());
        for(String u:Arrays.asList(WB_NATIVE+"?a=1",WB_NATIVE+"/","http://copilot.tencent.com/v1/messages",
                "https://copilot.tencent.com.evil/v1/messages","https://u@copilot.tencent.com/v1/messages",
                "https://copilot.tencent.com:444/v1/messages","https://www.workbuddy.ai/v1/messages"))
            check(v2.headers("workbuddy","anthropic",u).isEmpty());
        // subset of the native allowlist, still engine 2
        ProviderQuirks.Snapshot natSub=ProviderQuirks.parse(doc("2",nativeRule("wb-native-sub","{\"User-Agent\":\"CLI/2.1\",\"Accept\":\"text/event-stream\"}")).getBytes(StandardCharsets.UTF_8));
        check(natSub.headers("workbuddy","anthropic",WB_NATIVE).size()==2);
        // The live gateway consults exactly ("workbuddy","anthropic","/v1/messages"); the chat path
        // pins WorkBuddy to /v2/chat/completions, so an OpenAI-protocol row for /v1/messages is
        // unreachable and must not be accepted: it would parse and then silently never apply.
        reject(doc("2",rule("wb-native-openai","workbuddy","openai","https://copilot.tencent.com","/v1/messages",NATIVE_ALL)));
        reject(doc("1",rule("wb-native-anthropic","workbuddy","anthropic","https://copilot.tencent.com","/v1/messages",NATIVE_ALL)));
        // one destination, one binding: the native rule never answers a chat lookup
        check(!v2.headers("workbuddy","anthropic",WB_NATIVE).isEmpty());
        check(v2.headers("workbuddy","anthropic",url).isEmpty());
        // credentials, session identity, transport names, and non-allowlisted names are refused
        for(String bad:new String[]{"\"Authorization\":\"Bearer x\"","\"authorization\":\"Bearer x\"",
                "\"AUTHORIZATION\":\"Bearer x\"","\"X-API-Key\":\"k\"","\"Proxy-Authorization\":\"x\"",
                "\"Cookie\":\"s=1\"","\"X-User-Id\":\"1\"","\"X-Domain\":\"d\"",
                "\"X-Conversation-ID\":\"00000000-0000-0000-0000-000000000000\"",
                "\"X-Conversation-Request-ID\":\"1\"","\"X-Request-ID\":\"1\"","\"Host\":\"evil\"",
                "\"Content-Length\":\"0\"","\"anthropic-version\":\"2023-06-01\"",
                "\"X-Stainless-Helper-Method\":\".stream()\"","\"Accept-Encoding\":\"identity\"",
                "\"X-Stainless-Encoding\":\"gzip\"","\"Content-Encoding\":\"gzip\"",
                "\"anthropic-beta\":\"x\"","\"user-agent\":\"CLI/2.1\""})
            reject(doc("2",nativeRule("wb-bad","{\"User-Agent\":\"CLI/2.1\","+bad+"}")));
        reject(doc("2",nativeRule("wb-empty","{}")));
        reject(doc("2",nativeRule("wb-dollar","{\"User-Agent\":\"${ua}\"}")));
        reject(doc("2",nativeRule("wb-brace","{\"User-Agent\":\"{{ua}}\"}")));
        reject(doc("2",nativeRule("wb-ctl","{\"User-Agent\":\"a\\u0007b\"}")));
        reject(doc("2",nativeRule("wb-long","{\"User-Agent\":\""+new String(new char[257]).replace('\0','A')+"\"}")));
        reject(doc("2",nativeRule("wb-tab","{\"User-Agent\":\"a\\tb\"}")));
        reject(doc("2",rule("wb-native-evil","workbuddy","openai","https://evil.example","/v1/messages",NATIVE_ALL)));
        reject(doc("2",rule("wb-native-ai","workbuddy-ai","openai","https://copilot.tencent.com","/v1/messages",NATIVE_ALL)));
        reject(doc("2",rule("wb-native-other","workbuddy","openai","https://copilot.tencent.com","/v1/message",NATIVE_ALL)));
        // ---- v2: chat tuples keep their exact v1 name sets, subsets need engine 2 ----
        reject(doc("1",chat("c-sub","{\"User-Agent\":\"A\",\"X-Requested-With\":\"XMLHttpRequest\",\"X-Agent-Intent\":\"craft\",\"X-Agent-Purpose\":\"conversation\",\"X-Agent-Type\":\"main\",\"X-Auth-Refresh-Source\":\"workbuddy\"}")));
        ProviderQuirks.Snapshot v2chat=ProviderQuirks.parse(doc("2",chat("c-sub","{\"User-Agent\":\"A\",\"X-Requested-With\":\"XMLHttpRequest\",\"X-Agent-Intent\":\"craft\",\"X-Agent-Purpose\":\"conversation\",\"X-Agent-Type\":\"main\",\"X-Auth-Refresh-Source\":\"workbuddy\"}")).getBytes(StandardCharsets.UTF_8));
        Map<String,String> chatSub=v2chat.headers("workbuddy","openai",url);
        check(chatSub.size()==6 && !chatSub.containsKey("X-Product") && "A".equals(chatSub.get("User-Agent")));
        check(ProviderQuirks.parse(doc("2",chat("c-all")).getBytes(StandardCharsets.UTF_8)).headers("workbuddy","openai",url).size()==7);
        reject(doc("2",chat("c-id","{\"User-Agent\":\"A\",\"X-Conversation-ID\":\"00000000-0000-0000-0000-000000000000\"}")));
        // Raccoon stays all-or-nothing: the gateway's fallback for that binding is keyed on the
        // rule map being empty, so a partial rule would suppress it and leave the rest of the
        // fingerprint at the JDK default. Both names, or no rule, exactly as in v1.
        reject(doc("2",rule("r-sub","raccoon","openai","https://xiaohuanxiong.com","/api/web/llm/v2/chat/completions","{\"Accept\":\"text/event-stream\"}")));
        reject(doc("2",rule("r-sub","raccoon","openai","https://xiaohuanxiong.com","/api/web/llm/v2/chat/completions","{\"User-Agent\":\"Mozilla/5.0\"}")));
        check(ProviderQuirks.parse(doc("2",rule("r-all","raccoon","openai","https://xiaohuanxiong.com","/api/web/llm/v2/chat/completions",
                "{\"User-Agent\":\"Mozilla/5.0\",\"Accept\":\"text/event-stream\"}")).getBytes(StandardCharsets.UTF_8))
                .headers("raccoon","openai","https://xiaohuanxiong.com/api/web/llm/v2/chat/completions").size()==2);
        // ---- structure, uniqueness, and bounds ----
        reject(doc("2",nativeRule("dup",NATIVE_ALL)+","+nativeRule("dup",NATIVE_ALL)));
        reject(doc("2",nativeRule("dupA",NATIVE_ALL)+","+nativeRule("dupB",NATIVE_ALL)));
        // 33 rules is over MAX_RULES; the exact-tuple table has fewer than 33 rows, so the count
        // bound is the only thing standing between a document and an unbounded rule list
        StringBuilder many=new StringBuilder();
        for(int i=0;i<33;i++){ if(i>0)many.append(','); many.append(chat("c"+i)); }
        reject(doc("2",many.toString()));
        ProviderQuirks.Snapshot mixed=ProviderQuirks.parse(doc("2",chat("c1")+","+nativeRule("n1",NATIVE_ALL)).getBytes(StandardCharsets.UTF_8));
        check(mixed.ruleCount()==2 && mixed.headers("workbuddy","openai",url).size()==7
                && mixed.headers("workbuddy","anthropic",WB_NATIVE).size()==11);
        // ---- the reserved-name guard itself ----
        for(String name:new String[]{"Authorization","authorization","AUTHORIZATION","Proxy-Authorization","Cookie",
                "Set-Cookie","X-API-Key","x-api-key","Api-Key","Host","Content-Length","Content-Type","Content-Encoding",
                "Transfer-Encoding","Connection","X-Forwarded-For","X-User-Id","X-Domain","X-Request-ID",
                "X-Conversation-ID","X-Conversation-Request-ID","X-Conversation-Message-ID","anthropic-version"})
            check(ProviderQuirks.isReservedHeaderName(name));
        for(String name:new String[]{"User-Agent","Accept","X-Requested-With","X-Product","X-IDE-Version",
                "X-Agent-Intent","X-Agent-Purpose","X-Agent-Type","X-Agent-Runtime","X-Auth-Refresh-Source",
                "X-Stainless-Lang","X-Stainless-Package-Version","X-Stainless-OS","X-Stainless-Arch",
                "X-Stainless-Runtime","X-Stainless-Runtime-Version"})
            check(!ProviderQuirks.isReservedHeaderName(name));
        // ---- immutable snapshots and event-driven source handoff still work ----
        check(natSub.withSource("verified-update").headers("workbuddy","anthropic",WB_NATIVE).size()==2);
        check(q.outputDefaultCount()==0 && q.outputDefault("workbuddy","deepseek-v3")==0);
        // The native consumer filters through its own copy of the allowlist in
        // WorkBuddyNativeTransport.RULE_HEADERS. Pinning the exact 17 names here means a future
        // edit to NATIVE_HEADERS cannot silently create a rule the transport would drop, and vice
        // versa. The chat consumer's copy lives in GatewayServer.CHAT_RULE_HEADERS and is still the
        // original seven, so chatRuleVocabularyStaysAtSeven() pins that side instead.
        consumerContract();
        staticFingerprintExtension();
        encodingPins();
        versionGrammar();
        schemaTwo(); schemaTwoRefusals();
        System.out.println("ProviderQuirksOfflineTest OK");
    }

    /**
     * Signature safety: the store signs the exact payload bytes, so parse must accept exactly one
     * byte string per document. Any other encoding of the same text — invalid UTF-8, a BOM, UTF-16,
     * an escaped control — is refused rather than silently mapped onto a document that was never
     * signed.
     */
    static void encodingPins() throws Exception {
        byte[] ok="{\"schemaVersion\":2,\"minimumEngineVersion\":2,\"ruleVersion\":\"2.1.0\",\"rules\":[],\"outputDefaults\":[]}".getBytes(StandardCharsets.UTF_8);
        check(ProviderQuirks.parse(ok).schemaVersion==2);
        check(Arrays.equals(ok,new String(ok,StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8)));
        byte[] trailing=new byte[ok.length+1]; System.arraycopy(ok,0,trailing,0,ok.length); trailing[ok.length]=(byte)0xff;
        rejectBytes(trailing);
        byte[] broken=ok.clone(); broken[broken.length-2]=(byte)0xc3; rejectBytes(broken);
        byte[] bom=new byte[ok.length+3]; bom[0]=(byte)0xef; bom[1]=(byte)0xbb; bom[2]=(byte)0xbf;
        System.arraycopy(ok,0,bom,3,ok.length); rejectBytes(bom);
        rejectBytes(new String("{\"schemaVersion\":2,\"minimumEngineVersion\":2,\"ruleVersion\":\"2.1.0\",\"rules\":[],\"outputDefaults\":[]}").getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
        // an escaped control character cannot be hidden inside a value
        reject("{\"schemaVersion\":2,\"minimumEngineVersion\":2,\"ruleVersion\":\"2.1.0\\u0001\",\"rules\":[],\"outputDefaults\":[]}");
    }
    static void rejectBytes(byte[] bytes) throws Exception {
        try{ProviderQuirks.parse(bytes);throw new AssertionError("accepted "+bytes.length+" bytes");}
        catch(IllegalArgumentException|com.gaeul.allin.json.JSONException expected){}
    }

    /**
     * The engine's native vocabulary must equal what the live consumer applies, and every value the
     * engine yields for a matched tuple must survive that consumer's own filter unchanged.
     */
    static void consumerContract() throws Exception {
        // These 19 names must equal WorkBuddyNativeTransport.RULE_HEADERS exactly. The last eight are
        // the static SDK block WorkBuddyPolicy writes as literals; the call-shape helper, the
        // protocol headers and the transport encoding stay out on both sides.
        String[] nativeNames={"User-Agent","X-Requested-With","X-Product","X-IDE-Type","X-IDE-Name","X-IDE-Version",
                "X-Agent-Intent","X-Agent-Purpose","X-Agent-Type","X-Agent-Runtime","Accept",
                "X-Stainless-Lang","X-Stainless-Package-Version","X-Stainless-OS","X-Stainless-Arch",
                "X-Stainless-Runtime","X-Stainless-Runtime-Version","X-Stainless-Retry-Count",
                "X-Stainless-Timeout"};
        // each allowlisted name is accepted on its own, so the engine is not stricter than the transport
        for(String name:nativeNames) {
            String value="Accept".equals(name)?"text/event-stream":"v";
            ProviderQuirks.Snapshot one=ProviderQuirks.parse(doc("2",nativeRule("n-"+name.toLowerCase(Locale.ROOT),
                    "{\""+name+"\":\""+value+"\"}")).getBytes(StandardCharsets.UTF_8));
            Map<String,String> got=one.headers("workbuddy","anthropic",WB_NATIVE);
            check(got.size()==1 && got.containsKey(name) && value.equals(got.get(name)));
        }
        // the full nineteen-name rule yields exactly those names and nothing else
        ProviderQuirks.Snapshot all=ProviderQuirks.parse(doc("2",nativeRule("n-all",NATIVE_ALL_V2)).getBytes(StandardCharsets.UTF_8));
        check(all.headers("workbuddy","anthropic",WB_NATIVE).size()==nativeNames.length);
        check(new HashSet<>(all.headers("workbuddy","anthropic",WB_NATIVE).keySet()).equals(new HashSet<>(Arrays.asList(nativeNames))));
        // the shipped eleven stay a legal subset, so an older document keeps its meaning
        ProviderQuirks.Snapshot eleven=ProviderQuirks.parse(doc("2",nativeRule("n-eleven",NATIVE_ALL)).getBytes(StandardCharsets.UTF_8));
        check(eleven.headers("workbuddy","anthropic",WB_NATIVE).size()==11);
        // names the transport would drop are unrepresentable, so no rule can be silently inert
        for(String extra:new String[]{"Accept-Encoding","X-Stainless-Helper-Method","anthropic-beta",
                "Content-Encoding","X-Conversation-Message-ID","X-API-Key","x-user-agent","x-stainless-Lang"})
            reject(doc("2",nativeRule("n-extra","{\"User-Agent\":\"v\",\""+extra+"\":\"x\"}")));
        // engine-level value space equals the transport's: visible ASCII, <=256, non-empty
        check(all.headers("workbuddy","anthropic",WB_NATIVE).get("X-IDE-Version").equals("5.9.3"));
        reject(doc("2",nativeRule("n-long","{\"User-Agent\":\""+new String(new char[257]).replace('\0','A')+"\"}")));
        // every name the engine yields is non-reserved, so the transport's identity headers survive
        for(String name:nativeNames) check(!ProviderQuirks.isReservedHeaderName(name));
        // the chat tuple the live gateway reads keeps its v1 shape: no Accept, no identity names
        ProviderQuirks.Snapshot chatAll=ProviderQuirks.parse(doc("2",chat("c-all")).getBytes(StandardCharsets.UTF_8));
        Map<String,String> h=chatAll.headers("workbuddy","openai",WB_CHAT);
        check(h.size()==7 && !h.containsKey("Accept") && !h.containsKey("X-Conversation-ID"));
        for(String name:h.keySet()) check(!ProviderQuirks.isReservedHeaderName(name));
    }

    /**
     * Engine 2 static fingerprint extension, plus the freeze that keeps it safe.
     *
     * <p>Three invariants hold at once:
     * <ul>
     *   <li>a {@code minimumEngineVersion} 1 document keeps the exact set 1.0.1 and 1.0.2 were
     *       signed against, so nothing added here can change what those documents already do; and
     *   <li>the engine 2 vocabularies are exactly the sets the two live consumers apply:
     *       {@code GatewayServer.CHAT_RULE_HEADERS} names twenty values and
     *       {@code WorkBuddyNativeTransport.RULE_HEADERS} names nineteen (this route never sends
     *       {@code X-Auth-Refresh-Source}). A name the consumer would drop stays unrepresentable, so
     *       no signed rule can be silently inert; and
     *   <li>both engine 2 vocabularies are supersets of the frozen ones, so every document that
     *       parsed before still parses to the same decisions.
     * </ul>
     */
    static void staticFingerprintExtension() throws Exception {
        String[] chatAdded={"X-IDE-Type","X-IDE-Name","X-IDE-Version","X-Agent-Runtime","Accept",
                "X-Stainless-Lang","X-Stainless-Package-Version","X-Stainless-OS","X-Stainless-Arch",
                "X-Stainless-Runtime","X-Stainless-Runtime-Version","X-Stainless-Retry-Count",
                "X-Stainless-Timeout"};
        // every added name is legal on the chat binding at engine 2, alone
        for(String name:chatAdded) {
            String value="Accept".equals(name)?"application/json":"v";
            ProviderQuirks.Snapshot one=ProviderQuirks.parse(doc("2",chat("c-"+name.toLowerCase(Locale.ROOT),
                    "{\""+name+"\":\""+value+"\"}")).getBytes(StandardCharsets.UTF_8));
            Map<String,String> got=one.headers("workbuddy","openai",WB_CHAT);
            check(got.size()==1 && value.equals(got.get(name)));
            check(!ProviderQuirks.isReservedHeaderName(name));
        }
        // ... the whole twenty-name fingerprint at once
        Map<String,String> full=ProviderQuirks.parse(doc("2",chat("c-full",CHAT_ALL_V2))
                .getBytes(StandardCharsets.UTF_8)).headers("workbuddy","openai",WB_CHAT);
        check(full.size()==20);
        check("local-agent".equals(full.get("X-Agent-Runtime")) && "5.9.3".equals(full.get("X-IDE-Version")));
        check("1200".equals(full.get("X-Stainless-Timeout")) && "js".equals(full.get("X-Stainless-Lang")));
        // the same twenty are exactly what both halves of that contract name
        check(full.size()==CHAT_WRITER_NAMES.length);
        // the native binding takes the nineteen it actually sends, and not the chat-only name
        Map<String,String> nat=ProviderQuirks.parse(doc("2",nativeRule("n-full",NATIVE_ALL_V2))
                .getBytes(StandardCharsets.UTF_8)).headers("workbuddy","anthropic",WB_NATIVE);
        check(nat.size()==19 && "js".equals(nat.get("X-Stainless-Lang"))
                && "1200".equals(nat.get("X-Stainless-Timeout")));
        reject(doc("2",nativeRule("n-refresh","{\"X-Auth-Refresh-Source\":\"workbuddy\"}")));
        // a v1 document keeps its exact seven and the legacy conversation id, nothing new
        check(ProviderQuirks.parse(doc("1",chat("c1")).getBytes(StandardCharsets.UTF_8))
                .headers("workbuddy","openai",WB_CHAT).size()==7);
        for(String name:chatAdded)
            reject(doc("1",chat("c-v1-"+name.toLowerCase(Locale.ROOT),
                    CHAT_ALL.substring(0,CHAT_ALL.length()-1)+",\""+name+"\":\"v\"}")));
        reject(doc("1",chat("c-v1-sub","{\"User-Agent\":\"A\"}")));
        // raccoon is unchanged at both engine versions: both names, or no rule
        reject(doc("2",rule("r-sub2","raccoon","openai","https://xiaohuanxiong.com",
                "/api/web/llm/v2/chat/completions","{\"Accept\":\"text/event-stream\"}")));
        // the value space is shared: the new names cannot smuggle interpolation or controls
        reject(doc("2",chat("c-x1",CHAT_ALL_V2.replace("\"js\"","\"${lang}\""))));
        reject(doc("2",chat("c-x2",CHAT_ALL_V2.replace("\"Linux\"","\"{{os}}\""))));
        reject(doc("2",chat("c-x3",CHAT_ALL_V2.replace("\"js\"","\"ru\\u0007st\""))));
        reject(doc("2",chat("c-x4",CHAT_ALL_V2.replace("\"X-IDE-Type\":\"WorkBuddy\"","\"X-IDE-Type\":\"WorkBuddy\",\"X-IDE-Type\":\"dup\""))));
        // neither route can reach the transport encoding or the protocol headers
        for(String forbidden:new String[]{"Accept-Encoding","anthropic-version","anthropic-beta",
                "Content-Encoding","X-Stainless-Helper-Method"})
            reject(doc("2",chat("c-no-"+forbidden.toLowerCase(Locale.ROOT),
                    "{\"User-Agent\":\"A\",\""+forbidden+"\":\"x\"}")));
        check(ProviderQuirks.parse(doc("2",nativeRule("n-all19",NATIVE_ALL_V2)).getBytes(StandardCharsets.UTF_8))
                .headers("workbuddy","openai",WB_CHAT).isEmpty());
    }
    /** The twenty values {@code GatewayServer.CHAT_RULE_HEADERS} applies; quoted so the test reads. */
    static final String[] CHAT_WRITER_NAMES={"Accept","User-Agent","X-Requested-With","X-Product",
            "X-IDE-Type","X-IDE-Name","X-IDE-Version","X-Agent-Intent","X-Agent-Purpose","X-Agent-Type",
            "X-Agent-Runtime","X-Auth-Refresh-Source","X-Stainless-Retry-Count","X-Stainless-Timeout",
            "X-Stainless-Lang","X-Stainless-Package-Version","X-Stainless-OS","X-Stainless-Arch",
            "X-Stainless-Runtime","X-Stainless-Runtime-Version"};

    /**
     * Version grammar pin. {@code ruleVersion} must be exactly the three-part numeric form the
     * signed store orders, so a parsed document always has a version that can be compared against
     * the built-in and cached ones. Before this pin the parser accepted {@code 1.0.3-rc.1} and the
     * refusal only happened after a valid signature had already been accepted, which made an
     * unusable document look like a transport failure.
     */
    static void versionGrammar() throws Exception {
        for (String good : new String[]{"0.0.0", "1.0.2", "1.0.999999999", "2.1.0", "10.20.30"})
            check(ProviderQuirks.parse(("{\"schemaVersion\":1,\"minimumEngineVersion\":1,\"ruleVersion\":\"" + good
                    + "\",\"rules\":[]}").getBytes(StandardCharsets.UTF_8)).ruleVersion.equals(good));
        for (String bad : new String[]{"1.0", "1.0.2.1", "1.0.9999999999", "01.0.0", "1.0.02", "1.00.0",
                "1.0.3-rc.1", "1.0.3+build.7", "1.0.3-", "v1.0.2", "1.0.2 ", " 1.0.2", "1..2", ".1.2",
                "1.0.", "-1.0.2", "+1.0.2", "1.0.+2", "1.0.\u0663"})
            reject("{\"schemaVersion\":1,\"minimumEngineVersion\":1,\"ruleVersion\":\"" + bad + "\",\"rules\":[]}");
        // the shipped asset stays inside the grammar, and so does every schema 2 document
        byte[] asset = Files.readAllBytes(Paths.get(Files.exists(Paths.get("src/main/assets/default_quirks.json"))
                ? "src/main/assets/default_quirks.json" : "app/src/main/assets/default_quirks.json"));
        check(ProviderQuirks.parse(asset).ruleVersion.matches("[0-9]+\\.[0-9]+\\.[0-9]+"));
    }

    /** Schema 2 happy path: header rules and output defaults travel in one signed document. */
    static void schemaTwo() throws Exception {
        String defaults=out("workbuddy","deepseek-v3","8192")+","+out("zcode","glm-5.3","64000")
                +","+out("minimax-code","MiniMax-M2.7","2000000")+","+out("workbuddy","claude-3-5-sonnet","1");
        ProviderQuirks.Snapshot q=ProviderQuirks.parse(doc2(chat("c-all")+","+nativeRule("n1",NATIVE_ALL),defaults).getBytes(StandardCharsets.UTF_8));
        check(q.schemaVersion==2 && q.minimumEngineVersion==2 && q.engineVersion==2);
        check(q.ruleCount()==2 && q.outputDefaultCount()==4 && q.ruleVersion.equals("2.1.0"));
        // header rules keep their engine 2 semantics inside schema 2
        check(q.headers("workbuddy","openai",WB_CHAT).size()==7);
        check(q.headers("workbuddy","anthropic",WB_NATIVE).size()==11);
        // exact provider/model lookup, per provider
        check(q.outputDefault("workbuddy","deepseek-v3")==8192);
        check(q.outputDefault("zcode","glm-5.3")==64000);
        check(q.outputDefault("minimax-code","MiniMax-M2.7")==2000000);
        check(q.outputDefault("workbuddy","claude-3-5-sonnet")==1);
        // no cross-provider bleed, no prefix/suffix/case wildcarding
        check(q.outputDefault("zcode","deepseek-v3")==0);
        check(q.outputDefault("workbuddy","glm-5.3")==0);
        check(q.outputDefault("workbuddy","deepseek")==0);
        check(q.outputDefault("workbuddy","deepseek-v3-extra")==0);
        check(q.outputDefault("workbuddy","DEEPSEEK-V3")==0);
        check(q.outputDefault("workbuddy","deepseek-v3 ")==0);
        check(q.outputDefault("workbuddy","*")==0);
        check(q.outputDefault("workbuddy","deepseek-*")==0);
        check(q.outputDefault(null,"deepseek-v3")==0 && q.outputDefault("workbuddy",null)==0);
        // empty arrays are representable: a schema 2 document may carry header rules alone
        ProviderQuirks.Snapshot onlyRules=ProviderQuirks.parse(doc2(chat("c-all"),"").getBytes(StandardCharsets.UTF_8));
        check(onlyRules.outputDefaultCount()==0 && onlyRules.headers("workbuddy","openai",WB_CHAT).size()==7);
        ProviderQuirks.Snapshot onlyDefaults=ProviderQuirks.parse(doc2("",out("zcode","glm-5.3","1000")).getBytes(StandardCharsets.UTF_8));
        check(onlyDefaults.ruleCount()==0 && onlyDefaults.outputDefault("zcode","glm-5.3")==1000);
        // withSource keeps schema, engine, rules and defaults
        ProviderQuirks.Snapshot sourced=q.withSource("verified-update");
        check(sourced.schemaVersion==2 && sourced.minimumEngineVersion==2 && sourced.source.equals("verified-update"));
        check(sourced.ruleCount()==2 && sourced.outputDefaultCount()==4 && sourced.outputDefault("workbuddy","deepseek-v3")==8192);
        check(sourced.headers("workbuddy","anthropic",WB_NATIVE).size()==11);
    }

    /** Everything schema 2 must refuse: field smuggling, bad provider/model/limit, duplicates. */
    static void schemaTwoRefusals() throws Exception {
        String one=out("workbuddy","deepseek-v3","8192");
        // schema 2 requires engine 2, and engine 1 documents cannot gain the field
        reject(doc2("",one).replace("\"minimumEngineVersion\":2","\"minimumEngineVersion\":1"));
        reject(doc2("",one).replace("\"minimumEngineVersion\":2","\"minimumEngineVersion\":3"));
        // the field cannot be smuggled into schema 1 at either engine version
        reject("{\"schemaVersion\":1,\"minimumEngineVersion\":1,\"ruleVersion\":\"2.0.0\",\"outputDefaults\":[]}");
        reject("{\"schemaVersion\":1,\"minimumEngineVersion\":1,\"ruleVersion\":\"2.0.0\",\"rules\":[],\"outputDefaults\":[]}");
        reject("{\"schemaVersion\":1,\"minimumEngineVersion\":2,\"ruleVersion\":\"2.0.0\",\"rules\":[],\"outputDefaults\":["+one+"]}");
        // nor can a schema 2 document omit it, or add anything else
        reject("{\"schemaVersion\":2,\"minimumEngineVersion\":2,\"ruleVersion\":\"2.1.0\",\"rules\":[]}");
        reject(doc2("",one).replace("\"ruleVersion\":\"2.1.0\"","\"ruleVersion\":\"2.1.0\",\"extra\":1"));
        reject(doc2("",one).replace("\"schemaVersion\":2","\"schemaVersion\":3"));
        reject(doc2("",one).replace("\"schemaVersion\":2","\"schemaVersion\":0"));
        reject(doc2("",one).replace("\"schemaVersion\":2","\"schemaVersion\":\"2\""));
        reject(doc2("",one).replace("\"schemaVersion\":2","\"schemaVersion\":1.0"));
        reject(doc2("",one).replace("\"schemaVersion\":2","\"schemaVersion\":null"));
        // outputDefaults must be an array of exact provider/model/maxOutput objects
        reject(doc2("",one).replace("\"outputDefaults\":["+one+"]","\"outputDefaults\":{}"));
        reject(doc2("",one).replace("\"outputDefaults\":["+one+"]","\"outputDefaults\":\"x\""));
        reject(doc2("","[\"x\"]"));
        reject(doc2("","[1]"));
        reject(doc2("","["+one+",]"));
        reject(doc2("","{\"provider\":\"workbuddy\",\"model\":\"deepseek-v3\"}"));
        reject(doc2("","{\"provider\":\"workbuddy\",\"maxOutput\":1}"));
        reject(doc2("","{\"model\":\"deepseek-v3\",\"maxOutput\":1}"));
        reject(doc2("","{\"provider\":\"workbuddy\",\"model\":\"deepseek-v3\",\"maxOutput\":1,\"note\":\"x\"}"));
        reject(doc2("","{\"provider\":\"workbuddy\",\"model\":\"deepseek-v3\",\"maxOutput\":1,\"engine\":2}"));
        // provider identity whitelist: zcode, minimax-code, workbuddy only, exact case
        for(String bad:new String[]{"z-code","minimax","workbuddy-ai","openai","ZCode","WorkBuddy","MINIMAX-CODE",
                "zcode "," zcode","zcode\t","*",""})
            reject(doc2("",out(bad,"m","1")));
        // model: exact printable identity, no wildcard, no separators that read as patterns
        for(String bad:new String[]{"*","?","glm-*","glm-?","glm 5","${m}","{{m}}","../m","a/../b","a//b","./m",
                "-m",".m","/m","+m","m/","m.","m-","m\u0007","m\u007f","模块","m'n","m\"n",new String(new char[129]).replace('\0','a')})
            reject(doc2("",out("zcode",bad,"1")));
        check(ProviderQuirks.parse(doc2("",out("zcode",new String(new char[128]).replace('\0','a'),"1")).getBytes(StandardCharsets.UTF_8)).outputDefaultCount()==1);
        for(String good:new String[]{"glm-5.3","MiniMax-M2.7","MiniMax-M2.7-highspeed","claude-3-5-sonnet",
                "hunyuan-t1","zcode/glm-5.3","m_1","a+b","m:1","m.1","m-1","9"})
            check(ProviderQuirks.parse(doc2("",out("zcode",good,"1")).getBytes(StandardCharsets.UTF_8)).outputDefault("zcode",good)==1);
        // maxOutput: only a positive JSON integer up to 2000000
        for(String bad:new String[]{"0","-1","2000001","\"100\"","100.5","100.0","1e3","true","null","[]","{}","+1","9999999999999999999"})
            reject(doc2("",out("workbuddy","deepseek-v3",bad)));
        check(ProviderQuirks.parse(doc2("",out("workbuddy","deepseek-v3","1")).getBytes(StandardCharsets.UTF_8)).outputDefault("workbuddy","deepseek-v3")==1);
        check(ProviderQuirks.parse(doc2("",out("workbuddy","deepseek-v3","2000000")).getBytes(StandardCharsets.UTF_8)).outputDefault("workbuddy","deepseek-v3")==2000000);
        // duplicates: an exact repeat, and a case-only repeat of the same key
        reject(doc2("",out("workbuddy","deepseek-v3","1")+","+out("workbuddy","deepseek-v3","2")));
        reject(doc2("",out("workbuddy","deepseek-v3","1")+","+out("workbuddy","deepseek-v3","1")));
        reject(doc2("",out("workbuddy","DeepSeek-V3","1")+","+out("workbuddy","deepseek-v3","2")));
        reject(doc2("",out("workbuddy","deepseek-v3","1")+","+out("WorkBuddy","deepseek-v3","1")));
        // the same model under two different providers is not a duplicate
        check(ProviderQuirks.parse(doc2("",out("workbuddy","deepseek-v3","1")+","+out("zcode","deepseek-v3","2")).getBytes(StandardCharsets.UTF_8)).outputDefaultCount()==2);
        // bounded list
        StringBuilder many=new StringBuilder();
        for(int i=0;i<65;i++){ if(i>0)many.append(','); many.append(out("workbuddy","m"+i,"1")); }
        reject(doc2("",many.toString()));
        StringBuilder allowed=new StringBuilder();
        for(int i=0;i<64;i++){ if(i>0)allowed.append(','); allowed.append(out("workbuddy","m"+i,"1")); }
        check(ProviderQuirks.parse(doc2("",allowed.toString()).getBytes(StandardCharsets.UTF_8)).outputDefaultCount()==64);
        // schema 2 rides on engine 2, so it keeps every schema 1 rule refusal and gains the
        // engine 2 header subset vocabulary on the same tuples
        reject(doc2(chat("c-bad","{\"User-Agent\":\"A\",\"Authorization\":\"Bearer x\"}"),""));
        reject(doc2(chat("c-bad","{\"Cookie\":\"s=1\"}"),""));
        reject(doc2(chat("dup")+","+chat("dup"),""));
        reject(doc2(rule("evil","workbuddy","openai","https://evil.example","/v2/chat/completions",CHAT_ALL),""));
        reject(doc2(rule("ai-native","workbuddy-ai","openai","https://copilot.tencent.com","/v1/messages",NATIVE_ALL),""));
        ProviderQuirks.Snapshot two=ProviderQuirks.parse(doc2(chat("c-all")+","+nativeRule("n1",NATIVE_ALL),"").getBytes(StandardCharsets.UTF_8));
        check(two.ruleCount()==2 && two.headers("workbuddy","anthropic",WB_NATIVE).size()==11);
        check(ProviderQuirks.parse(doc2(chat("c-sub","{\"User-Agent\":\"A\"}"),"").getBytes(StandardCharsets.UTF_8))
                .headers("workbuddy","openai",WB_CHAT).size()==1);
        check(ProviderQuirks.parse(doc2(chat("c1"),"").getBytes(StandardCharsets.UTF_8)).schemaVersion==2);
    }
}
