package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Cline (cline.bot) 认证与协议调用。
 *
 * 移植自 upstream opencode-cline-auth (packages/cline)。
 * 支持两种认证途径：
 * 1. WorkOS 设备流（Device Code Flow）：
 *    - 向 WorkOS 申请 device_code 与 user_code，在浏览器中打开授权页；
 *    - 轮询 WorkOS 拿 access_token / refresh_token；
 *    - 在 Cline 的 /api/v1/auth/register 端点换取账号的真实令牌对 (access + refresh)；
 *    - 刷新走 /api/v1/auth/refresh，自动续期。
 * 2. Cline API Key：
 *    - app.cline.bot 生成的个人 Key，作为普通 Bearer Key 发送。
 *
 * 模型列表：
 * - 推荐模型 /ai/cline/recommended-models 与云端全量目录 /ai/cline/models 均为公开免登录接口；
 * - 包含 free 免费模型、clinePass 专属模型与按用量计费模型。
 */
public class ClineAuth {

    public static final String PROVIDER = "cline";
    public static final String API = "https://api.cline.bot/api/v1";

    public static final String WORKOS_CLIENT = "client_01K3A541FN8TA3EPPHTD2325AR";
    public static final String WORKOS_DEVICE = "https://api.workos.com/user_management/authorize/device";
    public static final String WORKOS_TOKEN = "https://api.workos.com/user_management/authenticate";
    public static final String DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";
    public static final String WORKOS_PREFIX = "workos:";

    public static final String CLIENT_TYPE = "cline-cli";
    public static final String CLIENT_VERSION = "3.0.68";
    public static final String CLIENT_PLATFORM = "cli";
    public static final String CLIENT_CORE = "0.0.90";

    // 内置默认推荐模型快照（网络未同步或离线时的兜底）
    public static final String[] DEFAULT_MODELS = new String[]{
            "anthropic/claude-sonnet-5.5",
            "anthropic/claude-opus-5.5",
            "anthropic/claude-sonnet-5",
            "openai/gpt-6-astra",
            "openai/gpt-6.1-sol",
            "spacexai/grok-4.7",
            "moonshotai/kimi-k3",
            "cline-free/deepseek-v4.1-flash",
            "stealth/space-bunny-alpha",
            "cline-free/mimo-v2.6-flash",
            "cline-free/muse-spark-1.3-contributor",
            "cline-pass/deepseek-v4.1-flash-1017",
            "cline-pass/deepseek-v4.1-flash",
            "cline-pass/mimo-v2.6-flash",
            "cline-pass/mimo-v2.6-pro",
            "cline-pass/glm-5.3",
            "cline-pass/deepseek-v4-pro",
            "cline-pass/qwen3.8-max",
            "cline-pass/muse-spark-1.3-contributor",
            "cline-pass/kimi-k3",
            "cline-pass/glm-5.3-flash",
            "cline-pass/qwen3.7-max",
            "cline-pass/qwen3.7-plus",
            "cline-pass/minimax-m3",
            "cline-pass/mimo-v2.5-pro",
            "cline-pass/mimo-v2.5"
    };

    public static HttpURLConnection openHttp(String urlStr) throws Exception {
        return NetworkPolicy.openHttp(urlStr);
    }

