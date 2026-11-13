package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import com.gaeul.allin.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import sun.misc.Unsafe;

/**
 * 监听端点：配置 host/port、真实 socket、状态回显三者必须一致，且任何一侧都不能被悄悄改变。
 *
 * 改前实测（上游对照 /workspace/magpie-upstream-review/internal/gateway/lan.go）：
 *  - 上游 listenAddr()：只有「共享且没有 pin 过地址」时才听 0.0.0.0；用户 pin 的地址永远不被放宽
 *    （#1112 原文：never widens the listener past the address the user chose）。
 *  - 本仓改前：GatewayService 固定 new GatewayServer(this, 3425)，NanoHTTPD 单参构造把 hostname
 *    留成 null，ServerRunnable 于是 bind InetSocketAddress(port) → 0.0.0.0；config.json 的
 *    server.host 从没参与 bind，/api/status 的 listenAddress 也只是 null 的默认值。配置写
 *    127.0.0.1 的用户以为「只监听本机」，实际局域网照连——安全假象。
 *
 * 本版语义（三条都不静默改变既有联网）：
 *  1. 缺省 / 空 / 0.0.0.0            → 通配 0.0.0.0:3425，逐字保持历史行为。
 *  2. 显式 server.loopbackOnly=true  → 真 bind 127.0.0.1（本版本新增的键，旧配置不可能带有）。
 *  3. 旧配置里的 127.0.0.1（无 opt-in）→ 保留通配、标未生效、给 enable-loopback 动作，绝不因升级
 *     静默切断已在用的局域网；配置损坏/不可读 → fail-closed 到回环（不是回退通配扩大暴露），并
 *     如实标未生效、给 recover-config 动作。
 *
 * 只在隔离临时目录 + 本机回环/本地网卡 + 临时端口上跑：不读装机用户配置、不访问外网、不用 Gradle。
 */
public final class GatewayListenAddressOfflineTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static final int PORT = 3425;

    // ---------- A: 配置 → 监听计划 ----------
    static void planFromConfigValue() {
        GatewayServer.ListenPlan absent = GatewayServer.planListen(null, false, PORT, PORT);
        check(GatewayServer.ListenPlan.ANY.equals(absent.host), "absent host keeps the wildcard listener");
        check(absent.hostApplied, "absent host is nothing to apply, not a failure");
        check(absent.requestedHost.isEmpty(), "absent host is reported empty");
        check(absent.note.isEmpty(), "absent host needs no note: " + absent.note);
        check(!absent.actionRequired(), "absent host needs no user action");
        check(!absent.loopback(), "absent host is not loopback");

        check(GatewayServer.ListenPlan.ANY.equals(GatewayServer.planListen("", false, PORT, PORT).host),
                "blank host keeps the wildcard listener");
        check(GatewayServer.ListenPlan.ANY.equals(GatewayServer.planListen("   ", false, PORT, PORT).host),
                "whitespace host keeps the wildcard listener");
        GatewayServer.ListenPlan shipped = GatewayServer.planListen("0.0.0.0", false, PORT, PORT);
        check(GatewayServer.ListenPlan.ANY.equals(shipped.host), "shipped default 0.0.0.0 is the wildcard");
        check(shipped.hostApplied && shipped.portApplied, "shipped default is fully applied");
        check(shipped.note.isEmpty() && !shipped.actionRequired(), "shipped default needs nothing");

        // 显式 opt-in 才收紧。
        for (String pinned : new String[]{"", "0.0.0.0", "127.0.0.1", "127.0.0.2", "localhost"}) {
            GatewayServer.ListenPlan plan = GatewayServer.planListen(pinned, true, PORT, PORT);
            check(GatewayServer.ListenPlan.LOOPBACK.equals(plan.host), "loopbackOnly binds loopback: " + pinned);
            check(plan.loopback() && plan.loopbackOnlyRequested, "loopbackOnly is flagged: " + pinned);
            check(plan.hostApplied, "loopbackOnly is really applied: " + pinned);
            check(!plan.actionRequired(), "loopbackOnly needs no user action: " + pinned);
        }
        GatewayServer.ListenPlan flagWins = GatewayServer.planListen("192.168.1.5", true, PORT, PORT);
        check(GatewayServer.ListenPlan.LOOPBACK.equals(flagWins.host), "loopbackOnly wins over a LAN host");
        check(flagWins.note.contains("loopbackOnly wins"), "the conflict is stated: " + flagWins.note);

        // 旧配置里的回环：保留历史绑定（不静默切断），标记未生效，给出可操作动作。
        for (String legacy : new String[]{"127.0.0.1", " 127.0.0.1 ", "localhost", "LOCALHOST",
                "127.0.0.2", "127.255.255.254"}) {
            GatewayServer.ListenPlan plan = GatewayServer.planListen(legacy, false, PORT, PORT);
            check(GatewayServer.ListenPlan.ANY.equals(plan.host),
                    "a legacy loopback host never narrows the listener by itself: " + legacy);
            check(!plan.hostApplied, "a legacy loopback host is reported as not applied: " + legacy);
            check(GatewayServer.ListenPlan.ACTION_ENABLE_LOOPBACK.equals(plan.action),
                    "the actionable token is enable-loopback: " + legacy + " -> " + plan.action);
            check(plan.note.contains("loopbackOnly=true"), "the note names the explicit switch: " + plan.note);
            check(plan.note.contains("LAN"), "the note says the LAN access is still open: " + plan.note);
        }

        // 无法安全采纳的取值：保持通配，说明原因，给出动作。
        for (String ipv6Loopback : new String[]{"::1", "[::1]", "0:0:0:0:0:0:0:1"}) {
            GatewayServer.ListenPlan plan = GatewayServer.planListen(ipv6Loopback, false, PORT, PORT);
            check(GatewayServer.ListenPlan.ANY.equals(plan.host), "ipv6 loopback is not honoured: " + ipv6Loopback);
            check(!plan.hostApplied, "ipv6 loopback is reported as not applied");
            check(GatewayServer.ListenPlan.ACTION_UNSUPPORTED_HOST.equals(plan.action), "token: " + plan.action);
            check(plan.note.contains("127.0.0.1 bridge"), "note says why: " + plan.note);
        }
        for (String other : new String[]{"::", "[::]", "0:0:0:0:0:0:0:0", "192.168.1.5", "10.0.0.2",
                "example.local", "0.0.0.0:3425", "127.1", "127.0.0.01", "::ffff:127.0.0.1", "<script>"}) {
            GatewayServer.ListenPlan plan = GatewayServer.planListen(other, false, PORT, PORT);
            check(GatewayServer.ListenPlan.ANY.equals(plan.host), "no silent narrowing for " + other);
            check(!plan.hostApplied, "not applied: " + other);
            check(GatewayServer.ListenPlan.ACTION_UNSUPPORTED_HOST.equals(plan.action), "token: " + other);
            check(!plan.note.isEmpty(), "explained: " + other);
        }

        // 端口固定：不采纳，但要说明。
        GatewayServer.ListenPlan otherPort = GatewayServer.planListen("", false, 8080, PORT);
        check(otherPort.port == PORT, "the socket keeps 3425");
        check(!otherPort.portApplied && otherPort.requestedPort == 8080, "port difference is reported");
        check(GatewayServer.ListenPlan.ACTION_PORT_FIXED.equals(otherPort.action), "token: " + otherPort.action);
        check(otherPort.note.contains("port is fixed at 3425"), "note names the port: " + otherPort.note);
        GatewayServer.ListenPlan both = GatewayServer.planListen("192.168.1.5", false, 8080, PORT);
        check(both.note.contains("is not applied") && both.note.contains("port is fixed at 3425"),
                "both deviations are explained: " + both.note);
        check(GatewayServer.ListenPlan.ACTION_UNSUPPORTED_HOST.equals(both.action),
                "the stronger action is kept: " + both.action);

        GatewayServer.ListenPlan wildcardDefault = GatewayServer.ListenPlan.wildcard(PORT);
        check(GatewayServer.ListenPlan.ANY.equals(wildcardDefault.host) && wildcardDefault.hostApplied,
                "the legacy two-argument constructor keeps the listener it always had");
        check(!wildcardDefault.actionRequired(), "the legacy constructor asks nothing of the user");
    }

    // ---------- B: 磁盘配置 → 监听计划（只读、故障优先） ----------
    static File tempDir(String prefix) throws Exception {
        File dir = java.nio.file.Files.createTempDirectory(prefix).toFile();
        check(dir.isDirectory(), "temp dir exists");
        return dir;
    }

    static byte[] readAll(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        } finally { in.close(); }
    }

    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        StringBuilder out = new StringBuilder();
        for (byte b : digest.digest(readAll(file))) out.append(String.format("%02x", b));
        return out.toString();
    }

    static File writeConfig(File dir, String body) throws Exception {
        File file = new File(dir, "config.json");
        FileOutputStream out = new FileOutputStream(file);
        try { out.write(body.getBytes(StandardCharsets.UTF_8)); } finally { out.close(); }
        return file;
    }

    static GatewayServer.ListenPlan planFromDisk(File file, String expectUnchanged) throws Exception {
        String before = expectUnchanged == null ? null : sha256(file);
        GatewayServer.ListenPlan plan = GatewayServer.planListen(file, PORT);
        if (before != null) check(before.equals(sha256(file)), "reading the plan never rewrote the configuration");
        return plan;
    }

    static void planFromDisk() throws Exception {
        File dir = tempDir("allin-listen-plan-");

        GatewayServer.ListenPlan shipped = planFromDisk(
                writeConfig(dir, "{\"server\":{\"host\":\"0.0.0.0\",\"port\":3425},\"providers\":[]}"), "x");
        check(GatewayServer.ListenPlan.ANY.equals(shipped.host) && shipped.hostApplied,
                "the shipped default keeps every existing LAN/client connection");
        check(!shipped.actionRequired(), "the shipped default asks nothing of the user");

        GatewayServer.ListenPlan optedIn = planFromDisk(
                writeConfig(dir, "{\"server\":{\"loopbackOnly\":true},\"providers\":[]}"), "x");
        check(GatewayServer.ListenPlan.LOOPBACK.equals(optedIn.host) && optedIn.hostApplied,
                "an explicit opt-in really narrows the listener");

        // 旧配置写 127.0.0.1：升级后不得静默切断已在用的局域网。
        GatewayServer.ListenPlan legacy = planFromDisk(
                writeConfig(dir, "{\"server\":{\"host\":\"127.0.0.1\",\"port\":3425},\"providers\":[]}"), "x");
        check(GatewayServer.ListenPlan.ANY.equals(legacy.host),
                "a legacy loopback configuration keeps the binding it was actually served on");
        check(!legacy.hostApplied, "the legacy pin is reported as not applied: " + legacy.note);
        check(GatewayServer.ListenPlan.ACTION_ENABLE_LOOPBACK.equals(legacy.action), "action: " + legacy.action);
        check(legacy.host.equals(GatewayServer.ListenPlan.wildcard(PORT).host),
                "the effective endpoint is byte-identical to the historical default");

        // 显式写 false 不能被当成 opt-in：仍然保留历史通配监听。
        GatewayServer.ListenPlan optedOut = planFromDisk(
                writeConfig(dir, "{\"server\":{\"loopbackOnly\":false,\"host\":\"127.0.0.1\"},\"providers\":[]}"), "x");
        check(GatewayServer.ListenPlan.ANY.equals(optedOut.host) && !optedOut.hostApplied,
                "an explicit loopbackOnly=false keeps the historical binding: " + optedOut.host);
        check(GatewayServer.ListenPlan.ACTION_ENABLE_LOOPBACK.equals(optedOut.action), "action: " + optedOut.action);

        // 随包的 0.0.0.0 加上显式 opt-in：opt-in 说了算，真的只监听回环。
        GatewayServer.ListenPlan shippedOptIn = planFromDisk(
                writeConfig(dir, "{\"server\":{\"host\":\"0.0.0.0\",\"loopbackOnly\":true},\"providers\":[]}"), "x");
        check(GatewayServer.ListenPlan.LOOPBACK.equals(shippedOptIn.host) && shippedOptIn.hostApplied,
                "the opt-in wins over the shipped wildcard host");

        GatewayServer.ListenPlan noServer = planFromDisk(writeConfig(dir, "{\"providers\":[]}"), "x");
        check(GatewayServer.ListenPlan.ANY.equals(noServer.host) && noServer.hostApplied,
                "configuration without a server block keeps the wildcard listener");

        GatewayServer.ListenPlan portOnly = planFromDisk(
                writeConfig(dir, "{\"server\":{\"port\":8080},\"providers\":[]}"), "x");
        check(GatewayServer.ListenPlan.ANY.equals(portOnly.host) && portOnly.hostApplied,
                "port-only configuration keeps the wildcard listener");
        check(!portOnly.portApplied && GatewayServer.ListenPlan.ACTION_PORT_FIXED.equals(portOnly.action),
                "port-only configuration is reported as deviating");

        // 损坏 / 不可读：fail-closed 到回环，绝不用 0.0.0.0 扩大暴露，也不谎报已生效。
        String[] damagedBodies = new String[]{
                "{ this is not json", "", "[]", "{\"providers\":{}}", "{\"server\":\"127.0.0.1\"}",
                "{\"server\":{\"host\":\"127.0.0.1\"},\"providers\":{}}"};
        for (String body : damagedBodies) {
            GatewayServer.ListenPlan damaged = planFromDisk(writeConfig(dir, body), "x");
            check(GatewayServer.ListenPlan.LOOPBACK.equals(damaged.host),
                    "a damaged configuration fails closed to loopback, never to the wildcard: " + body);
            check(!damaged.hostApplied, "a damaged configuration is not claimed as applied: " + body);
            check(GatewayServer.ListenPlan.ACTION_RECOVER_CONFIG.equals(damaged.action),
                    "the action is recover-config: " + body + " -> " + damaged.action);
            check(damaged.note.toLowerCase().contains("recover") || damaged.note.contains("unreadable"),
                    "the note says what happened: " + damaged.note);
            check(damaged.requestedHost.isEmpty(), "a damaged configuration pins nothing: " + body);
        }

        File notAFile = new File(dir, "directory-config.json");
        check(notAFile.mkdirs(), "the directory fixture exists");
        GatewayServer.ListenPlan unreadable = GatewayServer.planListen(notAFile, PORT);
        check(GatewayServer.ListenPlan.LOOPBACK.equals(unreadable.host),
                "an unreadable configuration path also fails closed to loopback");
        check(GatewayServer.ListenPlan.ACTION_RECOVER_CONFIG.equals(unreadable.action), "action: " + unreadable.action);

        GatewayServer.ListenPlan missing = planFromDisk(new File(dir, "absent.json"), null);
        check(GatewayServer.ListenPlan.ANY.equals(missing.host) && missing.hostApplied,
                "a configuration that does not exist yet is first run, not damage");
        check(GatewayServer.planListen((File) null, PORT).host.equals(GatewayServer.ListenPlan.ANY),
                "null configuration path keeps the wildcard listener");

        // fail-closed 的回环不能把 App 自己挡在外面：127.0.0.1 必须照常可达（D 节实测）。
        check(unreadable.loopback(), "the fail-closed plan listens on loopback, which the app itself uses");
    }

    // ---------- C: 状态回显 ----------
    /** 与其它离线测试一致：不构造真的 Service/Context，只给要被读的字段赋值。 */
    static GatewayServer fixture(String configBody, GatewayServer.ListenPlan plan) throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        GatewayServer instance = (GatewayServer) ((Unsafe) unsafeField.get(null)).allocateInstance(GatewayServer.class);
        Field configField = GatewayServer.class.getDeclaredField("configJson");
        configField.setAccessible(true);
        configField.set(instance, configBody == null ? null : new JSONObject(configBody));
        Field planField = GatewayServer.class.getDeclaredField("listenPlan");
        planField.setAccessible(true);
        planField.set(instance, plan);
        return instance;
    }

    static void echoMatchesPlan() throws Exception {
        GatewayServer.ListenPlan any = GatewayServer.planListen("0.0.0.0", false, PORT, PORT);
        GatewayServer.ListenPlan loopbackPlan = GatewayServer.planListen("", true, PORT, PORT);
        GatewayServer.ListenPlan damagedPlan = GatewayServer.ListenPlan.damaged(PORT);

        JSONObject shipped = fixture("{\"server\":{\"host\":\"0.0.0.0\",\"port\":3425}}", any).listenStatus();
        check("0.0.0.0".equals(shipped.getString("listenAddress")), "wildcard echo: " + shipped);
        check(shipped.getBoolean("configuredHostApplied"), "shipped default is in force: " + shipped);
        check(!shipped.has("listenNote"), "shipped default needs no note: " + shipped);
        check(!shipped.getBoolean("listenActionRequired") && shipped.getString("listenAction").isEmpty(),
                "shipped default asks nothing: " + shipped);
        check(shipped.getInt("configuredPort") == PORT && shipped.getBoolean("configuredPortApplied"),
                "shipped port is in force: " + shipped);

        JSONObject optedIn = fixture("{\"server\":{\"loopbackOnly\":true}}", loopbackPlan).listenStatus();
        check("127.0.0.1".equals(optedIn.getString("listenAddress")), "loopback echo: " + optedIn);
        check(optedIn.getBoolean("configuredHostApplied"), "the opt-in is in force once bound: " + optedIn);
        check(optedIn.getBoolean("configuredLoopbackOnly"), "the opt-in is echoed: " + optedIn);
        check(!optedIn.getBoolean("listenActionRequired"), "nothing to do: " + optedIn);

        // 旧配置里的 127.0.0.1：socket 仍是通配，必须如实说明并给出动作，不能假装已收紧。
        JSONObject legacy = fixture("{\"server\":{\"host\":\"127.0.0.1\"}}", any).listenStatus();
        check("0.0.0.0".equals(legacy.getString("listenAddress")), "the echo reports the socket, not the wish");
        check("127.0.0.1".equals(legacy.getString("configuredHost")), "the echo reports the live configuration");
        check(!legacy.getBoolean("configuredHostApplied"), "an unapplied legacy pin is not 'applied': " + legacy);
        check("enable-loopback".equals(legacy.getString("listenAction")),
                "the actionable token is exposed: " + legacy);
        check(legacy.getBoolean("listenActionRequired"), "the caller is told an action is required: " + legacy);
        check(legacy.getString("listenNote").contains("loopbackOnly=true"),
                "the note tells the user how to close LAN access: " + legacy);

        // 配置损坏：回环是 fail-closed 的结果，必须标未生效 + recover-config，不是「0.0.0.0 已生效」。
        JSONObject damaged = fixture(null, damagedPlan).listenStatus();
        check("127.0.0.1".equals(damaged.getString("listenAddress")),
                "a damaged configuration listens on loopback: " + damaged);
        check(!damaged.getBoolean("configuredHostApplied"),
                "a damaged configuration is never reported as applied: " + damaged);
        check("recover-config".equals(damaged.getString("listenAction")), "action: " + damaged);
        check(damaged.getString("listenNote").contains("recover"), "note: " + damaged);
        check(!damaged.getString("listenAddress").equals("0.0.0.0"),
                "a damaged configuration never widens to the wildcard: " + damaged);

        // 进程内恢复之后（配置又能读了，但 socket 仍是 fail-closed 的回环）：告知需要重启。
        JSONObject recovered = fixture("{\"server\":{\"host\":\"0.0.0.0\"}}", damagedPlan).listenStatus();
        check("127.0.0.1".equals(recovered.getString("listenAddress")), "the socket stays loopback until restart");
        check(!recovered.getBoolean("configuredHostApplied"), "not in force yet: " + recovered);
        check("restart".equals(recovered.getString("listenAction")), "action: " + recovered);

        // 运行中把 opt-in 打开：socket 还是通配，必须说「重启后生效」。
        JSONObject pendingClose = fixture("{\"server\":{\"loopbackOnly\":true}}", any).listenStatus();
        check("0.0.0.0".equals(pendingClose.getString("listenAddress")), "the socket is still the wildcard");
        check(!pendingClose.getBoolean("configuredHostApplied"), "not narrowed yet: " + pendingClose);
        check("restart".equals(pendingClose.getString("listenAction")), "action: " + pendingClose);
        check(pendingClose.getString("listenNote").contains("restarts"), "note: " + pendingClose);

        // 反向：socket 已收紧而配置改宽，同样不能谎报。
        JSONObject pendingWiden = fixture("{\"server\":{\"host\":\"0.0.0.0\"}}", loopbackPlan).listenStatus();
        check("127.0.0.1".equals(pendingWiden.getString("listenAddress")), "the socket stays loopback until restart");
        check(!pendingWiden.getBoolean("configuredHostApplied"), "widening is not in force yet: " + pendingWiden);

        JSONObject lanPin = fixture("{\"server\":{\"host\":\"192.168.1.5\"}}", any).listenStatus();
        check(!lanPin.getBoolean("configuredHostApplied") && "unsupported-host".equals(lanPin.getString("listenAction")),
                "an unadoptable LAN pin is reported, not silently ignored: " + lanPin);

        JSONObject ipv6 = fixture("{\"server\":{\"host\":\"::1\"}}", any).listenStatus();
        check(!ipv6.getBoolean("configuredHostApplied") && "unsupported-host".equals(ipv6.getString("listenAction")),
                "ipv6 loopback is not claimed as applied: " + ipv6);

        JSONObject portOnly = fixture("{\"server\":{\"port\":8080}}", any).listenStatus();
        check(portOnly.getInt("configuredPort") == 8080, "configured port is echoed");
        check(!portOnly.getBoolean("configuredPortApplied"), "the fixed port is reported as not applied");
        check("port-fixed".equals(portOnly.getString("listenAction")), "action: " + portOnly);

        JSONObject noServer = fixture("{}", any).listenStatus();
        check(noServer.getString("configuredHost").isEmpty() && noServer.getBoolean("configuredHostApplied"),
                "a configuration without a server block asks for nothing: " + noServer);

        // 地址回显：与实际 bind 主机严格一致，绝不替配置说谎。
        JSONObject loopbackUrls = GatewayServer.gatewayAddressInfo(PORT, "192.168.1.7", loopbackPlan.host);
        check("http://127.0.0.1:3425/v1".equals(loopbackUrls.getString("localBaseUrl")),
                "loopback listener echoes the loopback URL: " + loopbackUrls);
        check(loopbackUrls.getString("lanBaseUrl").isEmpty(),
                "loopback listener never advertises a LAN URL: " + loopbackUrls);
        check(GatewayServer.ListenPlan.LOOPBACK.equals(loopbackUrls.getString("listenAddress")),
                "loopback listener echoes 127.0.0.1: " + loopbackUrls);

        JSONObject anyUrls = GatewayServer.gatewayAddressInfo(PORT, "192.168.1.7", any.host);
        check("http://127.0.0.1:3425/v1".equals(anyUrls.getString("localBaseUrl")), "wildcard keeps the loopback URL");
        check("http://192.168.1.7:3425/v1".equals(anyUrls.getString("lanBaseUrl")), "wildcard advertises the LAN URL");

        JSONObject noPort = GatewayServer.gatewayAddressInfo(0, "192.168.1.7", loopbackPlan.host);
        check(noPort.getString("localBaseUrl").isEmpty() && noPort.getString("lanBaseUrl").isEmpty(),
                "an unbound port advertises nothing");
    }

    // ---------- D: 真实 socket 语义 ----------
    /**
     * This is a bind/HTTP fixture, not a reverse-DNS test. NanoHTTPD 2.3.1's
     * HTTPSession calls getHostName() before reading the request. Windows CI's
     * virtual NIC can accept TCP while its PTR lookup blocks past the deadline.
     * Keep the real peer bytes and the real socket; supply only the already-known
     * numeric display name. Never replace the peer with loopback, change bind,
     * filter a TCP-capable witness, or turn a partial HTTP response into a pass.
     * Production GatewayServer and its client handlers are intentionally unchanged.
     */
    static final class NumericPeerSocket extends Socket {
        private final Socket delegate;
        private final InetAddress peer;
        NumericPeerSocket(Socket delegate) throws java.io.IOException {
            this.delegate = delegate;
            InetAddress actual = delegate.getInetAddress();
            if (actual == null) throw new java.io.IOException("fixture socket has no peer");
            this.peer = InetAddress.getByAddress(actual.getHostAddress(), actual.getAddress());
        }
        @Override public InetAddress getInetAddress() { return peer; }
        @Override public InputStream getInputStream() throws java.io.IOException { return delegate.getInputStream(); }
        @Override public OutputStream getOutputStream() throws java.io.IOException { return delegate.getOutputStream(); }
        @Override public boolean isClosed() { return delegate.isClosed(); }
        @Override public void close() throws java.io.IOException { delegate.close(); }
    }

    static void fixtureNumericPeer() throws Exception {
        for (String numeric : new String[]{"192.0.2.7", "2001:db8::7", "127.0.0.1"}) {
            final InetAddress raw = InetAddress.getByAddress(InetAddress.getByName(numeric).getAddress());
            Socket original = new Socket() {
                @Override public InetAddress getInetAddress() { return raw; }
            };
            try (NumericPeerSocket view = new NumericPeerSocket(original)) {
                check(java.util.Arrays.equals(raw.getAddress(), view.getInetAddress().getAddress()), "numeric display keeps every peer byte: " + numeric);
                check(raw.getHostAddress().equals(view.getInetAddress().getHostName()), "fixture hostname is the numeric peer without PTR: " + numeric);
                check(raw.isLoopbackAddress() == view.getInetAddress().isLoopbackAddress(), "numeric display never changes loopback classification");
                check(!view.isClosed(), "socket view starts open");
            }
            check(original.isClosed(), "closing the view closes its real socket");
        }
        try (ServerSocket listener = new ServerSocket()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            try (Socket client = new Socket("127.0.0.1", listener.getLocalPort());
                 Socket accepted = listener.accept();
                 NumericPeerSocket view = new NumericPeerSocket(accepted)) {
                client.setSoTimeout(2000);
                accepted.setSoTimeout(2000);
                check(view.getInputStream() == accepted.getInputStream(), "view retains the accepted input stream");
                check(view.getOutputStream() == accepted.getOutputStream(), "view retains the accepted output stream");
                view.getOutputStream().write(7);
                view.getOutputStream().flush();
                check(client.getInputStream().read() == 7, "response bytes travel over the real accepted socket");
                client.getOutputStream().write(9);
                client.getOutputStream().flush();
                check(view.getInputStream().read() == 9, "request bytes travel over the real accepted socket");
            }
        }
        System.out.println("Numeric peer fixture checks OK (IPv4/IPv6 identity, loopback classification, real I/O and close)");
    }

    static final class Probe extends NanoHTTPD {
        Probe(String host, int port) { super(host, port); }
        @Override protected ClientHandler createClientHandler(Socket socket, InputStream input) {
            try { return super.createClientHandler(new NumericPeerSocket(socket), input); }
            catch (java.io.IOException failure) {
                try { socket.close(); } catch (java.io.IOException ignored) { }
                throw new IllegalStateException("fixture numeric peer unavailable", failure);
            }
        }
        @Override public Response serve(IHTTPSession session) {
            String peer = session.getRemoteIpAddress();
            check("127.0.0.1".equals(peer) ? "localhost".equals(session.getRemoteHostName())
                    : peer.equals(session.getRemoteHostName()), "HTTP session uses real numeric peer, not reverse DNS");
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "ok");
        }
    }

    static int freePort() throws Exception {
        ServerSocket socket = new ServerSocket(0);
        try { return socket.getLocalPort(); } finally { socket.close(); }
    }

    static List<String> localIpv4(boolean loopback) throws Exception {
        List<String> found = new ArrayList<String>();
        for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
            NetworkInterface intf = en.nextElement();
            if (!intf.isUp() || intf.isLoopback() != loopback) continue;
            for (Enumeration<InetAddress> addresses = intf.getInetAddresses(); addresses.hasMoreElements(); ) {
                InetAddress address = addresses.nextElement();
                if (!(address instanceof Inet4Address) || address.isLinkLocalAddress()) continue;
                String ip = address.getHostAddress();
                if (ip != null && !ip.isEmpty() && !found.contains(ip)) found.add(ip);
            }
        }
        return found;
    }

    /** Independent kernel TCP control: never use NanoHTTPD or the listen plan to qualify a NIC. */
    static boolean independentTcpReach(String ip) {
        try (ServerSocket control = new ServerSocket()) {
            control.bind(new InetSocketAddress("0.0.0.0", 0));
            control.setSoTimeout(2000);
            try (Socket client = new Socket()) {
                client.connect(new InetSocketAddress(ip, control.getLocalPort()), 2000);
                try (Socket accepted = control.accept()) {
                    return accepted.getPort() == client.getLocalPort()
                            && accepted.getInetAddress().equals(client.getLocalAddress());
                }
            }
        } catch (Exception unavailable) {
            System.out.println("NOTE: independent TCP control unavailable for " + ip + " (" + unavailable.getClass().getSimpleName() + ")");
            return false;
        }
    }
    static List<String> qualifiedLan(List<String> candidates, java.util.function.Predicate<String> control) {
        List<String> out = new ArrayList<>();
        for (String ip : candidates) if (control.test(ip)) out.add(ip);
        return out;
    }
    static void fixtureQualification() {
        List<String> candidates = java.util.Arrays.asList("192.0.2.10", "192.0.2.11");
        List<String> actual = qualifiedLan(candidates, ip -> "192.0.2.11".equals(ip));
        check(actual.size() == 1 && actual.get(0).equals("192.0.2.11"), "exclude only independently unreachable addresses");
        check(qualifiedLan(candidates, ip -> true).equals(candidates), "keep every TCP-capable LAN witness");
        check(qualifiedLan(candidates, ip -> false).isEmpty(), "a failed control never invents reachable LAN evidence");
    }

    /** These probes have fixed-length bodies. A complete HTTP frame must not wait for TCP EOF. */
    static String readFixedReply(InputStream in) throws Exception {
        ByteArrayOutputStream reply = new ByteArrayOutputStream(); byte[] buffer = new byte[512];
        while (reply.size() < 8192) {
            int n;
            try { n = in.read(buffer); }
            catch (Exception failure) { throw new java.io.IOException("fixture read failed after " + reply.size() + " bytes", failure); }
            if (n < 0) return null;
            if (n == 0) continue;
            reply.write(buffer, 0, n); if (reply.size() > 8192) return null;
            String text = reply.toString("ISO-8859-1"); int end = text.indexOf("\r\n\r\n");
            if (end < 0) continue;
            String header = text.substring(0, end);
            if (header.toLowerCase(java.util.Locale.ROOT).contains("\r\ntransfer-encoding:")) return null;
            java.util.regex.Matcher length = java.util.regex.Pattern.compile("(?im)^content-length:\\s*([0-9]+)\\s*$").matcher(header);
            if (!length.find()) return null;
            long body;
            try { body = Long.parseLong(length.group(1)); } catch (NumberFormatException bad) { return null; }
            if (length.find() || body > 8192) return null;
            long total = end + 4L + body; if (total > 8192) return null;
            if (reply.size() == total) return text;
            if (reply.size() > total) return null;
        }
        return null;
    }
    static void fixtureHttpFraming() throws Exception {
        final byte[] frame = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".getBytes(StandardCharsets.ISO_8859_1);
        final int[] reads = {0}; final java.io.ByteArrayInputStream bytes = new java.io.ByteArrayInputStream(frame);
        InputStream resetsAfterComplete = new InputStream() {
            public int read() throws java.io.IOException { throw new java.io.IOException("bulk reads only"); }
            public int read(byte[] b, int off, int len) throws java.io.IOException {
                if (reads[0]++ > 0) throw new java.net.SocketException("synthetic reset after complete frame");
                return bytes.read(b, off, len);
            }
        };
        check(readFixedReply(resetsAfterComplete).endsWith("\r\n\r\nok") && reads[0] == 1, "complete response does not read into a reset or wait for EOF");
        check(readFixedReply(new java.io.ByteArrayInputStream(java.util.Arrays.copyOf(frame, frame.length - 1))) == null, "partial body never establishes reachability");
        check(readFixedReply(new java.io.ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Length: x\r\n\r\nok".getBytes(StandardCharsets.ISO_8859_1))) == null, "invalid length rejected");
        check(readFixedReply(new java.io.ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1))) != null, "known zero-length response is complete");
    }
    static void socketFailureDiagnostic(String ip, String reply) {
        String first = reply == null ? "null" : reply.split("\r\n", 2)[0];
        System.out.println("Unexpected fixture HTTP reply for " + ip + ": " + first);
        for (java.util.Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            boolean relevant = false;
            for (StackTraceElement frame : entry.getValue()) if (frame.getClassName().startsWith("fi.iki.elonen.NanoHTTPD") || frame.getClassName().startsWith("java.net.InetAddress")) relevant = true;
            if (relevant) {
                System.out.println("Fixture thread: " + entry.getKey().getName());
                for (int i = 0; i < Math.min(10, entry.getValue().length); i++) System.out.println("  " + entry.getValue()[i]);
            }
        }
    }

    static String httpGet(String ip, int port, int timeoutMillis) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(ip, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            OutputStream out = socket.getOutputStream();
            out.write(("GET / HTTP/1.1\r\nHost: " + ip + ":" + port + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = socket.getInputStream();
            return readFixedReply(in);
        } catch (Exception unreachable) {
            System.out.println("Fixture HTTP failure for " + ip + ": " + unreachable.getClass().getSimpleName() + ": " + unreachable.getMessage());
            if (unreachable.getCause() != null) System.out.println("  cause: " + unreachable.getCause().getClass().getSimpleName());
            return null;
        } finally {
            try { socket.close(); } catch (Exception ignored) { }
        }
    }

    /** 在本机实测「这个 bind 主机到底能被谁连上」。 */
    static void probeReach(String bindHost, String label, boolean expectLoopback, boolean expectLan, List<String> lan) throws Exception {
        int port = freePort();
        Probe probe = new Probe(bindHost, port);
        try {
            probe.start(2000, true);
            String reply = httpGet("127.0.0.1", port, 2000);
            check(expectLoopback ? reply != null && reply.startsWith("HTTP/1.1 200")
                            : reply == null,
                    label + ": 127.0.0.1 reachable=" + expectLoopback);
            for (String ip : lan) {
                String lanReply = httpGet(ip, port, 2000);
                boolean observed = lanReply != null && lanReply.startsWith("HTTP/1.1 200") && lanReply.endsWith("\r\n\r\nok");
                if (observed != expectLan) socketFailureDiagnostic(ip, lanReply);
                check(expectLan ? observed : lanReply == null,
                        label + ": LAN " + ip + " expected=" + expectLan + " actual=" + observed);
            }
        } finally { probe.stop(); }
    }

    static void socketSemantics() throws Exception {
        List<String> candidates = localIpv4(false);
        List<String> lan = qualifiedLan(candidates, GatewayListenAddressOfflineTest::independentTcpReach);
        System.out.println("Observed LAN candidates: " + candidates + "; independently reachable witnesses: " + lan);
        check(candidates.isEmpty() || !lan.isEmpty(), "No independent TCP-capable LAN address: fixture is inconclusive, not a listener failure or a pass");
        fixtureQualification();
        fixtureHttpFraming();
        fixtureNumericPeer();
        // 1. 历史默认：回环与局域网都通（证明既有连接不受影响）。
        probeReach(GatewayServer.planListen("0.0.0.0", false, PORT, PORT).host,
                "wildcard listener", true, true, lan);

        // 2. 旧配置写 127.0.0.1：生效的仍必须是通配，局域网连接逐字不变。
        File dir = tempDir("allin-listen-socket-");
        GatewayServer.ListenPlan legacy = GatewayServer.planListen(
                writeConfig(dir, "{\"server\":{\"host\":\"127.0.0.1\"},\"providers\":[]}"), PORT);
        probeReach(legacy.host, "legacy 127.0.0.1 configuration (no silent cut)", true, true, lan);

        // 3. 显式 opt-in：只监听回环，局域网被关掉，App 自己的 127.0.0.1 照常。
        probeReach(GatewayServer.planListen("", true, PORT, PORT).host,
                "explicit loopbackOnly listener", true, false, lan);

        // 4. 损坏配置 fail-closed：同样只监听回环（不扩大暴露），且 127.0.0.1 仍然可达（恢复界面进得去）。
        GatewayServer.ListenPlan damaged = GatewayServer.planListen(
                writeConfig(dir, "{ damaged"), PORT);
        probeReach(damaged.host, "damaged configuration (fail-closed)", true, false, lan);

        // 5. 负对照：本次修改之前的 super(port)（hostname=null）确实在局域网可达——被修掉的假象。
        System.out.println("verified non-loopback IPv4 for negative controls: " + lan);
        if (lan.isEmpty()) {
            System.out.println("NOTE: no non-loopback IPv4 here; the negative control is not observed");
            return;
        }
        boolean caught = false;
        try { probeReach("127.0.0.1", "intentional incorrect wildcard", true, true, lan); }
        catch (AssertionError expected) { caught = true; }
        check(caught, "qualified witness must still detect a wildcard listener incorrectly restricted to loopback");
        caught = false;
        try { probeReach("0.0.0.0", "intentional exposed loopback-only", true, false, lan); }
        catch (AssertionError expected) { caught = true; }
        check(caught, "qualified witness must still detect an exposed loopback-only listener");
        int port = freePort();
        Probe before = new Probe((String) null, port);
        try {
            before.start(2000, true);
            check(before.getHostname() == null, "the pre-change wiring left NanoHTTPD's hostname unset");
            for (String ip : lan) {
                String reply = httpGet(ip, port, 2000);
                check(reply != null && reply.startsWith("HTTP/1.1 200"),
                        "control: an unset hostname really listened on " + ip + " (the illusion being fixed)");
            }
        } finally { before.stop(); }
    }

    // ---------- E: 生产装配守卫（源码级，不是运行时证明） ----------
    /** Works from the repository root and from the app module directory, like the other offline tests. */
    static String readSource(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile()) file = new File(path.replace("app/src/", "src/"));
        check(file.isFile(), "source under test is readable: " + path);
        return new String(readAll(file), StandardCharsets.UTF_8);
    }

    static void wiringGuard() throws Exception {
        String service = readSource("app/src/main/java/com/gaeul/allin/GatewayService.java");
        String server = readSource("app/src/main/java/com/gaeul/allin/GatewayServer.java");
        check(service.contains("static final int GATEWAY_PORT = 3425;"), "the fixed port is named once");
        check(!service.contains("new GatewayServer(this, 3425)") && !service.contains("new GatewayServer(owner, 3425)"),
                "no start path skips the listen plan");
        check(service.contains("candidate = createGatewayServer(this);")
                        && service.contains("GatewayServer candidate = createGatewayServer(owner);"),
                "both start paths build the server through the plan");
        check(service.contains("GatewayServer.planListen("), "the plan is read from the configuration");
        check(server.contains("super(plan.host, plan.port);"), "the plan's host is what NanoHTTPD binds");
        check(!server.contains("getHostname()"),
                "no status path can fall back to NanoHTTPD's unset hostname again");
        check(server.contains("JSONObject listen = listenStatus();"), "status endpoints use the shared echo");
        check(server.contains("status.put(name, listen.opt(name));")
                        && server.contains("ret.put(name, listen.opt(name));"),
                "both /api/status and the setup status echo the same helper");
        check(server.contains("static ListenPlan damaged(int port)"), "the fail-closed plan exists");
        // 只有读，没有写：网关自己绝不替用户打开/关闭暴露面。
        check(!server.contains("put(\"loopbackOnly\"") && !service.contains("put(\"loopbackOnly\""),
                "nothing in the app writes the loopback opt-in on the user's behalf");
        check(server.contains("optBoolean(\"loopbackOnly\", false)"),
                "the opt-in is read from the configuration only");
    }

    public static void main(String[] args) throws Exception {
        planFromConfigValue();
        planFromDisk();
        echoMatchesPlan();
        socketSemantics();
        wiringGuard();
        System.out.println("GatewayListenAddressOfflineTest OK");
    }
}
