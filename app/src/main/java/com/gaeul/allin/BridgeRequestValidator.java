package com.gaeul.allin;

import java.util.Locale;

/**
 * 原生桥的 HTTP 请求行校验（纯 Java，不依赖 android.*，可直接用 javac/java 隔离验证）。
 *
 * <p>面板里的 {@code AllInNative.request/requestAsync/startStream} 会把 JS 传入的 method 与
 * pathAndQuery 直接拼进 raw socket 的请求行：{@code method + ' ' + path + " HTTP/1.1"}。
 * 只要面板出现一处 XSS（账号昵称、模型名、用户自己填的 baseUrl 都曾被拼进 innerHTML），
 * 注入脚本就能构造 {@code "/api/x HTTP/1.1\r\nX-...: ..."} 或绝对 URI 形式的 request-target，
 * 污染发往本机网关的请求行/头，甚至借绝对 URI 绕过按路径判断的网关策略。
 *
 * <p>这里只做请求行必需的最小校验，保持与真实面板兼容：
 * <ul>
 *   <li>method：缺省/空串按 GET（沿用旧行为）；大小写不敏感；只允许标准方法白名单。</li>
 *   <li>pathAndQuery：允许 null/空串（按 "/" 处理）与旧行为里的相对路径（补前导 "/"）；
 *       必须是可见 ASCII、无空白/控制字符/反斜杠/片段标识符；禁止绝对 URI 与 "//" 协议相对形式；
 *       路径部分额外拒绝百分号编码的 CR/LF/NUL（查询串是数据，允许编码后的换行，避免误伤
 *       baseUrl 之类的用户输入）。</li>
 *   <li>长度：method 上限 {@link #MAX_METHOD_LENGTH}，pathAndQuery 上限 {@link #MAX_PATH_LENGTH}。</li>
 * </ul>
 *
 * <p>合法的面板调用都来自 {@code new URL(...)} 的 {@code pathname + search}，天然是
 * 以 "/" 开头的可见 ASCII，长度只有几十字节，因此上述限制不会误伤真实面板。
 */
public final class BridgeRequestValidator {

    /** HTTP method token 的合理上限；标准方法最长 7（OPTIONS），留余量。 */
    public static final int MAX_METHOD_LENGTH = 16;
    /** 请求行 target 上限；真实面板最长几十字节，8 KiB 足够且能挡住异常长串。 */
    public static final int MAX_PATH_LENGTH = 8192;

    private static final String[] ALLOWED_METHODS = {
            "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"
    };

    private BridgeRequestValidator() {
    }

    /** 校验并归一化后的请求行目标。 */
    public static final class Request {
        public final String method;
        public final String pathAndQuery;

        private Request(String method, String pathAndQuery) {
            this.method = method;
            this.pathAndQuery = pathAndQuery;
        }

        @Override
        public String toString() {
            return method + " " + pathAndQuery;
        }
    }

    /**
     * 校验 method 与 pathAndQuery，返回可直接拼请求行的归一化结果。
     *
     * @throws IllegalArgumentException 任一字段非法；调用方应直接拒绝请求，不要退回旧行为
     */
    public static Request validate(String method, String pathAndQuery) {
        String normalizedMethod = normalizeMethod(method);
        String normalizedPath = normalizePath(pathAndQuery);
        return new Request(normalizedMethod, normalizedPath);
    }

    static String normalizeMethod(String raw) {
        if (raw == null) return "GET";
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                // 先于 trim 检查：避免 "\r\nGET" 被 trim 成看似合法的 GET 而掩盖注入尝试。
                throw new IllegalArgumentException("method 含控制字符");
            }
        }
        String method = raw.trim().toUpperCase(Locale.ROOT);
        if (method.isEmpty()) return "GET";
        if (method.length() > MAX_METHOD_LENGTH) {
            throw new IllegalArgumentException(
                    "method 长度异常（上限 " + MAX_METHOD_LENGTH + " 字符）");
        }
        for (int i = 0; i < method.length(); i++) {
            char c = method.charAt(i);
            if (c < 'A' || c > 'Z') {
                throw new IllegalArgumentException("method 含非法字符（只允许 A-Z）");
            }
        }
        for (String allowed : ALLOWED_METHODS) {
            if (allowed.equals(method)) return method;
        }
        throw new IllegalArgumentException("method 不在白名单");
    }

    static String normalizePath(String raw) {
        if (raw == null || raw.isEmpty()) return "/";
        if (raw.length() > MAX_PATH_LENGTH) {
            throw new IllegalArgumentException(
                    "path 长度异常（上限 " + MAX_PATH_LENGTH + " 字符）");
        }
        if (raw.startsWith("//")) {
            throw new IllegalArgumentException("path 不允许绝对 URI / 协议相对形式");
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\r' || c == '\n') {
                throw new IllegalArgumentException("path 含 CRLF");
            }
            if (c < 0x20 || c == 0x7f) {
                throw new IllegalArgumentException("path 含控制字符");
            }
            if (c == ' ') {
                throw new IllegalArgumentException("path 含空白字符");
            }
            if (c > 0x7e) {
                throw new IllegalArgumentException("path 含非 ASCII 字符");
            }
        }

        String path = raw;
        if (path.charAt(0) != '/') {
            // 兼容旧行为的相对路径补 "/"；但 "scheme:..." 这类绝对 URI 直接拒绝。
            int query = path.indexOf('?');
            String beforeQuery = query >= 0 ? path.substring(0, query) : path;
            if (beforeQuery.indexOf(':') >= 0) {
                throw new IllegalArgumentException("path 不允许绝对 URI / scheme");
            }
            path = "/" + path;
        }
        int queryStart = path.indexOf('?');
        String pathOnly = queryStart >= 0 ? path.substring(0, queryStart) : path;
        if (pathOnly.indexOf("://") >= 0) {
            throw new IllegalArgumentException("path 不允许绝对 URI");
        }
        if (pathOnly.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("path 不允许反斜杠");
        }
        if (path.indexOf('#') >= 0) {
            throw new IllegalArgumentException("path 不允许片段标识符 #");
        }
        if (hasEncodedCrLfOrNul(pathOnly)) {
            throw new IllegalArgumentException("path 含编码后的 CRLF/NUL");
        }
        return path;
    }

    /** 拒绝 %0d/%0a/%00，避免下游解码后重新变成行终止符。 */
    private static boolean hasEncodedCrLfOrNul(String path) {
        for (int i = 0; i + 2 < path.length(); i++) {
            if (path.charAt(i) != '%') continue;
            int hi = hexValue(path.charAt(i + 1));
            int lo = hexValue(path.charAt(i + 2));
            if (hi < 0 || lo < 0) continue;
            int value = (hi << 4) | lo;
            if (value == 0x0d || value == 0x0a || value == 0x00) return true;
        }
        return false;
    }

    private static int hexValue(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }
}
