package com.gaeul.allin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.*;
import java.util.regex.*;

public class PrivacyMasker {
    // 注意：这里刻意不用 \b。Java 的 \b 按 Unicode 的 isLetterOrDigit 判边界，
    // 汉字也算 word 字符，导致"我的手机号是13812345678"这类中文紧邻敏感信息时
    // \b 判定失败、整条规则静默失效。改用只针对 ASCII 的前后否定断言。
    private static final Pattern API_KEY_PATTERN = Pattern.compile(
        "(?<![A-Za-z0-9])(sk-[a-zA-Z0-9]{20,}|ghp_[a-zA-Z0-9]{36}|github_pat_[a-zA-Z0-9_]{30,}|xox[baprs]-[a-zA-Z0-9]{10,}|AIza[0-9A-Za-z-_]{35}|Bearer\\s+[a-zA-Z0-9\\-_\\.]{24,})(?![A-Za-z0-9])"
    );
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,7}(?![A-Za-z0-9-])"
    );
    private static final Pattern PHONE_PATTERN = Pattern.compile(
        "(?<!\\d)(?:\\+?\\d{1,3}[- ]?)?1[3-9]\\d{9}(?!\\d)"
    );
    /** 中国大陆身份证：18 位，出生日期做粗校验，末位可为 X */
    private static final Pattern ID_PATTERN = Pattern.compile(
        "(?<!\\d)[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx](?![\\dXx])"
    );
    /** 银行卡/长数字账号：16-19 位连续数字 */
    private static final Pattern BANK_PATTERN = Pattern.compile(
        "(?<!\\d)\\d{16,19}(?!\\d)"
    );
    /** 座机：区号 0 开头 3-4 位 + 7-8 位号码，可带连字符 */
    private static final Pattern LANDLINE_PATTERN = Pattern.compile(
        "(?<!\\d)0\\d{2,3}[- ]?\\d{7,8}(?!\\d)"
    );

    /**
     * 脱敏开关。以前这里完全没有配置入口，maskText 无条件执行，
     * 导致后台的 privacy 配置项全是摆设、用户也看不出到底开没开。
     */
    public static class Config {
        public boolean enabled = true;
        public boolean maskApiKeys = true;
        public boolean maskEmails = true;
        public boolean maskPhones = true;
        /** 身份证（默认关闭：格式固定、误伤率低，但很多人不需要） */
        public boolean maskIds = false;
        /** 银行卡 / 16-19 位长数字（默认关闭：容易误伤时间戳、订单号） */
        public boolean maskBankCards = false;
        /** 座机号码 */
        public boolean maskLandlines = false;
        /** 用户自定义正则，一行一条 */
        public java.util.List<String> customPatterns = new java.util.ArrayList<String>();

        public static Config fromJson(com.gaeul.allin.json.JSONObject o) {
            Config c = new Config();
            if (o == null) return c;
            c.enabled = o.optBoolean("enabled", true);
            c.maskApiKeys = o.optBoolean("maskApiKeys", true);
            c.maskEmails = o.optBoolean("maskEmails", true);
            c.maskPhones = o.optBoolean("maskPhones", true);
            c.maskIds = o.optBoolean("maskIds", false);
            c.maskBankCards = o.optBoolean("maskBankCards", false);
            c.maskLandlines = o.optBoolean("maskLandlines", false);
            com.gaeul.allin.json.JSONArray arr = o.optJSONArray("customPatterns");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String v = arr.optString(i, "").trim();
                    if (!v.isEmpty()) c.customPatterns.add(v);
                }
            }
            return c;
        }

        public com.gaeul.allin.json.JSONObject toJson() {
            com.gaeul.allin.json.JSONObject o = new com.gaeul.allin.json.JSONObject();
            o.put("enabled", enabled);
            o.put("maskApiKeys", maskApiKeys);
            o.put("maskEmails", maskEmails);
            o.put("maskPhones", maskPhones);
            o.put("maskIds", maskIds);
            o.put("maskBankCards", maskBankCards);
            o.put("maskLandlines", maskLandlines);
            com.gaeul.allin.json.JSONArray arr = new com.gaeul.allin.json.JSONArray();
            for (String v : customPatterns) arr.put(v);
            o.put("customPatterns", arr);
            return o;
        }
    }

    public static final class PrivacyException extends IllegalStateException {
        public PrivacyException() { super("Privacy masking unavailable"); }
    }
    interface KeyProvider { byte[] key(); }
    interface DigestProvider { byte[] digest(byte[] key, String type, String value) throws Exception; }
    private static volatile KeyProvider provider;
    private static final DigestProvider HMAC = (key, type, value) -> {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        mac.update(type.getBytes(StandardCharsets.UTF_8));
        mac.update((byte) 0);
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    };
    /** Configure an installation scope; disk access and key creation are lazy. */
    private static final Map<String, KeyProvider> INSTALLATIONS = new HashMap<String, KeyProvider>();
    public static synchronized void initialize(File privateDir) {
        if (privateDir == null) throw new IllegalArgumentException("Invalid privacy key configuration");
        try {
            String path = privateDir.getCanonicalPath();
            KeyProvider selected = INSTALLATIONS.get(path);
            if (selected == null) {
                selected = new PrivacyKeyStore(privateDir);
                INSTALLATIONS.put(path, selected);
            }
            provider = selected;
        } catch (java.io.IOException e) { throw new PrivacyException(); }
    }
    static void setProviderForTest(KeyProvider p) { provider = p; }

    public static class Session {
        public Map<String, String> placeholderToReal = Collections.synchronizedMap(new HashMap<String, String>());
        // Composite type + NUL + value keys; retained publicly for A1's working-session copy.
        public Map<String, String> realToPlaceholder = Collections.synchronizedMap(new HashMap<String, String>());
        public int interceptedCount = 0;
        private KeyProvider keyProvider;
        private DigestProvider digestProvider;
        private final Set<String> reserved = new HashSet<String>();
        private final Map<String, String> fullDigests = new HashMap<String, String>();
        public Session() { this(provider, HMAC); }
        Session(KeyProvider keyProvider, DigestProvider digestProvider) {
            this.keyProvider = keyProvider; this.digestProvider = digestProvider;
        }
        synchronized Session fork() {
            Session copy = new Session(keyProvider, digestProvider);
            copy.placeholderToReal.putAll(placeholderToReal);
            copy.realToPlaceholder.putAll(realToPlaceholder);
            copy.reserved.addAll(reserved);
            copy.fullDigests.putAll(fullDigests);
            copy.interceptedCount = interceptedCount;
            return copy;
        }
        void replaceFrom(Session source) {
            if (source == null) throw new IllegalArgumentException("Missing privacy session");
            if (source == this) return;
            Session copy = source.fork();
            synchronized (this) {
                keyProvider = copy.keyProvider; digestProvider = copy.digestProvider;
                placeholderToReal.clear(); placeholderToReal.putAll(copy.placeholderToReal);
                realToPlaceholder.clear(); realToPlaceholder.putAll(copy.realToPlaceholder);
                reserved.clear(); reserved.addAll(copy.reserved);
                fullDigests.clear(); fullDigests.putAll(copy.fullDigests);
                interceptedCount = copy.interceptedCount;
            }
        }
    }
    private static final Pattern TOKEN = Pattern.compile("\\{\\{MASK_[A-Za-z0-9_]+\\}\\}");
    private static final int MAX_ENTRIES = 16384;
    private static final int MAX_VALUE_CHARS = 1048576;

    public static String getOrCreatePlaceholder(Session session, String realValue, String type) {
        synchronized (session) {
            String composite = type + "\0" + realValue;
            String existing = session.realToPlaceholder.get(composite);
            if (existing != null) return existing;
            if (session.placeholderToReal.size() >= MAX_ENTRIES || realValue.length() > MAX_VALUE_CHARS)
                throw new PrivacyException();
            long size = realValue.length();
            for (String v : session.placeholderToReal.values()) size += v.length();
            if (size > MAX_VALUE_CHARS || session.keyProvider == null) throw new PrivacyException();
            final byte[] digest;
            try {
                byte[] key = session.keyProvider.key();
                if (key == null || key.length != 32) throw new PrivacyException();
                try { digest = session.digestProvider.digest(key, type, realValue); }
                finally { java.util.Arrays.fill(key, (byte) 0); }
                if (digest == null || digest.length != 32) throw new PrivacyException();
            } catch (Exception e) { throw new PrivacyException(); }
            StringBuilder hex = new StringBuilder(64);
            for (byte v : digest) hex.append(Character.forDigit((v >>> 4) & 15, 16)).append(Character.forDigit(v & 15, 16));
            String full = type + "\0" + hex.toString();
            String prior = session.fullDigests.get(full);
            if (prior != null && !prior.equals(composite)) throw new PrivacyException();
            for (int length = 24; length <= 64; length += 2) {
                String placeholder = "{{MASK_" + type + "_" + hex.substring(0, length) + "}}";
                if (!session.placeholderToReal.containsKey(placeholder) && !session.reserved.contains(placeholder)) {
                    session.fullDigests.put(full, composite);
                    session.placeholderToReal.put(placeholder, realValue);
                    session.realToPlaceholder.put(composite, placeholder);
                    session.interceptedCount++;
                    return placeholder;
                }
            }
            throw new PrivacyException();
        }
    }

    /** 兼容旧调用：默认全开 */
    public static String maskText(String input, Session session) {
        return maskText(input, session, new Config());
    }

    private static List<Pattern> patterns(Config cfg) {
        List<Pattern> result = new ArrayList<Pattern>();
        if (cfg.maskApiKeys) result.add(API_KEY_PATTERN);
        if (cfg.maskEmails) result.add(EMAIL_PATTERN);
        if (cfg.maskPhones) result.add(PHONE_PATTERN);
        if (cfg.maskIds) result.add(ID_PATTERN);
        if (cfg.maskBankCards) result.add(BANK_PATTERN);
        if (cfg.maskLandlines) result.add(LANDLINE_PATTERN);
        for (String raw : cfg.customPatterns) {
            if (raw.isEmpty()) continue;
            try { result.add(Pattern.compile(raw)); }
            catch (PatternSyntaxException invalid) { throw new PrivacyException(); }
        }
        return result;
    }
    private static String typeFor(Pattern p) {
        if (p == API_KEY_PATTERN) return "API_KEY";
        if (p == EMAIL_PATTERN) return "EMAIL";
        if (p == PHONE_PATTERN) return "PHONE";
        if (p == ID_PATTERN) return "ID";
        if (p == BANK_PATTERN) return "BANKCARD";
        if (p == LANDLINE_PATTERN) return "TEL";
        return "CUSTOM";
    }
    private static boolean overlapsToken(int start, int end, List<int[]> tokens) {
        for (int[] range : tokens) {
            if (range[0] >= end) break;
            if (range[1] > start) return true;
        }
        return false;
    }
    private static List<int[]> tokenRanges(String input) {
        List<int[]> ranges = new ArrayList<int[]>();
        Matcher m = TOKEN.matcher(input);
        while (m.find()) ranges.add(new int[] { m.start(), m.end() });
        return ranges;
    }
    /** Pure detection; never initializes or reads a privacy key or changes a session. */
    public static boolean checkMatch(String input, Config cfg) {
        if (input == null || input.isEmpty()) return false;
        if (cfg == null) cfg = new Config();
        if (!cfg.enabled) return false;
        List<int[]> tokens = tokenRanges(input);
        for (Pattern p : patterns(cfg)) {
            Matcher m = p.matcher(input);
            while (m.find()) {
                if (m.start() != m.end() && !overlapsToken(m.start(), m.end(), tokens)) return true;
            }
        }
        return false;
    }
    /** Masks text while preserving literal or previously generated placeholder-shaped tokens. */
    public static String maskText(String input, Session session, Config cfg) {
        if (input == null || input.isEmpty()) return input;
        if (cfg == null) cfg = new Config();
        if (!cfg.enabled) return input;
        synchronized (session) {
            Matcher tokens = TOKEN.matcher(input);
            while (tokens.find()) {
                String token = tokens.group();
                // A literal indistinguishable from a live mapping cannot safely be restored.
                if (session.placeholderToReal.containsKey(token)) throw new PrivacyException();
                if (session.reserved.size() >= MAX_ENTRIES && !session.reserved.contains(token)) throw new PrivacyException();
                session.reserved.add(token);
            }
            String result = input;
            for (Pattern p : patterns(cfg)) result = maskByPattern(result, session, p, typeFor(p));
            return result;
        }
    }
    private static String maskByPattern(String input, Session session, Pattern pattern, String type) {
        Matcher m = pattern.matcher(input);
        List<int[]> tokens = tokenRanges(input);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            if (m.start() == m.end() || overlapsToken(m.start(), m.end(), tokens)) continue;
            String p = getOrCreatePlaceholder(session, m.group(), type);
            m.appendReplacement(sb, Matcher.quoteReplacement(p));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static String unmaskText(String input, Session session) {
        if (input == null || session == null) return input;
        synchronized (session) {
            StringBuilder out = new StringBuilder();
            int pos = 0;
            List<String> keys = new ArrayList<String>(session.placeholderToReal.keySet());
            keys.remove(null);
            keys.remove("");
            keys.sort((a, b) -> Integer.compare(b.length(), a.length()));
            while (pos < input.length()) {
                String found = null;
                for (String key : keys) {
                    if (input.startsWith(key, pos)) { found = key; break; }
                }
                if (found == null) out.append(input.charAt(pos++));
                else { out.append(session.placeholderToReal.get(found)); pos += found.length(); }
            }
            return out.toString();
        }
    }
}