    public static Map<String, String> clientHeaders(String taskId) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("HTTP-Referer", "https://cline.bot");
        h.put("X-Title", "Cline");
        h.put("User-Agent", "Cline/" + CLIENT_VERSION);
        h.put("X-IS-MULTIROOT", "false");
        h.put("X-CLIENT-TYPE", CLIENT_TYPE);
        h.put("X-CLIENT-VERSION", CLIENT_VERSION);
        h.put("X-PLATFORM", CLIENT_PLATFORM);
        h.put("X-PLATFORM-VERSION", CLIENT_VERSION);
        h.put("X-CORE-VERSION", CLIENT_CORE);
        if (taskId != null && !taskId.isEmpty()) {
            h.put("X-Task-ID", taskId);
        }
        return h;
    }

    public static class Flow {
        public String flowId;
        public String userCode;
        public String verificationUri;
        public String verificationUriComplete;
        public long expiresIn;
        public long intervalMs;
        public long createdAt;
    }

    public static class SignIn {
        public String access;
        public String refresh;
        public long expires;
        public String uid;
        public String email;
        public String name;
        public String accountId;
    }

    public static class PollResult {
        public String status; // pending, slow_down, ready, failed
        public String error;
        public SignIn signIn;
    }

    /**
     * 1. 发起 WorkOS 设备流授权
     */
    public static Flow initDeviceFlow() throws Exception {
        HttpURLConnection conn = openHttp(WORKOS_DEVICE);
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Accept", "application/json");

        String postBody = "client_id=" + URLEncoder.encode(WORKOS_CLIENT, "UTF-8");
        OutputStream os = conn.getOutputStream();
        os.write(postBody.getBytes("UTF-8"));
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        String body = readStream(is);
        if (code < 200 || code >= 400) {
            throw new Exception("WorkOS device code request failed (HTTP " + code + ")");
        }

        JSONObject j = new JSONObject(body);
        Flow f = new Flow();
        f.flowId = j.optString("device_code", "");
        f.userCode = j.optString("user_code", "");
        f.verificationUri = j.optString("verification_uri", "");
        f.verificationUriComplete = j.optString("verification_uri_complete", f.verificationUri);
        if (f.verificationUriComplete.isEmpty()) {
            f.verificationUriComplete = "https://app.cline.bot";
        }
        f.expiresIn = j.optLong("expires_in", 300);
        f.intervalMs = Math.max(1, j.optLong("interval", 5)) * 1000L;
        f.createdAt = System.currentTimeMillis();

        if (f.flowId.isEmpty()) {
            throw new Exception("WorkOS returned incomplete device code response");
        }
        return f;
    }

    /**
     * 2. 轮询 WorkOS 授权结果；用户通过后在 Cline /api/v1/auth/register 换取真实凭据
     */
    public static PollResult pollDeviceFlow(String deviceCode) throws Exception {
        PollResult pr = new PollResult();

        HttpURLConnection conn = openHttp(WORKOS_TOKEN);
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Accept", "application/json");

        String postBody = "grant_type=" + URLEncoder.encode(DEVICE_GRANT, "UTF-8")
                + "&device_code=" + URLEncoder.encode(deviceCode, "UTF-8")
                + "&client_id=" + URLEncoder.encode(WORKOS_CLIENT, "UTF-8");
        OutputStream os = conn.getOutputStream();
        os.write(postBody.getBytes("UTF-8"));
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        String body = readStream(is);

        JSONObject j;
        try {
            j = new JSONObject(body);
        } catch (Exception e) {
            pr.status = "failed";
            pr.error = "WorkOS returned non-JSON response (HTTP " + code + ")";
            return pr;
        }

        String error = j.optString("error", "");
        if ("authorization_pending".equals(error)) {
            pr.status = "pending";
            return pr;
        }
        if ("slow_down".equals(error)) {
            pr.status = "slow_down";
            return pr;
        }
        if (!error.isEmpty()) {
            pr.status = "failed";
            pr.error = "WorkOS authorization rejected";
            return pr;
        }

        String workosAccess = j.optString("access_token", "");
        String workosRefresh = j.optString("refresh_token", "");
        if (workosAccess.isEmpty()) {
            pr.status = "failed";
            pr.error = "WorkOS returned no access_token";
            return pr;
        }

        // 3. 在 Cline register 端点交换账号真实 token
        SignIn si = registerCline(workosAccess, workosRefresh);
        pr.status = "ready";
        pr.signIn = si;
        return pr;
    }

    /**
     * 向 Cline 注册 WorkOS token，获得 Cline 本地令牌对
     */
    private static SignIn registerCline(String workosAccess, String workosRefresh) throws Exception {
        HttpURLConnection conn = openHttp(API + "/auth/register");
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        Map<String, String> clH = clientHeaders(null);
        for (Map.Entry<String, String> e : clH.entrySet()) {
            conn.setRequestProperty(e.getKey(), e.getValue());
        }

        JSONObject req = new JSONObject();
        req.put("accessToken", workosAccess);
        req.put("refreshToken", workosRefresh != null ? workosRefresh : "");
        OutputStream os = conn.getOutputStream();
        os.write(req.toString().getBytes("UTF-8"));
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        String body = readStream(is);
        if (code < 200 || code >= 400) {
            throw new Exception("Cline register failed (HTTP " + code + ")");
        }

        JSONObject res = new JSONObject(body);
        JSONObject data = res.optJSONObject("data");
        if (data == null) data = res;

        return parseAuth(data, workosRefresh);
    }

    /**
     * 一次刷新请求死在哪一步。一次性 refresh token 的安全围栏只认这件事实：
     * 这次刷新有没有可能已经把凭据交给 Cline。
     */
    public enum RefreshDelivery {
        /** 传输层根本没被调用，什么也没离开本进程。 */
        NOT_ATTEMPTED,
        /** 有证据表明请求体一个字节都没能被发出去，凭据还是原值。 */
        NOT_SENT,
        /** 请求体可能（部分或全部）已经到了 Cline，但没有拿到可用的回答。 */
        POSSIBLY_SENT,
        /** Cline 答复了，或交换已经完成；一次性凭据可能已被消费。 */
        SENT
    }

    /**
     * 一次性凭据这次走在哪条通道上。
     *
     * <p>整条“写请求体之前失败＝凭据没交付”的推理只在一个前提下成立：
     * refresh token 只出现在请求体里，不出现在请求行、请求头、URL 或 Cookie 里。
     * 这个前提由 {@link #refreshHeaders()} 与 {@link #headersCarry} 在发请求之前当场核对，
     * 并把核对结论写进异常本身，让读证据的一方不用猜。
     */
    public enum CredentialChannel {
        /** 已核对：凭据只在请求体里，请求头里没有。 */
        BODY_ONLY,
        /** 没能核对（凭据为空、或在核对之前就失败了）：不许据此断言未发送。 */
        UNKNOWN
    }

    /**
     * Cline 的答复对这次刷新说了什么。这是唯一一处「已经收到答复」的结论，
     * 与「有没有可能已经交付」是两条正交的证据：{@link RefreshDelivery} 说凭据走到哪一步，
     * 这里说答复本身把这次尝试定成了哪一种。
     *
     * <p><b>判据逐字对齐上游插件</b>（第一事实源
     * {@code opencode-cline-auth/source/index.mjs}）：
     * <ul>
     *   <li>{@code index.mjs:220-226} 的 {@code refresh()} 把「登录已经没了」只认
     *       {@code lapsed: (status, text) => [400, 401, 403].includes(status) &&
     *       /invalid|expired|revoked|unauthorized/i.test(text)}；注释写明
     *       「A dead token is refused as 400 invalid_grant (WorkOS's wording rides in the body),
     *       not 401 — that refusal is the sign-in gone, anything else is worth a retry later」。
     *       也就是说：只有这三个码加上这段措辞才是登录失效，其余答复都是「稍后还能再试」。
     *   <li>{@code refresh.test.mjs} 的 "a refresh Cline refuses is the sign-in expired" 钉住
     *       400 {@code invalid_grant / refresh token revoked} → {@code e.signIn === "expired"}；
     *       同一文件的 "a refresh that failed for a while throws plainly, for magpie to retry"
     *       钉住 503 → 没有 {@code signIn}，并且<b>「the refresh token wasn't spent: the next try
     *       sends it again」</b>——下一次请求体里仍然是同一枚 {@code r1} 并成功换成新对子。
     * </ul>
     *
     * <p>所以一个非 2xx 的答复并不是「未知执行」：它是 Cline 对这次尝试的明确回答，
     * 而且答复里没有指名任何新凭据，这枚一次性值仍然是账号当前的值。这正是围栏自己要的
     * 「provider provably kept it」（{@code GatewayServer.resolveRefreshFence} 的注释）证据。
     */
    public enum Refusal {
        /** 没有收到可据以定性的拒绝答复：没发、发送中失败、答复没读全、2xx 但解析不出对子、或成功。 */
        NOT_REFUSED,
        /** Cline 明确说这枚 refresh 凭据已失效/过期/被撤销（400/401/403 + 明确措辞）：登录没了。 */
        SIGN_IN_REQUIRED,
        /** Cline 明确拒绝了这次尝试，但没有说凭据作废（503、429、其它 4xx/5xx）：稍后仍用同一枚值重试。 */
        RETRY_LATER
    }

    /**
     * 上游 {@code index.mjs:225} 的登录失效措辞，逐字一致（大小写不敏感、子串匹配）。
     *
     * <p>松匹配在这里是安全方向：它只把「可重试」缩小成「要重新登录」，不会把需要重新登录的
     * 答复说成可重试。{@code WorkOS} 的措辞（{@code invalid_grant}、{@code refresh token revoked}）
     * 就在这段里。
     */
    private static final java.util.regex.Pattern LAPSED_TEXT =
            java.util.regex.Pattern.compile("invalid|expired|revoked|unauthorized",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * 一个非 2xx 答复属于哪一类拒绝。
     *
     * <p>只认 400–599：3xx 已关闭自动跟随（见 {@link #refreshToken(String)}），它是「已送达但不可用」，
     * 不是对凭据的定性回答，按 {@link Refusal#NOT_REFUSED} 保守处理；2xx 不走这里。
     * 上界的另外半边是：正文没读全（{@code read} 阶段）时拿不到措辞，因此也不会走到这里 ——
     * 没读到 Cline 说的话，就不许替它说话。
     */
    static Refusal refusalOf(int status, String bodyText) {
        if (status < 400 || status > 599) return Refusal.NOT_REFUSED;
        boolean lapsedWording = bodyText != null && LAPSED_TEXT.matcher(bodyText).find();
        boolean lapsedStatus = status == 400 || status == 401 || status == 403;
        return lapsedStatus && lapsedWording ? Refusal.SIGN_IN_REQUIRED : Refusal.RETRY_LATER;
    }

    /**
     * 刷新失败的定型异常。它只回答“走到哪一步”，不携带任何凭据，
     * 也不把底层异常的原文带出来（底层消息可能是任意上游文本）。
     *
     * <p>阶段名与“凭据有没有可能已经交付”的对应关系：
     * <pre>
     * precondition  本地就没通过（空凭据、或凭据出现在请求头里）  未交付
     * open          还没拿到链路对象（factory/openHttp、请求配置） 未交付
     * connect       拿到了链路，但拿不到可写请求体的流            未交付（凭据只在体里）
     * write         请求体写过，但不确定写了多少                  可能已交付
     * response      请求体写完，但没等到状态行                    可能已交付
     * read          收到状态行，正文没读全                        可能已被消费
     * status        收到非 2xx 答复（含被拒、3xx）                可能已被消费
     * parse         收到 2xx，但解析不出可用的令牌对              可能已被消费
     * pair          交换调用返回，但拿不到 access                 可能已被消费
     * transport     传输层抛了不认识的异常                        可能已交付
     * superseded    交换发生了，账号已被别的写入方改掉            可能已被消费
     * </pre>
     *
     * <p>只有 {@code open} 与 {@code connect} 有可能被证明为未交付，而且必须同时满足
     * {@link #provablyNotSent()}：请求体没开始写，且凭据确实只在请求体里。
     */
    public static final class ClineRefreshException extends Exception {
        private static final long serialVersionUID = 1L;
        public final RefreshDelivery delivery;
        /** 收到过的 HTTP 码，未收到时为 0。 */
        public final int status;
        /**
         * precondition / open / connect / write / response / read / status / parse / pair /
         * transport / superseded。
         */
        public final String stage;
        /** 已经把凭据交给请求体写入了；此时失败无法知道到底写了多少。 */
        public final boolean bodyWriteStarted;
        /** 请求体全部字节都被流接受了；请求“可能完整到达”。 */
        public final boolean bodyWritten;
        /** 凭据当时走在哪条通道；只有 {@link CredentialChannel#BODY_ONLY} 才支持“未交付”的结论。 */
        public final CredentialChannel credentialChannel;
        /**
         * Cline 的答复对这次尝试的定性；没收到可定性的答复时是
         * {@link Refusal#NOT_REFUSED}。
         *
         * <p>它与 {@link #delivery} 正交：{@code SENT/status} 加上
         * {@link Refusal#RETRY_LATER} 是「Cline 明确拒绝了这次尝试、但没有换掉这枚值」，
         * 也就是上游 {@code refresh.test.mjs} 里「the next try sends it again」的那种情况；
         * 同一个阶段加上 {@link Refusal#NOT_REFUSED}（例如正文没读全）才是真正的未知执行。
         *
         * <p>注意：这不改变 {@link #retryable()} 与 {@link #fenceReleasable()} 的保守口径，
         * 那两个门禁仍然只认「证明未发送」。
         */
        public final Refusal refusal;
        /** 底层失败的异常类名，只有类名，没有消息。 */
        public final String source;

        ClineRefreshException(RefreshDelivery delivery, String stage, int status,
                              boolean bodyWriteStarted, boolean bodyWritten,
                              CredentialChannel credentialChannel, Throwable cause) {
            this(delivery, stage, status, bodyWriteStarted, bodyWritten, credentialChannel, cause,
                    Refusal.NOT_REFUSED);
        }

        ClineRefreshException(RefreshDelivery delivery, String stage, int status,
                              boolean bodyWriteStarted, boolean bodyWritten,
                              CredentialChannel credentialChannel, Throwable cause, Refusal refusal) {
            super(message(delivery, stage, status));
            this.delivery = delivery;
            this.stage = stage;
            this.status = status;
            this.bodyWriteStarted = bodyWriteStarted;
            this.bodyWritten = bodyWritten;
            this.credentialChannel = credentialChannel == null ? CredentialChannel.UNKNOWN : credentialChannel;
            this.refusal = refusal == null ? Refusal.NOT_REFUSED : refusal;
            this.source = cause == null ? "" : cause.getClass().getSimpleName();
        }

        private static String message(RefreshDelivery delivery, String stage, int status) {
            StringBuilder sb = new StringBuilder("Cline token refresh failed");
            if (status > 0) sb.append(" (HTTP ").append(status).append(')');
            return sb.append(" [").append(delivery).append('/').append(stage).append(']').toString();
        }

        /** 一次性凭据可能已经被消费：围栏必须留着。 */
        public boolean reachedProvider() {
            return delivery == RefreshDelivery.POSSIBLY_SENT || delivery == RefreshDelivery.SENT;
        }

        /**
         * 可以断言“凭据一个字节都没交付”：请求体没开始写，且凭据确实只在请求体里。
         *
         * <p>这是放开围栏的唯一依据。少了 {@link CredentialChannel#BODY_ONLY} 这一条，
         * 请求头也可能带着凭据，{@code connect} 阶段就不再安全。
         */
        public boolean provablyNotSent() {
            return delivery == RefreshDelivery.NOT_SENT
                    && !bodyWriteStarted && !bodyWritten
                    && credentialChannel == CredentialChannel.BODY_ONLY;
        }

        /** 只有“确定没发送”才允许放开围栏；剩下的未知一律按已发送处理。 */
        public boolean fenceReleasable() {
            return delivery == RefreshDelivery.NOT_ATTEMPTED || provablyNotSent();
        }

        /** 没有任何凭据被消费的风险，可以原样再试一次。 */
        public boolean retryable() {
            return fenceReleasable();
        }
    }

    /** Test seam for the real refresh socket; null means NetworkPolicy.openHttp. */
    interface RefreshConnectionFactory { HttpURLConnection open(String url) throws Exception; }
    private static volatile RefreshConnectionFactory refreshConnections;

    static void setRefreshConnectionsForTests(RefreshConnectionFactory factory) {
        refreshConnections = factory;
    }

    static String refreshUrl() {
        return API + "/auth/refresh";
    }

    private static ClineRefreshException refreshFailure(RefreshDelivery delivery, String stage, int status,
                                                        boolean bodyWriteStarted, boolean bodyWritten,
                                                        CredentialChannel channel, Throwable cause) {
        return new ClineRefreshException(delivery, stage, status, bodyWriteStarted, bodyWritten, channel, cause);
    }

    private static ClineRefreshException refreshFailure(RefreshDelivery delivery, String stage, int status,
                                                        boolean bodyWriteStarted, boolean bodyWritten,
                                                        CredentialChannel channel, Throwable cause, Refusal refusal) {
        return new ClineRefreshException(delivery, stage, status, bodyWriteStarted, bodyWritten, channel, cause, refusal);
    }

    private static ClineRefreshException refreshNotSent(String stage, Throwable cause) {
        return refreshFailure(RefreshDelivery.NOT_SENT, stage, 0, false, false,
                CredentialChannel.BODY_ONLY, cause);
    }

    /** 这份请求会带上哪些头。刷新请求不变的部分集中在这里，便于核对“头里没有凭据”。 */
    static Map<String, String> refreshHeaders() {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.putAll(clientHeaders(null));
        return headers;
    }

    /**
     * 凭据是不是出现在这些头里（键或值都算）。
     *
     * <p>这是“凭据只在请求体里”这条前提的核对原语：一旦为真，请求头也可能带着一次性凭据，
     * {@code connect} 阶段就不再能断言“未交付”，必须在发请求之前停下来。
     */
    static boolean headersCarry(String token, Map<String, String> headers) {
        if (token == null || token.isEmpty() || headers == null) return false;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().indexOf(token) >= 0) return true;
            if (e.getValue() != null && e.getValue().indexOf(token) >= 0) return true;
        }
        return false;
    }

    /**
     * 刷新 Cline access token
     *
     * <p><b>前提（本类唯一的凭据位置约定）：</b>一次性 refresh token 只放在请求体
     * {@code {"refreshToken": ...}} 里，请求行、请求头、URL 都不带它。为此
     * {@link #refreshHeaders()} 与 {@link #headersCarry} 在打开链路之前就核对一遍，
     * 核对不过就当场拒绝（{@code precondition}），一个字节都不发。
     *
     * <p>在这个前提下，失败点决定结论：
     * <ul>
     *   <li>{@code open}：连链路对象都没拿到（factory/openHttp、请求配置）→ 未交付；</li>
     *   <li>{@code connect}：拿到了链路但拿不到可写请求体的流。HttpURLConnection 在
     *       {@code getOutputStream()} 里建链、握手并送出请求头，但请求体还没开始写；
     *       由于头里没有凭据、体一个字节没写 → 未交付；</li>
     *   <li>{@code write} 起：请求体已经开始写，写失败可能是零字节也可能是整段进了内核缓冲，
     *       进程内无法证明，一律按“可能已交付”处理。</li>
     * </ul>
     *
     * <p>另外关闭自动重定向：JDK 会为 307/308 新开一条连接并把请求体原样再发一遍，
     * 那等于把一次性凭据重放给第二台主机。关掉之后 3xx 只是一个普通答复，按
     * {@code status} 记为“已送达、不可用”，围栏不放开。
     */
    public static SignIn refreshToken(String refreshToken) throws Exception {
        if (refreshToken == null || refreshToken.trim().isEmpty()) {
            throw refreshNotSent("precondition", null);
        }
        Map<String, String> headers = refreshHeaders();
        if (headersCarry(refreshToken, headers)) {
            // 前提被破坏：凭据会跟着请求头出去，那么“写体之前＝未发送”就不成立了。
            // 这里在打开链路之前就拒绝，因此确实什么都没发。
            throw refreshNotSent("precondition",
                    new IllegalStateException("refresh token must not travel in request headers"));
        }
        HttpURLConnection conn = null;
        OutputStream os = null;
        boolean bodyWriteStarted = false;
        boolean bodyWritten = false;
        try {
            // open：只有本地对象与请求配置，还没有任何链路建立。
            try {
                RefreshConnectionFactory factory = refreshConnections;
                conn = factory == null ? openHttp(refreshUrl()) : factory.open(refreshUrl());
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(20000);
                conn.setDoOutput(true);
                // 307/308 不允许自动跟随：JDK 会新开连接并把请求体（含一次性凭据）再发一遍。
                conn.setInstanceFollowRedirects(false);
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            } catch (Exception noLink) {
                throw refreshNotSent("open", noLink);
            }
            // connect：getOutputStream 触发建链与握手并送出请求头；体还没写，头里也没有凭据。
            try {
                os = conn.getOutputStream();
            } catch (Exception noStream) {
                throw refreshNotSent("connect", noStream);
            }

            JSONObject req = new JSONObject();
            req.put("refreshToken", refreshToken);
            req.put("grantType", "refresh_token");
            byte[] body = req.toString().getBytes("UTF-8");
            bodyWriteStarted = true;
            try {
                os.write(body);
                os.flush();
                os.close();
                os = null;
                bodyWritten = true;
            } catch (Exception partialWrite) {
                // 保守：写失败可能是零字节，也可能整段已经进内核缓冲，
                // 进程内无法证明，所以按“可能已发送”处理并要求保留围栏。
                throw refreshFailure(RefreshDelivery.POSSIBLY_SENT, "write", 0, bodyWriteStarted, false,
                        CredentialChannel.BODY_ONLY, partialWrite);
            }

            int code;
            try {
                code = conn.getResponseCode();
            } catch (Exception unanswered) {
                throw refreshFailure(RefreshDelivery.POSSIBLY_SENT, "response", 0, true, bodyWritten,
                        CredentialChannel.BODY_ONLY, unanswered);
            }

            String bodyText;
            try {
                InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                bodyText = readStream(is);
            } catch (Exception unreadable) {
                // 状态行已经到了：请求确实发出去了，只是正文没读全。
                throw refreshFailure(RefreshDelivery.SENT, "read", code, true, bodyWritten,
                        CredentialChannel.BODY_ONLY, unreadable);
            }
            if (code < 200 || code >= 300) {
                // 含 3xx：自动重定向已关闭，这里只是一个“已送达但不可用”的答复。
                // 4xx/5xx 是 Cline 对这次尝试的明确答复：把答复的定性记进异常（不含原文）。
                throw refreshFailure(RefreshDelivery.SENT, "status", code, true, bodyWritten,
                        CredentialChannel.BODY_ONLY, null, refusalOf(code, bodyText));
            }

            try {
                JSONObject res = new JSONObject(bodyText);
                JSONObject data = res.optJSONObject("data");
                if (data == null) data = res;
                return parseAuth(data, refreshToken);
            } catch (Exception unusable) {
                // 应答已经到了：凭据可能已被消费，只是这个对子不能用。
                throw refreshFailure(RefreshDelivery.SENT, "parse", code, true, bodyWritten,
                        CredentialChannel.BODY_ONLY, unusable);
            }
        } finally {
            if (os != null) {
                try { os.close(); } catch (Exception ignored) { }
            }
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * 更新已有账号对象的 access/refresh/expires
     */
    interface RefreshTransport { SignIn refresh(String token) throws Exception; }

    /**
     * 请求前预检的临期时机：对齐 Cline 插件请求内那一条 threshold。
     *
     * <p>上游 {@code opencode-cline-auth/source/index.mjs:25} 写的是
     * {@code const REFRESH_LEAD = 5 * 60 * 1000 // a token this close to its end is
     * refreshed before a request}；插件自己在请求前就是这么比的 ——
     * {@code index.mjs:967}、{@code index.mjs:972} 都是
     * {@code expires - Date.now() > REFRESH_LEAD} 才照用，否则先换。
     *
     * <p>不要把这里改成 10 分钟：{@code index.mjs:26} 的
     * {@code RENEW_LEAD = 10 * 60 * 1000} 是插件交给宿主 proactive hook 的值，
     * 由 {@code index.mjs:1073} 的 {@code refreshLead: RENEW_LEAD} 导出，宿主在发起
     * 请求／模型列表／用量之前主动调 {@code auth.refresh}；它与请求内的 threshold 是
     * 两条独立判据（{@code index.mjs:1066-1071} 的注释明确说请求内那条留给不调 hook 的
     * 客户端）。All-in 没有这个宿主 hook，所以这里只能是 5 分钟；要提前到 10 分钟得先在
     * 请求之外加一条续期调度，那是 ClineAuth 之外的接线。
     *
     * <p>时序留痕：GatewayServer 的 Cline 请求前预检（{@code refreshClineAccount}）
     * 用的是同一个 5 分钟门槛，本常量是 ClineAuth 自己 non-force 入口
     * （{@link #refreshCandidate(JSONObject, boolean)}／{@link #refreshAccount(JSONObject)}）
     * 的门槛，两边必须一致，否则同一份凭据经不同入口会在不同剩余寿命上换。
     */
    private static final long REQUEST_REFRESH_LEAD_MS = 300000L;

    /** Immutable pre-refresh credentials plus result, for a caller-managed config CAS.
     * The caller must validate account identity/enabled state and persist atomically. */
    public static final class RefreshCandidate {
        public final String token, access, refresh;
        public final long expires;
        public final SignIn next;
        private RefreshCandidate(JSONObject account, SignIn next) {
            token = account.optString("token", ""); access = account.optString("access", "");
            refresh = account.optString("refresh", ""); expires = account.optLong("expires", 0L);
            this.next = next;
        }
        public boolean matches(JSONObject account) {
            return account != null && token.equals(account.optString("token", ""))
                    && access.equals(account.optString("access", ""))
                    && refresh.equals(account.optString("refresh", ""))
                    && expires == account.optLong("expires", 0L);
        }
    }

    /**
     * 一次刷新的完整结果：要么拿到新的令牌对，要么带着“走到哪一步”说明失败。
     *
     * <p>这是给调用方（GatewayServer 的 Cline 刷新围栏）用的判定输入：
     * {@link #fenceReleasable()} 为真才说明一次性 refresh token 还是原值、
     * 围栏可以放开；其余情况都要按“可能已被消费”保留围栏。
     */
    public static final class RefreshReport {
        /** 非空：新的令牌对可用，且账号快照仍是这次交换基于的那一份。 */
        public final RefreshCandidate candidate;
        /** candidate 为空且有失败原因时非空。 */
        public final ClineRefreshException failure;
        /** 这次请求走到了哪一步。ready() 为真时一定是 SENT，调用方应先看 ready()。 */
        public final RefreshDelivery delivery;

        private RefreshReport(RefreshCandidate candidate, ClineRefreshException failure, RefreshDelivery delivery) {
            this.candidate = candidate;
            this.failure = failure;
            this.delivery = delivery;
        }

        private static RefreshReport ready(RefreshCandidate candidate) {
            return new RefreshReport(candidate, null, RefreshDelivery.SENT);
        }

        private static RefreshReport skipped() {
            return new RefreshReport(null, null, RefreshDelivery.NOT_ATTEMPTED);
        }

        private static RefreshReport failed(ClineRefreshException failure) {
            return new RefreshReport(null, failure, failure.delivery);
        }

        /** 是否有可用的新令牌对。 */
        public boolean ready() {
            return candidate != null;
        }

        /** 一次性凭据可能已经交给 Cline：不能重放，也不能放开围栏。 */
        public boolean reachedProvider() {
            return delivery == RefreshDelivery.POSSIBLY_SENT || delivery == RefreshDelivery.SENT;
        }

        /**
         * 可以断言“这次刷新一个凭据字节都没交付”。
         *
         * <p>没有失败（fresh 跳过、账号缺失）时只有 NOT_ATTEMPTED 算数；有失败时把判断交给
         * {@link ClineRefreshException#provablyNotSent()}，也就是说还必须满足
         * “凭据只在请求体里、请求体没开始写”。
         */
        public boolean provablyNotSent() {
            if (reachedProvider()) return false;
            if (failure != null) return failure.provablyNotSent();
            return delivery == RefreshDelivery.NOT_ATTEMPTED;
        }

        /** 只有确定没发送时才为真；未知一律不放行。 */
        public boolean fenceReleasable() {
            return provablyNotSent();
        }

        // ── 答复的定性（安全证据 API）──────────────────────────────────
        //
        // 上面三条只回答「凭据有没有可能离开本进程」。下面几条回答另一个问题：
        // 「Cline 对这次尝试说了什么」。两条证据正交，不能互相替代：
        //   · 没发出去（provablyNotSent）          → 值没动，可以原样重试；
        //   · 发了、Cline 明确拒绝且没换值（RETRY_LATER）→ 值仍是当前值，可以稍后用同一枚重试；
        //   · 发了、Cline 说这枚值死了（SIGN_IN_REQUIRED）→ 必须重新登录，重试没有意义；
        //   · 其余（没答复、写了一半、正文没读全、2xx 解析失败、3xx、被并发改掉）→ 未知执行，绝不重放。

        /**
         * Cline 的答复对这次刷新的定性；没有可定性的答复时是
         * {@link Refusal#NOT_REFUSED}。
         *
         * <p>判据与文案都以上游插件为准，见 {@link Refusal} 的注释：
         * {@code index.mjs:220-226} 只把「400\/401\/403 + invalid|expired|revoked|unauthorized」
         * 当登录失效，其余答复按 {@code refresh.test.mjs} 的说法都是“稍后再试，
         * 而且下一次发的还是同一枚 refresh token”。
         */
        public Refusal refusal() {
            return failure == null ? Refusal.NOT_REFUSED : failure.refusal;
        }

        /** Cline 明确答复了这次刷新（收到了 4xx/5xx，不是猜的）。 */
        public boolean refusedByProvider() {
            return refusal() != Refusal.NOT_REFUSED;
        }

        /**
         * 账号登录已经没了：Cline 明确说这枚 refresh 凭据无效／过期／被撤销。
         *
         * <p>这是上游 {@code signedInError} 里 {@code e.expired → 401} 的那一类，
         * 也是 {@code refresh.test.mjs} 的 "a refresh Cline refuses is the sign-in expired"。
         * 调用方该给用户「重新登录」，而不是让用户反复重试。
         */
        public boolean signInRequired() {
            return refusal() == Refusal.SIGN_IN_REQUIRED;
        }

        /**
         * 这枚一次性 refresh 值是否还能原样再发一次（不是重放已消费的凭据）。
         *
         * <p>只有两种情况为真：
         * <ul>
         *   <li>已经证明一个字节都没交付（{@link #fenceReleasable()}）；<\/li>
         *   <li>Cline 明确拒绝了这次尝试、且答复里没有换掉这枚值
         *       （{@link Refusal#RETRY_LATER}）。上游 {@code refresh.test.mjs} 为此写了
         *       「the refresh token wasn't spent: the next try sends it again」，
         *       并在下一次请求体里仍然发 {@code r1} 且成功。<\/li>
         * <\/ul>
         *
         * <p>未知执行（{@link #executionUnknown()}）永远为假：那正是围栏要挡住的
         * 「可能已经消费、却拿不到新值」的情况。
         *
         * <p><b>接线方式（网关侧，未在本类内实施）：<\/b>为真时说明围栏可以按
         * “provider kept the value”结清（{@code settleRefreshFence(provider, value, false)}
         * 或 {@code resolveRefreshFence}），再让后续请求用同一枚值重试；为假时围栏必须留着。
         */
        public boolean mayResendSameValue() {
            return fenceReleasable() || refusal() == Refusal.RETRY_LATER;
        }

        /**
         * 执行结果未知：一次性值可能已被消费，答复也没说是哪一种。
         *
         * <p>这包括没收到状态行、请求体写了一半、状态行到了但正文没读全、2xx 解析不出对子、
         * 3xx（自动跟随已关闭）、以及交换完成后账号被别的写入方改掉。此谓词为真时，
         * 任何重试都可能把已作废的值再发一遍，调用方只能保留围栏、要求重新登录或换账号，
         * 绝不能说成“可重试”。
         */
        public boolean executionUnknown() {
            return !ready() && !mayResendSameValue() && refusal() == Refusal.NOT_REFUSED;
        }

        /**
         * 一段机器可读、可安全放进答复正文或日志的证据标签：只含分类、阶段与状态码，
         * 不含凭据，也不含上游原文。
         *
         * <p>建议网关据此决定答复状态：{@code refused_sign_in:*} → 401 重新登录；
         * {@code refused_retry_later:*} → 503/429 + {@code Retry-After}；
         * {@code unknown:*} → 不可重试（保留围栏）；{@code not_sent:*} → 可立即原样重试。
         */
        public String evidenceCode() {
            if (ready()) return "ready";
            if (fenceReleasable()) return "not_sent:" + (failure == null ? "skipped" : failure.stage);
            int status = failure == null ? 0 : failure.status;
            switch (refusal()) {
                case SIGN_IN_REQUIRED: return "refused_sign_in:" + status;
                case RETRY_LATER: return "refused_retry_later:" + status;
                default: return "unknown:" + (failure == null ? "none" : failure.stage);
            }
        }
    }

    /** Null means already fresh, missing refresh credential, or refresh failure. Never mutates account. */
    public static RefreshCandidate refreshCandidate(JSONObject account, boolean force) {
        return refreshCandidate(account, force, ClineAuth::refreshToken);
    }
    static RefreshCandidate refreshCandidate(JSONObject account, boolean force, RefreshTransport transport) {
        return refreshReport(account, force, transport).candidate;
    }

    /** Same contract as {@link #refreshCandidate(JSONObject, boolean)}, plus the failure detail. */
    public static RefreshReport refreshReport(JSONObject account, boolean force) {
        return refreshReport(account, force, ClineAuth::refreshToken);
    }
    static RefreshReport refreshReport(JSONObject account, boolean force, RefreshTransport transport) {
        if (account == null) return RefreshReport.skipped();
        synchronized (account) {
            long expires = account.optLong("expires", 0L);
            if (!force && expires > System.currentTimeMillis() + REQUEST_REFRESH_LEAD_MS) return RefreshReport.skipped();
            String refresh = account.optString("refresh", "");
            // 没有一次性凭据可发，因此不可能有任何东西被消费。
            if (refresh.isEmpty()) return RefreshReport.failed(refreshNotSent("precondition", null));
            RefreshCandidate before = new RefreshCandidate(account, null);
            SignIn next;
            try {
                next = transport == null ? refreshToken(refresh) : transport.refresh(refresh);
            } catch (ClineRefreshException typed) {
                return RefreshReport.failed(typed);
            } catch (Exception opaque) {
                // 不认识的异常不能证明什么都没发出去：保守按“可能已发送”处理。
                return RefreshReport.failed(refreshFailure(RefreshDelivery.POSSIBLY_SENT, "transport", 0,
                        false, false, CredentialChannel.UNKNOWN, opaque));
            }
            if (next == null || next.access == null || next.access.isEmpty()) {
                // 交换调用正常返回却拿不到 access，无法证明上游没收到这次请求。
                return RefreshReport.failed(refreshFailure(RefreshDelivery.POSSIBLY_SENT, "pair", 0,
                        false, false, CredentialChannel.UNKNOWN, null));
            }
            if (!before.matches(account)) {
                // 交换已经发生，但账号被别的写入方改过：结果不能再交出去，
                // 而旧的一次性凭据仍可能已被消费。
                return RefreshReport.failed(refreshFailure(RefreshDelivery.SENT, "superseded", 0,
                        false, false, CredentialChannel.UNKNOWN, null));
            }
            return RefreshReport.ready(new RefreshCandidate(account, next));
        }
    }

    /** Legacy shared-object adapter. True also means another caller already refreshed it. */
    public static boolean refreshAccount(JSONObject acc) {
        return refreshAccount(acc, ClineAuth::refreshToken);
    }
    static boolean refreshAccount(JSONObject acc, RefreshTransport transport) {
        if (acc == null) return false;
        synchronized (acc) {
            if (acc.optLong("expires", 0L) > System.currentTimeMillis() + REQUEST_REFRESH_LEAD_MS
                    && !acc.optString("token", "").isEmpty()) return true;
            RefreshCandidate candidate = refreshCandidate(acc, false, transport);
            if (candidate == null || !candidate.matches(acc)) return false;
            SignIn next = candidate.next;
            try {
                acc.put("token", next.access); acc.put("access", next.access);
                if (next.refresh != null && !next.refresh.isEmpty()) acc.put("refresh", next.refresh);
                acc.put("expires", next.expires);
                if (next.email != null && !next.email.isEmpty()) acc.put("email", next.email);
                if (next.uid != null && !next.uid.isEmpty()) acc.put("uid", next.uid);
                return true;
            } catch (Exception ignored) { return false; }
        }
    }

    private static SignIn parseAuth(JSONObject data, String fallbackRefresh) throws Exception {
        String access = data.optString("accessToken", "");
        if (access.isEmpty()) access = data.optString("access_token", "");
        if (access.isEmpty()) throw new Exception("Cline sent back no access token");

        String refresh = data.optString("refreshToken", "");
        if (refresh.isEmpty()) refresh = data.optString("refresh_token", "");
        if (refresh.isEmpty()) refresh = fallbackRefresh;

        JSONObject ui = data.optJSONObject("userInfo");
        if (ui == null) ui = data.optJSONObject("user");
        if (ui == null) ui = new JSONObject();

        SignIn si = new SignIn();
        si.access = access;
        si.refresh = refresh;
        si.expires = isoToMs(data.optString("expiresAt", ""));
        si.uid = optFirst(ui.optString("clineUserId", ""), ui.optString("subject", ""), ui.optString("id", ""));
        si.email = ui.optString("email", "");
        si.name = ui.optString("name", "");
        si.accountId = !si.email.isEmpty() ? si.email : si.uid;
        return si;
    }

    /**
     * 拉取 Cline 推荐模型与全量云端目录（免登录）
     */
    interface CatalogFeedReader { Object read(String url) throws Exception; }

    public static List<String> fetchFeed(JSONObject metaOut) {
        return fetchFeedInternal(metaOut, false, ClineAuth::readCatalogFeed);
    }

    /** Returns an empty list on incomplete/invalid remote feeds; never synthesizes defaults. */
    public static List<String> fetchFeedStrict(JSONObject metaOut) {
        return fetchFeedInternal(metaOut, true, ClineAuth::readCatalogFeed);
    }

    static List<String> fetchFeedStrict(JSONObject metaOut, CatalogFeedReader reader) {
        return fetchFeedInternal(metaOut, true, reader);
    }

    private static Object readCatalogFeed(String url) throws Exception {
        HttpURLConnection conn = openHttp(url);
        try {
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000); conn.setReadTimeout(12000);
            conn.setRequestProperty("Accept", "application/json");
            for (Map.Entry<String, String> e : clientHeaders(null).entrySet()) conn.setRequestProperty(e.getKey(), e.getValue());
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            return new com.gaeul.allin.json.JSONTokener(readStream(conn.getInputStream())).nextValue();
        } finally { conn.disconnect(); }
    }

    private static List<String> fetchFeedInternal(JSONObject metaOut, boolean strict, CatalogFeedReader reader) {
        JSONObject pending = new JSONObject();
        List<String> result = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();

        try {
            // 1. 获取推荐模型列表（包含 recommended, free, clinePass）
            Object recRaw = reader.read(API + "/ai/cline/recommended-models");
            JSONObject recFeed = recRaw instanceof JSONObject ? (JSONObject) recRaw : null;
            boolean validRec = false;
            if (recFeed != null) {
                JSONObject data = recFeed.optJSONObject("data");
                if (data != null) recFeed = data;

                String[] groups = new String[]{"recommended", "free", "clinePass"};
                for (String g : groups) {
                    JSONArray list = recFeed.optJSONArray(g);
                    if (list == null) { if (strict && recFeed.has(g)) return new ArrayList<String>(); continue; }
                    validRec = true;
                    boolean isPass = "clinePass".equals(g);
                    for (int i = 0; i < list.length(); i++) {
                        boolean isFree = "free".equals(g);
                        Object item = list.get(i);
                        String id = "";
                        String name = "";
                        if (item instanceof String) {
                            id = ((String) item).trim();
                        } else if (item instanceof JSONObject) {
                            JSONObject o = (JSONObject) item;
                            id = optFirst(o.optString("id", ""), o.optString("model", ""), o.optString("modelId", ""),
                                    o.optString("model_id", ""), o.optString("key", ""), o.optString("slug", ""));
                            name = optFirst(o.optString("display_name", ""), o.optString("displayName", ""),
                                    o.optString("name", ""), o.optString("label", ""));
                            if (o.optBoolean("free", false)) isFree = true;
                        }
                        if (id.trim().isEmpty()) { if (strict) return new ArrayList<String>(); continue; }
                        id = id.trim();
                        if (!seen.contains(id)) {
                            seen.add(id);
                            result.add(id);
                        }
                        if (pending != null) {
                            JSONObject m = pending.optJSONObject(id);
                            if (m == null) {
                                m = new JSONObject();
                                pending.put(id, m);
                            }
                            if (name.isEmpty() || name.equals(id)) name = prettify(id);
                            m.put("name", name);
                            if (isFree) m.put("free", true);
                            if (isPass || id.startsWith("cline-pass/")) m.put("pass", true);
                        }
                    }
                }
            }
            if (strict && (!validRec || result.isEmpty())) return new ArrayList<String>();
        } catch (Throwable e) {
            if (strict) return new ArrayList<String>();
        }

        // 2. 尝试获取云端目录，补充上下文长度与输入能力
        JSONArray cloudInputModels = new JSONArray();
        try {
            Object cloudRaw = reader.read(API + "/ai/cline/models");
            if (cloudRaw instanceof JSONObject || cloudRaw instanceof JSONArray) {
                JSONObject cloudFeed = cloudRaw instanceof JSONObject ? (JSONObject) cloudRaw : null;
                JSONArray list = cloudRaw instanceof JSONArray ? (JSONArray) cloudRaw : cloudFeed.optJSONArray("data");
                if (list == null && cloudFeed != null) list = cloudFeed.optJSONArray("models");
                if (strict && (list == null || list.length() == 0)) return new ArrayList<String>();
                int validCloud = 0;
                if (list != null) {
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject o = list.optJSONObject(i);
                        if (o == null) { if (strict) return new ArrayList<String>(); continue; }
                        Object rawId = o.opt("id");
                        if (!(rawId instanceof String) || ((String) rawId).trim().isEmpty()) { if (strict) return new ArrayList<String>(); continue; }
                        String id = ((String) rawId).trim();
                        validCloud++;
                        if (seen.add(id)) result.add(id);
                        JSONObject cloudMeta = pending.optJSONObject(id);
                        if (cloudMeta == null) {
                            cloudMeta = new JSONObject();
                            String name = optFirst(o.optString("display_name", ""), o.optString("displayName", ""), o.optString("name", ""));
                            cloudMeta.put("name", name.isEmpty() ? prettify(id) : name);
                            if (id.startsWith("cline-pass/")) cloudMeta.put("pass", true);
                            pending.put(id, cloudMeta);
                        }
                        int ctx = o.optInt("context_length", 0);
                        JSONObject top = o.optJSONObject("top_provider");
                        if (ctx <= 0 && top != null) ctx = top.optInt("context_length", 0);
                        if (ctx <= 0) ctx = o.optInt("context", 0);
                        JSONObject input = inputCapabilities(o);
                        if (input != null) cloudInputModels.put(new JSONObject().put("id", id).put("input", input));
                        if (pending != null) {
                            JSONObject m = pending.optJSONObject(id);
                            if (m != null) {
                                if (ctx > 0) m.put("context", ctx);
                                if (input != null) m.put("input", input);
                            }
                        }
                    }
                }
                if (strict && validCloud == 0) return new ArrayList<String>();
            } else if (strict) return new ArrayList<String>();
        } catch (Throwable ignored) {
            if (strict) return new ArrayList<String>();
        }

        // Recommended ids can use Cline-specific prefixes while the cloud catalog
        // lists the same model under its maker. Copy modalities only when every
        // matching catalog entry agrees, so a bare-name collision stays unknown.
        if (pending != null) {
            java.util.Iterator<String> ids = pending.keys();
            while (ids.hasNext()) {
                String id = ids.next();
                JSONObject meta = pending.optJSONObject(id);
                if (meta == null || meta.has("input")) continue;
                String bare = bareModelId(id);
                JSONObject agreed = null;
                boolean ambiguous = false;
                for (int i = 0; i < cloudInputModels.length(); i++) {
                    JSONObject candidate = cloudInputModels.optJSONObject(i);
                    if (candidate == null || !bare.equals(bareModelId(candidate.optString("id", "")))) continue;
                    JSONObject input = candidate.optJSONObject("input");
                    if (input == null) continue;
                    if (agreed != null && !agreed.toString().equals(input.toString())) { ambiguous = true; break; }
                    agreed = input;
                }
                if (!ambiguous && agreed != null) meta.put("input", new JSONObject(agreed.toString()));
            }
        }

        // 如果网络完全不可达或未抓到模型，使用内置默认模型快照
        if (!strict && result.isEmpty()) {
            for (String defM : DEFAULT_MODELS) {
                result.add(defM);
                if (pending != null) {
                    JSONObject m = new JSONObject();
                    m.put("name", prettify(defM));
                    if (defM.contains("-free/")) m.put("free", true);
                    if (defM.startsWith("cline-pass/")) m.put("pass", true);
                    pending.put(defM, m);
                }
            }
        }
        if (metaOut != null) {
            java.util.Iterator<String> keys = pending.keys();
            while (keys.hasNext()) { String key = keys.next(); try { metaOut.put(key, pending.get(key)); } catch (Exception e) { return new ArrayList<String>(); } }
        }
        return result;
    }

    /**
     * 查询 Cline 账号额度与 ClinePass 限额
     */
    private static final int USAGE_BODY_LIMIT = 1024 * 1024;

    private static final class UsageRead {
        int status;
        JSONObject json;
        FailureLayers.Kind kind;
    }

    private static UsageRead usageRead(String url, Map<String, String> headers) {
        UsageRead result = new UsageRead();
        HttpURLConnection conn = null;
        try {
            conn = openHttp(url);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("Accept", "application/json");
            for (Map.Entry<String, String> e : headers.entrySet()) conn.setRequestProperty(e.getKey(), e.getValue());
            result.status = conn.getResponseCode();
            if (result.status < 200 || result.status >= 300) {
                result.kind = readKind(result.status);
                return result;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (bytes.size() + n > USAGE_BODY_LIMIT) {
                        result.kind = FailureLayers.Kind.SCHEMA;
                        return result;
                    }
                    bytes.write(buf, 0, n);
                }
            }
            try { result.json = new JSONObject(bytes.toString("UTF-8")); }
            catch (com.gaeul.allin.json.JSONException e) { result.kind = FailureLayers.Kind.SCHEMA; return result; }
            if (businessFailure(result.json)) result.kind = FailureLayers.Kind.BUSINESS;
        } catch (Exception e) {
            result.kind = FailureLayers.transport(e);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return result;
    }

    private static boolean businessFailure(JSONObject obj) {
        Object error = obj.opt("error");
        if (error != null && error != JSONObject.NULL && !Boolean.FALSE.equals(error)
                && !(error instanceof String && ((String) error).isEmpty())) return true;
        if (Boolean.FALSE.equals(obj.opt("success")) || Boolean.FALSE.equals(obj.opt("ok"))) return true;
        Object code = obj.opt("code");
        if (code instanceof Number) {
            double value = ((Number) code).doubleValue();
            if (value != 0 && value != 200) return true;
        } else if (code instanceof String && !((String) code).isEmpty()
                && !"0".equals(code) && !"200".equals(code)) return true;
        return false;
    }

    private static JSONObject usageData(UsageRead r) {
        if (r.json == null || r.kind != null) return null;
        if (r.json.has("data")) {
            JSONObject data = r.json.optJSONObject("data");
            if (data == null) { r.kind = FailureLayers.Kind.SCHEMA; return null; }
            if (businessFailure(data)) { r.kind = FailureLayers.Kind.BUSINESS; return null; }
            return data;
        }
        return r.json;
    }

    private static FailureLayers.Layer usageLayer(FailureLayers.Kind kind, String section) {
        FailureLayers.Layer known = FailureLayers.layer(kind);
        if (known == FailureLayers.Layer.NETWORK || kind == FailureLayers.Kind.AUTH || kind == FailureLayers.Kind.RESTRICTED) return known;
        return "账号信息".equals(section) ? FailureLayers.Layer.LOGIN : FailureLayers.Layer.BALANCE;
    }

    private static JSONObject usageSection(UsageRead r, boolean valid, String name, JSONArray unread) throws Exception {
        JSONObject section = new JSONObject();
        section.put("status", valid ? "ok" : "unknown");
        if (r.status > 0) section.put("httpStatus", r.status);
        if (!valid) {
            if (r.kind == null) r.kind = FailureLayers.Kind.SCHEMA;
            FailureLayers.describe(section, r.kind, usageLayer(r.kind, name));
            section.put("affectsInference", JSONObject.NULL);
            section.put("inferenceStatus", "unknown");
            unread.put(name + ": " + (r.status > 0 && (r.status < 200 || r.status >= 300)
                    ? readFailure(r.status) : "数据未能读取"));
        }
        return section;
    }

    public static JSONObject fetchUsage(String token, boolean isOauth) throws Exception {
        JSONObject out = new JSONObject(), sections = new JSONObject();
        JSONArray unread = new JSONArray();
        String bearer = token;
        if (isOauth && !bearer.toLowerCase(Locale.US).startsWith(WORKOS_PREFIX)) bearer = WORKOS_PREFIX + bearer;
        Map<String, String> headers = clientHeaders(null);
        headers.put("Authorization", "Bearer " + bearer);

        UsageRead me = usageRead(API + "/users/me", headers);
        JSONObject identity = usageData(me);
        String userId = "";
        if (identity != null) {
            userId = optFirst(identity.opt("clineUserId") instanceof String ? identity.optString("clineUserId") : "",
                    identity.opt("subject") instanceof String ? identity.optString("subject") : "",
                    identity.opt("id") instanceof String ? identity.optString("id") : "");
        }
        boolean identityOk = !userId.trim().isEmpty();
        if (identityOk) {
            String plan = optFirst(identity.optString("plan", ""), identity.optString("planType", ""), identity.optString("planName", ""));
            out.put("user", optFirst(identity.optString("email", ""), identity.optString("name", ""), userId));
            if (!plan.isEmpty()) out.put("plan", plan);
        }
        sections.put("identity", usageSection(me, identityOk, "账号信息", unread));

        UsageRead balance = new UsageRead();
        JSONObject bal = null;
        if (identityOk) {
            balance = usageRead(API + "/users/" + URLEncoder.encode(userId, "UTF-8") + "/balance", headers);
            bal = usageData(balance);
        }
        Object value = bal == null ? null : bal.opt("balance");
        double units = value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
        boolean balanceOk = !Double.isNaN(units) && !Double.isInfinite(units) && units >= 0;
        if (balanceOk) {
            double dollars = units / 1e6;
            out.put("balance", String.format(Locale.US, dollars >= 0.01 ? "$%.2f" : "$%.4f", dollars));
            out.put("balanceUSD", dollars);
        }
        sections.put("balance", usageSection(balance, balanceOk, "余额", unread));

        UsageRead limitsRead = usageRead(API + "/users/me/plan/usage-limits", headers);
        JSONObject limitData = usageData(limitsRead);
        JSONArray limits = limitData == null ? null : limitData.optJSONArray("limits");
        boolean limitsOk = limits != null;
        JSONArray winOut = new JSONArray();
        if (limits != null) {
            for (int i = 0; i < limits.length(); i++) {
                JSONObject l = limits.optJSONObject(i);
                Object percent = l == null ? null : l.opt("percentUsed");
                double pct = percent instanceof Number ? ((Number) percent).doubleValue() : Double.NaN;
                if (l == null || !(l.opt("type") instanceof String) || l.optString("type").isEmpty()
                        || Double.isNaN(pct) || Double.isInfinite(pct) || pct < 0) {
                    limitsOk = false;
                    continue;
                }
                JSONObject w = new JSONObject().put("type", l.optString("type")).put("percentUsed", pct);
                if (l.opt("resetsAt") instanceof String) w.put("resetsAt", l.optString("resetsAt"));
                winOut.put(w);
            }
            if (limitsOk || winOut.length() > 0) out.put("limits", winOut);
        }
        sections.put("limits", usageSection(limitsRead, limitsOk, "套餐限额", unread));
        boolean complete = identityOk && balanceOk && limitsOk;
        out.put("ok", identityOk);
        out.put("complete", complete);
        out.put("partial", !complete && (identityOk || balanceOk || limitsOk || winOut.length() > 0));
        out.put("sections", sections);
        out.put("readAt", System.currentTimeMillis());
        if (unread.length() > 0) out.put("unread", unread);
        if (!complete) {
            UsageRead failed = !identityOk ? me : !balanceOk ? balance : limitsRead;
            FailureLayers.describe(out, failed.kind, usageLayer(failed.kind, !identityOk ? "账号信息" : "余额"));
            out.put("affectsInference", JSONObject.NULL);
            out.put("inferenceStatus", "unknown");
            if (!identityOk) {
                out.put("reason", "identity_unread");
                out.put("error", "Cline 账号信息未能读取；这不代表账号有问题，也不代表额度为零。");
            }
        }
        return out;
    }

    private static JSONObject fetchJson(String urlStr) {
        try {
            HttpURLConnection conn = openHttp(urlStr);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("Accept", "application/json");
            Map<String, String> clH = clientHeaders(null);
            for (Map.Entry<String, String> e : clH.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
            if (conn.getResponseCode() >= 200 && conn.getResponseCode() < 400) {
                String str = readStream(conn.getInputStream());
                return new JSONObject(str);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static JSONObject fetchJsonWithHeaders(String urlStr, Map<String, String> headers) {
        return fetchJsonWithHeaders(urlStr, headers, null);
    }

    /**
     * 读一个 JSON，同时把这次读的结果交回去。status[0]：&gt;0 是 HTTP 码，0 是连接没通。
     *
     * <p>原来只有一个返回 null 的版本，401、404 和断网看起来一模一样，于是
     * fetchUsage 最后无条件写 ok:true——账号信息一段没读出来也会被当成"账号正常"。
     */
    private static JSONObject fetchJsonWithHeaders(String urlStr, Map<String, String> headers, int[] status) {
        try {
            HttpURLConnection conn = openHttp(urlStr);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("Accept", "application/json");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            int code = conn.getResponseCode();
            if (status != null) status[0] = code;
            if (code >= 200 && code < 400) {
                String str = readStream(conn.getInputStream());
                return new JSONObject(str);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 一次读取失败的说法：把 HTTP 码与连接没通分开。 */
    static String readFailure(int status) {
        if (status == 401) return "登录态被拒 (401)";
        if (status == 403) return "被限制访问 (403)";
        if (status == 404) return "端点不存在 (404)";
        if (status == 429) return "被限流 (429)";
        if (status > 0) return "HTTP " + status;
        return "连接没通";
    }

    /** 一次读取失败属于哪一类，喂给 FailureLayers 决定层与建议。 */
    static FailureLayers.Kind readKind(int status) {
        if (status == 401) return FailureLayers.Kind.AUTH;
        if (status == 403) return FailureLayers.Kind.RESTRICTED;
        if (status == 404) return FailureLayers.Kind.NOT_FOUND;
        if (status == 429) return FailureLayers.Kind.RATE;
        if (status > 0) return FailureLayers.Kind.UNREAD;
        return FailureLayers.Kind.NETWORK;
    }

    private static String bareModelId(String id) {
        if (id == null) return "";
        String bare = id;
        int slash = bare.lastIndexOf('/');
        if (slash >= 0) bare = bare.substring(slash + 1);
        int variant = bare.indexOf(':');
        if (variant >= 0) bare = bare.substring(0, variant);
        return bare.toLowerCase(Locale.ROOT);
    }

    private static JSONObject inputCapabilities(JSONObject model) {
        JSONObject architecture = model.optJSONObject("architecture");
        JSONArray modalities = architecture == null ? null : architecture.optJSONArray("input_modalities");
        if (modalities == null) modalities = model.optJSONArray("input_modalities");
        if (modalities == null || modalities.length() == 0) return null;
        Set<String> supported = new HashSet<String>();
        for (int i = 0; i < modalities.length(); i++) {
            String modality = modalities.optString(i, "").toLowerCase(Locale.ROOT);
            if (!modality.isEmpty()) supported.add(modality);
        }
        JSONObject result = new JSONObject();
        try {
            for (String kind : new String[]{"text", "image", "audio", "video", "pdf"}) {
                result.put(kind, supported.contains(kind));
            }
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
        return result;
    }

    public static String prettify(String id) {
        if (id == null) return "";
        String seg = id;
        int lastSlash = id.lastIndexOf('/');
        if (lastSlash >= 0) seg = id.substring(lastSlash + 1);

        String[] parts = seg.split("[-_]");
        StringBuilder sb = new StringBuilder();
        for (String w : parts) {
            if (w.isEmpty()) continue;
            String low = w.toLowerCase(Locale.US);
            if (sb.length() > 0) sb.append(' ');
            if ("gpt".equals(low) || "glm".equals(low) || "ai".equals(low) || "llm".equals(low) || "k3".equals(low)) {
                sb.append(low.toUpperCase(Locale.US));
            } else if ("mimo".equals(low)) {
                sb.append("MiMo");
            } else {
                sb.append(Character.toUpperCase(low.charAt(0))).append(low.substring(1));
            }
        }
        return sb.toString();
    }

    private static String optFirst(String... vals) {
        for (String v : vals) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return "";
    }

    private static long isoToMs(String iso) {
        if (iso == null || iso.trim().isEmpty()) return System.currentTimeMillis() + 3600000L;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            String clean = iso.replaceAll("\\.[0-9]+", "").replaceAll("Z$", "");
            Date d = sdf.parse(clean);
            if (d != null) return d.getTime();
        } catch (Throwable ignored) {
        }
        return System.currentTimeMillis() + 3600000L;
    }

    private static String readStream(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        is.close();
        return baos.toString("UTF-8");
    }
}
