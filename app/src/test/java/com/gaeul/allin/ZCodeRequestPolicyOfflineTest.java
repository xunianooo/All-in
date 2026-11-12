package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;

public final class ZCodeRequestPolicyOfflineTest {
    static int n;
    static void check(boolean b) { n++; if (!b) throw new AssertionError("check " + n); }
    static void rejected(String json) throws Exception {
        JSONObject input = new JSONObject(json);
        String before = input.toString();
        boolean threw = false;
        try { ZCodeRequestPolicy.chat(input); } catch (IllegalArgumentException expected) { threw = true; }
        check(threw && before.equals(input.toString()));
    }
    public static void main(String[] args) throws Exception {
        JSONObject chat = new JSONObject("{\"messages\":[{\"role\":\"system\",\"content\":\"first\"},{\"role\":\"developer\",\"content\":[{\"type\":\"text\",\"text\":\"rules\"}],\"extra\":1},{\"role\":\"system\",\"content\":\"second\"},{\"role\":\"user\",\"content\":\"hello\"},{\"role\":\"assistant\",\"reasoning_content\":\"thought\",\"tool_calls\":[{\"id\":\"call1\",\"type\":\"function\",\"function\":{\"name\":\"f\",\"arguments\":\"{}\"}}]},{\"role\":\"tool\",\"tool_call_id\":\"call1\",\"content\":\"ok\"}],\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"f\",\"parameters\":{\"type\":\"object\"}}}],\"temperature\":0.5}");
        String original = chat.toString();
        JSONObject normalized = ZCodeRequestPolicy.chat(chat);
        JSONObject expected = new JSONObject(original);
        expected.getJSONArray("messages").getJSONObject(1).put("role", "system");
        check(normalized.toString().equals(expected.toString()));
        check(chat.toString().equals(original));
        normalized.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0).put("text", "changed");
        check(chat.toString().equals(original));
        check(ZCodeRequestPolicy.chat(new JSONObject("{\"messages\":[]}")).getJSONArray("messages").length() == 0);
        for (String bad : new String[]{"{}", "{\"messages\":null}", "{\"messages\":{}}", "{\"messages\":[null]}", "{\"messages\":[3]}", "{\"messages\":[{}]}", "{\"messages\":[{\"role\":null}]}", "{\"messages\":[{\"role\":3}]}", "{\"messages\":[{\"role\":\"Developer\"}]}", "{\"messages\":[{\"role\":\"other\"}]}"}) rejected(bad);

        String root = "https://open.bigmodel.cn";
        check(ZCodeRequestPolicy.isOfficialMessages(root + "/api/anthropic/v1/messages"));
        check(ZCodeRequestPolicy.isOfficialMessages("https://api.z.ai/api/coding/paas/v4/messages"));
        for (String s : new String[]{"http://api.z.ai/api/anthropic/v1/messages", "https://api.z.ai.evil/api/anthropic/v1/messages", "https://api.z.ai/api/anthropic/v1/messages/count_tokens", "https://api.z.ai:444/api/anthropic/v1/messages"}) check(!ZCodeRequestPolicy.isOfficialMessages(s));
        JSONObject input = new JSONObject("{\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"x\",\"signature\":\"sig\"}]}],\"metadata\":{\"other\":7}}");
        JSONObject result = ZCodeRequestPolicy.marked(input,"device","session");
        check(!input.getJSONObject("metadata").has("user_id"));
        check(result.getJSONArray("messages").toString().equals(input.getJSONArray("messages").toString()));
        JSONObject uid = new JSONObject(result.getJSONObject("metadata").getString("user_id"));
        check(uid.getString("device_id").equals("device") && uid.getString("session_id").equals("session") && uid.getString("account_uuid").isEmpty());
        check(result.getJSONObject("metadata").getInt("other") == 7);
        for (String metadata : new String[]{"{\"user_id\":\"opaque\"}","{\"user_id\":null}","null","[]","\"opaque\""}) {
            JSONObject x = new JSONObject("{\"messages\":[],\"metadata\":"+metadata+"}");
            check(ZCodeRequestPolicy.marked(x,"device","session").toString().equals(x.toString()));
        }
        check(!ZCodeRequestPolicy.marked(input,"","session").getJSONObject("metadata").has("user_id"));
        check(!ZCodeRequestPolicy.marked(new JSONObject("{\"messages\":{}}"),"d","s").has("metadata"));
        System.out.println("ZCodeRequestPolicy offline checks passed: " + n);
    }
}
