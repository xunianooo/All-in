package com.gaeul.allin;

import org.json.JSONObject;

public final class ProviderAllowanceOfflineTest {
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static boolean near(double a, double b) { return Math.abs(a - b) < 1e-6; }
    private static JSONObject json(String text) throws Exception { return new JSONObject(text); }
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
        // Copilot seats the upstream corpus reads (opencode-copilot-auth usage.mjs and magpie's
        // internal/provider copilot tests), all numbers synthetic.
        JSONObject max=ProviderAllowance.fromCopilot(json("{\"access_type_sku\":\"free_github_star_quota\",\"copilot_plan\":\"individual_max\",\"token_based_billing\":true,\"quota_reset_date\":\"2026-11-01\",\"quota_snapshots\":{"
            +"\"chat\":{\"unlimited\":true,\"has_quota\":true,\"quota_reset_at\":0,\"entitlement\":0},"
            +"\"completions\":{\"unlimited\":true,\"has_quota\":true,\"quota_reset_at\":0,\"entitlement\":0},"
            +"\"premium_interactions\":{\"unlimited\":false,\"has_quota\":true,\"entitlement\":20000,\"quota_remaining\":19999.9,\"remaining\":19999,\"percent_remaining\":99.9,\"quota_reset_at\":0}}}"),1);
        check(max.getJSONArray("windows").length()==1,"unlimited chat and completions carry no share window");
        JSONObject premiumWindow=max.getJSONArray("windows").getJSONObject(0);
        check(premiumWindow.getString("name").equals("premium_interactions"));
        check(near(premiumWindow.getDouble("remainingPercent"),99.9995),"the counts are the precise reading, not percent_remaining");
        check(max.getDouble("remaining")==19999.9 && max.getString("unit").equals("credits"),"a credit seat states its balance");
        check(premiumWindow.getString("resetAt").equals("2026-11-01"),"a snapshot reset of 0 leaves the month's reset standing");
        JSONObject pooled=ProviderAllowance.fromCopilot(json("{\"copilot_plan\":\"enterprise\",\"token_based_billing\":true,\"quota_reset_date\":\"2026-11-01\",\"quota_snapshots\":{\"premium_interactions\":{\"unlimited\":true,\"has_quota\":false,\"entitlement\":\"0\",\"percent_remaining\":0,\"overage_permitted\":false,\"credits_used\":1234.5}}}"),1);
        check(pooled.getJSONArray("windows").length()==1,"a spent managed pool is not unlimited");
        check(pooled.getJSONArray("windows").getJSONObject(0).getDouble("remainingPercent")==0 && !pooled.has("remaining"),"a spent pool is used up, with no invented balance");
        check(pooled.getJSONArray("windows").getJSONObject(0).getString("resetAt").equals("2026-11-01"),"a spent pool keeps the month's reset");
        check(ProviderAllowance.fromCopilot(json("{\"quota_snapshots\":{\"chat\":{\"unlimited\":true,\"has_quota\":false,\"entitlement\":\"0\"},\"completions\":{\"unlimited\":true,\"has_quota\":false,\"entitlement\":\"0\"}}}"),1)==null,"unlimited chat stays unlimited, never a 0% window");
        check(ProviderAllowance.fromCopilot(json("{\"copilot_plan\":\"enterprise\",\"quota_snapshots\":{\"premium_interactions\":{\"unlimited\":true,\"entitlement\":\"0\",\"percent_remaining\":50}}}"),1)==null,"a pool with some left is still unlimited");
        check(ProviderAllowance.fromCopilot(json("{\"copilot_plan\":\"business\",\"quota_snapshots\":{\"premium_interactions\":{\"unlimited\":false,\"entitlement\":\"many\"}}}"),1)==null,"an unreadable entitlement is unknown, never unlimited");
        JSONObject free=ProviderAllowance.fromCopilot(json("{\"copilot_plan\":\"free\",\"token_based_billing\":true,\"quota_snapshots\":{"
            +"\"chat\":{\"unlimited\":false,\"has_quota\":false,\"entitlement\":\"200\",\"percent_remaining\":80},"
            +"\"completions\":{\"unlimited\":false,\"has_quota\":false,\"entitlement\":\"4000\",\"percent_remaining\":100},"
            +"\"premium_interactions\":{\"unlimited\":false,\"has_quota\":false,\"entitlement\":\"0\",\"percent_remaining\":0}}}"),1);
        check(free.getJSONArray("windows").length()==2,"a free seat omits the premium allowance it does not have");
        check(free.getJSONArray("windows").getJSONObject(0).getDouble("remainingPercent")==80 && free.getJSONArray("windows").getJSONObject(1).getDouble("remainingPercent")==100,"has_quota false still counts chat and completions");
        check(!free.has("remaining"),"a percentage is no balance");
        JSONObject strings=ProviderAllowance.fromCopilot(json("{\"copilot_plan\":\"business\",\"token_based_billing\":true,\"quota_snapshots\":{\"premium_interactions\":{\"unlimited\":false,\"has_quota\":false,\"entitlement\":\"1000\",\"quota_remaining\":\"250\",\"percent_remaining\":25}}}"),1);
        check(strings.getJSONArray("windows").getJSONObject(0).getDouble("remainingPercent")==25,"string counters count even with has_quota false");
        check(strings.getDouble("remaining")==250 && strings.getString("unit").equals("credits"),"string counters give the balance");
        JSONObject percentOnly=ProviderAllowance.fromCopilot(json("{\"quota_snapshots\":{\"premium_interactions\":{\"entitlement\":\"1000\",\"percent_remaining\":25}}}"),1);
        check(percentOnly.getJSONArray("windows").getJSONObject(0).getDouble("remainingPercent")==25 && !percentOnly.has("remaining"),"a percentage alone invents no balance");
        JSONObject over=ProviderAllowance.fromCopilot(json("{\"quota_snapshots\":{\"premium_interactions\":{\"entitlement\":300,\"quota_remaining\":350,\"percent_remaining\":5}}}"),1);
        check(over.getJSONArray("windows").getJSONObject(0).getDouble("remainingPercent")==100,"a count above the seat stays whole");
        JSONObject ownReset=ProviderAllowance.fromCopilot(json("{\"quota_reset_date\":\"2026-11-01\",\"quota_snapshots\":{\"premium_interactions\":{\"entitlement\":300,\"quota_remaining\":0,\"quota_reset_at\":\"2026-11-05T00:00:00Z\"}}}"),1);
        check(ownReset.getJSONArray("windows").getJSONObject(0).getString("resetAt").equals("2026-11-05T00:00:00Z"),"a snapshot's own reset wins over the month's");
        JSONObject legacy=ProviderAllowance.fromCopilot(json("{\"monthly_quotas\":{\"chat\":100},\"limited_user_quotas\":{\"chat\":150}}"),1);
        check(legacy.getJSONArray("windows").getJSONObject(0).getDouble("remainingPercent")==100,"a legacy count above its total stays whole");
        check(ProviderAllowance.fromCopilot(json("{\"monthly_quotas\":{\"chat\":0},\"limited_user_quotas\":{\"chat\":0}}"),1)==null,"a legacy total of 0 is no allowance");
        JSONObject mini=ProviderAllowance.fromMiniMax(new JSONObject("{\"model_remains\":[{\"model_name\":\"video\",\"current_interval_remaining_percent\":0},{\"model_name\":\"general\",\"current_interval_remaining_percent\":0,\"current_weekly_remaining_percent\":80}]}"),1);
        check(mini.getJSONArray("windows").length()==2 && mini.getJSONArray("windows").getJSONObject(0).getDouble("remainingPercent")==0);
        JSONObject only=ProviderAllowance.fromMiniMax(new JSONObject("{\"model_remains\":[{\"model_name\":\"video\",\"current_interval_remaining_percent\":0}]}"),1);
        check(only.getJSONArray("windows").length()==0 && only.getJSONArray("modelPools").getJSONObject(0).getJSONArray("windows").getJSONObject(0).getDouble("remainingPercent")==0);
        JSONObject counts=ProviderAllowance.fromMiniMax(new JSONObject("{\"model_remains\":[{\"model_name\":\"video\",\"current_interval_status\":1,\"current_interval_used_count\":1,\"current_interval_total_count\":3,\"current_interval_used_percent\":\"24%\",\"current_interval_total_percent\":\"150%\"}]}"),1);
        JSONObject pool=counts.getJSONArray("modelPools").getJSONObject(0).getJSONArray("windows").getJSONObject(0);
        check(pool.getDouble("remaining")==2 && pool.getDouble("total")==3 && pool.getDouble("remainingPercent")==84);
        check(ProviderAllowance.fromMiniMax(new JSONObject("{\"model_remains\":[{\"model_name\":\"video\",\"current_interval_status\":3,\"current_weekly_status\":3,\"current_interval_total_count\":0,\"current_weekly_total_count\":0}]}"),1)==null);
        // Counts alone do not establish a window in the reference plugin.
        check(ProviderAllowance.fromMiniMax(new JSONObject("{\"model_remains\":[{\"model_name\":\"video\",\"current_interval_used_count\":1,\"current_interval_total_count\":3}]}"),1)==null);
        JSONObject zeroTotal=ProviderAllowance.fromMiniMax(new JSONObject("{\"model_remains\":[{\"model_name\":\"video\",\"current_interval_used_percent\":\"24%\",\"current_interval_total_percent\":\"0%\"}]}"),1);
        check(zeroTotal.getJSONArray("modelPools").getJSONObject(0).getJSONArray("windows").getJSONObject(0).getDouble("remainingPercent")==76);
        JSONObject exhausted=ProviderAllowance.fromMiniMax(new JSONObject("{\"model_remains\":[{\"model_name\":\"video\",\"current_interval_status\":2,\"current_interval_remaining_percent\":40,\"current_interval_used_count\":1,\"current_interval_total_count\":3}]}"),1);
        JSONObject ew=exhausted.getJSONArray("modelPools").getJSONObject(0).getJSONArray("windows").getJSONObject(0);
        check(ew.getDouble("remainingPercent")==0 && ew.getDouble("remaining")==0);
        JSONObject boost=ProviderAllowance.fromMiniMax(new JSONObject("{\"model_remains\":[{\"model_name\":\"video\",\"current_weekly_remaining_percent\":83,\"weekly_boost_permille\":1500}]}"),1);
        check(boost.getJSONArray("modelPools").getJSONObject(0).getJSONArray("windows").getJSONObject(0).getDouble("remainingPercent")==83);
        JSONObject mixed=ProviderAllowance.fromMiniMax(new JSONObject("{\"model_remains\":[{\"model_name\":\"general\",\"current_interval_remaining_percent\":70},{\"model_name\":\"video\",\"current_interval_remaining_percent\":0}]}"),1);
        check(mixed.getJSONArray("windows").length()==1 && mixed.getJSONArray("windows").getJSONObject(0).getDouble("remainingPercent")==70 && mixed.getJSONArray("modelPools").length()==1);
        check(ProviderAllowance.fromMiniMax(new JSONObject("{\"base_resp\":{\"status_code\":1},\"model_remains\":[{\"model_name\":\"video\",\"current_interval_status\":2}]}"),1)==null);
        System.out.println("ProviderAllowanceOfflineTest OK");
    }
}
