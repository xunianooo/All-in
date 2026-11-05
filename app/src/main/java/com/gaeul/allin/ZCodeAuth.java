package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ZCode（智谱 GLM Coding Plan / Z.ai / BigModel）登录与换 Key。
 *
 * 移植自 magpie-community/plugins 的 packages/zcode（MIT），流程与字段名逐一对齐：
 *
 *  1. App 自己 POST {ZCODE}/api/v1/oauth/cli/init 开一个登录流程（不需要用户先打开网页）
 *  2. 拿回 authorize_url，补上 redirect_uri，丢给 WebView 让用户登录
 *  3. App 轮询 {ZCODE}/api/v1/oauth/cli/poll/{flow_id}，ready 时拿到
 *     zai.access_token（站点 token）+ token（ZCode 自己的 JWT）
 *  4. 用站点 token 换业务 token，读 getCustomerInfo 找默认机构/项目，
 *     在该项目里找到或创建名为 zcode-api-key 的 Key，读 secretKey → "<id>.<secret>"
 *  5. 这把 key 就是标准 Anthropic Messages API Key，可直接进供应商池
 *
 * 所以整个登录只需要 WebView 负责「让人登录」，其余全在原生侧完成，
 * 不需要抓 cookie、不需要本地监听端口。
 */
public class ZCodeAuth {

    public static final String ZCODE = "https://zcode.z.ai";
    public static final String ZAI_API = "https://api.z.ai";
    public static final String BIGMODEL_API = "https://bigmodel.cn";
    // 官方文档：GLM Coding Plan 同时支持两套协议
    //   Anthropic Messages : https://api.z.ai/api/anthropic
    //   OpenAI Chat        : https://api.z.ai/api/coding/paas/v4
    // 本项目只实现了 OpenAI 入口（/v1/chat/completions），没有 OpenAI→Anthropic
    // 的请求体转换，所以这里必须用 OpenAI 兼容地址，否则上游会返回 404。
    public static final String ZAI_BASE = "https://api.z.ai/api/coding/paas/v4";
    public static final String BIGMODEL_BASE = "https://open.bigmodel.cn/api/coding/paas/v4";
    public static final String APP_VERSION = "3.16.3";
    public static final String UA = "ZCode/" + APP_VERSION;

    /** 一个进行中的登录流程 */
    public static class Flow {
        public String site;        // zai / bigmodel
        public String flowId;
        public String device;      // X-Device-Mid
        public String pollAuth;    // "Bearer <64 hex>"
        public long intervalMs;
        public long deadline;
        public String authorizeUrl;
    }

    /** signedIn 的产物 */
    public static class SignIn {
        public String key;      // <id>.<secret>，可为空（无编码套餐时）
        public String base;     // anthropic base
        public String plan;     // 套餐名，可空
        public String jwt;      // ZCode session token（只有它能花 gift / Start Plan 额度）
        public String device;   // X-Device-Mid，gift 请求必须与签到登录一致
        public String error;    // 失败原因
    }

    // 进行中的流程缓存：flowId -> Flow
    private static final Map<String, Flow> FLOWS = new LinkedHashMap<String, Flow>();

    private static void log(String msg) {
        android.util.Log.i("MagpieZCode", msg);
    }

