package com.gaeul.allin;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public class ProviderQuirksOfflineTest {
    static void check(boolean x){if(!x)throw new AssertionError();}
    static void reject(String s)throws Exception{try{ProviderQuirks.parse(s.getBytes(StandardCharsets.UTF_8));throw new AssertionError("accepted: "+s);}catch(IllegalArgumentException|org.json.JSONException expected){}}
    public static void main(String[] args)throws Exception {
        byte[] b=Files.readAllBytes(Paths.get(args.length==0?(Files.exists(Paths.get("src/main/assets/default_quirks.json"))?"src/main/assets/default_quirks.json":"app/src/main/assets/default_quirks.json"):args[0])); String s=new String(b,StandardCharsets.UTF_8);
        ProviderQuirks.Snapshot q=ProviderQuirks.parse(b); check(q.ruleCount()==3 && q.ruleVersion.equals("1.0.2"));
        String url="https://copilot.tencent.com/v2/chat/completions";
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
        System.out.println("ProviderQuirksOfflineTest OK");
    }
}
