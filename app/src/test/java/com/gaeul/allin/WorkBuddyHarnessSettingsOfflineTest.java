package com.gaeul.allin;

import java.util.Map;
import com.gaeul.allin.json.*;

/** The automatic policy must not expose the retired manual settings API. */
public final class WorkBuddyHarnessSettingsOfflineTest {
    static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        JSONObject provider=new JSONObject().put("id","workbuddy").put("keys",new JSONArray().put("fixture-key"));
        JSONObject cfg=new JSONObject().put("server",new JSONObject().put("authKey","fixture-inference"))
            .put("setup",new JSONObject().put("done",true)).put("providers",new JSONArray().put(provider));
        GatewayServer server=RoutingSetupOfflineTest.server(cfg);
        String before=cfg.toString();
        for(String path:new String[]{"/api/providers/workbuddy-harness","/api/providers/workbuddy-harness/fence"}) {
            for(String method:new String[]{"GET","POST"}) {
                int code=GatewayAccessBoundaryOfflineTest.code(GatewayAccessBoundaryOfflineTest.call(server,"127.0.0.1",path,method,"{}",GatewayAccessBoundaryOfflineTest.management()));
                check(code>=400,"retired endpoint "+method+" "+path+" status="+code);
            }
        }
        check(before.equals(cfg.toString()),"retired calls do not mutate config");
        System.out.println("WorkBuddyHarnessSettingsOfflineTest OK");
    }
}
