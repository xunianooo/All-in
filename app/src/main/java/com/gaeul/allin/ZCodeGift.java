package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/**
 * ZCode 的 Start Plan / gift 通道。
 *
 * 为什么单独一个类：ZCode 送的额度（夜晚赠送、Start Plan、Trust Build）**只认 ZCode 自己的
 * session JWT**，走 zcode.z.ai 的 /api/v1/zcode-plan/anthropic，而不是 api.z.ai 的 /api/anthropic。
 * 换出来的 <id>.<secret> 那把 API Key 只能花 GLM Coding Plan，打 gift 会返回 1113。
 *
 * 而且 zcode.z.ai 只把额度放给「看起来像 ZCode 客户端」的请求，否则一律
 * 405 {"code":3012,"msg":"request has been blocked due to unusual activity."}。
 * 所以这里的 headers 与 body 都按 packages/zcode 的 startHeaders / dress 逐条复刻：
 *
 *  - headers 是**整组替换**，agent 的头一个都不留（不带 x-api-key、不带 X-Device-Mid、
 *    不带 anthropic-beta；只额外追加 x-session-id 与 x-query-id）。
 *  - X-Title 是 "Z Code@electron"：3.17.2 的桌面入口（zcode-protocol-entrypoint）报的就是 electron，
 *    "Z Code@cli" 是命令行那条路的（它不带桌面 prompt），与本通道不符。
 *  - x-query-id 由 queryOf 命名：同一句用户问话的续轮（模型发起的工具调用）共用同一个 id，
 *    用户下一句才是新的；id 只是种子哈希，问话内容不出本机。
 *  - system 恒为 3 块，每块一个 cache_control；agent 自己的 system 接在第 3 块尾部，
 *    第 4 块会被 405/3012 拒（#343）。
 *  - messages 最前面插一条 <system-reminder> 日期 turn。
 *  - cache_control 总数固定 4（3 system + 最后一条消息的最后一个块），tools 上一个都不留。
 *  - metadata.user_id 是**字符串化的 JSON**：{"device_id":…,"account_uuid":"","session_id":…}
 */
final class ZCodeGift {

    static final String START_BASE = "https://zcode.z.ai/api/v1/zcode-plan/anthropic";
    /** 与 ZCodeAuth 同一个号：gift 请求的 UA / X-ZCode-App-Version 不能跟别处不一致 */
    static final String APP_VERSION = ZCodeAuth.APP_VERSION;
    static final String UA = "ZCode/" + APP_VERSION;
    private static final String EPHEMERAL = "ephemeral";

    private ZCodeGift() {}

