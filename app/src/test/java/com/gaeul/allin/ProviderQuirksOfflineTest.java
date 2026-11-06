package com.gaeul.allin;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public class ProviderQuirksOfflineTest {
    static void check(boolean x){if(!x)throw new AssertionError();}
    static void reject(String s)throws Exception{try{ProviderQuirks.parse(s.getBytes(StandardCharsets.UTF_8));throw new AssertionError("accepted: "+s);}catch(IllegalArgumentException|org.json.JSONException expected){}}
    public static void main(String[] args)throws Exception {
        byte[] b=Files.readAllBytes(Paths.get(args.length==0?(Files.exists(Paths.get("src/main/assets/default_quirks.json"))?"src/main/assets/default_quirks.json":"app/src/main/assets/default_quirks.json"):args[0])); String s=new String(b,StandardCharsets.UTF_8);
        ProviderQuirks.Snapshot q=ProviderQuirks.parse(b); check(q.ruleCount()==3 && q.ruleVersion.equals("1.0.0"));
        String url="https://copilot.tencent.com/v2/chat/completions";
        Map<String,String> h=q.headers("workbuddy","openai",url); check(h.size()==8 && h.get("X-Product").equals("SaaS"));
        try{h.put("X-Product","bad");throw new AssertionError();}catch(UnsupportedOperationException expected){}
        ProviderQuirks.Snapshot changed=ProviderQuirks.parse(s.replace("CLI/2.63.2 CodeBuddy/2.63.2","CLI/test-static").getBytes(StandardCharsets.UTF_8));
        check("CLI/test-static".equals(changed.headers("workbuddy","openai",url).get("User-Agent")));
        reject(s.replace("1.0.0","made-up"));
        check(q.headers("workbuddy-ai","openai","https://www.workbuddy.ai/v2/chat/completions").size()==8);
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
