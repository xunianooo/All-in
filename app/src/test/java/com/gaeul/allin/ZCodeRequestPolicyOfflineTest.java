package com.gaeul.allin;

import org.json.JSONObject;

public final class ZCodeRequestPolicyOfflineTest {
    static int n;
    static void check(boolean b) { n++; if (!b) throw new AssertionError("check " + n); }
    public static void main(String[] args) throws Exception {
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
