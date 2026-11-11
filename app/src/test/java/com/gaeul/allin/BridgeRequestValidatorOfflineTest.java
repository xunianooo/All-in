package com.gaeul.allin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 原生桥请求行的离线安全与兼容性验证。
 *
 * <p>不启动 Gradle、不联网、不读写真实配置/账号/网关数据：
 * <ul>
 *   <li>直接调用 {@link BridgeRequestValidator}，覆盖 CRLF、控制字符、绝对 URI、长度与 method 白名单；</li>
 *   <li>扫描仓库里的面板资源 dashboard.html，确认真实面板使用的字面路径全部能通过校验；</li>
 *   <li>静态检查 MainActivity 的两个请求入口确实在拼请求行前调用了校验器。</li>
 * </ul>
 */
public final class BridgeRequestValidatorOfflineTest {

    static int checks = 0;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    static void checkEquals(String expected, String actual, String what) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError(what + "（期望 " + expected + "，实际 " + actual + "）");
    }

    static void accepts(String method, String path, String expectedMethod, String expectedPath) {
        BridgeRequestValidator.Request request = BridgeRequestValidator.validate(method, path);
        checkEquals(expectedMethod, request.method, "method 归一化: " + method);
        checkEquals(expectedPath, request.pathAndQuery, "path 归一化: " + path);
        check(request.pathAndQuery.startsWith("/"), "归一化 path 必须以 / 开头: " + request.pathAndQuery);
        String fields = request.method + request.pathAndQuery;
        for (int i = 0; i < fields.length(); i++) {
            char c = fields.charAt(i);
            check(c > 0x20 && c < 0x7f,
                    "归一化请求行只能含可见 ASCII: U+" + Integer.toHexString(c));
        }
    }

    static void rejects(String method, String path, String messageFragment) {
        try {
            BridgeRequestValidator.Request request = BridgeRequestValidator.validate(method, path);
            throw new AssertionError("本应拒绝却通过: method=" + describe(method)
                    + " path=" + describe(path) + " -> " + request);
        } catch (IllegalArgumentException expected) {
            String message = expected.getMessage() == null ? "" : expected.getMessage();
            check(message.contains(messageFragment),
                    "拒绝原因应包含「" + messageFragment + "」，实际: " + message);
        }
    }

    static String describe(String value) {
        if (value == null) return "<null>";
        return value.length() > 80 ? value.substring(0, 80) + "…" : value;
    }

    static void methods() {
        accepts(null, "/", "GET", "/");
        accepts("", "/", "GET", "/");
        accepts("get", "/api/state", "GET", "/api/state");
        accepts("  post  ", "/api/state", "POST", "/api/state");
        accepts("PUT", "/api/state", "PUT", "/api/state");
        accepts("PATCH", "/api/state", "PATCH", "/api/state");
        accepts("DELETE", "/api/state", "DELETE", "/api/state");
        accepts("HEAD", "/api/state", "HEAD", "/api/state");
        accepts("OPTIONS", "/api/state", "OPTIONS", "/api/state");

        rejects("TRACE", "/api/state", "白名单");
        rejects("CONNECT", "/api/state", "白名单");
        rejects("PROPFIND", "/api/state", "白名单");
        rejects("GET /api/state HTTP/1.1", "/api/state", "长度");
        rejects("G ET", "/api/state", "非法字符");
        rejects("GET\r\nX", "/api/state", "控制字符");
        rejects("\r\nGET", "/api/state", "控制字符");
        rejects("GET\r", "/api/state", "控制字符");
        rejects("G\tET", "/api/state", "控制字符");
        rejects("GE\u0000T", "/api/state", "控制字符");
        rejects("ABCDEFGHIJKLMNOPQ", "/api/state", "长度");
    }

    static void paths() {
        accepts("GET", null, "GET", "/");
        accepts("GET", "", "GET", "/");
        accepts("GET", "/", "GET", "/");
        accepts("GET", "/api/usage/csv?days=7", "GET", "/api/usage/csv?days=7");
        accepts("POST", "/api/auth/workbuddy/poll-login?state=abc-123", "POST",
                "/api/auth/workbuddy/poll-login?state=abc-123");
        accepts("POST", "/v1/chat/completions", "POST", "/v1/chat/completions");
        accepts("GET", "api/providers", "GET", "/api/providers");
        accepts("GET", "api/providers?q=a:b", "GET", "/api/providers?q=a:b");
        accepts("GET", "api/providers?u=http://example.test/x", "GET",
                "/api/providers?u=http://example.test/x");
        accepts("GET", "/api/%E4%B8%AD%E6%96%87", "GET", "/api/%E4%B8%AD%E6%96%87");

        rejects("GET", "/api/a b", "空白");
        rejects("GET", "/api/a\tb", "控制字符");
        rejects("GET", "/api/a\u007fb", "控制字符");
        rejects("GET", "/api/a\r\nX-All-In-Management: evil", "CRLF");
        rejects("GET", "/api/a\nX-All-In-Management: evil", "CRLF");
        rejects("GET", "/api/a\rX", "CRLF");

        rejects("GET", "http://127.0.0.1:3425/api/state", "绝对 URI");
        rejects("GET", "https://evil.example/api/state", "绝对 URI");
        rejects("GET", "HTTP://evil.example/api/state", "绝对 URI");
        rejects("GET", "http:evil", "scheme");
        rejects("GET", "//evil.example/api/state", "绝对 URI");
        rejects("GET", "\\\\evil\\api", "反斜杠");
        rejects("GET", "/api/a\\b", "反斜杠");
        rejects("GET", "/api/a#fragment", "片段");
        rejects("GET", "/api/%0d%0aX-All-In-Management:evil", "编码后的 CRLF");
        rejects("GET", "/api/%0A", "编码后的 CRLF");
        rejects("GET", "/api/%00", "编码后的 CRLF");
        // 查询串是数据不是请求行：编码后的换行允许通过，避免误伤 baseUrl 等用户输入。
        accepts("GET", "/api/auth/workbuddy/poll-login?baseUrl=https%3A%2F%2Fx%2F%0A",
                "GET", "/api/auth/workbuddy/poll-login?baseUrl=https%3A%2F%2Fx%2F%0A");
        rejects("GET", "/api/\u4e2d\u6587", "非 ASCII");

        String maxPath = "/" + repeat('a', BridgeRequestValidator.MAX_PATH_LENGTH - 1);
        accepts("GET", maxPath, "GET", maxPath);
        rejects("GET", maxPath + "a", "长度");
    }

    static String repeat(char c, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) builder.append(c);
        return builder.toString();
    }

    static String readFile(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    static File firstExisting(String... paths) {
        for (String path : paths) {
            File file = new File(path);
            if (file.isFile()) return file;
        }
        return null;
    }

    static void panelPathsStillValidate() throws IOException {
        File panel = firstExisting("app/src/main/assets/dashboard.html",
                "src/main/assets/dashboard.html");
        check(panel != null, "找不到 dashboard.html，无法做真实面板兼容性检查");
        String html = readFile(panel);

        // 面板 patchFetch 传给桥的是 new URL(...).pathname + search，永远是字面量或模板字符串；
        // 这里抓取所有以 /api/ 或 /v1/ 开头的字面量，逐条过校验器。
        Pattern pattern = Pattern.compile("[\"'`](/(?:api|v1)/[^\"'`\\s]*)");
        Matcher matcher = pattern.matcher(html);
        List<String> paths = new ArrayList<>();
        while (matcher.find()) paths.add(matcher.group(1));
        check(paths.size() >= 20, "面板路径样本过少: " + paths.size());

        for (String path : paths) {
            try {
                BridgeRequestValidator.validate("POST", path);
            } catch (IllegalArgumentException rejected) {
                throw new AssertionError("真实面板路径被误杀: " + path
                        + "（" + rejected.getMessage() + "）");
            }
        }
    }

    static void mainActivityWiring() throws IOException {
        File main = firstExisting("app/src/main/java/com/gaeul/allin/MainActivity.java",
                "src/main/java/com/gaeul/allin/MainActivity.java");
        check(main != null, "找不到 MainActivity.java，无法做接线检查");
        String source = readFile(main);

        check(count(source, "BridgeRequestValidator.validate(method, pathAndQuery)") >= 2,
                "doRequest 与 startStream 都必须调用请求行校验器");
        check(source.contains("String m = request.method;"), "doRequest 必须使用归一化后的 method");
        check(source.contains("String path = request.pathAndQuery;"),
                "doRequest 必须使用归一化后的 pathAndQuery");
        check(source.contains("LocalBridge.stream(request.method, request.pathAndQuery"),
                "startStream 必须使用归一化后的 method/pathAndQuery");
        check(source.contains("bridgeRejected(rejected.getMessage())"),
                "doRequest 被拒后必须直接返回 400，而不是继续建 socket");
        check(source.contains("window.__allInStreamEnd") && source.contains(",400,"),
                "startStream 被拒后必须把 400 回给面板");
    }

    static int count(String text, String needle) {
        int total = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            total++;
            from += needle.length();
        }
        return total;
    }

    public static void main(String[] args) throws Exception {
        methods();
        paths();
        panelPathsStillValidate();
        mainActivityWiring();
        System.out.println("BridgeRequestValidatorOfflineTest OK, checks=" + checks);
    }
}
