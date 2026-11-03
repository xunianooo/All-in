package com.gaeul.allin;

import java.security.MessageDigest;
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

        public static Config fromJson(org.json.JSONObject o) {
            Config c = new Config();
            if (o == null) return c;
            c.enabled = o.optBoolean("enabled", true);
            c.maskApiKeys = o.optBoolean("maskApiKeys", true);
            c.maskEmails = o.optBoolean("maskEmails", true);
            c.maskPhones = o.optBoolean("maskPhones", true);
            c.maskIds = o.optBoolean("maskIds", false);
            c.maskBankCards = o.optBoolean("maskBankCards", false);
            c.maskLandlines = o.optBoolean("maskLandlines", false);
            org.json.JSONArray arr = o.optJSONArray("customPatterns");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String v = arr.optString(i, "").trim();
                    if (!v.isEmpty()) c.customPatterns.add(v);
                }
            }
            return c;
        }

        public org.json.JSONObject toJson() {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("enabled", enabled);
            o.put("maskApiKeys", maskApiKeys);
            o.put("maskEmails", maskEmails);
            o.put("maskPhones", maskPhones);
            o.put("maskIds", maskIds);
            o.put("maskBankCards", maskBankCards);
            o.put("maskLandlines", maskLandlines);
            org.json.JSONArray arr = new org.json.JSONArray();
            for (String v : customPatterns) arr.put(v);
            o.put("customPatterns", arr);
            return o;
        }
    }

    public static class Session {
        public Map<String, String> placeholderToReal = new HashMap<>();
        public Map<String, String> realToPlaceholder = new HashMap<>();
        public int interceptedCount = 0;
    }

    public static String getOrCreatePlaceholder(Session session, String realValue, String type) {
        if (session.realToPlaceholder.containsKey(realValue)) {
            return session.realToPlaceholder.get(realValue);
        }
        String hash = "hash";
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(realValue.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            hash = sb.toString();
        } catch (Exception ignored) {}

        String placeholder = "{{MASK_" + type + "_" + hash + "}}";
        session.placeholderToReal.put(placeholder, realValue);
        session.realToPlaceholder.put(realValue, placeholder);
        session.interceptedCount++;
        return placeholder;
    }

    /** 兼容旧调用：默认全开 */
    public static String maskText(String input, Session session) {
        return maskText(input, session, new Config());
    }

    /** 按配置脱敏；关闭时原样返回，且不产生占位符 */
    public static String maskText(String input, Session session, Config cfg) {
        if (input == null || input.isEmpty()) return input;
        if (cfg == null) cfg = new Config();
        if (!cfg.enabled) return input;

        String result = input;

        // 1. API Keys
        if (cfg.maskApiKeys) {
        Matcher mKey = API_KEY_PATTERN.matcher(result);
        StringBuffer sbKey = new StringBuffer();
        while (mKey.find()) {
            String p = getOrCreatePlaceholder(session, mKey.group(1), "API_KEY");
            mKey.appendReplacement(sbKey, Matcher.quoteReplacement(p));
        }
        mKey.appendTail(sbKey);
        result = sbKey.toString();
        }

        // 2. Emails
        if (cfg.maskEmails) {
        Matcher mEmail = EMAIL_PATTERN.matcher(result);
        StringBuffer sbEmail = new StringBuffer();
        while (mEmail.find()) {
            String p = getOrCreatePlaceholder(session, mEmail.group(0), "EMAIL");
            mEmail.appendReplacement(sbEmail, Matcher.quoteReplacement(p));
        }
        mEmail.appendTail(sbEmail);
        result = sbEmail.toString();
        }

        // 3. Phones
        if (cfg.maskPhones) {
        Matcher mPhone = PHONE_PATTERN.matcher(result);
        StringBuffer sbPhone = new StringBuffer();
        while (mPhone.find()) {
            String p = getOrCreatePlaceholder(session, mPhone.group(0), "PHONE");
            mPhone.appendReplacement(sbPhone, Matcher.quoteReplacement(p));
        }
        mPhone.appendTail(sbPhone);
        result = sbPhone.toString();
        }

        // 4. 身份证
        if (cfg.maskIds) {
            result = maskByPattern(result, session, ID_PATTERN, "ID");
        }

        // 5. 银行卡 / 长数字
        if (cfg.maskBankCards) {
            result = maskByPattern(result, session, BANK_PATTERN, "BANKCARD");
        }

        // 6. 座机
        if (cfg.maskLandlines) {
            result = maskByPattern(result, session, LANDLINE_PATTERN, "TEL");
        }

        // 7. 用户自定义正则（一行一条）；单条写错只跳过该条，不影响其它
        for (String raw : cfg.customPatterns) {
            try {
                Pattern cp = Pattern.compile(raw);
                result = maskByPattern(result, session, cp, "CUSTOM");
            } catch (Exception ignored) {
            }
        }

        return result;
    }

    /** 用给定正则做一次替换，统一走占位符 */
    private static String maskByPattern(String input, Session session, Pattern pattern, String type) {
        Matcher m = pattern.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String p = getOrCreatePlaceholder(session, m.group(), type);
            m.appendReplacement(sb, Matcher.quoteReplacement(p));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static String unmaskText(String input, Session session) {
        if (input == null || session == null || session.placeholderToReal.isEmpty()) return input;
        String res = input;
        for (Map.Entry<String, String> entry : session.placeholderToReal.entrySet()) {
            res = res.replace(entry.getKey(), entry.getValue());
        }
        return res;
    }
}