    // ===================== 基础 HTTP =====================

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    /** Z.ai 系接口统一返回 {code,msg,data}，code 为 0 或 200 是成功 */
    private static JSONObject call(String method, String url, String auth, JSONObject body,
                                   String device, int timeoutMs) throws Exception {
        String host = "";
        try {
            host = new URL(url).getHost();
        } catch (Exception ignored) {
        }

        HttpURLConnection conn = NetworkPolicy.openHttp(url);
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", UA);
        if (auth != null && !auth.isEmpty()) conn.setRequestProperty("Authorization", auth);
        // 只有打 zcode.z.ai 时才带设备号，和原插件一致
        if (url.startsWith(ZCODE + "/")) {
            conn.setRequestProperty("X-Device-Mid", device == null || device.isEmpty()
                    ? UUID.randomUUID().toString() : device);
        }

        if (body != null && !"GET".equals(method)) {
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.flush();
            os.close();
        }

        int code;
        InputStream is;
        String text;
        try {
            code = conn.getResponseCode();
            is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            text = readAll(is);
        } catch (Exception netErr) {
            // 连接层面失败时才诊断 DNS：代理正常工作的情况下 fake-ip 也能连通，
            // 所以不能提前拦截，只能在真失败后给一个可读的原因。
            String hint = dnsHint(host);
            throw new Exception(hint != null ? hint : (netErr.getMessage() == null ? "网络错误" : netErr.getMessage()));
        }

        JSONObject env = null;
        try {
            env = new JSONObject(text);
        } catch (Exception ignored) {
        }

        String bizCode = (env == null || env.isNull("code")) ? "" : String.valueOf(env.opt("code"));
        if (code < 200 || code >= 400) {
            String msg = env == null ? null : env.optString("msg", "");
            if (msg == null || msg.isEmpty()) msg = "HTTP " + code;
            throw new Exception(msg + " (" + code + ")");
        }
        if (env == null) throw new Exception("上游返回的不是 JSON");
        if (!bizCode.isEmpty() && !"0".equals(bizCode) && !"200".equals(bizCode)) {
            throw new Exception(env.optString("msg", "error " + bizCode));
        }
        JSONObject data = env.optJSONObject("data");
        return data == null ? new JSONObject() : data;
    }