    // ===================== 身份派生 =====================

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "0000000000000000000000000000000000000000000000000000000000000000";
        }
    }

    /** 与插件 uuidOf 一致：由 seed 确定性地做出一个 v4 形状的 UUID，同 seed 每次相同 */
    static String uuidOf(String seed) {
        String h = sha256Hex(seed);
        int v = (Integer.parseInt(h.substring(16, 17), 16) & 3) | 8;
        return h.substring(0, 8) + "-" + h.substring(8, 12) + "-4" + h.substring(13, 16)
                + "-" + Integer.toHexString(v) + h.substring(17, 20) + "-" + h.substring(20, 32);
    }

    /** x-session-id：同一设备恒定，同一会话恒定 */
    static String sessionOf(String agentSessionId, String device) {
        String own = agentSessionId == null ? "" : agentSessionId.trim();
        if (own.matches("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) {
            return own.toLowerCase();
        }
        return own.isEmpty()
                ? uuidOf("zcode-session-device\0" + (device == null ? "" : device))
                : uuidOf("zcode-session\0" + own);
    }

    /**
     * x-query-id：3.17.2 的 createModelRequestAttributionHeaders 给「用户这一问」起的名字。
     * 与插件 queryOf 逐条一致：n 是说过话的 user message 数（带文本的 user message 才算，
     * 只有 tool_result 的不算），id = uuidOf("zcode-query\0" + session + "\0" + n)。
     * 所以同一轮里模型发起的工具调用（在同一句之后追加 assistant / tool_result）n 不变，
     * 续轮还是同一个 id；用户再说一句，n 加一，就是新的 id。id 只是种子哈希，问话内容不出本机。
     * 读不出 body（非法 JSON、messages 不是数组、一句用户说的话都没有）就返回空串，也就不发这个头。
     */
    static String queryOf(String text, String session) {
        JSONObject parsed;
        try { parsed = new JSONObject(text); } catch (Exception e) { return ""; }
        JSONArray messages = parsed.optJSONArray("messages");
        if (messages == null) return "";
        int n = 0;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null || !"user".equals(m.optString("role", ""))) continue;
            if (saidUser(m.opt("content"))) n++;
        }
        return n == 0 ? "" : uuidOf("zcode-query\0" + (session == null ? "" : session) + "\0" + n);
    }

    /** 一条 user message 的 content 里有没有「用户说的话」：只有 tool_result 的那条没有 */
    private static boolean saidUser(Object content) {
        if (content instanceof String) return !blank((String) content);
        if (!(content instanceof JSONArray)) return false;
        JSONArray parts = (JSONArray) content;
        for (int i = 0; i < parts.length(); i++) {
            Object part = parts.opt(i);
            if (part == null || part == JSONObject.NULL) continue;
            if (part instanceof JSONObject && "tool_result".equals(((JSONObject) part).optString("type", ""))) continue;
            if (part instanceof String && ((String) part).isEmpty()) continue;
            return true;
        }
        return false;
    }

    /** 插件那边用的是 JS 的 {@code trim()}：空白不止 ASCII，NBSP、全角空格、BOM、行分隔符都算 */
    private static boolean blank(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r'
                    || Character.isSpaceChar(c) || c == 0xFEFF) continue;
            return false;
        }
        return true;
    }

    /** metadata.user_id 是字符串化的 JSON，键序固定，account_uuid 恒为空串 */
    static String userIdOf(String device, String session) {
        return "{\"device_id\":\"" + device + "\",\"account_uuid\":\"\",\"session_id\":\"" + session + "\"}";
    }

    // ===================== 本机环境（ZCode 客户端会报这些） =====================

    /** 与插件 platform() 一致：linux-<arch>。gift 请求和 marketing 头用同一个口径。 */
    static String platform() {
        String arch = System.getProperty("os.arch", "");
        if (arch.contains("aarch64") || arch.contains("arm64")) arch = "arm64";
        else if (arch.contains("x86_64") || arch.contains("amd64")) arch = "x64";
        return "linux-" + arch;
    }

    private static String osCategory() {
        return "linux";
    }

    /** 对应 os.release()：Android 上是内核版本串 */
    private static String osRelease() {
        return System.getProperty("os.version", "unknown");
    }

    /** 对应 osVersion()：platform + " " + release() + " " + arch()（与 x-os-version 不同） */
    private static String fullOsVersion() {
        return "linux " + osRelease() + " " + System.getProperty("os.arch", "");
    }

    private static String locale() {
        Locale l = Locale.getDefault();
        if (l == null) return "unknown";
        String lang = l.getLanguage();
        String country = l.getCountry();
        if (lang == null || lang.isEmpty()) return "unknown";
        return country == null || country.isEmpty() ? lang : lang + "-" + country;
    }

    private static String timezone() {
        TimeZone tz = TimeZone.getDefault();
        return tz == null ? "unknown" : tz.getID();
    }

    private static String today() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        return f.format(new Date());
    }

    // ===================== body 整形（dress） =====================

    /** # Environment 段，与插件 environment() 一致 */
    private static String environmentBlock() {
        return ZCodePrompt.ENV_HEADING + "\n" + ZCodePrompt.ENV_INVOKED_LINE
                + "\n - " + ZCodePrompt.ENV_PLATFORM_LABEL + ": linux"
                + "\n - " + ZCodePrompt.ENV_OS_VERSION_LABEL + ": " + fullOsVersion()
                + "\n - " + ZCodePrompt.ENV_UNTRUSTED_NOTE;
    }

    /** 最前面那条 <system-reminder> 日期 turn，标签紧贴正文，前后不留空白 */
    private static JSONObject contextPrefix() {
        String body = ZCodePrompt.CTX_CURRENT_DATE_LINE.replace("{date}", today());
        JSONArray content = new JSONArray();
        JSONObject t = new JSONObject();
        t.put("type", "text");
        t.put("text", "<system-reminder>" + body + "</system-reminder>");
        content.put(t);
        JSONObject m = new JSONObject();
        m.put("role", "user");
        m.put("content", content);
        return m;
    }

    private static JSONObject cachedText(String text) {
        JSONObject o = new JSONObject();
        o.put("type", "text");
        o.put("text", text);
        JSONObject cc = new JSONObject();
        cc.put("type", EPHEMERAL);
        o.put("cache_control", cc);
        return o;
    }

    /** 收集 agent 自己的 system 文本（数组或字符串），用于接到第 3 块尾部 */
    private static java.util.List<String> ownSystem(JSONObject body) {
        java.util.List<String> own = new java.util.ArrayList<String>();
        Object sys = body.opt("system");
        if (sys instanceof String) {
            String s = (String) sys;
            if (!s.trim().isEmpty()) own.add(s);
        } else if (sys instanceof JSONArray) {
            JSONArray arr = (JSONArray) sys;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject x = arr.optJSONObject(i);
                if (x == null) continue;
                if (!"text".equals(x.optString("type"))) continue;
                String t = x.optString("text", "");
                if (t != null && !t.trim().isEmpty()) own.add(t);
            }
        }
        return own;
    }

    /**
     * 把一份 Anthropic Messages 请求体整成 ZCode 客户端的样子。
     * 只改 system / messages / metadata.user_id / tools[].cache_control，其余字段原样保留。
     */
    static JSONObject dress(JSONObject body, String site, String model, String device, String session) {
        String provider = "bigmodel".equals(site) ? "bigmodel-api" : "zai-api";
        java.util.List<String> own = ownSystem(body);

        java.util.List<String> dynamic = new java.util.ArrayList<String>();
        dynamic.add(ZCodePrompt.BEFORE_ENVIRONMENT);
        JSONArray offeredTools = body.optJSONArray("tools");
        for (int i=0; offeredTools!=null && i<offeredTools.length(); i++) {
            JSONObject tool=offeredTools.optJSONObject(i);
            if (tool!=null && "Skill".equals(tool.optString("name"))) { dynamic.add(ZCodePrompt.SESSION_GUIDANCE); break; }
        }
        dynamic.add(environmentBlock());
        if (model!=null && !model.isEmpty()) dynamic.add(ZCodePrompt.ENV_POWERED_BY_LINE.replace("{provider}",provider).replace("{model}",model));
        dynamic.add(ZCodePrompt.AFTER_ENVIRONMENT);
        dynamic.addAll(own);

        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < dynamic.size(); i++) {
            if (i > 0) joined.append("\n\n");
            joined.append(dynamic.get(i));
        }

        JSONArray sections = new JSONArray();
        sections.put(cachedText(ZCodePrompt.CLI_PREFIX));
        StringBuilder stable = new StringBuilder();
        for (int i = 0; i < ZCodePrompt.STABLE_SECTIONS.length; i++) {
            if (i > 0) stable.append("\n\n");
            stable.append(ZCodePrompt.STABLE_SECTIONS[i]);
        }
        sections.put(cachedText(stable.toString()));
        sections.put(cachedText("\n\n" + joined));
        body.put("system", sections);

        // 前面插日期 turn
        JSONArray old = body.optJSONArray("messages");
        JSONArray msgs = new JSONArray();
        msgs.put(contextPrefix());
        for (int i = 0; old != null && i < old.length(); i++) msgs.put(old.opt(i));
        body.put("messages", msgs);

        // 清掉消息上所有 cache 标记，只补最后一个块
        JSONObject last = null;
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null) continue;
            if ("system".equals(m.optString("role"))) continue;
            last = m;
            Object c = m.opt("content");
            if (c instanceof JSONArray) {
                JSONArray arr = (JSONArray) c;
                for (int k = 0; k < arr.length(); k++) {
                    JSONObject x = arr.optJSONObject(k);
                    if (x != null) x.remove("cache_control");
                }
            }
        }
        if (last != null) {
            Object c = last.opt("content");
            if (c instanceof String) {
                JSONArray arr = new JSONArray();
                arr.put(cachedText((String) c));
                try { last.put("content", arr); } catch (Exception ignored) {}
            } else if (c instanceof JSONArray && ((JSONArray) c).length() > 0) {
                JSONArray arr = (JSONArray) c;
                JSONObject x = arr.optJSONObject(arr.length() - 1);
                if (x != null && !x.has("cache_control")) {
                    JSONObject cc = new JSONObject();
                    try {
                        cc.put("type", EPHEMERAL);
                        x.put("cache_control", cc);
                    } catch (Exception ignored) {}
                }
            }
        }

        // tools 上一个 cache 标记都不留
        JSONArray tools = body.optJSONArray("tools");
        for (int i = 0; tools != null && i < tools.length(); i++) {
            JSONObject t = tools.optJSONObject(i);
            if (t != null) t.remove("cache_control");
        }

        // Gift metadata is bound to the authenticated device/session; caller attribution is not an identity.
        Object existingMeta = body.opt("metadata");
        JSONObject meta = existingMeta instanceof JSONObject ? (JSONObject)existingMeta : new JSONObject();
        if (existingMeta instanceof JSONArray) {
            JSONArray values=(JSONArray)existingMeta;
            for(int i=0;i<values.length();i++)meta.put(String.valueOf(i),values.opt(i));
        }
        meta.put("user_id",new JSONObject().put("device_id",device).put("account_uuid","").put("session_id",session).toString());
        body.put("metadata",meta);
        return body;
    }

    // ===================== 发请求 =====================

    private static final String POLICY_URL = START_BASE + "/v1/messages";
    private static volatile String fixtureBase;
    private static volatile ZCodeRefusalPolicy cooldown = new ZCodeRefusalPolicy();
    private static final OutboundLimiter limiter = new OutboundLimiter(1, 32);
    static {
        java.util.Map<String, OutboundLimiter.Policy> policies = new java.util.HashMap<>();
        policies.put("zcode-gift", new OutboundLimiter.Policy(0, 0, 8, 16, 30000));
        limiter.configure(policies);
    }
    static void resetCooldownForTests() { cooldown = new ZCodeRefusalPolicy(); }
    static void resetBusyForTests() { giftBusyHold.reset(); }
    static long busyHoldMillis() { return giftBusyHold.remainingMillis(); }
    static void setCooldownClockForTests(ZCodeRefusalPolicy.Clock clock) { cooldown = new ZCodeRefusalPolicy(clock); }
    static void setFixtureBase(String base) {
        if (base == null) { fixtureBase = null; return; }
        try {
            java.net.URI uri = new java.net.URI(base);
            String h = uri.getHost();
            if (!"http".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !("127.0.0.1".equals(h) || "localhost".equals(h) || "::1".equals(h)))
                throw new IllegalArgumentException("loopback fixture required");
            fixtureBase = base;
        } catch (java.net.URISyntaxException e) { throw new IllegalArgumentException("invalid fixture"); }
    }
    /** Only call for a request known to use gift; this may suppress unrelated plan routing. */
    static ZCodeRefusalPolicy.Hold hold(String jwt, String device, RequestContext ctx) {
        NetworkPolicy.Snapshot network = ctx != null && ctx.network != null ? ctx.network : NetworkPolicy.capture();
        return cooldown.before(POLICY_URL, accountScope(jwt, device), network.toJson().toString());
    }
    private static String accountScope(String jwt, String device) {
        return jwt == null || jwt.trim().isEmpty() || device == null || device.trim().isEmpty() ? "" : jwt + "\0" + device;
    }
    private static void checkRead(HttpURLConnection conn, RequestContext ctx, int timeoutMs) throws java.io.IOException {
        if (ctx != null) {
            ctx.check();
            conn.setReadTimeout((int)Math.max(1, Math.min(Math.max(1, timeoutMs), ctx.remainingMillis())));
        }
    }
    static class Answer {
        int status; String body = ""; boolean refused, edge, held, sent;
        long retryAfterMillis; ZCodeRefusalPolicy.Kind classification = ZCodeRefusalPolicy.Kind.NONE;
        String error = ""; String transportKind;
        boolean observed; String source; String businessCode; String retryAfter;
    }
    static Answer send(String jwt, String device, String site, String model,
                       JSONObject body, String session, int timeoutMs) {
        return send(jwt, device, site, model, body, session, timeoutMs, null);
    }
    static Answer send(String jwt, String device, String site, String model,
                       JSONObject body, String session, int timeoutMs, RequestContext ctx) {
        Stream stream = openStream(jwt, device, site, model, body, session, timeoutMs, true, ctx);
        Answer out = new Answer();
        out.status=stream.status; out.body=stream.errorBody; out.refused=stream.refused; out.edge=stream.edge;
        out.held=stream.held; out.sent=stream.sent; out.retryAfterMillis=stream.retryAfterMillis;
        out.classification=stream.classification; out.error=stream.error; out.transportKind=stream.transportKind;
        out.observed=stream.observed; out.source=stream.source; out.businessCode=stream.businessCode; out.retryAfter=stream.retryAfter;
        if (stream.is != null) try {
            byte[] bytes = readBounded(stream.is, 8 * 1024 * 1024);
            if (bytes == null) { out.status=-1; out.body=""; out.error="Response exceeds local limit"; }
            else out.body = new String(bytes, "UTF-8");
        } catch (Exception e) { if (out.status==0) out.status=-1; out.error="ZCode transport failed"; out.transportKind=FailureLayers.transportName(e); }
        finally { try { stream.is.close(); } catch (Exception ignored) {} }
        return out;
    }

    // ===================== 额度支持的模型 =====================

    /**
     * 一个余额包说得出哪些模型名。
     *
     * 语义与上游 {@code bucketModels}（＝{@code ZCodeAuth.giftBucketModels}）逐条对齐：
     * {@code "model:x"} 取 x；**没有冒号的整段就是模型名**；{@code "tool:web-search"} 这类
     * 别的能力不算模型；两侧空白去掉；包里重复的名字只留一份（大小写按上游那样保留原写法）。
     * 直接复用 {@code ZCodeAuth.giftBucketModels}，卡片和路由不会因为两处各写一遍而漂移。
     */
    private static java.util.List<String> bucketModelNames(JSONObject bucket) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (bucket == null) return out;
        JSONArray names = ZCodeAuth.giftBucketModels(bucket);
        for (int i = 0; i < names.length(); i++) {
            String m = names.optString(i, "").trim();
            if (!m.isEmpty() && !out.contains(m)) out.add(m);
        }
        // 只有摘要的 buckets[] 带 "models" 数组、没带 capabilities 时（老摘要/别处构造的摘要），
        // 按同一口径直接读那份数组，不因为少一个字段就把能花的模型丢掉。
        if (out.isEmpty()) {
            JSONArray models = bucket.optJSONArray("models");
            for (int i = 0; models != null && i < models.length(); i++) {
                String m = models.optString(i, "").trim();
                if (!m.isEmpty() && !out.contains(m)) out.add(m);
            }
        }
        return out;
    }

    /**
     * 摘要里的一个数，按**小数**原样读；读不出来（缺字段、null、不是数、NaN/Infinity）是 {@code null}。
     *
     * 为什么不用 {@code optLong}：它走的是 {@code Number.longValue()}，{@code 0.4} 读成 {@code 0}、
     * {@code 12.5} 读成 {@code 12}。上游 {@code num()} 读 {@code remaining_units} 用的是浮点
     * （{@code parseFloat} 那一路），同样的 0.4 在那边是**还剩一点**。按 long 读就会把它当成用完
     * （见 {@link #bucketSpent}）。余额里的数本来就可能是小数（按比例折算出来的 credit），
     * 所以这里一律按 double 读，不再截断。
     */
    private static Double bucketNumber(JSONObject source, String key) {
        if (source == null || !source.has(key) || source.isNull(key)) return null;
        Number n = source.optNumber(key, null);
        if (n == null) return null;
        double v = n.doubleValue();
        if (Double.isNaN(v) || Double.isInfinite(v)) return null;
        return Double.valueOf(v);
    }

    /**
     * 这个包还能不能花，读法就是上游 {@code giftSpent}：
     * 有 {@code remaining} 就只看它（{@code <= 0} 即花完）；否则看 {@code used} 与 {@code total}
     * 谁大。摘要里这几个数已经折成 {@code remaining} 与 {@code spent} 两个字段，所以以
     * {@code remaining} 为主。
     *
     * <p>两条边界：
     * <ul>
     *   <li><b>按 double 比，不按 long</b>：{@code 0.4} 是"还剩一点"，不是"用完了"。用
     *       {@code optLong} 读会把 0.4 截成 0，于是把这个还能花的包判成花完、把它覆盖的型号
     *       从 {@link #allowedModels} 里删掉，请求就掉到付费 Coding Plan 上（用户付钱）。
     *       上游 giftSpent 在同一份数据上给的是 false，这里必须一样；</li>
     *   <li>{@code remaining = -1} 是摘要自己标的"这个包的余量读不出来"（配合
     *       {@code remainderUnknown}），那是**未知**，不能当成"已经用完"；摘要若明确标了
     *       {@code spent}，即使 {@code remaining} 读不出来也照它算。余额真为负时摘要把 leftOf
     *       先折成 0 并置 spent，所以这里的负数只可能是那个占位符。</li>
     * </ul>
     */
    private static boolean bucketSpent(JSONObject bucket) {
        if (bucket == null) return false;
        Double left = bucketNumber(bucket, "remaining");
        if (left != null && !bucket.optBoolean("remainderUnknown", false)) {
            double v = left.doubleValue();
            if (v >= 0) return v <= 0;                // 0 就是花完；0.4 之类的还剩着（上游也是浮点比）
        }
        return bucket.optBoolean("spent", false);     // 余量读不出来时才听摘要自己给的结论
    }

    /**
     * 覆盖某个型号的该包还剩多少，**小数原样**：{@code null} 是**未知**（不是 0）。
     * 只有摘要明确说花完了（{@code spent}）才算确定的 0。
     */
    private static Double bucketRemainingUnits(JSONObject bucket) {
        if (bucket == null) return null;
        Double left = bucketNumber(bucket, "remaining");
        if (left != null && !bucket.optBoolean("remainderUnknown", false) && left.doubleValue() >= 0) return left;
        return bucket.optBoolean("spent", false) ? Double.valueOf(0) : null;
    }

    /**
     * 从 billing/balance 的摘要里解析出 gift **现在真的能花**的模型名。
     *
     * 口径就是上游 {@code startPlan} 的 {@code models}：模型名来自 {@code bucketModels}，
     * 只有没花完的包（{@code !giftSpent}）才算进来；花完的包仍然说得名字，但一个都花不到，
     * 所以不进这份表（那部分在摘要的 {@code allModels} 里）。
     *
     * 三条不变量：
     * <ul>
     *   <li>算不出剩多少的包**不是花完**，它的模型照样列出来 —— 上游 giftSpent 就是 false。
     *       这里漏掉它，请求会掉到付费 Coding Plan 上（也就是"误导付费"）；</li>
     *   <li>一次都不把未知额度写成 0：{@code remaining = -1} 与 {@code remaining = 0} 是两件事；</li>
     *   <li>名字写法与卡片同一套：{@code model:x} 取 x，裸名字（没有冒号）本身就是模型名，
     *       {@code tool:xxx} 不算，空白去掉，重复的只留一份。</li>
     * </ul>
     *
     * 摘要里已经有 {@code models}（{@code ZCodeAuth.giftSummary} 按同一口径算好的可花清单）时
     * 就用它，保证卡片、探测、路由说的是同一句话；只有在没有这份清单时才从 buckets 现算。
     */
    static java.util.List<String> allowedModels(JSONObject summary) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (summary == null) return out;
        JSONArray known = summary.optJSONArray("models");
        if (known != null) {
            for (int i = 0; i < known.length(); i++) {
                String m = known.optString(i, "").trim();
                if (!m.isEmpty() && !out.contains(m)) out.add(m);
            }
            return out;
        }
        JSONArray buckets = summary.optJSONArray("buckets");
        for (int i = 0; buckets != null && i < buckets.length(); i++) {
            JSONObject b = buckets.optJSONObject(i);
            if (b == null) continue;
            if (bucketSpent(b)) continue;        // 用光了就不列；读不出来的仍然列
            for (String m : bucketModelNames(b)) if (!out.contains(m)) out.add(m);
        }
        return out;
    }

    /**
     * 覆盖某个型号的赠送额度还剩多少，**小数原样**。{@code null} 是未知（没有覆盖它的桶，
     * 或其中一个桶读不出余量）。
     *
     * 摘要里的 remainingUnits 是全部未过期桶的合计，不区分型号。用它回答
     * "测这个型号时还剩多少"，会把别的型号的额度算进来；这里按能力标签取
     * 覆盖该型号的桶，读不出来就不报，不拿合计冒充。
     *
     * "覆盖该型号"用的是同一个 bucketModels 口径（{@code model:x}、裸名字都算，
     * {@code tool:x} 不算，空白先去掉、比较不分大小写），所以卡片说"能花这个型号"时，
     * 这里一定读得到它的额度，不会一边可路由一边报未知。多个包覆盖同一型号时相加；
     * 其中任何一个的额度读不出来就整份报未知，只报读得出的那部分等于少报。
     *
     * 这是这条链路上唯一不丢精度的那把尺：{@code 0.4} 就报 {@code 0.4}。
     */
    static Double remainingUnitsFor(JSONObject summary, String model) {
        JSONArray buckets = summary == null ? null : summary.optJSONArray("buckets");
        if (buckets == null) return null;
        String want = model == null ? "" : model.trim();
        if (want.isEmpty()) return null;
        double total = 0;
        boolean any = false;
        for (int i = 0; i < buckets.length(); i++) {
            JSONObject b = buckets.optJSONObject(i);
            if (b == null) continue;
            if (!bucketCoversModel(b, want)) continue;
            Double left = bucketRemainingUnits(b);
            if (left == null) return null;         // 未知就是未知，不补 0
            total += left.doubleValue();
            any = true;
        }
        return any ? Double.valueOf(total) : null;  // 一个桶都没覆盖这个型号：未知，不是 0
    }

    /**
     * 覆盖某个型号的赠送额度还剩多少，**整单位**。返回 -1 表示读不出来，调用方必须把 -1
     * 当"未知"，不能当成 0。
     *
     * <p><b>已知的精度上限（这不是修好了，是明说）</b>：返回类型是 {@code long}，两个调用点
     * （{@code GatewayServer} 的 billing 窗口与赠送探测的 {@code giftRemaining}）也都按
     * {@code long} 取值、按整数写进 JSON。所以 0.4 个单位**装不进来**：
     * <ul>
     *   <li>截成 0 是谎报 —— 面板会写"剩 0"，而这个型号其实还能花（{@link #allowedModels}
     *       里有它，路由也真的走赠送）；</li>
     *   <li>进成 1 也是谎报 —— 多报出一份不存在的额度。</li>
     * </ul>
     * 所以这里只在合计**就是整数**时给数（{@code 0.4+0.6} 这种浮点噪声按 1e-9 容差认），
     * 其余一律 -1（未知）。要真的把小数显示出来，得改这个方法的返回类型与那两个调用点
     * （{@code GatewayServer} 的 allowance 窗口、赠送探测的 {@code giftRemaining}）；
     * 那些文件不在这条修复的授权范围内，所以在那边接线之前，这条路径选择"报未知"，
     * 不选"报一个假数"。精确值用 {@link #remainingUnitsFor}。
     */
    static long remainingFor(JSONObject summary, String model) {
        Double exact = remainingUnitsFor(summary, model);
        if (exact == null) return -1;
        double v = exact.doubleValue();
        if (Math.abs(v) > 9.0e15 || Math.abs(v - Math.rint(v)) > 1e-9) return -1;  // 整单位装不下小数
        return (long) Math.rint(v);
    }

    /** 这个包的能力标签里点到了 {@code model} 没有（口径同 bucketModels，比较不分大小写）。 */
    private static boolean bucketCoversModel(JSONObject bucket, String model) {
        for (String m : bucketModelNames(bucket)) if (m.equalsIgnoreCase(model)) return true;
        return false;
    }

    interface Observer {
        void admission(long startedNanos);
        void handoff(long outboundAt);
        default void send(int ordinal, long at) { handoff(at); }
        default void busy(String code, int sends, long waitedMillis, String source) {}
    }
    static class Stream {
        HttpURLConnection conn; int status; InputStream is; String errorBody = "";
        boolean refused, edge, held, sent; long retryAfterMillis;
        ZCodeRefusalPolicy.Kind classification = ZCodeRefusalPolicy.Kind.NONE;
        String error = ""; String transportKind;
        boolean observed; String source; String businessCode; String retryAfter;
        int sends; long retryWaitMillis; String busyCode;
    }
    private static final ZCodeGiftBusyPolicy.Gate giftGate = new ZCodeGiftBusyPolicy.Gate();
    private static final ZCodeGiftBusyPolicy.BusyHold giftBusyHold = new ZCodeGiftBusyPolicy.BusyHold();
    static String safeBusinessCode(String body) {
        try {
            com.gaeul.allin.json.JSONTokener parser=new com.gaeul.allin.json.JSONTokener(body);
            Object parsed=parser.nextValue();if(!(parsed instanceof JSONObject)||parser.nextClean()!=0)return null;
            JSONObject json=(JSONObject)parsed, error=json.optJSONObject("error");
            Object root=json.opt("code"), nested=error==null?null:error.opt("code");
            String first=root==null||root==JSONObject.NULL?null:String.valueOf(root);
            String second=nested==null||nested==JSONObject.NULL?null:String.valueOf(nested);
            if(first!=null&&!first.matches("[0-9]{1,12}"))return null;
            if(second!=null&&!second.matches("[0-9]{1,12}"))return null;
            if(first!=null&&second!=null&&!first.equals(second))return null;
            return first==null?second:first;
        } catch(Exception invalid){return null;}
    }
    private static byte[] readBounded(InputStream in, int limit) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n;
        while ((n=in.read(buf)) != -1) { if (out.size() + n > limit) return null; out.write(buf,0,n); }
        return out.toByteArray();
    }
    private static InputStream decoded(final HttpURLConnection c, InputStream input, final RequestContext ctx, final int timeoutMs) throws java.io.IOException {
        if (input == null) return null;
        InputStream guarded = new java.io.FilterInputStream(input) {
            @Override public int read() throws java.io.IOException {
                checkRead(c, ctx, timeoutMs); int n = in.read(); if (ctx != null) ctx.check(); return n;
            }
            @Override public int read(byte[] b, int off, int len) throws java.io.IOException {
                checkRead(c, ctx, timeoutMs); int n = in.read(b, off, len); if (ctx != null) ctx.check(); return n;
            }
        };
        return "gzip".equalsIgnoreCase(c.getContentEncoding()) ? new java.util.zip.GZIPInputStream(guarded) : guarded;
    }
    private static void localBusy(Stream out, long remaining) {
        out.businessCode=null;
        out.status=429; out.source="local_busy_hold"; out.held=true; out.retryAfterMillis=remaining;
        out.retryAfter=Long.toString((remaining+999)/1000); out.error="Local gift busy hold";
        out.errorBody=localError(out.error,remaining);
    }
    private static boolean hasUsage(Object value) { return hasUsage(value,0); }
    private static boolean hasUsage(Object value,int depth) {
        if(depth>32)return true;
        if (value instanceof JSONObject) {
            JSONObject o=(JSONObject)value; java.util.Iterator<String> keys=o.keys();
            while(keys.hasNext()) { String k=keys.next(); if ("usage".equalsIgnoreCase(k) && !o.isNull(k)) return true;
                if (hasUsage(o.opt(k),depth+1)) return true; }
        } else if (value instanceof JSONArray) {
            JSONArray a=(JSONArray)value; for(int i=0;i<a.length();i++) if(hasUsage(a.opt(i),depth+1)) return true;
        }
        return false;
    }
    private static String completeJson(byte[] data) {
        if (data == null) return null;
        try {
            String text=java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(data)).toString();
            com.gaeul.allin.json.JSONTokener tokener=new com.gaeul.allin.json.JSONTokener(text);
            Object obj=tokener.nextValue();
            if (!(obj instanceof JSONObject) || tokener.nextClean()!=0) return null;
            return text;
        } catch(Exception e) { return null; }
    }
    private static byte[] readCompleteError(HttpURLConnection conn, RequestContext ctx, int timeoutMs) throws java.io.IOException {
        String contentType=conn.getContentType(), encoding=conn.getContentEncoding();
        if(contentType!=null && contentType.toLowerCase(Locale.ROOT).contains("text/event-stream"))return null;
        if(encoding!=null && !encoding.isEmpty() && !"identity".equalsIgnoreCase(encoding) && !"gzip".equalsIgnoreCase(encoding))return null;
        InputStream raw=conn.getErrorStream();
        if(raw==null && conn.getResponseCode()>=300 && conn.getResponseCode()<400)raw=conn.getInputStream();
        if (raw==null) return null;
        final long[] count={0};
        InputStream counted=new java.io.FilterInputStream(raw) {
            @Override public int read() throws java.io.IOException { int n=in.read(); if(n>=0) count[0]++; return n; }
            @Override public int read(byte[] b,int off,int len) throws java.io.IOException { int n=in.read(b,off,len); if(n>0) count[0]+=n; return n; }
        };
        byte[] result; try(InputStream input=decoded(conn,counted,ctx,timeoutMs)) { result=readBounded(input,65536); }
        long length=conn.getContentLengthLong();
        return length>=0 && count[0]!=length ? null : result;
    }
    private static String localError(String message, long retry) {
        JSONObject error = new JSONObject(); JSONObject root = new JSONObject();
        try { error.put("type", "local_cooldown"); error.put("message", message); error.put("retry_after_ms", retry); root.put("error", error); }
        catch (Exception ignored) {} return root.toString();
    }
    static Stream openStream(String jwt, String device, String site, String model,
                             JSONObject body, String session, int timeoutMs, boolean dressBody) {
        return openStream(jwt, device, site, model, body, session, timeoutMs, dressBody, null);
    }
    static Stream openStream(String jwt, String device, String site, String model,
                             JSONObject request, String session, int timeoutMs, boolean dressBody, RequestContext ctx) {
        return openStream(jwt, device, site, model, request, session, timeoutMs, dressBody, ctx, null);
    }
    static Stream openStream(String jwt, String device, String site, String model,
                             JSONObject request, String session, int timeoutMs, boolean dressBody, RequestContext ctx, Observer observer) {
        return openStream(jwt, device, site, model, request, session, timeoutMs, dressBody, ctx, observer, "");
    }
    static Stream openStream(String jwt, String device, String site, String model,
                             JSONObject request, String session, int timeoutMs, boolean dressBody, RequestContext ctx, Observer observer, String stableAccount) {
        Stream out = new Stream(); out.observed=true; HttpURLConnection conn = null; OutboundLimiter.Ticket ticket = null;
        ZCodeGiftBusyPolicy.Slot slot = null;
        Runnable cleanup = null; Runnable slotCleanup = null; boolean owned = false;
        if (ctx == null) ctx = new RequestContext("zcode-gift", null, 300000);
        try {
            NetworkPolicy.Snapshot network = ctx != null && ctx.network != null ? ctx.network : NetworkPolicy.capture();
            String account = accountScope(jwt, device);
            String scope = network.toJson().toString();
            ZCodeRefusalPolicy.Hold hold = cooldown.before(POLICY_URL, account, scope);
            if (hold != null) {
                out.status=429; out.source="local_cooldown"; out.held=true; out.retryAfterMillis=hold.retryAfterMillis; out.classification=hold.kind;
                out.errorBody=localError(hold.message, hold.retryAfterMillis); out.error="Local cooldown"; return out;
            }
            long busyRemaining=giftBusyHold.remainingMillis();
            if (busyRemaining > 0) { localBusy(out,busyRemaining); return out; }
            if (ctx != null) ctx.check();
            long gateStarted=System.nanoTime();
            try { slot=giftGate.acquire(ZCodeGiftBusyPolicy.accountKey(jwt,device,stableAccount),ctx); }
            finally {if(observer!=null)observer.admission(gateStarted);}
            if (slot == null) { out.status=429; out.source="local_admission"; out.retryAfterMillis=5000; out.retryAfter="5"; out.error="Local gift concurrency limit"; return out; }
            final ZCodeGiftBusyPolicy.Slot ownedSlot=slot;
            slotCleanup=ownedSlot::close; if (ctx != null) ctx.onClose(slotCleanup);
            busyRemaining=giftBusyHold.remainingMillis();
            if (busyRemaining > 0) { localBusy(out,busyRemaining); return out; }
            long admissionStart=System.nanoTime(); OutboundLimiter.Result admission;
            try { admission = limiter.await(java.util.Collections.singleton("zcode-gift"),
                    ctx == null ? System.nanoTime()+30000000000L : ctx.deadlineNanos, ctx); }
            finally { if (observer != null) observer.admission(admissionStart); }
            if (admission.ticket == null) { out.status=429; out.source="local_admission"; out.retryAfterMillis=admission.retryAfterMillis; out.error="Local outbound limit"; return out; }
            ticket=admission.ticket; final OutboundLimiter.Ticket ownedTicket=ticket;
            cleanup = () -> { ownedTicket.close(); ownedSlot.close(); }; if (ctx != null) ctx.onClose(cleanup);
            // Recheck after queueing, before touching request or opening a connection.
            hold=cooldown.before(POLICY_URL,account,scope);
            if (hold != null) { out.status=429; out.source="local_cooldown"; out.held=true; out.retryAfterMillis=hold.retryAfterMillis;
                out.classification=hold.kind; out.errorBody=localError(hold.message,hold.retryAfterMillis); out.error="Local cooldown"; return out; }
            String sid=sessionOf(session,device);
            JSONObject body=dressBody ? dress(new JSONObject(request.toString()),site,model,device,sid) : ZCodeRequestPolicy.marked(new JSONObject(request.toString()),device,sid);
            byte[] bytes=body.toString().getBytes("UTF-8");
            // 这一问的名字取自 agent 原来那份 body（dress 会往 messages 前面插一条 system-reminder 的
            // user turn，按 dress 后的算就多算一句话），并与 x-session-id 同一个 sid。
            String queryId=queryOf(request.toString(),sid);
            String requestId=UUID.randomUUID().toString(), traceId=UUID.randomUUID().toString();
            boolean countedSustained=false;
            while (true) {
            busyRemaining=giftBusyHold.remainingMillis();
            if (busyRemaining > 0) { localBusy(out,busyRemaining); return out; }
            out.status=0;out.errorBody="";out.businessCode=null;out.retryAfter=null;out.retryAfterMillis=0;
            String base=fixtureBase; conn=network.openHttp(base == null ? POLICY_URL : base + "/v1/messages");
            if (ctx != null) { ctx.attach(conn); ctx.check(); }
            conn.setInstanceFollowRedirects(false); conn.setRequestMethod("POST"); conn.setDoOutput(true);
            int budget=ctx == null ? Integer.MAX_VALUE : (int)Math.max(1,Math.min(Integer.MAX_VALUE,ctx.remainingMillis()));
            conn.setConnectTimeout(Math.min(20000,budget)); conn.setReadTimeout(Math.max(1,Math.min(timeoutMs,budget)));
            conn.setFixedLengthStreamingMode(bytes.length);
            conn.setRequestProperty("content-type","application/json"); conn.setRequestProperty("Accept-Encoding","gzip");
            conn.setRequestProperty("Authorization","Bearer " + jwt); conn.setRequestProperty("anthropic-version","2023-06-01");
            conn.setRequestProperty("HTTP-Referer","https://zcode.z.ai"); conn.setRequestProperty("User-Agent",UA + " ai-sdk/anthropic/3.0.81");
            conn.setRequestProperty("X-ZCode-App-Version",APP_VERSION); conn.setRequestProperty("X-Title","Z Code@electron");
            conn.setRequestProperty("X-Release-Channel","production"); conn.setRequestProperty("X-Client-Language",locale());
            conn.setRequestProperty("X-Client-Timezone",timezone()); conn.setRequestProperty("X-ZCode-Agent","glm");
            conn.setRequestProperty("X-Platform",platform()); conn.setRequestProperty("X-Os-Category",osCategory());
            conn.setRequestProperty("X-Os-Version",osRelease()); conn.setRequestProperty("x-request-id",requestId);
            conn.setRequestProperty("x-zcode-session-type","main"); conn.setRequestProperty("x-zcode-trace-id",traceId);
            conn.setRequestProperty("x-zcode-query-source","main_turn"); conn.setRequestProperty("x-session-id",sid);
            if (!queryId.isEmpty()) conn.setRequestProperty("x-query-id",queryId);
            if (body.optBoolean("stream",false)) conn.setRequestProperty("Accept","text/event-stream");
            if (ctx != null) ctx.check(); ticket.markSent(); out.sent=true; out.sends++; out.source="upstream"; if (observer != null) observer.send(out.sends,System.currentTimeMillis());
            try (OutputStream os=conn.getOutputStream()) { if (ctx != null) ctx.check(); os.write(bytes); if (ctx != null) ctx.check(); }
            if (ctx != null) ctx.check();
            if (ctx != null) { ctx.check(); conn.setReadTimeout((int)Math.max(1,Math.min(timeoutMs,ctx.remainingMillis()))); }
            out.status=conn.getResponseCode();
            out.retryAfterMillis=0; out.retryAfter = ProviderRetryPolicy.safeRetryAfter(conn.getHeaderField("Retry-After"));
            if (out.retryAfter != null) out.retryAfterMillis = Long.parseLong(out.retryAfter) * 1000L;
            if (out.status < 200 || out.status >= 300) {
                byte[] data=readCompleteError(conn,ctx,timeoutMs);
                if(data==null) {out.status=-1;out.error="ZCode error response incomplete";out.transportKind="network";return out;}
                String complete=completeJson(data);
                out.errorBody=data == null ? "" : new String(data,"UTF-8");
                out.businessCode=complete == null ? null : safeBusinessCode(complete);
                if (complete != null && ZCodeGiftBusyPolicy.busy(out.status,complete) && safeBusinessCode(complete) != null && !hasUsage(new JSONObject(complete))) {
                    out.busyCode=ZCodeGiftBusyPolicy.busyCode(out.status,complete);
                    if (observer != null) observer.busy(out.busyCode,out.sends,out.retryWaitMillis,"upstream");
                    if (out.sends >= 2 && !countedSustained) { countedSustained=true; giftBusyHold.sustainedCap(); }
                    busyRemaining=giftBusyHold.remainingMillis();
                    if (busyRemaining > 0) { localBusy(out,busyRemaining); return out; }
                    long delay=Math.max(out.sends * 1000L,out.retryAfterMillis);
                    if (out.sends < 3 && (ctx == null || (ctx.mayContinueAfterRejection() && ctx.remainingMillis() > delay))) {
                        if (ctx != null) ctx.detach(conn); conn.disconnect(); conn=null;
                        long start=System.nanoTime();
                        if (ctx != null) ctx.awaitMillis(delay); else Thread.sleep(delay);
                        out.retryWaitMillis += (System.nanoTime()-start)/1000000L;
                        if(observer!=null)observer.busy(out.busyCode,out.sends,out.retryWaitMillis,"upstream");
                        if (ctx != null && !ctx.mayContinueAfterRejection()) return out;
                        continue;
                    }
                }
                out.classification=cooldown.observe(POLICY_URL,account,scope,out.status,data == null ? null : out.errorBody);
                out.refused=out.classification==ZCodeRefusalPolicy.Kind.ACCOUNT || out.classification==ZCodeRefusalPolicy.Kind.EDGE;
                out.edge=out.classification==ZCodeRefusalPolicy.Kind.EDGE; out.error="HTTP " + out.status; return out;
            }
            InputStream input=decoded(conn,conn.getInputStream(),ctx,timeoutMs);
            String contentType=conn.getContentType();
            if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("json")) {
                byte[] data; try (InputStream read=input) { data=readBounded(read,8*1024*1024); }
                if (data == null) { out.status=-1; out.error="Response exceeds local limit"; return out; }
                String text=new String(data,"UTF-8");
                if (data.length <= 65536 && ZCodeRefusalPolicy.classify(405,text)==ZCodeRefusalPolicy.Kind.ACCOUNT) {
                    out.status=405; out.errorBody=text; out.businessCode=safeBusinessCode(text); out.classification=cooldown.observe(POLICY_URL,account,scope,405,text);
                    out.refused=true; out.error="HTTP 405"; return out;
                }
                input=new java.io.ByteArrayInputStream(data);
            }
            final HttpURLConnection c=conn; final Runnable release=cleanup; final Runnable releaseSlot=slotCleanup; final RequestContext context=ctx;
            out.conn=conn; out.is=new java.io.FilterInputStream(input) {
                boolean closed;
                private void release() { if (closed) return; closed=true; try { super.close(); } catch (Exception ignored) {}
                    if (context != null) { context.detach(c); context.removeCleanup(release); context.removeCleanup(releaseSlot); } c.disconnect(); release.run(); }
                @Override public int read() throws java.io.IOException {
                    try { checkRead(c,context,timeoutMs); int n=in.read(); if (context != null) context.check(); if (n<0) release(); return n; }
                    catch (java.io.IOException | RuntimeException e) { release(); throw e; }
                }
                @Override public int read(byte[] b,int off,int len) throws java.io.IOException {
                    try { checkRead(c,context,timeoutMs); int n=in.read(b,off,len); if (context != null) context.check(); if (n<0) release(); return n; }
                    catch (java.io.IOException | RuntimeException e) { release(); throw e; }
                }
                @Override public void close() { release(); }
            }; owned=true; return out;
            }
        } catch (Exception e) { out.status=-1;out.businessCode=null;out.errorBody="";out.error="ZCode transport failed";out.transportKind=FailureLayers.transportName(e);return out; }
        finally { if (!owned) { if (ctx != null && conn != null) ctx.detach(conn); if (conn != null) conn.disconnect();
            if (ctx != null && cleanup != null) ctx.removeCleanup(cleanup); if (ctx != null && slotCleanup != null) ctx.removeCleanup(slotCleanup); if (ticket != null) ticket.close(); if (slot != null) slot.close(); out.conn=null; } }
    }

    /** 把 Anthropic 的 message 响应压成纯文本（给网关转 OpenAI 用） */
    static String textOf(JSONObject msg) {
        if (msg == null) return "";
        JSONArray content = msg.optJSONArray("content");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; content != null && i < content.length(); i++) {
            JSONObject c = content.optJSONObject(i);
            if (c == null) continue;
            if ("text".equals(c.optString("type"))) sb.append(c.optString("text", ""));
        }
        return sb.toString();
    }

    /** 把 Anthropic 的 thinking 块压出来 */
    static String thinkingOf(JSONObject msg) {
        if (msg == null) return "";
        JSONArray content = msg.optJSONArray("content");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; content != null && i < content.length(); i++) {
            JSONObject c = content.optJSONObject(i);
            if (c == null) continue;
            if ("thinking".equals(c.optString("type"))) sb.append(c.optString("thinking", ""));
        }
        return sb.toString();
    }
}
