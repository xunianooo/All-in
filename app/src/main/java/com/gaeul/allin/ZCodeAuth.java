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
    /**
     * 向 ZCode 自称的客户端版本：登录换 Key、取余额、领取、能力同步都带上它，ZCode 也按它放行。
     *
     * 上游 packages/zcode 的 APP_VERSION 是 3.17.2，并在注释里记了实测：marketing feed
     * 对更老的版本返回空（#678）；Start Plan 余额 "judges capability by the real
     * app_version"；/billing/claim 在 3.16.3 的 header 下让六个账号全返 1004
     * (not eligible)，同一晚换成 3.17.2 的 header 后其中四个 code 0 领取成功。
     * 所以这个号跟着上游走，不自行挑版本。
     */
    public static final String APP_VERSION = "3.17.2";
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

    public static final long MAX_RETRY_AFTER_MS = 6L * 60 * 60 * 1000;

    /** Structured service failure; never contains an upstream response body. */
    public static class ServiceException extends Exception {
        public final int status;
        public final int businessCode;
        public final long retryAfterMs;
        public ServiceException(int status, int businessCode, long retryAfterMs) {
            super(businessCode == 3002 || status == 429 ? "ZCode rate limited" :
                    "ZCode request failed (" + status + ")" + (businessCode != 0 ? " code " + businessCode : ""));
            this.status = status;
            this.businessCode = businessCode;
            this.retryAfterMs = Math.max(0, Math.min(MAX_RETRY_AFTER_MS, retryAfterMs));
        }
        public boolean isRateLimited() { return status == 429 || businessCode == 3002; }
    }

    /**
     * 领取要 ZCode 自己客户端的阿里云 captcha 证明（X-Aliyun-Captcha-Verify-Param），
     * 本应用造不出来：配置说校验开着时一个请求都不发，交给 ZCode 客户端领。
     *
     * 对齐上游 claimDaily 的 captcha 分支（它给的是 outcome "captcha"，不发 claim）。
     */
    public static final class CaptchaRequiredException extends Exception {
        CaptchaRequiredException(String message) { super(message); }
    }

    /**
     * 领取前的「要不要验证码」读不出来：不知道就等于不能发。
     *
     * 上游把这种情形算 failed（unknown），从不当成「没有校验」。
     */
    public static final class ClaimUnknownException extends Exception {
        ClaimUnknownException(String message) { super(message); }
    }

    static long parseRetryAfter(String value, long now) {
        if (value == null || value.trim().isEmpty()) return 0;
        String v = value.trim();
        try {
            double seconds = Double.parseDouble(v);
            if (!Double.isFinite(seconds) || seconds <= 0) return 0;
            return (long)Math.min(MAX_RETRY_AFTER_MS, seconds * 1000.0);
        } catch (NumberFormatException ignored) { }
        try {
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US);
            fmt.setLenient(false);
            java.text.ParsePosition pos = new java.text.ParsePosition(0);
            java.util.Date date = fmt.parse(v, pos);
            if (date == null || pos.getIndex() != v.length()) return 0;
            long at = date.getTime();
            if (at <= now) return 0;
            return at - now < 0 ? MAX_RETRY_AFTER_MS : Math.min(MAX_RETRY_AFTER_MS, at - now);
        } catch (Exception ignored) { return 0; }
    }

    /** Z.ai 系接口统一返回 {code,msg,data}，code 为 0 或 200 是成功 */
    private static JSONObject call(String method, String url, String auth, JSONObject body,
                                   String device, int timeoutMs) throws Exception {
        return call(method, url, auth, body, device, timeoutMs, null);
    }

    /** extra 是这个请求要额外带的头（marketing 通道的版本/平台/语言头），按给定顺序补上 */
    private static JSONObject call(String method, String url, String auth, JSONObject body,
                                   String device, int timeoutMs, Map<String,String> extra) throws Exception {
        JSONObject envelope = callEnvelope(method, url, auth, body, device, timeoutMs, extra);
        JSONObject data = envelope.optJSONObject("data");
        return data == null ? new JSONObject() : data;
    }

    /**
     * 同一个请求，返回整包 {code,msg,data}。
     *
     * 只有 marketing/touch 用它：data 是数组或标量时说明这份 feed 读不懂，不能当成
     * 「没有可领」，而 call() 交给其他调用方的契约是 data 对象本身。
     */
    private static JSONObject callEnvelope(String method, String url, String auth, JSONObject body,
                                           String device, int timeoutMs, Map<String,String> extra) throws Exception {
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
        if (extra != null) {
            for (Map.Entry<String,String> header : extra.entrySet()) {
                if (header.getKey() != null && header.getValue() != null) {
                    conn.setRequestProperty(header.getKey(), header.getValue());
                }
            }
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
        int businessCode = 0;
        try { businessCode = Integer.parseInt(bizCode); } catch (Exception ignored) { }
        if (code < 200 || code >= 400 || (!bizCode.isEmpty() && !"0".equals(bizCode) && !"200".equals(bizCode))) {
            throw new ServiceException(code, businessCode, parseRetryAfter(conn.getHeaderField("Retry-After"), System.currentTimeMillis()));
        }
        if (env == null) throw new Exception("上游返回的不是 JSON");
        JSONObject data = env.optJSONObject("data");
        // Only an explicitly successful answer can mean "nothing to claim" — for the
        // old preview and for the feed that replaced it. A reply without the success
        // marker is unread, and unread is never none. Keep other shared call sites'
        // response contracts unchanged.
        if ((GIFT_BASE + "/billing/preview").equals(url) || url.startsWith(TOUCH + "?")) {
            if (!"0".equals(bizCode) && !"200".equals(bizCode))
                throw new GiftDataException("gift 可领取计划响应缺少成功标志");
            if (data == null && env.has("data") && !env.isNull("data"))
                throw new GiftDataException("gift 可领取计划数据不完整");
        }
        // 领取和它的验证码配置也只认显式的成功标志：一个没有 code 的 200 既不是
        // 「领到了」也不是「不用验证码」，是读不懂（上游把这两种都算 unknown，
        // 从不把没读懂的回复当成成功）。
        if ((GIFT_BASE + "/billing/claim").equals(url) || url.startsWith(CLIENT_CONFIGS + "?")) {
            if (!"0".equals(bizCode) && !"200".equals(bizCode))
                throw new ServiceException(code, businessCode,
                        parseRetryAfter(conn.getHeaderField("Retry-After"), System.currentTimeMillis()));
        }
        return env;
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

    /** 旧的可领取列表（billing/preview）。v3.17 客户端已不再读它，只留给兼容调用方。 */
    public static JSONObject giftPreview(String jwt, String device) throws Exception {
        return call("GET", GIFT_BASE + "/billing/preview", giftAuth(jwt), null, device, 20000);
    }

    /**
     * 各 gift 包剩多少（balances[].remaining_units / total_units）。
     *
     * app_version 不能省：上游每次取余额都是 billing/balance?app_version=APP_VERSION，
     * 注释写明 Start Plan 余额 "judges capability by the real app_version"；不带它
     * 等于让上游按一个读不出能力的版本回答，能力标签可能为空，gift 路由就会以为
     * 这个号没有可花的额度。
     */
    public static JSONObject giftBalance(String jwt, String device) throws Exception {
        return call("GET", GIFT_BASE + "/billing/balance?app_version=" + APP_VERSION,
                giftAuth(jwt), null, device, 20000);
    }

    /**
     * 领取某个额度包；plan_id 来自 marketing/touch 里的 claim_zcode_plan 动作。
     *
     * 领取同样按客户端版本放行：上游 claimOne 带 UA、X-Device-Mid 和整组
     * marketingHeaders，这里也带版本/平台/语言三个头。
     *
     * 发之前先问 client/configs 要不要验证码（上游 claimDaily 的 claimNeedsCaptcha）：
     * 说开着、或者读不懂，这里一个请求都不发——没有 App 的 captcha 证明，
     * 发出去只会换一个 3007。读不懂是 unknown，不是「不用校验」。
     */
    public static JSONObject giftClaim(String jwt, String device, String planId) throws Exception {
        Boolean guarded;
        try {
            guarded = giftClaimNeedsCaptcha(giftClientConfigs(jwt, device));
        } catch (Exception unread) {
            throw new ClaimUnknownException("读不到 ZCode 的领取校验配置，未发送领取请求");
        }
        if (guarded == null) throw new ClaimUnknownException("ZCode 的领取校验配置读不懂，未发送领取请求");
        if (guarded.booleanValue()) throw new CaptchaRequiredException("ZCode 的领取要它自己客户端的验证码，请在 ZCode 客户端里领取");
        JSONObject body = new JSONObject();
        body.put("plan_id", planId);
        return call("POST", GIFT_BASE + "/billing/claim", giftAuth(jwt), body, device, 20000, marketingHeaders());
    }

    // ===================== 领取前的验证码配置：client/configs =====================
    //
    // 上游 clientConfigs 读 /api/v1/client/configs?app_version=…&platform=…：读通了留
    // 10 分钟，读失败不留也不吞（抛给调用方当未知）。claimNeedsCaptcha 只看
    // data.configs.captcha：缺省=不校验；不是对象=未知；否则 enabled !== false。

    static final String CLIENT_CONFIGS = ZCODE + "/api/v1/client/configs";
    private static final long CLIENT_CONFIGS_TTL_MS = 10 * 60_000L;
    private static volatile JSONObject clientConfigsCached;
    private static volatile long clientConfigsAt;

    static String clientConfigsUrl() {
        return CLIENT_CONFIGS + "?app_version=" + APP_VERSION + "&platform=" + ZCodeGift.platform();
    }

    /** 读 ZCode 给这个客户端版本的配置；读失败不缓存，也不当成「没有配置」。 */
    public static JSONObject giftClientConfigs(String jwt, String device) throws Exception {
        JSONObject cached = clientConfigsCached;
        long at = clientConfigsAt;
        long now = System.currentTimeMillis();
        if (cached != null && now >= at && now - at < CLIENT_CONFIGS_TTL_MS) return cached;
        JSONObject read = call("GET", clientConfigsUrl(), null, null, device, 20000);
        clientConfigsCached = read;
        clientConfigsAt = now;
        return read;
    }

    /** 只给测试：让下一次读真的去问上游。 */
    static void resetGiftClientConfigsCache() { clientConfigsCached = null; clientConfigsAt = 0; }

    /** 上游 claimNeedsCaptcha：true=要校验，false=不校验，null=读不懂（未知）。 */
    static Boolean giftClaimNeedsCaptcha(JSONObject configs) {
        JSONObject cfg = configs == null ? null : configs.optJSONObject("configs");
        if (cfg == null || !cfg.has("captcha") || cfg.isNull("captcha")) return Boolean.FALSE;
        Object captcha = cfg.opt("captcha");
        if (!(captcha instanceof JSONObject)) return null;
        return Boolean.valueOf(((JSONObject) captcha).optBoolean("enabled", true));
    }

    // ===================== 可领取计划：marketing/touch =====================
    //
    // v3.17 客户端把老的 billing/preview 轮询换成 marketing/touch 这一个 feed
    // （上游 toClaim 的注释、README 的 The daily claim 一节都是这么写的），而且
    // 横幅型计划（ZCODE WEEKEND BUILD 那一类）只在 feed 里、从不进 preview
    // （#503）：只读 preview 会把它漏掉，所以可领取计划必须读 feed。
    // 每个 claim_zcode_plan 动作就是一个可领取计划，名字在同一条 banner 的
    // zcode_plan.name 上（feed 里放哪儿不固定，所以按整棵 deliveries 找）。

    private static final String TOUCH = ZCODE + "/api/v1/marketing/touch";
    private static final String CLAIM_ACTION = "claim_zcode_plan";
    private static final java.util.concurrent.atomic.AtomicInteger TOUCH_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    /** feed 和领取都按客户端版本/平台/语言放行（上游 marketingHeaders） */
    private static Map<String,String> marketingHeaders() {
        Map<String,String> headers = new LinkedHashMap<String,String>();
        headers.put("X-ZCode-App-Version", APP_VERSION);
        headers.put("X-Platform", ZCodeGift.platform());
        headers.put("X-Client-Language", clientLanguage());
        return headers;
    }

    /** marketing/touch 只认 ZCode 的两种界面语言：zh-CN / en-US */
    private static String clientLanguage() {
        String language = "";
        try {
            java.util.Locale l = java.util.Locale.getDefault();
            language = l == null ? "" : l.getLanguage();
        } catch (Exception ignored) { }
        return language != null && language.toLowerCase(java.util.Locale.US).startsWith("zh") ? "zh-CN" : "en-US";
    }

    /**
     * GET marketing/touch：seq 是 ZCode 客户端进程内的 feed 请求序号（bootRequestSeq
     * 的等价物，只供服务端日志定位、不改变投放），每次问都自增后带上。
     */
    public static JSONObject giftTouch(String jwt, String device) throws Exception {
        int seq = TOUCH_SEQ.getAndIncrement();
        if (seq < 0) { TOUCH_SEQ.set(0); seq = 0; }
        return callEnvelope("GET", TOUCH + "?seq=" + seq, giftAuth(jwt), null, device, 20000, marketingHeaders());
    }

    /**
     * feed 里每个 delivery 的 claim_zcode_plan 动作 = 一个可领取计划。
     * 空数组表示 feed 读懂了但没有可领的；null 表示读不懂（未知，不能当成没有）。
     *
     * 按上游 touchPlans 走整条 delivery（它的注释写明理由是「按钮被挪进 banner 里也还认」），
     * 一条 delivery 的 plan_id 与名字成对取，跨 delivery 不混。名字取这条 delivery 里第一个
     * zcode_plan.name，取不到就用 plan_id 本身（上游 first(names[0] ?? "", id)）；
     * 同一个 plan_id 只留一次（上游 toClaim 的 add）。真正决定「要不要发领取」的是用户
     * 点的那一下和 captcha 门，扫描面只决定列出哪些计划。
     */
    static JSONArray touchPlans(JSONObject envelope) {
        if (envelope == null) return new JSONArray();
        JSONObject data = envelope.optJSONObject("data");
        // data 不是对象（数组/标量）：这不是「没有可领」，是读不懂
        if (data == null && envelope.has("data") && !envelope.isNull("data")) return null;
        if (data == null) return new JSONArray();
        if (!data.has("deliveries") || data.isNull("deliveries")) return new JSONArray();
        JSONArray deliveries = data.optJSONArray("deliveries");
        if (deliveries == null) return null;
        JSONArray out = new JSONArray();
        for (int i = 0; i < deliveries.length(); i++) {
            JSONObject delivery = deliveries.optJSONObject(i);
            if (delivery == null) continue;
            java.util.List<String> ids = new java.util.ArrayList<String>();
            java.util.List<String> names = new java.util.ArrayList<String>();
            java.util.List<Long> ends = new java.util.ArrayList<Long>();
            collectClaimActions(delivery, 0, ids);
            collectPlanNames(delivery, 0, names, ends);
            for (int k = 0; k < ids.size(); k++) {
                String id = ids.get(k);
                if (id == null || id.isEmpty() || containsPlan(out, id)) continue;
                try {
                    JSONObject plan = new JSONObject().put("planId", id);
                    plan.put("name", names.isEmpty() ? id : names.get(0));
                    Long end = ends.isEmpty() ? null : ends.get(0);
                    if (end != null && end.longValue() > 0) plan.put("endsAt", end.longValue());
                    out.put(plan);
                } catch (Exception ignored) { }
            }
        }
        return out;
    }

    private static boolean containsPlan(JSONArray plans, String id) {
        for (int i = 0; i < plans.length(); i++) {
            JSONObject known = plans.optJSONObject(i);
            if (known != null && id.equals(known.optString("planId", ""))) return true;
        }
        return false;
    }

    /** 一条 delivery 里找 claim_zcode_plan 的 args.plan_id，位置不限（上游 walk 同口径） */
    private static void collectClaimActions(Object node, int depth, java.util.List<String> ids) {
        if (node == null || depth > 12) return;
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) collectClaimActions(arr.opt(i), depth + 1, ids);
            return;
        }
        if (!(node instanceof JSONObject)) return;
        JSONObject obj = (JSONObject) node;
        if (CLAIM_ACTION.equals(obj.optString("type", ""))) {
            JSONObject args = obj.optJSONObject("args");
            String id = args == null ? "" : args.optString("plan_id", "").trim();
            if (!id.isEmpty()) ids.add(id);
        }
        java.util.Iterator<String> keys = obj.keys();
        while (keys.hasNext()) collectClaimActions(obj.opt(keys.next()), depth + 1, ids);
    }

    /** 同一条 delivery 里找 zcode_plan.name / ends_at */
    private static void collectPlanNames(Object node, int depth, java.util.List<String> names,
                                         java.util.List<Long> ends) {
        if (node == null || depth > 12) return;
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) collectPlanNames(arr.opt(i), depth + 1, names, ends);
            return;
        }
        if (!(node instanceof JSONObject)) return;
        JSONObject obj = (JSONObject) node;
        JSONObject plan = obj.optJSONObject("zcode_plan");
        if (plan != null) {
            String name = plan.optString("name", "");
            if (name != null && !name.trim().isEmpty()) names.add(name);
            ends.add(plan.optLong("ends_at", 0));
        }
        java.util.Iterator<String> keys = obj.keys();
        while (keys.hasNext()) collectPlanNames(obj.opt(keys.next()), depth + 1, names, ends);
    }

    private static final class GiftDataException extends Exception {
        GiftDataException(String message) { super(message); }
    }

    // ===================== gift 余额：有效计划 + 模型能力 =====================
    //
    // 对齐上游 startPlan / giftOf 的判定（只有它能说明「现在能花什么」）：
    //   · 一个计划算数要 status == active、已经开始了（starts_at 不在未来）、没结束（ends_at）；
    //   · 余额包要属于一个算数的计划：两边都有 user_plan_id 就比它，否则比 plan_id；
    //   · 包自己过期（expires_at <= now）也算花完；
    //   · 一个数都没有的包摆不出额度，跳过（不是「剩 0」）；
    //   · 旧的余额可能没有 remaining_units，但给了 used/total —— 那时 spent 用 used >= total 判断，
    //     剩余量按 total - used 算（上游 giftSpent 正是这么读的）；
    //   · capabilities 里 model:x 才是模型，tool:y 这类不算，没有冒号的也当模型名。

    /**
     * 上游 num()：数字或数字字符串；其余是未知（null），不拿 0 冒充。
     *
     * 返回值保留小数：上游 giftSpent 判的是 `num(x.remaining_units) <= 0`，0.4 是没花完。
     * 这里若先 long 截断，0.4 会变成 0 而被判成「已用完」—— 那是把「还剩一点」说成
     * 「一点不剩」，正好会把这个号推去花付费 Plan。
     */
    static Double giftNum(JSONObject o, String key) {
        if (o == null || key == null || !o.has(key) || o.isNull(key)) return null;
        Object raw = o.opt(key);
        double value;
        if (raw instanceof Number) value = ((Number) raw).doubleValue();
        else if (raw instanceof String) {
            String text = ((String) raw).trim();
            if (text.isEmpty()) return null;
            try { value = Double.parseDouble(text); } catch (Exception ignored) { return null; }
        } else return null;
        if (!Double.isFinite(value)) return null;
        return Double.valueOf(value);
    }

    /** 整数照旧给 Long（既有调用方 optLong 读得到），有小数就给 Double，不截断也不四舍五入。 */
    static Object giftNumber(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 9.0e15) return Long.valueOf((long) value);
        return Double.valueOf(value);
    }

    private static String giftStatus(JSONObject plan) {
        return plan == null ? "" : plan.optString("status", "").trim().toLowerCase(java.util.Locale.US);
    }

    /** 上游 notStarted：starts_at 在未来、且状态不是 expired。 */
    static boolean giftNotStarted(JSONObject plan, long now) {
        Double at = giftNum(plan, "starts_at");
        return at != null && at.doubleValue() > now && !"expired".equals(giftStatus(plan));
    }

    /** 上游 startPlan：这个计划现在算数（active、已开始、没结束）。 */
    static boolean giftPlanLive(JSONObject plan, long now) {
        if (plan == null || !"active".equals(giftStatus(plan))) return false;
        Double end = giftNum(plan, "ends_at");
        if (end != null && end.doubleValue() > 0 && end.doubleValue() <= now) return false;
        return !giftNotStarted(plan, now);
    }

    /** 上游 same()：两个 user_plan_id 都有就比它，否则比 plan_id 字符串。 */
    static boolean giftSamePlan(JSONObject bucket, JSONObject plan) {
        if (bucket == null || plan == null) return false;
        String mine = bucket.optString("user_plan_id", "");
        String theirs = plan.optString("user_plan_id", "");
        if (!mine.isEmpty() && !theirs.isEmpty()) return mine.equals(theirs);
        return bucket.optString("plan_id", "").equals(plan.optString("plan_id", ""));
    }

    /** 上游 bucketModels：capabilities 里 "model:x" 取 x；没有冒号的当模型名；tool: 之类不算。 */
    static JSONArray giftBucketModels(JSONObject bucket) {
        JSONArray out = new JSONArray();
        JSONArray caps = bucket == null ? null : bucket.optJSONArray("capabilities");
        java.util.List<String> seen = new java.util.ArrayList<String>();
        for (int i = 0; caps != null && i < caps.length(); i++) {
            Object raw = caps.opt(i);
            if (!(raw instanceof String)) continue;
            String cap = ((String) raw).trim();
            if (cap.isEmpty()) continue;
            String model;
            if (cap.startsWith("model:")) model = cap.substring(6).trim();
            else if (cap.indexOf(':') < 0) model = cap;
            else continue;
            if (model.isEmpty()) continue;
            boolean duplicate = false;
            for (int k = 0; k < seen.size(); k++) if (seen.get(k).equalsIgnoreCase(model)) { duplicate = true; break; }
            if (duplicate) continue;
            seen.add(model);
            out.put(model);
        }
        return out;
    }

    private static boolean modelsHas(JSONArray models, String model) {
        for (int i = 0; i < models.length(); i++) if (model.equals(models.optString(i, ""))) return true;
        return false;
    }

    /**
     * 汇总：今天可领几个、各包还剩多少、能花哪些模型。
     *
     * 三条边界，都是「未知就明确未知」：
     *
     * <ul>
     *   <li>可领列表（feed）和余额分开读：feed 读失败只让 {@code claimableKnown=false}，
     *       余额照样报（上游 toClaim 失败只让 claimHint 为 null，卡片照样读余额）；</li>
     *   <li>余额没答 {@code plans} 就是未知（{@code ok=false}），不是「没有计划」，更不是
     *       零额度；计划列表明确为空才是「现在没有生效中的赠送」（{@code noLivePlan=true}）；</li>
     *   <li>剩多少只报读得出的：remaining_units 缺失但有 total/used 就按 total-used；
     *       只有一边的数算不出剩多少，那个包 {@code remaining=-1, remainderUnknown=true}，
     *       合计整个不报（{@code remainingKnown=false}），绝不补 0。</li>
     * </ul>
     *
     * {@code hasGift} 跟的是能不能花（{@code models} 非空），不是合计多少：算不出剩多少的包
     * 不算花完（上游 giftSpent 如此），所以「未知余量」与「可路由」在这里是同一句话。
     */
    public static JSONObject giftSummary(String jwt, String device) {
        JSONObject out = new JSONObject();
        try {
            // 可领取计划和余额是两件事，各自读、各自判：feed 读失败只说明「可领列表未知」，
            // 不能把已经读出来的余额一起判成未知（上游 toClaim 失败只是让 claimHint 为 null，
            // 卡片照样读余额）。所以这里先读 feed，失败只记在 claimableKnown 上。
            JSONArray claimable = new JSONArray();
            String claimableError = "";
            try {
                JSONArray read = touchPlans(giftTouch(jwt, device));
                if (read == null) claimableError = "gift 可领取计划数据不完整";
                else claimable = read;
            } catch (Exception feed) {
                claimableError = feed instanceof ServiceException || feed instanceof GiftDataException
                        ? feed.getMessage() : "gift 可领取计划读取失败";
            }
            out.put("claimable", claimable);
            out.put("claimableKnown", claimableError.isEmpty());
            if (!claimableError.isEmpty()) out.put("claimableError", claimableError);

            JSONObject bal = giftBalance(jwt, device);
            JSONArray buckets = bal.optJSONArray("balances");
            if (buckets == null) throw new GiftDataException("gift 余额数据不完整");
            // 计划是这份余额唯一能证明「这笔额度现在算数」的东西。余额没答 plans 就是没答，
            // 不是「没有计划」，更不是「零额度」：上游 giftOf 在没有 live plan 时给的是
            // NO_START，从不给一张零卡。所以这里按未知报，交给调用方显示未知，
            // 也不让它被当成「确认没有赠送」而去花付费 Plan。
            if (!bal.has("plans") || bal.isNull("plans") || bal.optJSONArray("plans") == null)
                throw new GiftDataException("gift 计划数据不完整");
            Double serverTime = giftNum(bal, "server_time");
            long now = serverTime != null && serverTime.doubleValue() > 0
                    ? (long) Math.floor(serverTime.doubleValue()) : System.currentTimeMillis() / 1000L;
            JSONArray plans = bal.optJSONArray("plans");
            JSONArray live = new JSONArray(), models = new JSONArray(), allModels = new JSONArray();
            double remaining = 0;
            int livePlans = 0;
            boolean unknownRemainder = false;
            for (int p = 0; p < plans.length(); p++) if (giftPlanLive(plans.optJSONObject(p), now)) livePlans++;
            for (int i = 0; i < buckets.length(); i++) {
                JSONObject bucket = buckets.optJSONObject(i);
                if (bucket == null) throw new GiftDataException("gift 余额数据不完整");

                // 计划是这份余额唯一能证明「这笔额度现在算数」的东西：没有它就不猜。
                JSONObject owner = null;
                for (int p = 0; plans != null && p < plans.length(); p++) {
                    JSONObject plan = plans.optJSONObject(p);
                    if (giftSamePlan(bucket, plan)) { owner = plan; break; }
                }
                if (owner == null || !giftPlanLive(owner, now)) continue;

                Double expires = giftNum(bucket, "expires_at");
                if (expires != null && expires.doubleValue() > 0 && expires.doubleValue() <= now) continue;

                Double left = giftNum(bucket, "remaining_units");
                Double total = giftNum(bucket, "total_units");
                Double used = giftNum(bucket, "used_units");
                if (left == null && total == null && used == null) continue;   // 一个数都没有：摆不出额度

                boolean spent;
                double leftOf = -1;
                if (left != null) {
                    // 上游 giftSpent：有 remaining_units 就只看它，<= 0 即花完；0.4 是没花完
                    spent = left.doubleValue() <= 0;
                    leftOf = spent ? 0 : left.doubleValue();
                } else if (total != null && used != null) {
                    spent = used.doubleValue() >= total.doubleValue();         // 旧余额：用 used/total 判断
                    leftOf = Math.max(0, total.doubleValue() - used.doubleValue());
                } else {
                    spent = false;                                             // 只有一边的数：剩多少未知
                }
                boolean remainderUnknown = leftOf < 0;

                // 上游 bucketModels：capabilities 不是数组时是空列表（这个包一个模型都说不出），
                // 不是「读不懂」——整份余额照样读得完，只是这个包不参与路由。
                JSONArray caps = bucket.optJSONArray("capabilities");
                // 这个包到底有没有说过它覆盖哪些模型：missing 与「明确说了一个也没有」在
                // 上游读出来都是空列表，但对要决定「要不要为它回落到付费 Plan」的一侧不是一回事，
                // 所以如实地把这点记下来，行为不变、也不替它补模型。
                boolean modelsStated = caps != null;
                if (caps == null) caps = new JSONArray();
                JSONArray bucketModels = giftBucketModels(bucket);
                JSONObject o = new JSONObject();
                o.put("planId", bucket.optString("plan_id", ""));
                o.put("name", bucket.optString("show_name", bucket.optString("name", "")));
                if (total != null) o.put("total", giftNumber(total.doubleValue()));
                if (used != null) o.put("used", giftNumber(used.doubleValue()));
                o.put("remaining", giftNumber(leftOf));
                o.put("remainderUnknown", remainderUnknown);
                o.put("spent", spent);
                o.put("expiresAt", expires == null ? 0L : giftNumber(expires.doubleValue()));
                o.put("capabilities", caps);
                o.put("models", bucketModels);
                o.put("modelsKnown", modelsStated);
                live.put(o);

                for (int k = 0; k < bucketModels.length(); k++) {
                    String model = bucketModels.optString(k, "");
                    if (model.isEmpty()) continue;
                    if (!modelsHas(allModels, model)) allModels.put(model);
                    // 花完的包仍然说得出它的模型名，但一个都花不到（上游 models / allModels 的区别）
                    if (!spent && !modelsHas(models, model)) models.put(model);
                }
                if (leftOf > 0) remaining += leftOf;
                if (remainderUnknown) unknownRemainder = true;
            }
            out.put("buckets", live);
            out.put("models", models);
            out.put("allModels", allModels);
            out.put("livePlans", livePlans);
            out.put("noLivePlan", livePlans == 0);
            out.put("plansKnown", true);
            // 有一个生效中的包算不出剩多少，就不报合计：报 0 是拿未知冒充「已经用完」。
            if (!unknownRemainder) out.put("remainingUnits", giftNumber(remaining));
            out.put("remainingKnown", !unknownRemainder);
            // 能不能花看模型：算不出剩多少的包不算花完（上游 giftSpent 就是这样读的），
            // 所以这里不能因为合计是 0 就说这个号没有赠送 —— 那和路由自相矛盾。
            out.put("hasGift", models.length() > 0);
            out.put("ok", true);
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("error", e instanceof ServiceException || e instanceof GiftDataException ? e.getMessage() : "gift 查询失败，请检查网络后重试");
                if (e instanceof ServiceException) {
                    ServiceException failure = (ServiceException)e;
                    out.put("status", failure.status);
                    out.put("businessCode", failure.businessCode);
                    out.put("retryAfterMs", failure.retryAfterMs);
                }
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
