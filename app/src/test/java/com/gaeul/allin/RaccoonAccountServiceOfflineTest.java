package com.gaeul.allin;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Queue;
import com.gaeul.allin.json.JSONObject;

public final class RaccoonAccountServiceOfflineTest {
    static class Mock implements RaccoonAccountService.Transport {
        Queue<Object> responses = new ArrayDeque<>(); int calls; String last; boolean desktop;
        public JSONObject request(String method, String path, boolean headers) throws IOException {
            calls++; last=method+" "+path; desktop=headers; Object r=responses.remove();
            if (r instanceof IOException) throw (IOException)r;
            return (JSONObject)r;
        }
        Mock add(String json) { responses.add(new JSONObject(json)); return this; }
        Mock fail(boolean expired) { responses.add(new RaccoonAccountService.Failure("mock",expired)); return this; }
    }
    static void ok(boolean x) { if(!x) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        Mock m=new Mock().add("{\"code\":0,\"data\":{\"available_points\":123.9}}");
        ok(new RaccoonAccountService(m).balance()==123 && m.calls==1);
        m=new Mock().fail(false).add("{\"data\":{\"available_points\":\"9\"}}");
        ok(new RaccoonAccountService(m).balance()==9 && m.calls==2);
        m=new Mock().add("{\"code\":200001}");
        try { new RaccoonAccountService(m).balance(); throw new AssertionError(); } catch(RaccoonAccountService.Failure e) { ok(e.expired && m.calls==1); }
        String old="{\"bill_id\":\"old\",\"created_at\":\"2026-11-08T08:00:00+08:00\",\"points\":10,\"biz_type\":\"daily_grant\"}";
        String fresh="{\"bill_id\":\"new\",\"created_at\":\"2026-11-08T09:00:00+08:00\",\"points\":20,\"biz_type\":\"daily_grant\"}";
        m=new Mock().add("{\"data\":{\"items\":["+old+"]}}").add("{}").add("{\"data\":{\"items\":["+old+","+fresh+","+fresh+"]}}");
        JSONObject r=new RaccoonAccountService(m).checkIn(1794096000000L);
        ok(r.optString("outcome").equals("claimed") && r.optLong("credit")==20 && m.calls==3);
        m=new Mock().fail(false).add("{}");
        ok(new RaccoonAccountService(m).checkIn(1794096000000L).optString("outcome").equals("unknown") && m.desktop);
        m=new Mock().add("{\"data\":{\"granted\":true,\"popup\":{\"points\":30}}}");
        r=new RaccoonAccountService(m).claimDesktopOnboarding();
        ok(r.optBoolean("settled") && r.optLong("credit")==30 && m.desktop && m.last.startsWith("POST "));
        StringBuilder page=new StringBuilder();
        for(int i=0;i<20;i++) { if(i>0) page.append(','); page.append("{\"bill_id\":\"").append(i).append("\"}"); }
        m=new Mock().add("{\"data\":{\"items\":["+page+"]}}").add("{}").add("{\"data\":{\"items\":["+page+"]}}");
        ok(new RaccoonAccountService(m).checkIn(1794096000000L).optString("outcome").equals("unknown"));
        System.out.println("RaccoonAccountServiceOfflineTest passed");
    }
}