    /**
     * 连接失败时判断是否被代理软件的 fake-ip 坑了，给出可读提示。
     *
     * 代理开 fake-ip 时域名会解析到 28.x.x.x / 198.18.x.x / 0.x.x.x 这类保留地址，
     * 一旦该 App 的流量没被代理接管，就会去连假 IP 并失败，原始报错是
     * "Failed to connect to api.z.ai/28.0.0.187:443"，用户看不懂。
     *
     * @return 可读提示；不像 fake-ip 时返回 null，由调用方沿用原始错误
     */
    private static String dnsHint(String host) {
        if (host == null || host.isEmpty()) return null;
        String fake = null;
        try {
            java.net.InetAddress[] addrs = java.net.InetAddress.getAllByName(host);
            if (addrs != null) {
                for (java.net.InetAddress a : addrs) {
                    String ip = a.getHostAddress();
                    if (ip != null && looksLikeFakeIp(ip)) {
                        fake = ip;
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
            return "解析不了 " + host + "，请检查手机网络";
        }
        if (fake == null) return null;
        return "连不上 " + host + "：它被解析到 " + fake
                + "（代理软件的 fake-ip 假地址）。请关闭代理，或把本应用设为直连后重试。";
    }

    /** fake-ip 常见段：28/8（Clash 等）、198.18/15（基准测试保留段）、0.x */
    private static boolean looksLikeFakeIp(String ip) {
        if (ip == null) return false;
        if (ip.startsWith("28.")) return true;
        if (ip.startsWith("198.18.") || ip.startsWith("198.19.")) return true;
        if (ip.startsWith("0.")) return true;
        return false;
    }

    /** 设置 query 参数：存在同名则就地替换并丢弃多余的同名项，否则追加 */
    private static String setQueryParam(String url, String name, String value) {
        int q = url.indexOf('?');
        String head = q < 0 ? url : url.substring(0, q);
        String query = q < 0 ? "" : url.substring(q + 1);
        StringBuilder sb = new StringBuilder();
        boolean done = false;
        if (!query.isEmpty()) {
            for (String kv : query.split("&")) {
                if (kv.isEmpty()) continue;
                int eq = kv.indexOf('=');
                String k = eq < 0 ? kv : kv.substring(0, eq);
                if (name.equals(k)) {
                    if (!done) {
                        if (sb.length() > 0) sb.append('&');
                        sb.append(k).append('=').append(value);
                        done = true;
                    }
                } else {
                    if (sb.length() > 0) sb.append('&');
                    sb.append(kv);
                }
            }
        }
        if (!done) {
            if (sb.length() > 0) sb.append('&');
            sb.append(name).append('=').append(value);
        }
        return head + "?" + sb.toString();
    }

    private static String first(String... ss) {
        for (String s : ss) {
            if (s != null && !s.trim().isEmpty()) return s.trim();
        }
        return "";
    }

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // ===================== 1. 开启登录流程 =====================

    /**
     * 开一个 ZCode 登录流程，返回可丢给 WebView 的授权页 URL。
     * @param site "zai" 或 "bigmodel"
     */
    public static Flow start(String site) throws Exception {
        String s = "bigmodel".equals(site) ? "bigmodel" : "zai";
        Flow f = new Flow();
        f.site = s;
        f.device = UUID.randomUUID().toString();
        f.pollAuth = "Bearer " + hex(32);

        JSONObject body = new JSONObject();
        body.put("provider", s);

        JSONObject data;
        try {
            data = call("POST", ZCODE + "/api/v1/oauth/cli/init", f.pollAuth, body, f.device, 20000);
        } catch (Exception e) {
            throw new Exception("ZCode 登录初始化失败: " + e.getMessage());
        }

        f.flowId = data.optString("flow_id", "");
        String authorizeUrl = data.optString("authorize_url", "");
        if (f.flowId.isEmpty() || authorizeUrl.isEmpty()) {
            throw new Exception("ZCode 没有返回登录页");
        }
        if (!authorizeUrl.startsWith("https://")) throw new Exception("ZCode 返回的登录页地址不合法");

        // 补上回跳地址（BigModel 的页面把它叫 redirect）
        String back = ZCODE + "/app/oauth/login?redirect="
                + URLEncoder.encode("zcode://oauth/callback", "UTF-8")
                + "&app_version=" + APP_VERSION;
        String param = "bigmodel".equals(s) ? "redirect" : "redirect_uri";
        // 必须替换同名参数而不是追加：原 authorize_url 里已经带了 redirect_uri
        // （指向 cli/callback/zai），追加会变成两个 redirect_uri，页面通常只认第一个
        f.authorizeUrl = setQueryParam(authorizeUrl, param, URLEncoder.encode(back, "UTF-8"));

        int intervalSec = data.optInt("poll_interval_sec", 0);
        f.intervalMs = Math.max(intervalSec, 1) * 1000L;
        long expiresAt = data.optLong("expires_at", 0);
        f.deadline = expiresAt > 0 ? expiresAt * 1000L : System.currentTimeMillis() + 5 * 60 * 1000L;

        FLOWS.put(f.flowId, f);
        log("flow started: " + f.flowId + " site=" + s);
        return f;
    }

    // ===================== 2. 轮询结果 =====================

    public static class PollResult {
        public String status;   // pending / ready / failed
        public String error;
        public SignIn signIn;
        public String account;
        public String access;   // 成功时的 access（key 或 jwt），供展示/兜底
    }

    public static PollResult poll(String flowId) throws Exception {
        Flow f = FLOWS.get(flowId);
        if (f == null) throw new Exception("登录流程不存在或已结束，请重新发起");

        PollResult out = new PollResult();
        if (System.currentTimeMillis() > f.deadline) {
            FLOWS.remove(flowId);
            out.status = "failed";
            out.error = "登录已超时，请重新发起";
            return out;
        }

        JSONObject data;
        try {
            data = call("GET", ZCODE + "/api/v1/oauth/cli/poll/"
                    + URLEncoder.encode(f.flowId, "UTF-8"), f.pollAuth, null, f.device, 20000);
        } catch (Exception e) {
            // 4xx（除 408/429）说明流程废了，其余当网络抖动继续问
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (m.contains("(4") && !m.contains("(408") && !m.contains("(429")) {
                FLOWS.remove(flowId);
                out.status = "failed";
                out.error = "ZCode 登录: " + m;
                return out;
            }
            out.status = "pending";
            return out;
        }

        String status = data.optString("status", "");
        JSONObject user = data.optJSONObject("user");
        out.account = user == null ? "" : first(user.optString("email"), user.optString("name"), user.optString("id"));

        if (status.isEmpty() || "pending".equals(status)) {
            out.status = "pending";
            return out;
        }
        if ("failed".equals(status)) {
            FLOWS.remove(flowId);
            out.status = "failed";
            out.error = "登录被拒绝";
            return out;
        }
        if (!"ready".equals(status)) {
            FLOWS.remove(flowId);
            out.status = "failed";
            out.error = "未知状态: " + status;
            return out;
        }

        // ready：取站点 token
        String token = "";
        if ("bigmodel".equals(f.site)) {
            JSONObject bm = data.optJSONObject("bigmodel");
            if (bm != null) token = first(bm.optString("access_token"), bm.optString("accessToken"));
        } else {
            JSONObject zai = data.optJSONObject("zai");
            if (zai != null) token = first(zai.optString("access_token"), zai.optString("accessToken"));
        }
        String jwt = data.optString("token", "");
        if (token.isEmpty()) {
            FLOWS.remove(flowId);
            out.status = "failed";
            out.error = "ZCode 没有返回访问令牌";
            return out;
        }

        try {
            SignIn si = signedIn(f.site, token, jwt, f.device);
            out.signIn = si;
            out.status = "ready";
            out.access = first(si.key, si.jwt);
            FLOWS.remove(flowId);
        } catch (Exception e) {
            FLOWS.remove(flowId);
            out.status = "failed";
            out.error = e.getMessage();
        }
        return out;
    }

    // ===================== 3. 换出 API Key =====================

    // ===================== 4. Gift / Start Plan（只有 session JWT 能花） =====================
    //
    // 关键事实（packages/zcode README + checkin/gift 测试）：
    //   API Key 只能花 GLM Coding Plan，花不到 ZCode 送的 gift（夜晚赠送 / Start Plan / Trust Build）。
    //   gift 挂在 ZCode 自己的 session token（登录时 data.token 那个 JWT）上，
    //   走 zcode.z.ai 的 /api/v1/zcode-plan/anthropic，而不是 api.z.ai 的 /api/anthropic。
    //   所以「key 有值但调用 1113」= 这个号本身没 Plan 或 Plan 额度用完，额度在 gift 里。

    public static final String GIFT_BASE = ZCODE + "/api/v1/zcode-plan";

    private static String giftAuth(String jwt) {
        if (jwt == null || jwt.trim().isEmpty()) return "";
        String j = jwt.trim();
        return j.startsWith("Bearer ") ? j : "Bearer " + j;
    }

    /** 今天有没有可领取的额度包（preview） */
    public static JSONObject giftPreview(String jwt, String device) throws Exception {
        return call("GET", GIFT_BASE + "/billing/preview", giftAuth(jwt), null, device, 20000);
    }

    /** 各 gift 包剩多少（balances[].remaining_units / total_units） */
    public static JSONObject giftBalance(String jwt, String device) throws Exception {
        return call("GET", GIFT_BASE + "/billing/balance", giftAuth(jwt), null, device, 20000);
    }

    /** 领取某个额度包；plan_id 来自 preview 的 data.plans[].plan_id */
    public static JSONObject giftClaim(String jwt, String device, String planId) throws Exception {
        JSONObject body = new JSONObject();
        body.put("plan_id", planId);
        return call("POST", GIFT_BASE + "/billing/claim", giftAuth(jwt), body, device, 20000);
    }

    /** 汇总：今天可领几个、各包还剩多少、合计剩余单位 */
    public static JSONObject giftSummary(String jwt, String device) {
        JSONObject out = new JSONObject();
        try {
            JSONObject prev = giftPreview(jwt, device);
            JSONArray plans = prev.optJSONArray("plans");
            JSONArray claimable = new JSONArray();
            for (int i = 0; plans != null && i < plans.length(); i++) {
                JSONObject p = plans.optJSONObject(i);
                if (p == null) continue;
                String st = p.optString("status", "");
                if (!st.isEmpty() && !"active".equalsIgnoreCase(st)) continue;
                JSONObject o = new JSONObject();
                o.put("planId", p.optString("plan_id", ""));
                o.put("name", p.optString("name", ""));
                o.put("endsAt", p.optLong("ends_at", 0));
                claimable.put(o);
            }
            out.put("claimable", claimable);

            JSONObject bal = giftBalance(jwt, device);
            JSONArray buckets = bal.optJSONArray("balances");
            JSONArray live = new JSONArray();
            long remaining = 0;
            for (int i = 0; buckets != null && i < buckets.length(); i++) {
                JSONObject b = buckets.optJSONObject(i);
                if (b == null) continue;
                long rem = b.optLong("remaining_units", -1);
                long total = b.optLong("total_units", 0);
                long exp = b.optLong("expires_at", 0);
                long now = System.currentTimeMillis() / 1000L;
                if (exp > 0 && exp < now) continue;
                JSONObject o = new JSONObject();
                o.put("planId", b.optString("plan_id", ""));
                o.put("name", b.optString("show_name", b.optString("name", "")));
                o.put("total", total);
                o.put("used", b.optLong("used_units", 0));
                o.put("remaining", rem);
                o.put("expiresAt", exp);
                JSONArray caps = b.optJSONArray("capabilities");
                o.put("capabilities", caps == null ? new JSONArray() : caps);
                live.put(o);
                if (rem > 0) remaining += rem;
            }
            out.put("buckets", live);
            out.put("remainingUnits", remaining);
            out.put("hasGift", remaining > 0);
            out.put("ok", true);
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("error", e.getMessage() == null ? "gift 查询失败" : e.getMessage());
            } catch (Exception ignored) {}
        }
        return out;
    }

    /** Z.ai 的 token 要换一次业务 token；BigModel 直接用 */
    private static String bizAuth(String site, String token) throws Exception {
        if ("bigmodel".equals(site)) return token;
        JSONObject body = new JSONObject();
        body.put("token", token);
        JSONObject biz;
        try {
            biz = call("POST", ZAI_API + "/api/auth/z/login", null, body, null, 20000);
        } catch (Exception e) {
            throw new Exception("Z.ai 登录: " + e.getMessage());
        }
        String at = biz.optString("access_token", "");
        if (at.isEmpty()) throw new Exception("Z.ai 登录: 没有返回业务令牌");
        return "Bearer " + at;
    }

    private static String keysUrl(String root, String org, String project) throws Exception {
        return root + "/api/biz/v1/organization/" + URLEncoder.encode(org, "UTF-8")
                + "/projects/" + URLEncoder.encode(project, "UTF-8") + "/api_keys";
    }

    /** 找到或创建项目里名为 zcode-api-key 的 Key，并读出 <id>.<secret> */
    private static String projectKey(String site, String keys, String auth, JSONObject headers, String wantName)
            throws Exception {
        String name = "bigmodel".equals(site) ? "BigModel" : "Z.ai";
        // 列表接口的 data 是数组，走 fetchArray
        JSONArray arr;
        try {
            arr = fetchArray("GET", keys, auth, null);
        } catch (Exception e) {
            throw new Exception(name + " API keys: " + e.getMessage());
        }
        String id = "";
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject k = arr.optJSONObject(i);
                if (k == null) continue;
                if (wantName.equals(k.optString("name")) && !first(k.optString("apiKey")).isEmpty()) {
                    id = first(k.optString("apiKey"));
                }
            }
        }
        if (id.isEmpty()) {
            JSONObject body = new JSONObject();
            body.put("name", wantName);
            JSONObject created;
            try {
                created = postForData(keys, auth, body);
            } catch (Exception e) {
                throw new Exception(name + " API key: " + e.getMessage());
            }
            id = first(created.optString("apiKey"));
        }
        String secret = "";
        if (!id.isEmpty()) {
            JSONObject copied;
            try {
                copied = call("GET", keys + "/copy/" + URLEncoder.encode(id, "UTF-8"),
                        auth, null, null, 20000);
            } catch (Exception e) {
                throw new Exception(name + " API key: " + e.getMessage());
            }
            secret = first(copied.optString("secretKey"));
        }
        if (id.isEmpty() || secret.isEmpty()) throw new Exception(name + " 没有给出可用的 API Key");
        return id + "." + secret;
    }

