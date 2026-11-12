package com.gaeul.allin;
import java.util.Map;
import com.gaeul.allin.json.JSONObject;
import com.gaeul.allin.json.JSONArray;

public class WorkBuddyPolicyOfflineTest {
    private static final String CN = "https://copilot.tencent.com/v2/chat/completions";
    private static final String AI = "https://www.workbuddy.ai/v2/chat/completions";
    static void check(boolean b) { if (!b) throw new AssertionError(); }
    static void check(boolean b,String message) { if(!b) throw new AssertionError(message); }
    static Map<String,String> h(String p,String url,String key,JSONObject a,String s,String r) {
        return WorkBuddyPolicy.headers(p,url,key,a,s,r);
    }
    static String turn(String provider, String url, String key, JSONObject account, String session, JSONObject body) {
        return WorkBuddyPolicy.headers(provider, url, key, account, session, "request", body).get("X-Conversation-Request-ID");
    }
    static JSONObject body(String messages) throws Exception {
        return new JSONObject().put("messages", new JSONArray(messages));
    }
    public static void main(String[] args) throws Exception {
        JSONObject a = new JSONObject().put("uid", "uid1").put("domain", "tenant");
        Map<String,String> x = h("workbuddy",CN,"ck_secret123",a,"session one", "r1");
        check("ck_secret123".equals(x.get("X-API-Key")) && !x.containsKey("X-Domain"));
        String id=x.get("X-Conversation-ID");
        check(id.equals(h("workbuddy",CN,"ck_secret123",a,"session one","r2").get("X-Conversation-ID")));
        check(!id.equals(h("workbuddy",CN,"ck_secret123",a,"session two","r1").get("X-Conversation-ID")));
        check(id.equals(h("workbuddy",CN,"ck_other123",a,"session one","r1").get("X-Conversation-ID")));
        check(!id.equals(h("workbuddy",CN,"ck_secret123",new JSONObject().put("uid","other").put("domain","tenant"),"session one","r1").get("X-Conversation-ID")));
        check(!id.contains("secret") && !id.equals("00000000-0000-0000-0000-000000000000"));
        String uuid="  12345678-1234-1234-1234-123456789abc  ";
        String uuidId=h("workbuddy",CN,"pt_secret123",a,uuid,"").get("X-Conversation-ID");
        check(!uuidId.equals(uuid.trim()) && uuidId.equals(h("workbuddy",CN,"newtoken",a,uuid,"r").get("X-Conversation-ID")));
        check(!uuidId.equals(h("workbuddy",CN,"newtoken",new JSONObject().put("uid","other"),uuid,"r").get("X-Conversation-ID")));
        check(!h("workbuddy",CN,"token",null,uuid,"r").get("X-Conversation-ID").equals(h("workbuddy",CN,"token",null,uuid,"r").get("X-Conversation-ID")));
        check(h("workbuddy-ai",AI,"ck_secret123",null,"","r").containsKey("X-API-Key"));
        check(h("workbuddy",CN,"pt_secret123",null,"","r").containsKey("X-API-Key"));
        check(h("workbuddy",CN,"token",a,"","r").get("X-Domain").equals("tenant"));
        check(!h("workbuddy",CN,"token",null,"","r").containsKey("X-User-Id"));
        check(!h("workbuddy",CN,"token",a,"","r").get("X-Conversation-ID").equals(h("workbuddy",CN,"token",a,"","r").get("X-Conversation-ID")));
        check(h("workbuddy",AI,"ck_secret123",a,"","r").isEmpty());
        check(h("workbuddy",CN+"?x=1","ck_secret123",a,"","r").isEmpty());
        check(h("workbuddy",CN.replace(".com/", ".com:443/"),"ck_secret123",a,"","r").isEmpty());
        check(h("workbuddy",CN.replace("copilot.tencent.com", "evil.example"),"ck_secret123",a,"","r").isEmpty());
        check(h("workbuddy",CN,"x\r\ny",a,"","r").isEmpty());
        String keyOnly=h("workbuddy",CN,"ck_first",null,"session","r1").get("X-Conversation-ID");
        check(keyOnly.equals(h("workbuddy",CN,"ck_first",null,"session","r2").get("X-Conversation-ID")));
        check(!keyOnly.equals(h("workbuddy",CN,"ck_second",null,"session","r1").get("X-Conversation-ID")));
        check(!keyOnly.contains("ck_first"));
        JSONObject oauth=new JSONObject().put("type","oauth").put("uid","same");
        check(h("workbuddy-ai",AI,"old-token",oauth,"session","r1").get("X-Conversation-ID").equals(h("workbuddy-ai",AI,"new-token",oauth,"session","r2").get("X-Conversation-ID")));
        JSONObject unknownOauth=new JSONObject().put("type","oauth");
        check(!h("workbuddy-ai",AI,"old-token",unknownOauth,"session","r1").get("X-Conversation-ID").equals(h("workbuddy-ai",AI,"old-token",unknownOauth,"session","r1").get("X-Conversation-ID")));
        JSONObject acct = new JSONObject().put("uid", "u").put("enterpriseId", "e");
        JSONObject initial = body("[{\"role\":\"user\",\"content\":\"hello\"}]");
        String before = initial.toString();
        String first = turn("workbuddy", CN, "oauth1", acct, "session", initial);
        check(first.matches("[0-9a-f]{32}"));
        JSONObject arrayText = body("[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}]");
        JSONObject splitText = body("[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"he\"},{\"type\":\"input_text\",\"text\":\"llo\"}]}]");
        check(first.equals(turn("workbuddy", CN, "oauth1", acct, "session", arrayText)));
        check(first.equals(turn("workbuddy", CN, "oauth1", acct, "session", splitText)));
        JSONObject arrayTool = body("[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"he\"},{\"type\":\"input_text\",\"text\":\"llo\"}]},{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"call\"}]},{\"role\":\"tool\",\"content\":\"result\"}]");
        check(first.equals(turn("workbuddy", CN, "oauth1", acct, "session", arrayTool)));
        JSONObject mixed = body("[{\"role\":\"user\",\"content\":\"hello\"},{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"a\"}]},{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"a\",\"content\":\"result\"},{\"type\":\"text\",\"text\":\"reminder\"}]}]");
        check(first.equals(turn("workbuddy", CN, "oauth1", acct, "session", mixed)));
        mixed.getJSONArray("messages").put(new JSONObject().put("role", "assistant").put("content", "done"))
                .put(new JSONObject().put("role", "user").put("content", "hello"));
        check(!first.equals(turn("workbuddy", CN, "oauth1", acct, "session", mixed)));
        JSONObject imageOne = body("[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hello\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"a\"}}]}]");
        JSONObject imageTwo = body("[{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"a\"}},{\"type\":\"text\",\"text\":\"hello\"}]}]");
        check(!turn("workbuddy", CN, "oauth1", acct, "session", imageOne).equals(turn("workbuddy", CN, "oauth1", acct, "session", imageTwo)));
        JSONObject metadata = body("[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hello\",\"extra\":\"meaningful\"}]}]");
        check(!first.equals(turn("workbuddy", CN, "oauth1", acct, "session", metadata)));
        JSONObject tool = body("[{\"role\":\"user\",\"content\":\"hello\"},{\"role\":\"assistant\",\"tool_calls\":[]},{\"role\":\"tool\",\"content\":\"answer\"}]");
        check(first.equals(turn("workbuddy", CN, "oauth2", acct, "session", tool)));
        JSONObject nativeTool = body("[{\"role\":\"user\",\"content\":\"hello\"},{\"role\":\"assistant\",\"content\":[]},{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"a\",\"content\":\"result\"},{\"type\":\"tool_result\",\"tool_use_id\":\"b\",\"content\":\"result\"}]}]");
        check(first.equals(turn("workbuddy", CN, "oauth1", acct, "session", nativeTool)));
        JSONObject next = body("[{\"role\":\"user\",\"content\":\"hello\"},{\"role\":\"assistant\",\"content\":\"ok\"},{\"role\":\"user\",\"content\":\"hello\"}]");
        check(!first.equals(turn("workbuddy", CN, "oauth1", acct, "session", next)));
        next.getJSONArray("messages").getJSONObject(2).put("content", "different");
        check(!first.equals(turn("workbuddy", CN, "oauth1", acct, "session", next)));
        check(!first.equals(turn("workbuddy", CN, "oauth1", new JSONObject().put("uid", "other").put("enterpriseId", "e"), "session", initial)));
        check(!first.equals(turn("workbuddy", CN, "oauth1", new JSONObject().put("uid", "u").put("enterpriseId", "other"), "session", initial)));
        check(!first.equals(turn("workbuddy-ai", AI, "oauth1", acct, "session", initial)));
        initial.put("model", "another-model");
        check(first.equals(turn("workbuddy", CN, "oauth1", acct, "session", initial)));
        initial.remove("model");
        check(before.equals(initial.toString()));
        String missing = turn("workbuddy", CN, "oauth1", acct, "", initial);
        check(!missing.equals(turn("workbuddy", CN, "oauth1", acct, "", initial)));
        JSONObject unknown = body("[{\"role\":\"user\",\"content\":[{\"type\":\"mystery\"}]}]");
        check(!turn("workbuddy", CN, "oauth1", acct, "session", unknown).equals(turn("workbuddy", CN, "oauth1", acct, "session", unknown)));
        Map<String,String> request = WorkBuddyPolicy.headers("workbuddy", CN, "oauth1", acct, "session", "r", initial);
        check(request.get("X-Request-ID").equals(request.get("X-Conversation-Message-ID")));
        check(!request.get("X-Request-ID").equals(WorkBuddyPolicy.headers("workbuddy", CN, "oauth1", acct, "session", "r", initial).get("X-Request-ID")));
        check(WorkBuddyPolicy.headers("workbuddy", AI, "oauth1", acct, "session", "r", initial).isEmpty());
        System.out.println("WorkBuddyPolicyOfflineTest passed");
    }
}
