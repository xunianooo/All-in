package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.ArrayList;

public final class MiniMaxAllowanceRegressionTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    private static JSONObject row(Object granted, Object left, Object expiry, int kind) throws Exception {
        return new JSONObject().put("granted_amount", granted).put("remaining_amount", left).put("expire_at_ms", expiry).put("credit_type", kind);
    }
    private static void invalid(JSONArray rows, String why) throws Exception {
        boolean failed = false; try { MiniMaxAllowance.fromCredits(rows, 100); } catch (IOException expected) { failed = true; }
        check(failed, why);
    }
    private static final class Conn extends HttpURLConnection {
        final String text; final int status; boolean closed;
        Conn(String url, String text, int status) throws Exception { super(new URL(url)); this.text = text; this.status = status; }
        public void connect() { }
        public void disconnect() { closed = true; }
        public boolean usingProxy() { return false; }
        public int getResponseCode() { return status; }
        public InputStream getInputStream() throws IOException { return new ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    }
    public static void main(String[] args) throws Exception {
        JSONArray rows = new JSONArray().put(row("5,000", "4,949.52", 200, 1)).put(row("200", "200", 200, 2)).put(row("9000", "9000", 99, 1));
        JSONObject credits = MiniMaxAllowance.fromCredits(rows, 100);
        check(Math.abs(credits.getDouble("remaining") - 5149.52) < 1e-9, "decimal credits and expired batches");
        check("credits".equals(credits.getString("unit")), "credits are not plan percentages");
        check(credits.getJSONArray("windows").length() == 0, "no invented plan window");
        check(MiniMaxAllowance.fromCredits(new JSONArray(), 100).getDouble("remaining") == 0, "known empty ledger is zero");
        check(MiniMaxAllowance.fromCredits(new JSONArray().put(row("10", "-2.5", "200", 2)), 100).getDouble("remaining") == 0, "negative balances clamp to zero");
        check(MiniMaxAllowance.fromCredits(new JSONArray().put(row("10", "20", 200, 1)), 100).getDouble("remaining") == 10, "no balance beyond grant");
        invalid(null, "missing ledger unknown");
        invalid(new JSONArray().put(row("10", "", 200, 1)), "missing remaining unknown");
        invalid(new JSONArray().put(row("-1", "0", 200, 1)), "negative grant invalid");
        invalid(new JSONArray().put(row("10", "NaN", 200, 1)), "NaN unknown");
        invalid(new JSONArray().put(row("10", "2", "unknown", 1)), "unknown expiry cannot invent live balance");
        invalid(new JSONArray().put(row("10", "2", 200, 1).put("credit_type", "1")), "wrong type unknown");
        ArrayList<Conn> opened = new ArrayList<>();
        JSONObject got = MiniMaxAllowance.fetch("synthetic-access", "", url -> {
            Conn c;
            if (url.contains("/v1/api/user/info?")) c = new Conn(url, "{\"data\":{\"userInfo\":{\"realUserID\":\"user-123\"}},\"statusInfo\":{\"code\":0}}", 200);
            else c = new Conn(url, "{\"data\":{\"details\":[{\"credit_type\":1,\"granted_amount\":\"100\",\"remaining_amount\":\"12.5\",\"expire_at_ms\":9999999999999}],\"total_count\":1},\"statusInfo\":{\"code\":0}}", 200);
            opened.add(c); return c;
        });
        check(got.getDouble("remaining") == 12.5, "signed identity and ledger without M Plan");
        check(opened.size() == 2, "no sign-in, refresh, claim or platform quota when group unknown");
        Conn ledger = opened.get(1);
        check(ledger.getURL().toString().contains("&page_number=1&page_size=20&include_expired=true"), "valid additional signed query");
        check(ledger.getURL().toString().contains("user_id=user-123"), "verified identity scoped to query");
        check(ledger.getRequestProperty("yy") != null && ledger.getRequestProperty("x-signature") != null, "account API actually signed");
        check(ledger.getConnectTimeout() == 3000 && ledger.getReadTimeout() == 5000, "bounded query timeouts");
        check(!ledger.getInstanceFollowRedirects(), "no credential-following cross-site redirect");
        check(opened.get(0).closed && ledger.closed, "all connections disconnected");
        boolean failed = false;
        try { MiniMaxAllowance.fetch("synthetic-access", "", url -> new Conn(url, "{\"data\":{\"userInfo\":{\"realUserID\":\"user-123\"}},\"statusInfo\":{\"code\":0}}", 401)); }
        catch (IOException expected) { failed = true; }
        check(failed, "HTTP rejection never parses a user identity");
        failed = false;
        try { MiniMaxAllowance.fetch("synthetic-access", "", url -> new Conn(url, "{\"data\":{\"userInfo\":{\"realUserID\":\"user-123\"}},\"statusInfo\":{\"code\":1}}", 200)); }
        catch (IOException expected) { failed = true; }
        check(failed, "business rejection never accepted");
        failed = false;
        try { MiniMaxAllowance.fetch("synthetic-access", "", url -> new Conn(url, url.contains("user/info") ? "{\"data\":{\"userInfo\":{\"realUserID\":\"user-123\"}},\"statusInfo\":{\"code\":0}}" : "{\"details\":[],\"total_count\":1,\"statusInfo\":{\"code\":0}}", 200)); }
        catch (IOException expected) { failed = true; }
        check(failed, "missing page never produces a zero");
        UsageLedger.Row usage = new UsageLedger.Row(); usage.requestId = "synthetic-request"; usage.accountId = "pool-account-123"; usage.prov = "raccoon";
        Method encode = UsageLedger.class.getDeclaredMethod("toJsonLine", UsageLedger.Row.class); encode.setAccessible(true);
        String line = (String) encode.invoke(null, usage);
        check(new JSONObject(line).getString("accountId").equals(usage.accountId), "account identity persisted separately from credentials");
        Method parse = UsageLedger.class.getDeclaredMethod("parse", String.class); parse.setAccessible(true);
        check(((UsageLedger.Row) parse.invoke(null, line)).accountId.equals(usage.accountId), "account identity survives reload");
        check(((UsageLedger.Row) parse.invoke(null, "{\"t\":1,\"prov\":\"raccoon\"}")).accountId.isEmpty(), "legacy rows remain unknown, never guessed");
        Method begin = GatewayServer.class.getDeclaredMethod("beginUsage", String.class, String.class, String.class, JSONObject.class); begin.setAccessible(true);
        begin.invoke(null, "raccoon", "raccoon/test", "test", new JSONObject().put("id", "pool-account-123").put("token", "synthetic-secret"));
        Field f = GatewayServer.class.getDeclaredField("U_ROW"); f.setAccessible(true);
        @SuppressWarnings("unchecked") ThreadLocal<UsageLedger.Row> current = (ThreadLocal<UsageLedger.Row>) f.get(null);
        check("pool-account-123".equals(current.get().accountId), "actual dispatched account captured");
        check(!((String) encode.invoke(null, current.get())).contains("synthetic-secret"), "no token in usage record");
        current.remove();
        System.out.println("MiniMaxAllowanceRegressionTest OK " + checks + " checks");
    }
}