    /** 列表接口的 data 是数组时，用这个方法取 */
    private static JSONArray fetchArray(String method, String url, String auth, JSONObject body) throws Exception {
        HttpURLConnection conn = NetworkPolicy.openHttp(url);
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", UA);
        if (auth != null && !auth.isEmpty()) conn.setRequestProperty("Authorization", auth);
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        String text = readAll(is);
        try {
            JSONObject env = new JSONObject(text);
            if (env.has("code")) {
                String c = String.valueOf(env.opt("code"));
                if (!"0".equals(c) && !"200".equals(c)) throw new Exception(env.optString("msg", "error " + c));
            }
            return env.optJSONArray("data");
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().startsWith("error")) throw e;
            try {
                return new JSONArray(text);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private static JSONObject postForData(String url, String auth, JSONObject body) throws Exception {
        return call("POST", url, auth, body, null, 20000);
    }

    /** 账号默认机构/默认项目里建档，再读套餐名 */
    private static SignIn signedIn(String site, String token, String jwt, String device) throws Exception {
        String apiRoot = "bigmodel".equals(site) ? BIGMODEL_API : ZAI_API;
        String base = "bigmodel".equals(site) ? BIGMODEL_BASE : ZAI_BASE;
        String name = "bigmodel".equals(site) ? "BigModel" : "Z.ai";

        String auth = bizAuth(site, token);

        JSONObject info;
        try {
            info = call("GET", apiRoot + "/api/biz/customer/getCustomerInfo", auth, null, null, 20000);
        } catch (Exception e) {
            throw new Exception(name + " 账号: " + e.getMessage());
        }

        // 选默认机构 / 默认项目（不含 team 项目 projectType=2）
        String org = "";
        String proj = "";
        JSONArray organizations = info.optJSONArray("organizations");
        if (organizations != null) {
            for (int i = 0; i < organizations.length(); i++) {
                JSONObject o = organizations.optJSONObject(i);
                if (o == null) continue;
                String oid = o.optString("organizationId", "");
                JSONArray projects = o.optJSONArray("projects");
                if (projects == null || oid.isEmpty()) continue;
                String def = "";
                int n = 0;
                for (int j = 0; j < projects.length(); j++) {
                    JSONObject p = projects.optJSONObject(j);
                    if (p == null) continue;
                    String pid = p.optString("projectId", "");
                    if (pid.isEmpty() || "2".equals(String.valueOf(p.opt("projectType")))) continue;
                    n++;
                    if (def.isEmpty()) def = pid;
                    if (p.optString("projectName", "").contains("默认项目")) def = pid;
                }
                if (n == 0 || def.isEmpty()) continue;
                boolean named = o.optString("organizationName", "").contains("默认机构");
                if (org.isEmpty() || named) {
                    org = oid;
                    proj = def;
                    if (named) break;
                }
            }
        }
        if (org.isEmpty()) {
            throw new Exception("这个 " + name + " 账号还没有可用于创建 API Key 的项目");
        }

        SignIn out = new SignIn();
        out.jwt = jwt;
        out.device = device;
        out.base = base;
        String key = projectKey(site, keysUrl(apiRoot, org, proj), auth, null, "zcode-api-key");
        out.key = key;

        // 读套餐名（失败不影响登录）
        try {
            JSONArray subs = fetchArray("GET", apiRoot + "/api/biz/subscription/list", key, null);
            if (subs != null) {
                for (int i = 0; i < subs.length(); i++) {
                    JSONObject s = subs.optJSONObject(i);
                    if (s == null) continue;
                    if ("VALID".equalsIgnoreCase(s.optString("status", ""))) {
                        out.plan = s.optString("productName", "");
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 用已有的 API Key 补一次套餐名（供绑定时展示） */
    public static String planOf(String site, String key) {
        try {
            String apiRoot = "bigmodel".equals(site) ? BIGMODEL_API : ZAI_API;
            JSONArray subs = fetchArray("GET", apiRoot + "/api/biz/subscription/list", key, null);
            if (subs != null) {
                for (int i = 0; i < subs.length(); i++) {
                    JSONObject s = subs.optJSONObject(i);
                    if (s != null && "VALID".equalsIgnoreCase(s.optString("status", ""))) {
                        return s.optString("productName", "");
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    public static String baseOf(String site) {
        return "bigmodel".equals(site) ? BIGMODEL_BASE : ZAI_BASE;
    }

    /**
     * 用 key 打一次 /models，判断该端点在这条网络链路上是否真的可用。
     * 背景：api.z.ai 在国内部分网络会被直接切断（TLS 握手阶段 EOF），
     * 端口能连但拿不到响应，表现为「供应商配好了却一直调不通」。
     */
    public static boolean probeBase(String base, String key) {
        java.net.HttpURLConnection conn = null;
        try {
            conn = NetworkPolicy.openHttp(
                    base.replaceAll("/+$", "") + "/models");
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("Authorization", "Bearer " + key);
            return conn.getResponseCode() == 200;
        } catch (Throwable t) {
            return false;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * 选一个真正可达的端点：优先用户登录的站点，不通就换另一个。
     * 两个都不通返回 null（调用方自行回退到 baseOf）。
     */
    public static String pickReachableBase(String site, String key) {
        String primary = baseOf(site);
        String alt = ZAI_BASE.equals(primary) ? BIGMODEL_BASE : ZAI_BASE;
        if (probeBase(primary, key)) return primary;
        if (probeBase(alt, key)) return alt;
        return null;
    }
}
