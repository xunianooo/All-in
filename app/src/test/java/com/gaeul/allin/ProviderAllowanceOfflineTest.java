package com.gaeul.allin;

import org.json.JSONObject;

public final class ProviderAllowanceOfflineTest {
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        check(ProviderAllowance.fromCodex(new JSONObject("{}"), 1)==null);
        JSONObject codex=ProviderAllowance.fromCodex(new JSONObject("{\"rate_limit\":{\"primary_window\":{\"used_percent\":100,\"reset_at\":123},\"secondary_window\":{\"used_percent\":25}}}"),1);
        check(codex.getJSONArray("windows").length()==2 && codex.getJSONArray("windows").getJSONObject(0).getDouble("remainingPercent")==0);
        check(ProviderAllowance.fromCodex(new JSONObject("{\"rate_limit\":{\"primary_window\":{\"used_percent\":101}}}"),1)==null);
        JSONObject cline=ProviderAllowance.fromCline(new JSONObject("{\"balanceUSD\":0,\"limits\":[{\"type\":\"weekly\",\"percentUsed\":10,\"resetsAt\":\"2026-11-08T00:00:00Z\"}]}"),1);
        check(cline.getDouble("remaining")==0 && cline.getString("unit").equals("USD") && cline.getJSONArray("windows").getJSONObject(0).getDouble("remainingPercent")==90);
        check(ProviderAllowance.fromCatPaw(new JSONObject("{\"credit\":\"0\"}"),1).getDouble("remaining")==0);
        check(ProviderAllowance.fromCatPaw(new JSONObject("{\"credit\":\"\"}"),1)==null);
        JSONObject copilot=ProviderAllowance.fromCopilot(new JSONObject("{\"quota_snapshots\":{\"premium_interactions\":{\"entitlement\":100,\"quota_remaining\":0},\"chat\":{\"percent_remaining\":40},\"other\":{\"percent_remaining\":80}}}"),1);
        check(copilot.getDouble("remaining")==0 && copilot.getString("unit").equals("requests") && copilot.getJSONArray("windows").length()==2);
        check(ProviderAllowance.fromCopilot(new JSONObject("{\"quota_snapshots\":{\"chat\":{\"unlimited\":true}}}"),1)==null);
        JSONObject mini=ProviderAllowance.fromMiniMax(new JSONObject("{\"model_remains\":[{\"model_name\":\"video\",\"current_interval_remaining_percent\":0},{\"model_name\":\"general\",\"current_interval_remaining_percent\":0,\"current_weekly_remaining_percent\":80}]}"),1);
        check(mini.getJSONArray("windows").length()==2 && mini.getJSONArray("windows").getJSONObject(0).getDouble("remainingPercent")==0);
        check(ProviderAllowance.fromMiniMax(new JSONObject("{\"model_remains\":[{\"model_name\":\"video\",\"current_interval_remaining_percent\":0}]}"),1)==null);
        System.out.println("ProviderAllowanceOfflineTest OK");
    }
}
