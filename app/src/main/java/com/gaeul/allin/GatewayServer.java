package com.gaeul.allin;

import android.content.Context;
import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class GatewayServer extends NanoHTTPD {
    private final Context context;
    private JSONObject configJson;
    private DeepSeekWeb.Transport deepSeekTransport = new DeepSeekWeb.HttpTransport();
    void setDeepSeekTransportForTests(DeepSeekWeb.Transport transport) { deepSeekTransport=transport; }
    private final File configFile;

    public final AtomicInteger totalRequests = new AtomicInteger(0);
    public final AtomicLong totalTokens = new AtomicLong(0);
    public final AtomicInteger totalMasked = new AtomicInteger(0);
    public final AtomicInteger totalContextDropped = new AtomicInteger(0);
    public volatile JSONObject lastGuardStats = new JSONObject();
    /** 最近一次请求被脱敏的项数，以及当时开关是否开启（供后台显示真实状态） */
    public volatile int lastMaskedCount = 0;
    public volatile boolean lastMaskEnabled = false;
    /** 最近一次真实转发的时间戳；0 表示还没有请求经过网关 */
    public volatile long lastMaskAt = 0;

    private final AtomicInteger wbRoundRobin = new AtomicInteger(0);

    /** Qoder 进行中的登录流程（flowId -> Flow），进程内保存即可 */
        /** Cline (cline.bot) 进行中的设备流登录（flowId -> Flow） */
    /** Codex 进行中的 OAuth 登录流程（flowId -> Flow） */
    private final java.util.Map<String, CodexAuth.Flow> codexFlows =
            new java.util.concurrent.ConcurrentHashMap<String, CodexAuth.Flow>();

    private final java.util.Map<String, ClineAuth.Flow> clineFlows =
            new java.util.concurrent.ConcurrentHashMap<String, ClineAuth.Flow>();

    /** Antigravity（Google Code Assist）进行中的 OAuth 登录流程（flowId -> Flow） */
    private final java.util.Map<String, AntigravityAuth.Flow> antigravityFlows =
            new java.util.concurrent.ConcurrentHashMap<String, AntigravityAuth.Flow>();

    /** Qoder 进行中的登录流程（flowId -> Flow），进程内保存即可 */
    private final java.util.Map<String, QoderAuth.Flow> qoderFlows =
            new java.util.concurrent.ConcurrentHashMap<String, QoderAuth.Flow>();

    /** 妙手 CatPaw 进行中的网页登录（sid -> 截止时间戳）。妙手是服务端轮询，不需要回跳端口 */
    private final java.util.Map<String, Long> catpawSids =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();

    /** GitHub Copilot 进行中的设备流登录（flowId = device_code -> Flow） */
    private final java.util.Map<String, CopilotAuth.Flow> copilotFlows =
            new java.util.concurrent.ConcurrentHashMap<String, CopilotAuth.Flow>();

    /** MiniMax Code 进行中的设备流登录（flowId = device_code 或 user_code -> Flow） */
    private final java.util.Map<String, MiniMaxAuth.Flow> minimaxFlows =
            new java.util.concurrent.ConcurrentHashMap<String, MiniMaxAuth.Flow>();

    /** 守护进程上报文件：记录最近解冻次数、时间与心跳（与 config.json 分开，避免污染账号配置） */
    private final File watchdogFile;
    private volatile JSONObject watchdogState;
    private final java.util.List<JSONObject> watchdogLog = new java.util.ArrayList<JSONObject>();

    public GatewayServer(Context context, int port) {
        super(port);
        this.context = context;
        this.configFile = new File(context.getFilesDir(), "config.json");
        this.watchdogFile = new File(context.getFilesDir(), "watchdog.json");
        loadWatchdogState();
        loadConfig();
        restoreAntigravityBases();
        try {
            UsageLedger.init(context.getFilesDir());
        } catch (Throwable e) {
            android.util.Log.w("Magpie", "用量账本初始化失败: " + e.getMessage());
        }
        calibrateZCodeBaseAsync();
        // MiniMax 的聊天花的是签到积分：启动后补签一次，同一天只签一次
        try {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    autoCheckinMiniMax();
                }
            }, "minimax-checkin").start();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 后台校正 ZCode 的接入点。
     * 背景：ZCode 有两个官方端点（api.z.ai / open.bigmodel.cn），同一把 key 两边都能用，
     * 但 api.z.ai 在国内部分网络会被直接切断（TLS 握手 EOF），表现为「配置齐全却一直调不通」。
     * 这里用 key 打一次 /models 探测，当前端点不可达就自动切到另一个，避免用户自己猜。
     */
    private void calibrateZCodeBaseAsync() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject zc = getProviderById("zcode");
                    if (zc == null) return;
                    String key = "";
                    JSONArray keys = zc.optJSONArray("keys");
                    if (keys != null && keys.length() > 0) key = keys.optString(0, "");
                    if (key.isEmpty()) {
                        JSONArray accs = zc.optJSONArray("accounts");
                        if (accs != null && accs.length() > 0) {
                            key = accs.getJSONObject(0).optString("token", "");
                        }
                    }
                    if (key == null || key.isEmpty()) return;

                    String cur = zc.optString("baseUrl", "");
                    // 当前端点就通，不必动它
                    if (cur != null && !cur.isEmpty() && ZCodeAuth.probeBase(cur, key)) return;

                    String site = zc.optString("site", "zai");
                    String pick = ZCodeAuth.pickReachableBase(site, key);
                    if (pick == null || pick.equals(cur)) return;

                    synchronized (GatewayServer.this) {
                        JSONObject now = getProviderById("zcode");
                        if (now != null) {
                            now.put("baseUrl", pick);
                            saveConfig(configJson);
                        }
                    }
                    android.util.Log.d("Magpie", "zcode baseUrl 自动校正: " + cur + " -> " + pick);
                } catch (Throwable ignored) {
                }
            }
        }, "zcode-base-calibrate");
        t.setDaemon(true);
        t.start();
    }

    private synchronized void loadWatchdogState() {
        JSONObject st = new JSONObject();
        try {
            if (watchdogFile.exists()) {
                st = new JSONObject(readFileToString(watchdogFile));
            }
        } catch (Throwable ignored) {
        }
        try {
            st.put("unfreezeCount", st.optInt("unfreezeCount", 0));
            st.put("lastUnfreezeAt", st.optLong("lastUnfreezeAt", 0L));
            st.put("lastBeatAt", st.optLong("lastBeatAt", 0L));
            st.put("lastBeatPid", st.optString("lastBeatPid", ""));
        } catch (Throwable ignored) {
        }
        watchdogState = st;
    }

    private synchronized void saveWatchdogState() {
        try {
            FileOutputStream fos = new FileOutputStream(watchdogFile);
            fos.write(watchdogState.toString(2).getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 读取本进程当前的冻结标记。
     * 返回 0=运行中 1=被系统冻结 -1=读不到（权限或路径不可用）。
     * 说明：被冻结时本进程根本不执行，所以这里只能读到"被解冻之后"的值，
     * 真正反映冻结史的是守护进程上报的 unfreezeCount。
     */
    public static int selfFreezeFlag(Context ctx) {
        try {
            java.io.File self = new java.io.File("/proc/self");
            String real = self.getCanonicalPath();          // 解析成 /proc/<pid>
            String pid = real.substring(real.lastIndexOf('/') + 1);
            int uid = ctx.getApplicationInfo().uid;
            java.io.File f = new java.io.File("/sys/fs/cgroup/apps/uid_" + uid
                    + "/pid_" + pid + "/cgroup.freeze");
            if (!f.exists() || !f.canRead()) return -1;
            FileInputStream fis = new FileInputStream(f);
            byte[] b = new byte[8];
            int n = fis.read(b);
            fis.close();
            if (n <= 0) return -1;
            String s = new String(b, 0, n, "UTF-8").trim();
            if ("1".equals(s)) return 1;
            if ("0".equals(s)) return 0;
            return -1;
        } catch (Throwable e) {
            return -1;
        }
    }

    private void loadConfig() {
        try {
            if (configFile.exists()) {
                String str = readFileToString(configFile);
                configJson = new JSONObject(str);
                mergeDefaultProviders();
                migrateProviders();
                migrateDeepSeekWeb();
            } else {
                InputStream is = context.getAssets().open("default_config.json");
                byte[] bytes = new byte[is.available()];
                is.read(bytes);
                is.close();
                String str = new String(bytes, "UTF-8");
                configJson = new JSONObject(str);
                configJson.put("schemaVersion", 4);
                migrateDeepSeekWeb();
                saveConfig(configJson);
            }
        } catch (Exception e) {
            e.printStackTrace();
            // 坏配置先另存一份，用户还能手工救回来；不要静默留个空配置等着被覆盖
            try {
                if (configFile != null && configFile.exists() && configFile.length() > 0) {
                    File bad = new File(configFile.getParentFile(),
                            "config.corrupt-" + System.currentTimeMillis() + ".json");
                    FileInputStream fis = new FileInputStream(configFile);
                    FileOutputStream out = new FileOutputStream(bad);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = fis.read(buf)) != -1) out.write(buf, 0, n);
                    fis.close();
                    out.flush();
                    out.close();
                    android.util.Log.w("Magpie", "配置损坏，已另存为 " + bad.getName());
                }
            } catch (Throwable ignored) {
            }
            configJson = new JSONObject();
        }
    }

    /**
     * 一次性迁移：把老配置升级到「供应商带 status/plugin」的新形态，并修正几处已知错误的默认值。
     * 只动默认值，绝不动用户已入池的账号与 Key。
     */
    private void migrateDeepSeekWeb() {
        JSONArray providers = configJson.optJSONArray("providers");
        for (int i=0; providers!=null && i<providers.length(); i++) {
            JSONObject p=providers.optJSONObject(i);
            if (p==null || !"deepseek_web".equals(p.optString("id"))) continue;
            boolean placeholder = "placeholder".equals(p.optString("status"));
            boolean staleNote = "deepseek-web".equals(p.optString("protocol"))
                    && (p.optString("note", "").contains("只有 WebView 登录壳")
                        || p.optString("note", "").equals("社区 DeepSeek 网页模式；网页登录自动入池，仅支持文本，不支持工具、图片和输出 Token 限额"));
            if (!placeholder && !staleNote) continue;
            try {
                if (placeholder) {
                    p.put("protocol", "deepseek-web"); p.put("status", "ported");
                    JSONArray models=new JSONArray(); for (String m:DeepSeekWeb.MODELS) models.put(m);
                    p.put("models", models);
                }
                p.put("note", "社区 DeepSeek 网页模式；网页登录自动入池，支持文本与提示词工具桥，不支持图片；客户端输出限额不保证强制执行");
                saveConfig(configJson);
            } catch (Exception ignored) { }
        }
    }

    private void migrateProviders() {
        if (configJson.optInt("schemaVersion", 1) >= 4) return;
        try {
            InputStream is = context.getAssets().open("default_config.json");
            byte[] bytes = new byte[is.available()];
            is.read(bytes);
            is.close();
            JSONObject defaults = new JSONObject(new String(bytes, "UTF-8"));
            JSONArray defaultProvs = defaults.optJSONArray("providers");
            JSONArray cur = configJson.optJSONArray("providers");
            if (defaultProvs == null || cur == null) return;

            // 默认表里每家能提供 status / plugin / note
            java.util.Map<String, JSONObject> defMap = new java.util.HashMap<String, JSONObject>();
            for (int i = 0; i < defaultProvs.length(); i++) {
                JSONObject d = defaultProvs.optJSONObject(i);
                if (d != null) defMap.put(d.optString("id", ""), d);
            }

            boolean changed = false;
            for (int i = 0; i < cur.length(); i++) {
                JSONObject p = cur.optJSONObject(i);
                if (p == null) continue;
                String id = p.optString("id", "");
                JSONObject d = defMap.get(id);
                if (d == null) continue;

                if (!p.has("status")) {
                    p.put("status", d.optString("status", "passthrough"));
                    changed = true;
                }
                // 名字也一并对齐（老配置里还叫「CatPaw (美团爪子 AI)」「Loomy AI」）
                if (("catpaw".equals(id) || "loomy".equals(id)) && d.has("name")) {
                    if (!p.optString("name", "").equals(d.optString("name"))) {
                        p.put("name", d.optString("name"));
                        changed = true;
                    }
                }
                if (!p.has("plugin") && d.has("plugin")) {
                    p.put("plugin", d.optString("plugin", ""));
                    changed = true;
                }

                // zcode：老默认写的是 api.z.ai + anthropic，那一组在本机 TLS 直接被切断
                if ("cline".equals(id)) {
                    p.put("status", "ported");
                    p.put("plugin", "packages/cline");
                    p.put("baseUrl", "https://api.cline.bot/api/v1");
                    p.put("protocol", "cline");
                    p.put("note", "WorkOS 设备流 / API Key；OpenAI 兼容；推荐模型免登录");
                    changed = true;
                }
                if ("zcode".equals(id)) {
                    String base = p.optString("baseUrl", "");
                    if (base.contains("api.z.ai") || "anthropic".equals(p.optString("protocol", ""))) {
                        p.put("baseUrl", d.optString("baseUrl"));
                        p.put("protocol", d.optString("protocol"));
                        p.put("models", new JSONArray(d.optJSONArray("models").toString()));
                        changed = true;
                    }
                }

                // catpaw：老配置是「打开 ai.catpaw.meituan.com 抓 Cookie 直连 /api/v1」的壳
                if ("catpaw".equals(id) && !"catpaw".equals(p.optString("protocol", ""))) {
                    p.put("baseUrl", d.optString("baseUrl"));
                    p.put("protocol", "catpaw");
                    p.put("models", new JSONArray(d.optJSONArray("models").toString()));
                    p.put("status", "ported");
                    changed = true;
                }

                // loomy：老默认域名 api.loomy.ai 是错的
                if ("loomy".equals(id)) {
                    String base = p.optString("baseUrl", "");
                    if (base.contains("api.loomy.ai")) {
                        p.put("baseUrl", d.optString("baseUrl"));
                        p.put("loginUrl", d.optString("loginUrl"));
                        p.put("models", new JSONArray(d.optJSONArray("models").toString()));
                        changed = true;
                    }
                }
            }

            // 老的 trae 壳（api.trae.ai/v1）没有任何凭据时直接删掉，由默认表的 trae-cn 顶替
            for (int i = cur.length() - 1; i >= 0; i--) {
                JSONObject p = cur.optJSONObject(i);
                if (p == null) continue;
                if (!"trae".equals(p.optString("id", ""))) continue;
                JSONArray accs = p.optJSONArray("accounts");
                JSONArray keys = p.optJSONArray("keys");
                int n = (accs == null ? 0 : accs.length()) + (keys == null ? 0 : keys.length());
                if (n == 0) {
                    cur.remove(i);
                    changed = true;
                }
            }

            configJson.put("schemaVersion", 4);
            if (changed) saveConfig(configJson);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 启动时自动合并内置默认供应商：补齐老版本配置中缺失的全部 Provider（绝不清空用户已有账号与密钥）
    private void mergeDefaultProviders() {
        try {
            InputStream is = context.getAssets().open("default_config.json");
            byte[] bytes = new byte[is.available()];
            is.read(bytes);
            is.close();
            JSONObject defaults = new JSONObject(new String(bytes, "UTF-8"));
            JSONArray defaultProvs = defaults.optJSONArray("providers");
            if (defaultProvs == null) return;

            JSONArray currentProvs = configJson.optJSONArray("providers");
            if (currentProvs == null) {
                currentProvs = new JSONArray();
                configJson.put("providers", currentProvs);
            }

            boolean changed = false;
            for (int i = 0; i < defaultProvs.length(); i++) {
                JSONObject defProv = defaultProvs.optJSONObject(i);
                if (defProv == null) continue;
                String defId = defProv.optString("id", "");
                if (defId.isEmpty()) continue;

                boolean exists = false;
                for (int j = 0; j < currentProvs.length(); j++) {
                    JSONObject cur = currentProvs.optJSONObject(j);
                    if (cur != null && defId.equals(cur.optString("id"))) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    JSONObject clone = new JSONObject(defProv.toString());
                    clone.put("accounts", new JSONArray());
                    clone.put("keys", new JSONArray());
                    currentProvs.put(clone);
                    changed = true;
                }
            }

            if (changed) {
                saveConfig(configJson);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 启动时恢复上次探测选出的 Antigravity base（否则重启后又回到默认 daily） */
    private void restoreAntigravityBases() {
        try {
            JSONObject ag = getProviderById("antigravity");
            if (ag == null) return;
            String chat = ag.optString("probeChatBase", "");
            String load = ag.optString("probeLoadBase", "");
            if (chat.isEmpty() && load.isEmpty()) return;
            AntigravityAuth.applyBases(chat, load);
            android.util.Log.d("Magpie", "antigravity base 恢复: chat=" + AntigravityAuth.chatBase
                    + " load=" + AntigravityAuth.loadBase);
        } catch (Throwable ignored) {
        }
    }

    /** 组装守护进程状态：是否在跑、最近解冻、心跳新鲜度 */
    private JSONObject buildWatchdogStatus() {
        JSONObject out = new JSONObject();
        try {
            JSONObject st = watchdogState;
            long now = System.currentTimeMillis();
            long lastBeat = st.optLong("lastBeatAt", 0L);
            long lastUnfreeze = st.optLong("lastUnfreezeAt", 0L);
            out.put("unfreezeCount", st.optInt("unfreezeCount", 0));
            out.put("lastUnfreezeAt", lastUnfreeze);
            out.put("lastBeatAt", lastBeat);
            out.put("lastBeatPid", st.optString("lastBeatPid", ""));
            // 心跳超过 15 分钟没来，认为守护进程已停止（脚本 5 分钟一次心跳，
            // 留 3 倍余量容忍设备休眠/短暂卡顿）
            boolean alive = lastBeat > 0 && (now - lastBeat) < 15L * 60L * 1000L;
            out.put("alive", alive);
            out.put("beatAgeSeconds", lastBeat > 0 ? (now - lastBeat) / 1000L : -1);
            out.put("unfreezeAgeSeconds", lastUnfreeze > 0 ? (now - lastUnfreeze) / 1000L : -1);
            JSONArray arr = new JSONArray();
            synchronized (this) {
                for (int i = 0; i < watchdogLog.size() && i < 10; i++) {
                    arr.put(watchdogLog.get(i));
                }
            }
            out.put("recent", arr);
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 写配置：先写 config.json.tmp 并 fsync，再 rename 覆盖。
     * 原实现直接写目标文件，进程写一半被杀就留半截 JSON，下次启动解析失败退化成空配置，
     * 再随便一写就把账号全丢了。rename 在同一目录内是原子的（要么旧文件，要么新文件）。
     */
    private synchronized void saveConfig(JSONObject newConfig) {
        if (newConfig == null) return;
        this.configJson = newConfig;
        File tmp = new File(configFile.getParentFile(), "config.json.tmp");
        FileOutputStream fos = null;
        try {
            byte[] data = newConfig.toString(2).getBytes("UTF-8");
            fos = new FileOutputStream(tmp, false);
            fos.write(data);
            fos.flush();
            try {
                fos.getFD().sync();
            } catch (Throwable ignored) {
            }
            fos.close();
            fos = null;
            if (!tmp.renameTo(configFile)) {
                // 个别文件系统不允许 rename 覆盖：退化成流式复制
                FileInputStream fis = new FileInputStream(tmp);
                FileOutputStream out = new FileOutputStream(configFile, false);
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) != -1) out.write(buf, 0, n);
                fis.close();
                out.flush();
                try {
                    out.getFD().sync();
                } catch (Throwable ignored) {
                }
                out.close();
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception e) {
            android.util.Log.e("Magpie", "保存配置失败: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private String readFileToString(File file) throws Exception {
        FileInputStream fis = new FileInputStream(file);
        // 必须循环读：单次 read() 不保证填满缓冲区，长配置会被截断成半截 JSON
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = fis.read(buf)) != -1) bo.write(buf, 0, n);
        fis.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    // ===== 访问控制 =====
    //
    // 之前 authKey 只是个摆设：全项目没有一处读它，/api/config 又是明文返回全部
    // token（WorkBuddy / 小浣熊 / ZCode API Key / ZCode session JWT / Qoder 设备令牌），
    // 加上 Access-Control-Allow-Origin: *，同一 WiFi 下任何人 curl 一下就能拿走。
    // 更糟的是 POST /api/config 直接落盘，等于别人能改我的 baseUrl 把请求引走。
    //
    // 现在：/api/* 与 /v1/* 一律要凭据；面板通过 #key= 注入，其它客户端照常带
    // Authorization: Bearer <authKey> 或 x-api-key。

    /** 读 body 时的错误（超限等），供 serve 返回 413 而不是静默空 body */
    private static final ThreadLocal<String> BODY_ERROR = new ThreadLocal<String>();

    /** 当前请求的 Origin，供 addCorsHeaders 回显（NanoHTTPD 每次请求一个线程，覆盖式 set 即可） */
    private static final ThreadLocal<String> CURRENT_ORIGIN = new ThreadLocal<String>();

    /**
     * 路由组是否启用。默认关闭：用户不选组时应该直接挑具体模型，
     * 而不是被「虚拟模型」悄悄接管；需要时在面板「路由」页打开。
     */
    private boolean groupsEnabled() {
        try {
            JSONObject routing = configJson.optJSONObject("routing");
            return routing != null && routing.optBoolean("groupsEnabled", false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 该路径是否属于需要鉴权的接口面 */
    private static boolean needsAuth(String uri) {
        if (uri == null) return false;
        // /backend-api/：Codex 原生端点，之前不在鉴权面内，等于同网段能白用订阅额度
        return uri.startsWith("/api/") || uri.startsWith("/v1/") || uri.startsWith("/backend-api/");
    }

    /** 首启向导的状态：要不要跑向导、当前口令是不是弱口令、权限与自检此刻的真实情况 */
    private JSONObject buildSetupStatus() throws org.json.JSONException {
        JSONObject ret = new JSONObject();
        ret.put("ok", true);

        JSONObject setup = configJson == null ? null : configJson.optJSONObject("setup");
        boolean done = setup != null && setup.optBoolean("done", false);
        String key = configuredAuthKey();
        boolean weak = key.isEmpty() || key.length() < 8 || "magpie".equalsIgnoreCase(key);

        ret.put("needed", !done);
        ret.put("done", done);
        if (setup != null) ret.put("doneAt", setup.optLong("doneAt", 0));
        ret.put("authKeyIsWeak", weak);
        ret.put("authKeyLength", key.length());
        ret.put("authKeyIsDefault", "magpie".equalsIgnoreCase(key));
        ret.put("port", getListeningPort());
        ret.put("ip", getLocalIpAddress());
        try {
            ret.put("permissions", PermissionCenter.status(context));
        } catch (Throwable ignored) {
        }
        try {
            ret.put("keepalive", KeepAliveScheduler.status(context));
        } catch (Throwable ignored) {
        }
        try {
            ret.put("watchdog", buildWatchdogStatus());
        } catch (Throwable ignored) {
        }
        return ret;
    }

    /** 配置里的网关口令；没配（或空）时返回空串，表示不拦 */
    private String configuredAuthKey() {
        try {
            JSONObject server = configJson == null ? null : configJson.optJSONObject("server");
            if (server == null) return "";
            return server.optString("authKey", "").trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** 请求携带的凭据：Authorization: Bearer x / x-api-key / ?key= */
    private static String presentedKey(IHTTPSession session) {
        try {
            Map<String, String> h = session.getHeaders();
            if (h != null) {
                String auth = h.get("authorization");
                if (auth != null) {
                    String t = auth.trim();
                    if (t.toLowerCase().startsWith("bearer ")) return t.substring(7).trim();
                    if (!t.isEmpty()) return t;
                }
                String xk = h.get("x-api-key");
                if (xk != null && !xk.trim().isEmpty()) return xk.trim();
            }
            Map<String, List<String>> p = session.getParameters();
            if (p != null && p.get("key") != null && !p.get("key").isEmpty()) {
                String k = p.get("key").get(0);
                if (k != null) return k.trim();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private boolean authorized(IHTTPSession session, String uri) {
        if (!needsAuth(uri)) return true;
        if (session != null && "OPTIONS".equalsIgnoreCase(session.getMethod().name())) return true;
        String expected = configuredAuthKey();
        String presented = presentedKey(session);
        // 口令非空时必须匹配；比较用恒定时间实现，避免逐字节时序差
        if (!expected.isEmpty() && constantTimeEquals(expected, presented)) return true;

        // 免口令只保留「真正的本机回路」：面板 WebView 与 LocalBridge 都从 127.0.0.1 出发。
        // 原来还有两个旁路，实测都能被外部利用，已删除：
        //   1) remoteIp 以 10./172.19./198.18. 开头 —— 等于放行整个私网；
        //   2) Origin 为 null / 含 127.0.0.1 —— Origin 是客户端自己写的请求头。
        try {
            String remoteIp = session.getRemoteIpAddress();
            if (remoteIp != null) {
                String ip = remoteIp.trim();
                if (ip.startsWith("::ffff:")) ip = ip.substring(7);
                if ("127.0.0.1".equals(ip) || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 恒定时间比较口令（长度不同也走完比较） */
    private static boolean constantTimeEquals(String a, String b) {
        try {
            return java.security.MessageDigest.isEqual(
                    a.getBytes("UTF-8"), b == null ? new byte[0] : b.getBytes("UTF-8"));
        } catch (Exception e) {
            return false;
        }
    }

    /** Origin 是否允许读取响应（file:// 面板的 Origin 是 "null"；其余只放本机页面） */
    private static boolean originAllowed(String origin) {
        if (origin == null) return true;           // 非浏览器请求（curl / SDK）没有 Origin
        String o = origin.trim();
        if (o.isEmpty() || "null".equalsIgnoreCase(o) || o.startsWith("file:") || o.startsWith("content:")) return true;
        try {
            java.net.URI u = java.net.URI.create(o);
            String host = u.getHost();
            if (host == null) return true; // file:// 等协议 host 为 null，放行
            return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
        } catch (Exception e) {
            return true;
        }
    }

    /** 鉴权失败的统一回包 */
    private Response unauthorizedResponse() {
        JSONObject err = new JSONObject();
        try {
            err.put("error", "需要网关口令");
            err.put("hint", "请求头带上 Authorization: Bearer <authKey>（或 x-api-key），"
                    + "或在 URL 加 ?key=<authKey>。口令见面板「网关设置」里的 server.authKey。");
        } catch (Exception ignored) {
        }
        Response resp = newFixedLengthResponse(Status.UNAUTHORIZED, "application/json; charset=UTF-8", err.toString());
        addCorsHeaders(resp);
        return resp;
    }

    public static String getLocalIpAddress() {
        // 以前的实现直接返回第一个非回环 IPv4，开着 Clash 时会先命中 tun0(172.19.0.1)，
        // 面板「服务地址」就是错的，电脑根本连不上。
        // 现在按可用性打分：
        //   0 = Wi-Fi 局域网地址（192.168/10/172.16-31，且不是 tun/tap），最想让用户看到
        //   1 = 蜂窝网络 IPv4（100.x 等），仅本机/同设备可用
        //   2 = VPN 隧道地址（tun0），最后才用
        String best = null;
        int bestScore = Integer.MAX_VALUE;
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                try {
                    if (intf.isLoopback()) continue;
                } catch (Exception ignored) {
                }
                String nm = intf.getName() == null ? "" : intf.getName().toLowerCase();
                boolean tunnel = nm.startsWith("tun") || nm.startsWith("tap") || nm.startsWith("ip6")
                        || nm.startsWith("sit") || nm.startsWith("dummy") || nm.startsWith("ifb");
                boolean cellular = nm.startsWith("ccmni") || nm.startsWith("rmnet") || nm.startsWith("pdp")
                        || nm.startsWith("wwan");
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (inetAddress.isLoopbackAddress() || !(inetAddress instanceof Inet4Address)) continue;
                    if (inetAddress.isLinkLocalAddress()) continue;
                    String ip = inetAddress.getHostAddress();
                    if (ip == null || ip.isEmpty()) continue;
                    boolean privateIp = ip.startsWith("192.168.") || ip.startsWith("10.")
                            || ip.startsWith("172.16.") || ip.startsWith("172.17.") || ip.startsWith("172.18.")
                            || ip.startsWith("172.19.") || ip.startsWith("172.2") || ip.startsWith("172.30.")
                            || ip.startsWith("172.31.");
                    int score;
                    if (tunnel) score = 2;
                    else if (privateIp) score = 0;
                    else if (cellular) score = 1;
                    else score = 1;
                    // 192.168 段是家用/办公 Wi-Fi 最常见形态，同分时优先
                    if (score < bestScore || (score == bestScore && ip.startsWith("192.168."))) {
                        bestScore = score;
                        best = ip;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return best == null ? "127.0.0.1" : best;
    }

    @Override
    public Response serve(IHTTPSession session) {
        NATIVE_REQUEST.remove();
        NATIVE_BASELINE.remove();
        NATIVE_DIRECT_ALLOWED.remove();
        try { return serveRequest(session); }
        finally { NATIVE_REQUEST.remove(); NATIVE_BASELINE.remove(); NATIVE_DIRECT_ALLOWED.remove(); }
    }

    private Response serveRequest(IHTTPSession session) {
        String uri = session.getUri();
        String method = session.getMethod().name();
        // 关键：NanoHTTPD 的 parseBody 在 Content-Type 未声明 charset 时按 US-ASCII 解码，
        // 中文会被逐字节替换成 U+FFFD。这里直接读原始字节按 UTF-8 解码，避免内容损坏。
        CLIENT_ANTHROPIC.set(Boolean.FALSE);
        CLIENT_MODEL.set(null);

        // 记录来源，供 addCorsHeaders 回显（每请求覆盖式 set，NanoHTTPD 一请求一线程）
        try {
            Map<String, String> hs = session.getHeaders();
            CURRENT_ORIGIN.set(hs == null ? null : hs.get("origin"));
        } catch (Exception ignored) {
            CURRENT_ORIGIN.set(null);
        }

        // 会话标识（用于「按会话」统计），必须在转发前就位
        try {
            U_SESSION.set(pickSession(session.getHeaders()));
        } catch (Throwable ignored) {
            U_SESSION.remove();
        }

        // 关键：CORS 预检 OPTIONS 请求无条件优先放行，绝不能被鉴权拦截或读 body 阻塞！
        if ("OPTIONS".equalsIgnoreCase(method)) {
            Response resp = newFixedLengthResponse(Status.NO_CONTENT, "text/plain", "");
            addCorsHeaders(resp);
            return resp;
        }

        // 鉴权闸门放在读 body 之前：未授权请求不该先把内存吃掉
        if (!authorized(session, uri)) return unauthorizedResponse();

        final String rawBody = readUtf8Body(session);
        String bodyErr = BODY_ERROR.get();
        if (bodyErr != null && !bodyErr.isEmpty()) {
            return errorResponse(413, bodyErr);
        }

        try {
            // 0. Codex 原生 Responses / Models 端点直通拦截
            if (uri.startsWith("/backend-api/codex")) {
                return handleCodexNative(session, uri, method, session.getHeaders(), rawBody);
            }

            // 0.05 Copilot 的 Responses 直通：gpt-5.4-nano 这类模型只在 /responses 上可用
            if (uri.endsWith("/responses") && "POST".equalsIgnoreCase(method)) {
                Response cop = handleCopilotResponses(uri, session.getHeaders(), rawBody);
                if (cop != null) return cop;
            }

            // 0.1 本地 3425 端口上的 Codex 授权回调拦截
            if ("/auth/callback".equals(uri)) {
                return handleCodexHttpCallback(session);
            }

            // 1. Dashboard 首页（App 内直接以 http://127.0.0.1:3425/ 打开，规避 file:// 跨域被 Chromium 拦死）
            if ("/".equals(uri) || "/index.html".equals(uri) || "/dashboard.html".equals(uri)) {
                InputStream is = context.getAssets().open("dashboard.html");
                byte[] data = new byte[is.available()];
                is.read(data);
                is.close();
                Response resp = newFixedLengthResponse(Status.OK, "text/html; charset=UTF-8", new String(data, "UTF-8"));
                resp.addHeader("Cache-Control", "no-store");
                addCorsHeaders(resp);
                return resp;
            }

            // 供应商图标仅允许单层、受限字符的文件名。
            if (uri.startsWith("/provider-icons/")) {
                if ("GET".equals(method) && uri.matches("/provider-icons/[a-z0-9_-]+\\.(svg|png)")) {
                    try {
                        InputStream is = context.getAssets().open(uri.substring(1));
                        Response resp = newFixedLengthResponse(Status.OK,
                                uri.endsWith(".svg") ? "image/svg+xml" : "image/png", is, is.available());
                        resp.addHeader("Cache-Control", "public, max-age=86400");
                        resp.addHeader("X-Content-Type-Options", "nosniff");
                        addCorsHeaders(resp);
                        return resp;
                    } catch (java.io.IOException ignored) { }
                }
                Response resp = newFixedLengthResponse(Status.NOT_FOUND, "text/plain; charset=UTF-8", "Not Found");
                resp.addHeader("X-Content-Type-Options", "nosniff");
                addCorsHeaders(resp);
                return resp;
            }

            // 1.1 面板静态资源（图标等）
            if ("/icon.png".equals(uri) || "/favicon.ico".equals(uri) || "/favicon.png".equals(uri)) {
                try {
                    InputStream is = context.getAssets().open("icon.png");
                    Response resp = newFixedLengthResponse(Status.OK, "image/png", is, is.available());
                    resp.addHeader("Cache-Control", "public, max-age=86400");
                    addCorsHeaders(resp);
                    return resp;
                } catch (Throwable ignored) {
                }
            }

            // 2. 状态 API
            if ("/api/status".equals(uri)) {
                JSONObject status = new JSONObject();
                status.put("ok", true);
                status.put("port", getListeningPort());
                status.put("ip", getLocalIpAddress());
                status.put("totalRequests", totalRequests.get());
                status.put("totalTokens", totalTokens.get());
                status.put("totalMasked", totalMasked.get());
                status.put("contextDropped", totalContextDropped.get());
                status.put("guard", ContextGuard.Config.fromJson(configJson.optJSONObject("guard")).toJson());
                status.put("lastGuardStats", lastGuardStats);
                return jsonResponse(status);
            }

            // 2.4.0 隐私脱敏开关与真实状态
            // 一键对接 ETA：把「Base URL + 口令 + 推荐模型」一次给前端，
            // 用户复制后粘进 ETA 的提供商里就能用（ETA 会自己从 /v1/models 拉模型）
            if ("/api/eta/import".equals(uri)) {
                JSONObject ret = new JSONObject();
                String base = "http://127.0.0.1:3425/v1";
                String key = configuredAuthKey();
                String recommended = "";
                JSONArray modelsOut = new JSONArray();
                JSONArray provs = configJson.optJSONArray("providers");
                for (int i = 0; provs != null && i < provs.length(); i++) {
                    JSONObject p = provs.optJSONObject(i);
                    if (p == null || !providerHasCredential(p)) continue;
                    JSONArray ms = p.optJSONArray("models");
                    for (int k = 0; ms != null && k < ms.length(); k++) {
                        String ref = p.optString("id") + "/" + ms.optString(k);
                        if (modelsOut.length() < 40) modelsOut.put(ref);
                        if (recommended.isEmpty()) recommended = ref;
                        if ("workbuddy/glm-5.3".equals(ref)) recommended = ref;
                    }
                }
                ret.put("ok", true);
                ret.put("baseUrl", base);
                ret.put("apiKey", key);
                ret.put("recommendedModel", recommended);
                ret.put("models", modelsOut);
                ret.put("snippet", "Base URL: " + base + "\nAPI Key: "
                        + (key.isEmpty() ? "(未设置)" : key)
                        + (recommended.isEmpty() ? "" : "\n推荐模型: " + recommended));
                return jsonResponse(ret);
            }

            if ("/api/privacy".equals(uri)) {
                if ("POST".equalsIgnoreCase(method)) {
                    JSONObject req = (rawBody == null || rawBody.isEmpty())
                            ? new JSONObject() : new JSONObject(rawBody);
                    JSONObject patch = req.optJSONObject("privacy");
                    if (patch != null) {
                        PrivacyMasker.Config base = PrivacyMasker.Config.fromJson(configJson.optJSONObject("privacy"));
                        JSONObject merged = base.toJson();
                        java.util.Iterator<String> it = patch.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            if (patch.opt(k) != null) merged.put(k, patch.opt(k));
                        }
                        configJson.put("privacy", PrivacyMasker.Config.fromJson(merged).toJson());
                        saveConfig(configJson);
                    }
                }
                PrivacyMasker.Config cfg = PrivacyMasker.Config.fromJson(configJson.optJSONObject("privacy"));
                JSONObject ret = new JSONObject();
                ret.put("ok", true);
                ret.put("privacy", cfg.toJson());
                JSONObject live = new JSONObject();
                live.put("totalMasked", totalMasked.get());
                live.put("lastMaskedCount", lastMaskedCount);
                live.put("lastEnabled", lastMaskEnabled);
                live.put("lastAt", lastMaskAt);
                ret.put("live", live);
                return jsonResponse(ret);
            }

            // 2.4.1 权限与保活状态（供后台"权限管理"卡片展示）
            if ("/api/permissions".equals(uri)) {
                JSONObject ret = new JSONObject();
                ret.put("ok", true);
                JSONObject perm = PermissionCenter.status(context);
                // 本进程冻结标记：读得到才有意义，读不到返回 -1（由守护进程上报兜底）
                try {
                    perm.put("freezeFlag", selfFreezeFlag(context));
                } catch (Throwable ignored) {
                }
                ret.put("permissions", perm);
                // 顺带把当前服务是否真在前台也报出去，便于判断是否已掉到后台
                JSONObject svc = new JSONObject();
                svc.put("running", GatewayService.isRunning());
                svc.put("port", getListeningPort());
                ret.put("service", svc);
                ret.put("watchdog", buildWatchdogStatus());
                // 非 root 保活的真实状态：闹钟排了没、上次自检什么时候、重启过几次
                try {
                    ret.put("keepalive", KeepAliveScheduler.status(context));
                } catch (Throwable ignored) {
                }
                return jsonResponse(ret);
            }

            // 2.4.1.1 用户确认自启动 / 省电策略
            // 这两项在 MIUI 上没有可读接口，只能让用户自己确认一次并记下来。
            if ("/api/permissions/ack".equals(uri) && "POST".equalsIgnoreCase(method)) {
                JSONObject req;
                try {
                    req = new JSONObject(rawBody == null ? "{}" : rawBody);
                } catch (Exception e) {
                    return errorResponse(400, "请求体不是合法 JSON");
                }
                String target = req.optString("target", "").trim();
                boolean on = req.optBoolean("ack", true);
                if (!"autostart".equals(target) && !"powersave".equals(target)) {
                    return errorResponse(400, "target 只能是 autostart 或 powersave");
                }
                boolean ok = PermissionCenter.setAck(context, target, on);
                JSONObject ret = new JSONObject();
                ret.put("ok", ok);
                ret.put("target", target);
                ret.put("acked", on);
                ret.put("permissions", PermissionCenter.status(context));
                return jsonResponse(ret);
            }

            // 2.4.2 防冻结守护进程上报（由 watchdog 脚本在解冻成功 / 定时心跳时调用）
            if ("/api/watchdog/report".equals(uri) && "POST".equalsIgnoreCase(method)) {
                JSONObject req = new JSONObject(rawBody == null ? "{}" : rawBody);
                String event = req.optString("event", "beat");
                long now = System.currentTimeMillis();
                synchronized (this) {
                    try {
                        if ("unfreeze".equals(event)) {
                            // count 是守护进程攒下的未上报次数，补报时一次计入，避免丢事件
                            int add = req.optInt("count", 1);
                            if (add < 1) add = 1;
                            if (add > 500) add = 500;
                            int c = watchdogState.optInt("unfreezeCount", 0) + add;
                            watchdogState.put("unfreezeCount", c);
                            watchdogState.put("lastUnfreezeAt", now);
                            for (int k = 0; k < add && k < 3; k++) {
                                JSONObject rec = new JSONObject();
                                rec.put("at", now);
                                rec.put("pid", req.optString("pid", ""));
                                watchdogLog.add(0, rec);
                            }
                            while (watchdogLog.size() > 20) watchdogLog.remove(watchdogLog.size() - 1);
                        }
                        watchdogState.put("lastBeatAt", now);
                        watchdogState.put("lastBeatPid", req.optString("pid", ""));
                        saveWatchdogState();
                    } catch (Throwable ignored) {
                    }
                }
                JSONObject res = new JSONObject();
                res.put("ok", true);
                return jsonResponse(res);
            }

            // 2.4.3 防冻结守护状态查询
            if ("/api/watchdog".equals(uri)) {
                JSONObject ret = new JSONObject();
                ret.put("ok", true);
                ret.put("watchdog", buildWatchdogStatus());
                return jsonResponse(ret);
            }

            // 2.5 配额与用量看板（本地统计）
            // 用量账本：真实 per-request 记账（口径对齐上游 usage.Totals）
            if (uri.startsWith("/api/usage")) {
                Map<String, List<String>> up = session.getParameters();
                int udays = 7;
                try {
                    if (up.containsKey("days") && !up.get("days").isEmpty()) {
                        udays = Integer.parseInt(up.get("days").get(0));
                    }
                } catch (Exception ignored) {
                }
                if (udays < 1) udays = 1;
                if (udays > 365) udays = 365;
                String ugroup = (up.containsKey("group") && !up.get("group").isEmpty())
                        ? up.get("group").get(0) : "model";

                if ("/api/usage/csv".equals(uri)) {
                    String csv = UsageLedger.csv(udays);
                    Response r = newFixedLengthResponse(Status.OK, "text/csv; charset=UTF-8", csv);
                    r.addHeader("Content-Disposition", "attachment; filename=\"usage-" + udays + "d.csv\"");
                    addCorsHeaders(r);
                    return r;
                }
                return jsonResponse(UsageLedger.summary(udays, ugroup));
            }

            if ("/api/quota".equals(uri)) {
                JSONObject q = new JSONObject();
                q.put("hours", 24);
                q.put("since", System.currentTimeMillis() - 86400000L);
                JSONObject totals = new JSONObject();
                // 请求数与 token 用账本 24 小时窗口（内存计数器重启即归零，两个口径对不上）
                long[] win = UsageLedger.windowTotals(1);
                totals.put("requests", win[0]);
                totals.put("tokens", win[1]);
                totals.put("failed", win[2]);
                totals.put("masked", totalMasked.get());
                totals.put("contextDropped", totalContextDropped.get());
                q.put("totals", totals);
                // 供应商维度与时段分布来自用量账本（原先硬编码空数组，面板那两块永远是「暂无记录」）
                q.put("providers", UsageLedger.providerStats(1));
                q.put("hourly", UsageLedger.hourlyStats(1));

                JSONArray provCfg = new JSONArray();
                JSONArray providers = configJson.optJSONArray("providers");
                if (providers != null) {
                    for (int i = 0; i < providers.length(); i++) {
                        JSONObject p = providers.optJSONObject(i);
                        if (p == null) continue;
                        JSONObject c = new JSONObject();
                        c.put("id", p.optString("id"));
                        c.put("name", p.optString("name"));
                        c.put("enabled", p.optBoolean("enabled", true));
                        c.put("accounts", p.optJSONArray("accounts") == null ? 0 : p.optJSONArray("accounts").length());
                        c.put("keys", p.optJSONArray("keys") == null ? 0 : p.optJSONArray("keys").length());
                        c.put("models", p.optJSONArray("models") == null ? 0 : p.optJSONArray("models").length());
                        provCfg.put(c);
                    }
                }
                q.put("providerConfig", provCfg);
                return jsonResponse(q);
            }

            // 2.6 最近请求明细
            if ("/api/sessions".equals(uri)) {
                Map<String, List<String>> sp = session.getParameters();
                int limit = 12;
                try {
                    if (sp.containsKey("limit") && !sp.get("limit").isEmpty()) {
                        limit = Integer.parseInt(sp.get("limit").get(0));
                    }
                } catch (Exception ignored) {
                }
                if (limit < 1) limit = 1;
                if (limit > 50) limit = 50;
                JSONObject out = new JSONObject();
                out.put("sessions", UsageLedger.recentRequests(limit));
                return jsonResponse(out);
            }

            // 2.7 网关中间件配置
            // 中间件沙盒：直接把一段文本喂进去看处理结果，不消耗任何上游额度。
            // 用来验证 think-tags 与 word-guard 在真实请求之前到底会怎么改内容。
            if ("/api/middleware/test".equals(uri) && "POST".equalsIgnoreCase(method)) {
                JSONObject ret = new JSONObject();
                JSONObject req;
                try {
                    req = new JSONObject(rawBody == null || rawBody.trim().isEmpty() ? "{}" : rawBody);
                } catch (Exception e) {
                    return errorResponse(400, "请求体不是合法 JSON");
                }
                JSONObject mw = configJson.optJSONObject("middleware");
                if (mw == null) mw = new JSONObject();

                String text = req.optString("text", "");
                String reasoning = req.optString("reasoning", "");

                // think-tags
                String[] fixed = Middleware.applyThinkTags(text, reasoning, mw);
                JSONObject thinkOut = new JSONObject();
                thinkOut.put("enabled", Middleware.thinkTagsEnabled(mw));
                thinkOut.put("text", fixed[0]);
                thinkOut.put("reasoning", fixed[1]);
                ret.put("thinkTags", thinkOut);

                // word-guard
                JSONObject guardOut = new JSONObject();
                guardOut.put("enabled", Middleware.wordGuardEnabled(mw));
                String hit = Middleware.hitWord(text, mw);
                guardOut.put("hit", hit == null ? "" : hit);
                guardOut.put("blocked", hit != null && Middleware.wordGuardBlocks(mw));
                guardOut.put("masked", Middleware.maskWords(text, mw));
                ret.put("wordGuard", guardOut);

                // param-override / system-prompt：用一份最小请求体跑一遍给用户看
                JSONObject sample = new JSONObject();
                sample.put("model", "demo");
                sample.put("max_tokens", 128000);
                sample.put("temperature", 1.0);
                JSONArray demoMsgs = new JSONArray();
                JSONObject um = new JSONObject();
                um.put("role", "user");
                um.put("content", text.isEmpty() ? "你好" : text);
                demoMsgs.put(um);
                sample.put("messages", demoMsgs);

                JSONObject overrideOut = new JSONObject();
                boolean overridden = Middleware.applyParamOverride(sample, req.optString("provider", ""), mw);
                overrideOut.put("changed", overridden);
                if (sample.has("max_tokens")) overrideOut.put("max_tokens", sample.opt("max_tokens"));
                if (sample.has("temperature")) overrideOut.put("temperature", sample.opt("temperature"));
                ret.put("paramOverride", overrideOut);

                boolean injected = Middleware.applySystemPrompt(sample, req.optString("provider", ""), mw);
                JSONObject sysOut = new JSONObject();
                sysOut.put("injected", injected);
                JSONArray after = sample.optJSONArray("messages");
                if (after != null && after.length() > 0) {
                    JSONObject first = after.optJSONObject(0);
                    if (first != null && "system".equals(first.optString("role"))) {
                        sysOut.put("system", first.optString("content", ""));
                    }
                }
                ret.put("systemPrompt", sysOut);

                ret.put("ok", true);
                return jsonResponse(ret);
            }

            if ("/api/middleware".equals(uri)) {
                if ("POST".equalsIgnoreCase(method)) {
                    String postData = rawBody;
                    JSONObject req = (postData != null && !postData.isEmpty()) ? new JSONObject(postData) : new JSONObject();
                    configJson.put("middleware", req);
                    saveConfig(configJson);
                    JSONObject ret = new JSONObject();
                    ret.put("ok", true);
                    ret.put("middleware", req);
                    return jsonResponse(ret);
                }
                JSONObject ret = new JSONObject();
                ret.put("ok", true);
                ret.put("middleware", configJson.optJSONObject("middleware") != null
                        ? configJson.optJSONObject("middleware") : new JSONObject());
                return jsonResponse(ret);
            }

            // 2.8 网关上下文保护与回退策略（用户可自由设置）
            if ("/api/guard".equals(uri)) {
                if ("POST".equalsIgnoreCase(method)) {
                    String postData = rawBody;
                    JSONObject req = (postData != null && !postData.isEmpty()) ? new JSONObject(postData) : new JSONObject();

                    if (req.optJSONObject("guard") != null) {
                        configJson.put("guard", mergedGuardConfig(req.optJSONObject("guard")).toJson());
                    }
                    if (req.optJSONObject("failover") != null) {
                        configJson.put("failover", req.optJSONObject("failover"));
                    }
                    saveConfig(configJson);

                    JSONObject ret = new JSONObject();
                    ret.put("ok", true);
                    ret.put("guard", ContextGuard.Config.fromJson(configJson.optJSONObject("guard")).toJson());
                    ret.put("failover", failoverJson());
                    return jsonResponse(ret);
                }
                JSONObject guard = ContextGuard.Config.fromJson(configJson.optJSONObject("guard")).toJson();
                guard.put("failover", failoverJson());
                // 当前正在「休息」的候选（运行时降级的记忆），便于排查为什么某些目标被排到后面
                JSONArray restingArr = new JSONArray();
                long nowMs = System.currentTimeMillis();
                for (java.util.Map.Entry<String, long[]> e : RESTS.entrySet()) {
                    long[] v = e.getValue();
                    if (v[0] <= nowMs) continue;
                    JSONObject r = new JSONObject();
                    r.put("target", e.getKey());
                    r.put("status", v[1]);
                    r.put("restMs", v[0] - nowMs);
                    restingArr.put(r);
                }
                JSONObject foState = failoverJson();
                foState.put("resting", restingArr);
                guard.put("failover", foState);
                guard.put("lastStats", lastGuardStats);
                JSONObject ret = new JSONObject();
                ret.put("ok", true);
                ret.put("guard", guard);
                return jsonResponse(ret);
            }

            // 2.8.1 上下文裁剪预览：用一段样例对话测试当前参数，不发起上游请求
            if ("/api/guard/preview".equals(uri) && "POST".equalsIgnoreCase(method)) {
                String postData = rawBody;
                JSONObject req = (postData != null && !postData.isEmpty()) ? new JSONObject(postData) : new JSONObject();
                JSONObject sample = req.optJSONObject("payload");
                if (sample == null) sample = buildGuardSamplePayload();
                ContextGuard.Config cfg = req.optJSONObject("guard") != null
                        ? mergedGuardConfig(req.optJSONObject("guard"))
                        : ContextGuard.Config.fromJson(configJson.optJSONObject("guard"));
                ContextGuard.Result result = ContextGuard.fit(sample, cfg);
                JSONArray beforeArr = sample.optJSONArray("messages");
                JSONArray afterArr = result.payload.optJSONArray("messages");
                JSONObject ret = new JSONObject();
                ret.put("ok", true);
                ret.put("result", result.toJson());
                ret.put("messagesBefore", beforeArr == null ? 0 : beforeArr.length());
                ret.put("messagesAfter", afterArr == null ? 0 : afterArr.length());
                return jsonResponse(ret);
            }

            // 3. 配置 API
            if ("/api/config".equals(uri)) {
                if ("POST".equalsIgnoreCase(method)) {
                    JSONObject newConf;
                    try {
                        newConf = new JSONObject(rawBody);
                    } catch (Exception pe) {
                        return errorResponse(400, "配置不是合法 JSON，已拒绝写入");
                    }
                    // 最小 schema 校验：没有 providers 数组的一律拒绝，避免一个 {} 就把账号池清空
                    if (newConf.optJSONArray("providers") == null) {
                        return errorResponse(400, "配置缺少 providers 数组，已拒绝写入");
                    }
                    synchronized (this) {
                    // Full config snapshots can be stale. DeepSeek accounts are managed by
                    // auth/add and auth/remove; retain the current authoritative pool.
                    preserveDeepSeekAccounts(configJson,newConf);
                    // 顺手保护口令：前端只提交自己知道的部分时，保留原来的 server / guard / privacy
                    JSONObject oldServer = configJson.optJSONObject("server");
                    JSONObject newServer = newConf.optJSONObject("server");
                    if (newServer == null) {
                        if (oldServer != null) newConf.put("server", new JSONObject(oldServer.toString()));
                    } else if (oldServer != null) {
                        String oldKey = oldServer.optString("authKey", "").trim();
                        String newKey = newServer.optString("authKey", "").trim();
                        if (newKey.isEmpty() && !oldKey.isEmpty()) newServer.put("authKey", oldKey);
                    }
                    if (newConf.optJSONObject("guard") == null && configJson.optJSONObject("guard") != null) {
                        newConf.put("guard", new JSONObject(configJson.optJSONObject("guard").toString()));
                    }
                    if (newConf.optJSONObject("privacy") == null && configJson.optJSONObject("privacy") != null) {
                        newConf.put("privacy", new JSONObject(configJson.optJSONObject("privacy").toString()));
                    }
                    try { persistConfigStrict(newConf); }
                    catch(IOException e) { return errorResponse(500,"配置保存失败"); }
                    JSONObject ret = new JSONObject();
                    ret.put("ok", true);
                    return jsonResponse(ret);
                    }
                } else {
                    synchronized(this) { return jsonResponse(new JSONObject(configJson.toString())); }
                }
            }

            // 3.3 路由组：整体读取 / 保存
            //
            // 之前 groups 完全写死在 default_config.json 里，界面只读，
            // 用户想换顺序、加个自家账号的模型都做不到。
            // 现在整组交给前端编辑，一次提交整个数组（最简单也最不容易出现半更新状态）。
            // 模型白名单：挑好要暴露给客户端的供应商/模型，/v1/models 只返回这些
            if ("/api/exposed".equals(uri)) {
                if ("POST".equalsIgnoreCase(method)) {
                    JSONObject req;
                    try {
                        req = new JSONObject(rawBody);
                    } catch (Exception e) {
                        return errorResponse(400, "请求体不是合法 JSON");
                    }
                    JSONArray items = req.optJSONArray("items");
                    // 校验：只接受配置里真实存在的 provider/model 或 group/<id>
                    java.util.Set<String> known = new java.util.HashSet<String>();
                    JSONArray provs0 = configJson.optJSONArray("providers");
                    for (int i = 0; provs0 != null && i < provs0.length(); i++) {
                        JSONObject p = provs0.optJSONObject(i);
                        if (p == null) continue;
                        String pid = p.optString("id", "");
                        JSONArray ms = p.optJSONArray("models");
                        for (int j = 0; ms != null && j < ms.length(); j++) {
                            known.add(pid + "/" + ms.optString(j, ""));
                        }
                    }
                    JSONArray gs = configJson.optJSONArray("groups");
                    for (int i = 0; gs != null && i < gs.length(); i++) {
                        JSONObject g = gs.optJSONObject(i);
                        if (g != null) known.add("group/" + g.optString("id", ""));
                    }

                    JSONArray clean = new JSONArray();
                    java.util.Set<String> seen = new java.util.HashSet<String>();
                    for (int i = 0; items != null && i < items.length(); i++) {
                        String v = items.optString(i, "").trim();
                        if (v.isEmpty() || !known.contains(v) || !seen.add(v)) continue;
                        clean.put(v);
                    }

                    JSONObject cfg = new JSONObject();
                    cfg.put("enabled", req.optBoolean("enabled", true));
                    cfg.put("items", clean);
                    cfg.put("updatedAt", System.currentTimeMillis());
                    configJson.put("exposed", cfg);
                    saveConfig(configJson);

                    JSONObject ret = new JSONObject();
                    ret.put("ok", true);
                    ret.put("enabled", cfg.optBoolean("enabled"));
                    ret.put("count", clean.length());
                    ret.put("items", clean);
                    return jsonResponse(ret);
                }

                // GET：返回当前设置 + 所有可选项（带是否已配置凭据）
                JSONObject out = new JSONObject();
                JSONObject cfg = configJson.optJSONObject("exposed");
                out.put("ok", true);
                out.put("enabled", cfg != null && cfg.optBoolean("enabled", false));
                out.put("items", cfg == null ? new JSONArray() : cfg.optJSONArray("items"));

                JSONArray provs = new JSONArray();
                JSONArray ps = configJson.optJSONArray("providers");
                for (int i = 0; ps != null && i < ps.length(); i++) {
                    JSONObject p = ps.optJSONObject(i);
                    if (p == null) continue;
                    JSONArray ms = p.optJSONArray("models");
                    if (ms == null || ms.length() == 0) continue;
                    JSONObject one = new JSONObject();
                    one.put("id", p.optString("id", ""));
                    one.put("name", p.optString("name", p.optString("id", "")));
                    one.put("live", providerHasCredential(p));
                    JSONArray list = new JSONArray();
                    for (int j = 0; j < ms.length(); j++) list.put(ms.optString(j, ""));
                    one.put("models", list);
                    provs.put(one);
                }
                out.put("providers", provs);

                JSONArray garr = new JSONArray();
                JSONArray gs2 = groupsEnabled() ? configJson.optJSONArray("groups") : null;
                for (int i = 0; gs2 != null && i < gs2.length(); i++) {
                    JSONObject g = gs2.optJSONObject(i);
                    if (g == null) continue;
                    JSONObject one = new JSONObject();
                    one.put("id", "group/" + g.optString("id", ""));
                    one.put("name", g.optString("name", g.optString("id", "")));
                    garr.put(one);
                }
                out.put("groups", garr);
                return jsonResponse(out);
            }

            if ("/api/routing".equals(uri)) {
                if ("POST".equalsIgnoreCase(method)) {
                    JSONObject req;
                    try {
                        req = new JSONObject(rawBody == null || rawBody.isEmpty() ? "{}" : rawBody);
                    } catch (Exception e) {
                        return errorResponse(400, "请求体不是合法 JSON");
                    }
                    JSONObject routing = configJson.optJSONObject("routing");
                    if (routing == null) routing = new JSONObject();
                    if (req.has("enabled")) routing.put("groupsEnabled", req.optBoolean("enabled", false));
                    configJson.put("routing", routing);
                    saveConfig(configJson);
                }
                JSONObject out = new JSONObject();
                out.put("ok", true);
                out.put("enabled", groupsEnabled());
                JSONArray gs = configJson.optJSONArray("groups");
                out.put("groups", gs == null ? 0 : gs.length());
                return jsonResponse(out);
            }

            if ("/api/groups".equals(uri)) {
                if ("POST".equalsIgnoreCase(method)) {
                    JSONObject req;
                    try {
                        req = new JSONObject(rawBody);
                    } catch (Exception e) {
                        return errorResponse(400, "请求体不是合法 JSON");
                    }
                    JSONArray in = req.optJSONArray("groups");
                    if (in == null) return errorResponse(400, "缺少 groups 数组");

                    JSONArray out = new JSONArray();
                    java.util.Set<String> seenIds = new java.util.HashSet<String>();
                    for (int i = 0; i < in.length(); i++) {
                        JSONObject g = in.optJSONObject(i);
                        if (g == null) continue;
                        String gid = g.optString("id", "").trim();
                        String gname = g.optString("name", "").trim();
                        if (gid.isEmpty()) {
                            return errorResponse(400, "第 " + (i + 1) + " 个路由组缺少 id");
                        }
                        if (!gid.matches("[a-zA-Z0-9_-]{1,48}")) {
                            return errorResponse(400, "路由组 id「" + gid
                                    + "」只能用字母、数字、下划线、连字符，且不超过 48 个字符");
                        }
                        if (!seenIds.add(gid)) {
                            return errorResponse(400, "路由组 id「" + gid + "」重复了");
                        }

                        String strategy = g.optString("strategy", "order");
                        if (!isKnownStrategy(strategy)) {
                            return errorResponse(400, "路由组「" + gid + "」的策略 " + strategy
                                    + " 不受支持（可选 order / rotate / manual）");
                        }

                        JSONArray targetsIn = g.optJSONArray("targets");
                        JSONArray targetsOut = new JSONArray();
                        for (int j = 0; targetsIn != null && j < targetsIn.length(); j++) {
                            String t = targetsIn.optString(j, "").trim();
                            if (t.isEmpty()) continue;
                            String pid = providerIdOf(t);
                            if (pid == null) {
                                return errorResponse(400, "目标「" + t + "」格式不对，应为 provider/model");
                            }
                            if (getProviderById(pid) == null) {
                                return errorResponse(400, "目标「" + t + "」指向的供应商 " + pid + " 不存在");
                            }
                            targetsOut.put(t);
                        }
                        if (targetsOut.length() == 0) {
                            return errorResponse(400, "路由组「" + gid + "」至少要有一个目标");
                        }

                        JSONObject o = new JSONObject();
                        o.put("id", gid);
                        o.put("name", gname.isEmpty() ? gid : gname);
                        o.put("strategy", strategy);
                        o.put("targets", targetsOut);
                        String pick = g.optString("pick", "");
                        if ("manual".equals(strategy) && !pick.isEmpty()) o.put("pick", pick);
                        out.put(o);
                    }

                    configJson.put("groups", out);
                    saveConfig(configJson);
                    JSONObject ret = new JSONObject();
                    ret.put("ok", true);
                    ret.put("count", out.length());
                    ret.put("groups", out);
                    return jsonResponse(ret);
                }
                JSONObject ret = new JSONObject();
                ret.put("ok", true);
                JSONArray groups = configJson.optJSONArray("groups");
                ret.put("groups", groups == null ? new JSONArray() : groups);
                return jsonResponse(ret);
            }

            // 3.2 网关口令（authKey）：查看与修改
            // 面板需要把真实口令展示出来并支持一键复制/修改，用户不用再去翻 config.json。
            // 2.9 首启向导：口令 + 权限 + 保活方式
            // needed 只看 setup.done；老装机第一次升级上来也会走一遍，顺便把弱口令强制确认掉
            if ("/api/setup".equals(uri)) {
                return jsonResponse(buildSetupStatus());
            }

            if ("/api/setup/complete".equals(uri) && "POST".equalsIgnoreCase(method)) {
                JSONObject req;
                try {
                    req = new JSONObject(rawBody == null || rawBody.trim().isEmpty() ? "{}" : rawBody);
                } catch (Exception e) {
                    return errorResponse(400, "请求体不是合法 JSON");
                }
                String nextKey = req.optString("authKey", "").trim();
                boolean keepCurrent = req.optBoolean("keepCurrent", false);
                JSONObject server = configJson.optJSONObject("server");
                if (server == null) {
                    server = new JSONObject();
                    configJson.put("server", server);
                }
                if (!nextKey.isEmpty()) {
                    if (nextKey.length() < 4) {
                        return errorResponse(400, "口令至少 4 个字符（太短等于没有）");
                    }
                    if (nextKey.length() > 128) {
                        return errorResponse(400, "口令最多 128 个字符");
                    }
                    if (!nextKey.matches("[A-Za-z0-9._~!@#$%^&*+=:-]+")) {
                        return errorResponse(400, "口令只能包含字母、数字和 . _ ~ ! @ # $ % ^ & * + = : - ");
                    }
                    server.put("authKey", nextKey);
                } else if (!keepCurrent) {
                    return errorResponse(400, "要么给新口令，要么明确 keepCurrent=true 保持当前口令");
                }
                JSONObject setup = new JSONObject();
                setup.put("done", true);
                setup.put("doneAt", System.currentTimeMillis());
                setup.put("version", 1);
                setup.put("keptCurrentKey", nextKey.isEmpty());
                configJson.put("setup", setup);
                saveConfig(configJson);

                JSONObject ret = buildSetupStatus();
                ret.put("authKey", configuredAuthKey());
                return jsonResponse(ret);
            }

            // 2.9.1 非 root 保活：状态查询与立即自检
            if ("/api/keepalive".equals(uri)) {
                JSONObject ret = new JSONObject();
                ret.put("ok", true);
                ret.put("keepalive", KeepAliveScheduler.status(context));
                return jsonResponse(ret);
            }

            if ("/api/keepalive/ping".equals(uri) && "POST".equalsIgnoreCase(method)) {
                KeepAliveScheduler.tickNow(context);
                JSONObject ret = new JSONObject();
                ret.put("ok", true);
                ret.put("triggeredAt", System.currentTimeMillis());
                ret.put("hint", "自检广播已发出，1~2 秒后刷新看 lastCheckAt 与 lastAlive");
                return jsonResponse(ret);
            }

            if ("/api/server-key".equals(uri)) {
                JSONObject ret = new JSONObject();
                if ("POST".equalsIgnoreCase(method)) {
                    JSONObject req = rawBody.isEmpty() ? new JSONObject() : new JSONObject(rawBody);
                    String newKey = req.optString("authKey", "").trim();
                    if (newKey.length() < 4) {
                        return errorResponse(400, "口令至少 4 个字符（太短等于没有）");
                    }
                    if (newKey.length() > 128) {
                        return errorResponse(400, "口令最多 128 个字符");
                    }
                    // 不允许塞进换行/引号之类会破坏 shell 复制粘贴的字符
                    if (!newKey.matches("[A-Za-z0-9._~!@#$%^&*+=:-]+")) {
                        return errorResponse(400, "口令只能包含字母、数字和 . _ ~ ! @ # $ % ^ & * + = : - ");
                    }
                    JSONObject server = configJson.optJSONObject("server");
                    if (server == null) {
                        server = new JSONObject();
                        configJson.put("server", server);
                    }
                    server.put("authKey", newKey);
                    saveConfig(configJson);
                    ret.put("ok", true);
                    ret.put("authKey", newKey);
                    return jsonResponse(ret);
                }
                ret.put("ok", true);
                ret.put("authKey", configuredAuthKey());
                ret.put("port", getListeningPort());
                ret.put("ip", getLocalIpAddress());
                return jsonResponse(ret);
            }

            // 3.9 ZCode（智谱 GLM Coding / Z.ai / BigModel）登录初始化
            // 原生侧调 cli/init 拿授权页，WebView 只负责让用户登录
            if ("/api/auth/zcode/init".equals(uri)) {
                Map<String, List<String>> params = session.getParameters();
                String site = (params.containsKey("site") && !params.get("site").isEmpty())
                        ? params.get("site").get(0) : "zai";
                JSONObject ret = new JSONObject();
                try {
                    ZCodeAuth.Flow flow = ZCodeAuth.start(site);
                    ret.put("ok", true);
                    ret.put("url", flow.authorizeUrl);
                    ret.put("flowId", flow.flowId);
                    ret.put("site", flow.site);
                    ret.put("intervalMs", flow.intervalMs);
                    ret.put("deadline", flow.deadline);
                } catch (Exception e) {
                    ret.put("ok", false);
                    ret.put("error", e.getMessage() == null ? "初始化失败" : e.getMessage());
                }
                return jsonResponse(ret);
            }

            // 3.9.1 ZCode 登录轮询；ready 时直接把 API Key 写进供应商池
            if ("/api/auth/zcode/poll".equals(uri)) {
                Map<String, List<String>> params = session.getParameters();
                String flowId = (params.containsKey("flowId") && !params.get("flowId").isEmpty())
                        ? params.get("flowId").get(0) : "";
                JSONObject ret = new JSONObject();
                if (flowId.isEmpty()) {
                    ret.put("ok", false);
                    ret.put("error", "缺少 flowId");
                    return jsonResponse(ret);
                }
                try {
                    ZCodeAuth.PollResult pr = ZCodeAuth.poll(flowId);
                    ret.put("ok", true);
                    ret.put("status", pr.status);
                    if (pr.error != null) ret.put("error", pr.error);
                    if (pr.account != null && !pr.account.isEmpty()) ret.put("account", pr.account);
                    if ("ready".equals(pr.status) && pr.signIn != null) {
                        String site = "bigmodel".equals(pr.signIn.base.contains("bigmodel")) ? "bigmodel" : "zai";
                        // 有 api key 才算真正可用；没有 key 只有 jwt 时提示订阅
                        if (pr.signIn.key == null || pr.signIn.key.isEmpty()) {
                            ret.put("ok", false);
                            ret.put("status", "no-plan");
                            ret.put("error", "该账号没有 GLM Coding Plan（ZCode 的免费额度只能用 App 自身会话，不支持 API Key 方式）");
                            return jsonResponse(ret);
                        }
                        // 同名同账号已经在池里：不再重复入池，回一个 duplicate 状态让原生层提示
                        String zcName = (pr.account == null || pr.account.isEmpty())
                                ? ("ZCode " + ("bigmodel".equals(site) ? "BigModel" : "Z.ai")) : pr.account;
                        JSONObject zcDup = findDuplicateAccount(getProviderById("zcode"), zcName, pr.signIn.key);
                        if (zcDup != null) {
                            String dupMsg = "ZCode 池内已经有同名同账号「"
                                    + zcDup.optString("name", zcName) + "」了，本次未重复加入。";
                            ret.put("ok", false);
                            ret.put("status", "duplicate");
                            ret.put("duplicate", true);
                            ret.put("keyId", zcDup.optString("id", ""));
                            ret.put("error", dupMsg);
                            return jsonResponse(ret);
                        }

                        String accId = addZCodeKey(site, pr.signIn.key,
                                pr.signIn.plan, pr.account,
                                pr.signIn.jwt, pr.signIn.device);
                        ret.put("keyId", accId);
                        ret.put("plan", pr.signIn.plan == null ? "" : pr.signIn.plan);
                        ret.put("site", site);
                    }
                } catch (Exception e) {
                    ret.put("ok", false);
                    ret.put("error", e.getMessage() == null ? "轮询失败" : e.getMessage());
                }
                return jsonResponse(ret);
            }

            // 3.10 Qoder（国内版 qoder.cn）设备流登录初始化
            // 与 ZCode 同款思路：原生侧生成 PKCE，WebView 只负责让用户登录授权
            if ("/api/auth/qoder_cn/init".equals(uri)) {
                JSONObject ret = new JSONObject();
                try {
                    QoderAuth.Flow flow = QoderAuth.start();
                    qoderFlows.put(flow.flowId, flow);
                    // 顺手清掉过期的流程，避免内存里堆着
                    long now = System.currentTimeMillis();
                    java.util.Iterator<java.util.Map.Entry<String, QoderAuth.Flow>> it =
                            qoderFlows.entrySet().iterator();
                    while (it.hasNext()) {
                        if (it.next().getValue().deadline < now) it.remove();
                    }
                    ret.put("ok", true);
                    ret.put("url", flow.authorizeUrl);
                    ret.put("flowId", flow.flowId);
                    ret.put("intervalMs", flow.intervalMs);
                    ret.put("deadline", flow.deadline);
                } catch (Exception e) {
                    ret.put("ok", false);
                    ret.put("error", e.getMessage() == null ? "初始化失败" : e.getMessage());
                }
                return jsonResponse(ret);
            }

            // 3.10.1 Qoder 登录轮询；ready 时把账号写进供应商池
            if ("/api/auth/qoder_cn/poll".equals(uri)) {
                Map<String, List<String>> params = session.getParameters();
                String flowId = (params.containsKey("flowId") && !params.get("flowId").isEmpty())
                        ? params.get("flowId").get(0) : "";
                JSONObject ret = new JSONObject();
                if (flowId.isEmpty()) {
                    ret.put("ok", false);
                    ret.put("error", "缺少 flowId");
                    return jsonResponse(ret);
                }
                QoderAuth.Flow flow = qoderFlows.get(flowId);
                if (flow == null) {
                    ret.put("ok", false);
                    ret.put("status", "failed");
                    ret.put("error", "登录流程已失效，请重新发起");
                    return jsonResponse(ret);
                }
                if (System.currentTimeMillis() > flow.deadline) {
                    qoderFlows.remove(flowId);
                    ret.put("ok", false);
                    ret.put("status", "failed");
                    ret.put("error", "登录超时，请重新发起");
                    return jsonResponse(ret);
                }
                try {
                    QoderAuth.PollResult pr = QoderAuth.poll(flow);
                    ret.put("ok", true);
                    ret.put("status", pr.status);
                    if (pr.error != null && !pr.error.isEmpty()) ret.put("hint", pr.error);
                    if (pr.account != null && !pr.account.isEmpty()) ret.put("account", pr.account);

                    if ("ready".equals(pr.status) && pr.signIn != null) {
                        QoderAuth.SignIn si = pr.signIn;
                        String accName = !si.email.isEmpty() ? si.email
                                : (!si.uid.isEmpty() ? si.uid : "Qoder CN 账号");
                        // 同名同账号不重复入池（复用统一判定）
                        JSONObject dup = findDuplicateAccount(getProviderById("qoder_cn"), accName, si.access);
                        if (dup != null) {
                            ret.put("ok", false);
                            ret.put("status", "duplicate");
                            ret.put("duplicate", true);
                            ret.put("error", "Qoder CN 池内已经有同名同账号「" + accName + "」了，本次未重复加入。");
                            qoderFlows.remove(flowId);
                            return jsonResponse(ret);
                        }
                        String accId = addQoderAccount(si, accName);
                        ret.put("keyId", accId);
                        ret.put("account", accName);
                        ret.put("deviceChat", si.deviceChat);
                        qoderFlows.remove(flowId);
                    }
                } catch (Exception e) {
                    ret.put("ok", false);
                    ret.put("error", e.getMessage() == null ? "轮询失败" : e.getMessage());
                }
                return jsonResponse(ret);
            }

                        // 3.10.2 Cline (cline.bot) 设备流登录初始化
            if ("/api/auth/cline/init".equals(uri)) {
                JSONObject ret = new JSONObject();
                try {
                    ClineAuth.Flow flow = ClineAuth.initDeviceFlow();
                    clineFlows.put(flow.flowId, flow);
                    long now = System.currentTimeMillis();
                    java.util.Iterator<java.util.Map.Entry<String, ClineAuth.Flow>> it =
                            clineFlows.entrySet().iterator();
                    while (it.hasNext()) {
                        ClineAuth.Flow f = it.next().getValue();
                        if (now - f.createdAt > f.expiresIn * 1000L) it.remove();
                    }
                    ret.put("ok", true);
                    ret.put("url", flow.verificationUriComplete);
                    ret.put("flowId", flow.flowId);
                    ret.put("userCode", flow.userCode);
                    ret.put("intervalMs", flow.intervalMs);
                    ret.put("expiresIn", flow.expiresIn);
                } catch (Exception e) {
                    ret.put("ok", false);
                    ret.put("error", e.getMessage() == null ? "初始化失败" : e.getMessage());
                }
                return jsonResponse(ret);
            }

            // 3.10.3 Cline 设备流轮询；ready 时把账号写进供应商池
            if ("/api/auth/cline/poll".equals(uri)) {
                Map<String, List<String>> params = session.getParameters();
                String flowId = (params.containsKey("flowId") && !params.get("flowId").isEmpty())
                        ? params.get("flowId").get(0) : "";
                JSONObject ret = new JSONObject();
                if (flowId.isEmpty()) {
                    ret.put("ok", false);
                    ret.put("error", "缺少 flowId");
                    return jsonResponse(ret);
                }
                ClineAuth.Flow flow = clineFlows.get(flowId);
                if (flow == null) {
                    ret.put("ok", false);
                    ret.put("status", "failed");
                    ret.put("error", "登录流程已失效，请重新发起");
                    return jsonResponse(ret);
                }
                if (System.currentTimeMillis() - flow.createdAt > flow.expiresIn * 1000L) {
                    clineFlows.remove(flowId);
                    ret.put("ok", false);
                    ret.put("status", "failed");
                    ret.put("error", "登录超时，请重新发起");
                    return jsonResponse(ret);
                }
                try {
                    ClineAuth.PollResult pr = ClineAuth.pollDeviceFlow(flow.flowId);
                    ret.put("ok", !"failed".equals(pr.status));
                    ret.put("status", pr.status);
                    if (pr.error != null && !pr.error.isEmpty()) ret.put("hint", pr.error);

                    if ("ready".equals(pr.status) && pr.signIn != null) {
                        ClineAuth.SignIn si = pr.signIn;
                        String accName = !si.email.isEmpty() ? si.email
                                : (!si.name.isEmpty() ? si.name : (!si.uid.isEmpty() ? si.uid : "Cline 账号"));
                        JSONObject dup = findDuplicateAccount(getProviderById("cline"), accName, si.access);
                        if (dup != null) {
                            ret.put("ok", false);
                            ret.put("status", "duplicate");
                            ret.put("duplicate", true);
                            ret.put("error", "Cline 池内已经有同名同账号「" + accName + "」了，本次未重复加入。");
                            clineFlows.remove(flowId);
                            return jsonResponse(ret);
                        }
                        String accId = addClineAccount(si, accName);
                        ret.put("keyId", accId);
                        ret.put("account", accName);
                        clineFlows.remove(flowId);
                    }
                } catch (Exception e) {
                    ret.put("ok", false);
                    ret.put("error", e.getMessage() == null ? "轮询失败" : e.getMessage());
                }
                return jsonResponse(ret);
            }

            // ===== 3.11 GitHub Copilot：官方 OAuth 设备流（免 Key，账号池） =====
            if ("/api/auth/github-copilot/init".equals(uri)) {
                JSONObject ret = new JSONObject();
                try {
                    CopilotAuth.Flow flow = CopilotAuth.initDeviceFlow();
                    copilotFlows.put(flow.flowId, flow);
                    long now = System.currentTimeMillis();
                    java.util.Iterator<java.util.Map.Entry<String, CopilotAuth.Flow>> it =
                            copilotFlows.entrySet().iterator();
                    while (it.hasNext()) {
                        CopilotAuth.Flow f = it.next().getValue();
                        if (now - f.createdAt > f.expiresIn * 1000L) it.remove();
                    }
                    ret.put("ok", true);
                    ret.put("url", flow.verificationUriComplete);
                    ret.put("flowId", flow.flowId);
                    ret.put("userCode", flow.userCode);
                    ret.put("intervalMs", flow.intervalMs);
                    ret.put("expiresIn", flow.expiresIn);
                } catch (Exception e) {
                    ret.put("ok", false);
                    ret.put("error", e.getMessage() == null ? "初始化失败" : e.getMessage());
                }
                return jsonResponse(ret);
            }

            if ("/api/auth/github-copilot/poll".equals(uri)) {
                Map<String, List<String>> params = session.getParameters();
                String flowId = (params.containsKey("flowId") && !params.get("flowId").isEmpty())
                        ? params.get("flowId").get(0) : "";
                JSONObject ret = new JSONObject();
                if (flowId.isEmpty()) {
                    ret.put("ok", false);
                    ret.put("error", "缺少 flowId");
                    return jsonResponse(ret);
                }
                CopilotAuth.Flow flow = copilotFlows.get(flowId);
                if (flow == null) {
                    ret.put("ok", false);
                    ret.put("status", "failed");
                    ret.put("error", "登录流程已失效，请重新发起");
                    return jsonResponse(ret);
                }
                if (System.currentTimeMillis() - flow.createdAt > flow.expiresIn * 1000L) {
                    copilotFlows.remove(flowId);
                    ret.put("ok", false);
                    ret.put("status", "failed");
                    ret.put("error", "登录超时，请重新发起");
                    return jsonResponse(ret);
                }
                try {
                    CopilotAuth.PollResult pr = CopilotAuth.pollDeviceFlow(flow.flowId);
                    ret.put("ok", !"failed".equals(pr.status));
                    ret.put("status", pr.status);
                    ret.put("userCode", flow.userCode);
                    if (pr.error != null && !pr.error.isEmpty()) ret.put("hint", pr.error);
                    if ("ready".equals(pr.status) && pr.signIn != null) {
                        CopilotAuth.SignIn si = pr.signIn;
                        String accName = si.githubLogin.isEmpty() ? "GitHub Copilot" : si.githubLogin;
                        JSONObject dup = findDuplicateAccount(getProviderById("github-copilot"), accName, si.githubToken);
                        if (dup != null) {
                            ret.put("ok", false);
                            ret.put("status", "duplicate");
                            ret.put("duplicate", true);
                            ret.put("error", "Copilot 池内已经有同一个 GitHub 账号「" + accName + "」了，本次未重复加入。");
                            copilotFlows.remove(flowId);
                            return jsonResponse(ret);
                        }
                        String accId = addCopilotAccount(si, accName);
                        ret.put("keyId", accId);
                        ret.put("account", accName);
                        ret.put("copilot", true);
                        copilotFlows.remove(flowId);
                    }
                } catch (Exception e) {
                    ret.put("ok", false);
                    ret.put("error", e.getMessage() == null ? "轮询失败" : e.getMessage());
                }
                return jsonResponse(ret);
            }

            // ===== 3.12 MiniMax Code：设备流（PKCE S256）+ Anthropic messages =====
            if ("/api/auth/minimax-code/init".equals(uri)) {
                JSONObject ret = new JSONObject();
                try {
                    MiniMaxAuth.Flow flow = MiniMaxAuth.initDeviceFlow();
                    minimaxFlows.put(flow.flowId, flow);
                    long now = System.currentTimeMillis();
                    java.util.Iterator<java.util.Map.Entry<String, MiniMaxAuth.Flow>> it =
                            minimaxFlows.entrySet().iterator();
                    while (it.hasNext()) {
                        MiniMaxAuth.Flow f = it.next().getValue();
                        if (now > f.deadline) it.remove();
                    }
                    ret.put("ok", true);
                    ret.put("url", flow.verificationUriComplete);
                    ret.put("flowId", flow.flowId);
                    ret.put("userCode", flow.userCode);
                    ret.put("intervalMs", flow.intervalMs);
                } catch (Exception e) {
                    ret.put("ok", false);
                    ret.put("error", e.getMessage() == null ? "初始化失败" : e.getMessage());
                }
                return jsonResponse(ret);
            }

            if ("/api/auth/minimax-code/poll".equals(uri)) {
                Map<String, List<String>> params = session.getParameters();
                String flowId = (params.containsKey("flowId") && !params.get("flowId").isEmpty())
                        ? params.get("flowId").get(0) : "";
                JSONObject ret = new JSONObject();
                MiniMaxAuth.Flow flow = minimaxFlows.get(flowId);
                if (flow == null) {
                    ret.put("ok", false);
                    ret.put("status", "failed");
                    ret.put("error", "登录流程已失效，请重新发起");
                    return jsonResponse(ret);
                }
                try {
                    MiniMaxAuth.PollResult pr = MiniMaxAuth.pollDeviceFlow(flow);
                    ret.put("ok", !"failed".equals(pr.status));
                    ret.put("status", pr.status);
                    ret.put("userCode", flow.userCode);
                    if (pr.error != null && !pr.error.isEmpty()) ret.put("hint", pr.error);
                    if ("ready".equals(pr.status) && pr.signIn != null) {
                        MiniMaxAuth.SignIn si = pr.signIn;
                        String accName = si.userId.isEmpty() ? "MiniMax Code 账号" : si.userId;
                        JSONObject dup = findDuplicateAccount(getProviderById("minimax-code"), accName, si.refresh);
                        if (dup != null) {
                            ret.put("ok", false);
                            ret.put("status", "duplicate");
                            ret.put("duplicate", true);
                            ret.put("error", "MiniMax 池内已经有同一个账号「" + accName + "」了，本次未重复加入。");
                            minimaxFlows.remove(flowId);
                            return jsonResponse(ret);
                        }
                        String accId = addMiniMaxAccount(si, accName);
                        ret.put("keyId", accId);
                        ret.put("account", accName);
                        minimaxFlows.remove(flowId);
                    }
                } catch (Exception e) {
                    ret.put("ok", false);
                    ret.put("error", e.getMessage() == null ? "轮询失败" : e.getMessage());
                }
                return jsonResponse(ret);
            }

            // ===== 3.13 MiniMax 每日签到（花的是签到积分，不签到就只有 1008 insufficient balance） =====
            if ("/api/minimax/checkin".equals(uri)) {
                JSONObject prov = getProviderById("minimax-code");
                JSONObject ret = new JSONObject();
                if (prov == null) return errorResponse(404, "供应商 minimax-code 不存在");
                JSONArray accs = prov.optJSONArray("accounts");
                JSONObject acc = null;
                for (int i = 0; accs != null && i < accs.length(); i++) {
                    JSONObject a = accs.optJSONObject(i);
                    if (a != null && !a.optString("refresh", "").trim().isEmpty()) { acc = a; break; }
                }
                if (acc == null) return errorResponse(400, "请先登录 MiniMax Code 再签到");
                JSONObject r = minimaxCheckinAccount(acc);
                java.util.Iterator<String> it = r.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    ret.put(k, r.opt(k));
                }
                return jsonResponse(ret);
            }

            // 3.10.4 Cline 额度与限额查询 API: /api/cline/usage
            if ("/api/cline/usage".equals(uri)) {
                JSONObject prov = getProviderById("cline");
                if (prov == null) return errorResponse(404, "供应商 cline 不存在");
                String token = "";
                boolean isOauth = true;
                JSONArray accs = prov.optJSONArray("accounts");
                if (accs != null && accs.length() > 0) {
                    JSONObject a = accs.optJSONObject(0);
                    if (a != null) {
                        token = a.optString("token", "");
                        isOauth = !"api".equals(a.optString("type", "oauth"));
                    }
                }
                if (token.isEmpty()) {
                    JSONArray keys = prov.optJSONArray("keys");
                    if (keys != null && keys.length() > 0) {
                        token = keys.optString(0, "");
                        isOauth = false;
                    }
                }
                if (token.isEmpty()) return errorResponse(400, "Cline 尚未配置账号或 API Key");
                try {
                    JSONObject u = ClineAuth.fetchUsage(token, isOauth);
                    return jsonResponse(u);
                } catch (Exception e) {
                    return errorResponse(500, "读取 Cline 额度失败: " + e.getMessage());
                }
            }

            // 3.10.4.-3 连接抖动测量：同域名连打 N 次，量化握手成功率
            if ("/api/antigravity/flap".equals(uri)) {
                Map<String, List<String>> fp = session.getParameters();
                String host = (fp.containsKey("host") && !fp.get("host").isEmpty())
                        ? fp.get("host").get(0) : "daily-cloudcode-pa.googleapis.com";
                int n = 10;
                try {
                    if (fp.containsKey("n") && !fp.get("n").isEmpty()) n = Integer.parseInt(fp.get("n").get(0));
                } catch (Exception ignored) {
                }
                if (n < 1) n = 1;
                if (n > 40) n = 40;
                String mode = (fp.containsKey("mode") && !fp.get("mode").isEmpty())
                        ? fp.get("mode").get(0) : "direct";
                return jsonResponse(AntigravityAuth.flap(host, n, mode));
            }

            // 3.10.4.-2 Antigravity 逐接口诊断（带真凭据逐个打，定位到底哪一步断）
            if ("/api/antigravity/diag".equals(uri)) {
                JSONObject prov = getProviderById("antigravity");
                if (prov == null) return errorResponse(404, "供应商 antigravity 不存在");
                JSONObject acc = null;
                JSONArray accs = prov.optJSONArray("accounts");
                for (int i = 0; accs != null && i < accs.length(); i++) {
                    JSONObject a = accs.optJSONObject(i);
                    if (a != null && !a.optString("token", "").trim().isEmpty()) { acc = a; break; }
                }
                if (acc == null) return errorResponse(400, "Antigravity 还没有账号，请先登录");
                String access;
                try {
                    access = AntigravityAuth.ensureToken(acc);
                } catch (Exception e) {
                    return errorResponse(502, "token 刷新失败: " + e.getMessage());
                }
                String project = acc.optString("project", "");
                String base = AntigravityAuth.chatBase;
                JSONObject ret = AntigravityAuth.diag(base, access, project);
                return jsonResponse(ret);
            }

            // 3.10.4.0 Antigravity 端点探测：并发 + 重试 + 打真实业务端点，结论直接生效并落盘
            if ("/api/antigravity/probe".equals(uri)) {
                JSONObject ret = AntigravityAuth.probeAll();
                try {
                    if (ret.optBoolean("ok", false) && !"down".equals(ret.optString("verdict", ""))) {
                        JSONObject ag = getProviderById("antigravity");
                        if (ag == null) {
                            ag = new JSONObject();
                            ag.put("id", "antigravity");
                            ag.put("name", "Antigravity (Google 账号)");
                            ag.put("status", "ported");
                            ag.put("plugin", "builtin/antigravity");
                            ag.put("protocol", "antigravity");
                            ag.put("enabled", true);
                            ag.put("models", new JSONArray());
                            ag.put("keys", new JSONArray());
                            ag.put("accounts", new JSONArray());
                            JSONArray provs = configJson.optJSONArray("providers");
                            if (provs == null) {
                                provs = new JSONArray();
                                configJson.put("providers", provs);
                            }
                            provs.put(ag);
                        }
                        ag.put("baseUrl", AntigravityAuth.chatBase);
                        ag.put("probeChatBase", AntigravityAuth.chatBase);
                        ag.put("probeLoadBase", AntigravityAuth.loadBase);
                        ag.put("probeAt", System.currentTimeMillis());
                        ag.put("probeVerdict", ret.optString("verdict", ""));
                        saveConfig(configJson);
                        AntigravityAuth.applyBases(AntigravityAuth.chatBase, AntigravityAuth.loadBase);
                    }
                } catch (Throwable ignored) {
                }
                return jsonResponse(ret);
            }

            // 3.10.4.-1 TLS/ALPN 诊断（排障用：确认是握手被杀还是接口拒绝）
            if ("/api/antigravity/tlslab".equals(uri)) {
                JSONObject ret = new JSONObject();
                JSONArray arr = new JSONArray();
                String[] hosts = new String[]{"cloudcode-pa.googleapis.com",
                        "daily-cloudcode-pa.googleapis.com"};
                for (String h : hosts) {
                    JSONObject one = AntigravityAuth.tlsLab(h, false, null, 9000);
                    one.put("host", h);
                    one.put("mode", "不协商 ALPN（纯 HTTP/1.1）");
                    arr.put(one);
                }
                ret.put("ok", true);
                ret.put("note", "仅诊断用；ALPN 一旦协商到 h2，cloudcode-pa 会直接 reset。");
                ret.put("results", arr);
                return jsonResponse(ret);
            }

            // 3.10.4 Antigravity (Google Code Assist) OAuth 初始化
            // 已有未完成流程时直接复用（?fresh=1 可强制新建）：从系统浏览器回来后
            // Activity 会重建并再次调 init，如果每次新建，用户会看到浏览器又弹一次。
            if ("/api/auth/antigravity/init".equals(uri)) {
                JSONObject ret = new JSONObject();
                try {
                    startAntigravityCallbackListener();
                    Map<String, List<String>> ip = session.getParameters();
                    boolean fresh = ip.containsKey("fresh") && !ip.get("fresh").isEmpty()
                            && "1".equals(ip.get("fresh").get(0));
                    AntigravityAuth.Flow flow = null;
                    if (!fresh) {
                        long newest = 0L;
                        for (AntigravityAuth.Flow f : antigravityFlows.values()) {
                            if ("pending".equals(f.status)
                                    && System.currentTimeMillis() - f.createdAt < 15 * 60 * 1000L
                                    && f.createdAt > newest) {
                                newest = f.createdAt;
                                flow = f;
                            }
                        }
                    }
                    boolean resumed = flow != null;
                    if (flow == null) {
                        flow = AntigravityAuth.createFlow();
                        antigravityFlows.put(flow.flowId, flow);
                    }
                    ret.put("ok", true);
                    ret.put("flowId", flow.flowId);
                    ret.put("authUrl", flow.authUrl);
                    ret.put("resumed", resumed);
                    ret.put("redirectUri", flow.redirect);
                    ret.put("hint", "Google 不允许在 App 内 WebView 里登录，请在系统浏览器完成授权；回调会打到本机 "
                            + AntigravityAuth.CALLBACK_PORT + " 端口自动入池。");
                } catch (Exception e) {
                    ret.put("ok", false);
                    ret.put("error", e.getMessage() == null ? "初始化 Antigravity 授权失败" : e.getMessage());
                }
                return jsonResponse(ret);
            }

            // 3.10.4.1 Antigravity OAuth 轮询
            // 关键：App 从系统浏览器回来后 Activity 可能被重建，local flowId 会丢。
            // 所以这里 flowId 缺省时自动接「最新一条未完成流程」，不再报「已失效」。
            // 回调失败时流程保留（status=failed），让前端能读到真正的失败原因，
            // 直到成功或 15 分钟超时才移除。
            if ("/api/auth/antigravity/poll".equals(uri)) {
                Map<String, List<String>> params = session.getParameters();
                String flowId = (params.containsKey("flowId") && !params.get("flowId").isEmpty())
                        ? params.get("flowId").get(0) : "";
                JSONObject ret = new JSONObject();
                AntigravityAuth.Flow flow = flowId.isEmpty() ? null : antigravityFlows.get(flowId);
                if (flow == null) {
                    long newest = 0L;
                    for (AntigravityAuth.Flow f : antigravityFlows.values()) {
                        if (f.createdAt > newest) {
                            newest = f.createdAt;
                            flow = f;
                        }
                    }
                }
                if (flow == null) {
                    ret.put("ok", true);
                    ret.put("status", "pending");
                    ret.put("waiting", true);
                    ret.put("flowId", "");
                    ret.put("message", "还没有发起授权：请点「🔑 Google 账号登录」");
                    return jsonResponse(ret);
                }
                if (System.currentTimeMillis() - flow.createdAt > 15 * 60 * 1000L) {
                    antigravityFlows.remove(flow.flowId);
                    ret.put("ok", false);
                    ret.put("status", "failed");
                    ret.put("error", "授权超时（15 分钟），请重新点一次登录");
                    return jsonResponse(ret);
                }
                ret.put("ok", true);
                ret.put("flowId", flow.flowId);
                ret.put("status", flow.status);
                if ("done".equals(flow.status) && flow.account != null) {
                    ret.put("account", flow.account.optString("email", ""));
                    ret.put("project", flow.account.optString("project", ""));
                    ret.put("plan", flow.account.optString("plan", ""));
                    // 成功后保留 60 秒再清，避免 App 重建后轮询到空
                    if (System.currentTimeMillis() - flow.createdAt > 60 * 1000L) {
                        antigravityFlows.remove(flow.flowId);
                    }
                } else if ("failed".equals(flow.status)) {
                    ret.put("ok", false);
                    ret.put("error", flow.error == null || flow.error.isEmpty() ? "授权失败" : flow.error);
                }
                return jsonResponse(ret);
            }

            // 3.10.4.2 Antigravity 手动提交授权 code / 回调完整 URL
            if ("/api/auth/antigravity/submit-code".equals(uri) && "POST".equalsIgnoreCase(method)) {
                JSONObject ret = new JSONObject();
                try {
                    JSONObject req = rawBody.isEmpty() ? new JSONObject() : new JSONObject(rawBody);
                    String input = req.optString("code", "").trim();
                    String flowId = req.optString("flowId", "").trim();
                    if (input.isEmpty()) return errorResponse(400, "请输入 code 或回调链接");
                    String code = input;
                    String state = "";
                    if (input.contains("code=")) {
                        String query = input.contains("?") ? input.substring(input.indexOf('?') + 1) : input;
                        for (String part : query.split("&")) {
                            String[] kv = part.split("=");
                            if (kv.length == 2) {
                                if ("code".equals(kv[0])) code = java.net.URLDecoder.decode(kv[1], "UTF-8");
                                else if ("state".equals(kv[0])) state = java.net.URLDecoder.decode(kv[1], "UTF-8");
                            }
                        }
                    }
                    AntigravityAuth.Flow flow = antigravityFlows.get(flowId);
                    if (flow == null && !state.isEmpty()) {
                        for (AntigravityAuth.Flow f : antigravityFlows.values()) {
                            if (f.state.equals(state)) { flow = f; break; }
                        }
                    }
                    if (flow == null) {
                        for (AntigravityAuth.Flow f : antigravityFlows.values()) { flow = f; break; }
                    }
                    if (flow == null) return errorResponse(400, "未找到对应的授权流程，请先点击发起授权");
                    JSONObject acc = AntigravityAuth.exchangeCode(code, flow.verifier, flow.redirect);
                    String accId = addAntigravityAccount(acc, acc.optString("email", ""));
                    ret.put("ok", true);
                    ret.put("keyId", accId);
                    ret.put("account", acc.optString("email", ""));
                    ret.put("project", acc.optString("project", ""));
                    ret.put("plan", acc.optString("plan", ""));
                    flow.account = acc;
                    flow.status = "done";
                    antigravityFlows.remove(flow.flowId);
                } catch (Exception e) {
                    String msg = e.getMessage() == null ? "授权换取失败" : e.getMessage();
                    ret.put("ok", false);
                    ret.put("error", msg);
                    // 记到流程上，轮询那边能拿到同一份原因，不会一直卡在 pending
                    AntigravityAuth.Flow f = antigravityFlows.get(
                            rawBody.isEmpty() ? "" : new JSONObject(rawBody).optString("flowId", ""));
                    if (f != null) {
                        f.status = "failed";
                        f.error = msg;
                    }
                }
                return jsonResponse(ret);
            }

            // 3.10.5 Codex (ChatGPT 订阅) OAuth PKCE 流程初始化
            if ("/api/auth/codex/init".equals(uri)) {
                JSONObject ret = new JSONObject();
                try {
                    // 先探明地区/连通性：授权页开在系统浏览器里，浏览器走系统网络，
                    // 没代理时 OpenAI 只会回一坨 unsupported_country_region_territory 的 JSON，
                    // 用户完全不知道发生了什么。这里提前把原因带回去。
                    String regionWarn = null;
                    try {
                        regionWarn = CodexAuth.precheckRegion();
                    } catch (Throwable ignored) {
                    }

                    startCodexCallbackListener();
                    CodexAuth.Flow flow = CodexAuth.createFlow();
                    codexFlows.put(flow.flowId, flow);
                    ret.put("ok", true);
                    ret.put("flowId", flow.flowId);
                    ret.put("authUrl", flow.authUrl);
                    ret.put("redirectUri", CodexAuth.REDIRECT_URI);
                    if (regionWarn != null) {
                        ret.put("regionWarning", regionWarn);
                        ret.put("regionBlocked", true);
                    } else {
                        ret.put("regionBlocked", false);
                    }
                } catch (Exception e) {
                    ret.put("ok", false);
                    ret.put("error", e.getMessage() == null ? "初始化 Codex 授权失败" : e.getMessage());
                }
                return jsonResponse(ret);
            }

            // 3.10.6 Codex OAuth 流程轮询
            if ("/api/auth/codex/poll".equals(uri)) {
                Map<String, List<String>> params = session.getParameters();
                String flowId = (params.containsKey("flowId") && !params.get("flowId").isEmpty())
                        ? params.get("flowId").get(0) : "";
                JSONObject ret = new JSONObject();
                if (flowId.isEmpty()) {
                    ret.put("ok", false);
                    ret.put("error", "缺少 flowId");
                    return jsonResponse(ret);
                }
                CodexAuth.Flow flow = codexFlows.get(flowId);
                if (flow == null) {
                    ret.put("ok", false);
                    ret.put("status", "failed");
                    ret.put("error", "登录流程已失效或不存在");
                    return jsonResponse(ret);
                }
                if (System.currentTimeMillis() - flow.createdAt > 10 * 60 * 1000L) {
                    codexFlows.remove(flowId);
                    ret.put("ok", false);
                    ret.put("status", "failed");
                    ret.put("error", "授权超时");
                    return jsonResponse(ret);
                }
                ret.put("ok", true);
                ret.put("status", flow.status);
                if ("done".equals(flow.status) && flow.result != null) {
                    ret.put("account", flow.result.email);
                    ret.put("plan", flow.result.planType);
                    codexFlows.remove(flowId);
                } else if ("failed".equals(flow.status)) {
                    ret.put("error", flow.error);
                    codexFlows.remove(flowId);
                }
                return jsonResponse(ret);
            }

            // 3.10.7 Codex 手动提交授权 code 或回调完整 URL
            if ("/api/auth/codex/submit-code".equals(uri) && "POST".equalsIgnoreCase(method)) {
                JSONObject ret = new JSONObject();
                try {
                    JSONObject req = rawBody.isEmpty() ? new JSONObject() : new JSONObject(rawBody);
                    String input = req.optString("code", "").trim();
                    String flowId = req.optString("flowId", "").trim();
                    if (input.isEmpty()) return errorResponse(400, "请输入 code 或回调链接");

                    String code = input;
                    String verifier = "";
                    if (input.contains("code=")) {
                        String query = input.contains("?") ? input.substring(input.indexOf('?') + 1) : input;
                        for (String part : query.split("&")) {
                            String[] kv = part.split("=");
                            if (kv.length == 2) {
                                if ("code".equals(kv[0])) code = java.net.URLDecoder.decode(kv[1], "UTF-8");
                                else if ("state".equals(kv[0])) {
                                    String st = java.net.URLDecoder.decode(kv[1], "UTF-8");
                                    for (CodexAuth.Flow f : codexFlows.values()) {
                                        if (f.state.equals(st)) { verifier = f.verifier; break; }
                                    }
                                }
                            }
                        }
                    }
                    if (verifier.isEmpty() && !flowId.isEmpty()) {
                        CodexAuth.Flow f = codexFlows.get(flowId);
                        if (f != null) verifier = f.verifier;
                    }
                    if (verifier.isEmpty()) {
                        for (CodexAuth.Flow f : codexFlows.values()) {
                            verifier = f.verifier;
                            break;
                        }
                    }
                    if (verifier.isEmpty()) {
                        return errorResponse(400, "未找到对应的授权流程，请先点击发起授权");
                    }

                    CodexAuth.SignInInfo info = CodexAuth.exchangeCode(code, verifier);
                    String accId = addCodexAccount(info, info.email);
                    ret.put("ok", true);
                    ret.put("keyId", accId);
                    ret.put("account", info.email);
                    ret.put("plan", info.planType);
                    ret.put("accountId", info.accountId);
                    if (!flowId.isEmpty()) codexFlows.remove(flowId);
                } catch (Exception e) {
                    ret.put("ok", false);
                    ret.put("error", e.getMessage() == null ? "授权换取失败" : e.getMessage());
                }
                return jsonResponse(ret);
            }

            // 3.10.8 Codex 凭据文本直接导入 (auth.json / token / refresh_token)
            if ("/api/auth/codex/import".equals(uri) && "POST".equalsIgnoreCase(method)) {
                JSONObject ret = new JSONObject();
                try {
                    JSONObject req = rawBody.isEmpty() ? new JSONObject() : new JSONObject(rawBody);
                    String raw = req.optString("raw", "").trim();
                    if (raw.isEmpty()) return errorResponse(400, "凭据内容为空");
                    CodexAuth.SignInInfo info = CodexAuth.parseCredentials(raw);
                    String accId = addCodexAccount(info, info.email);
                    ret.put("ok", true);
                    ret.put("keyId", accId);
                    ret.put("account", info.email);
                    ret.put("plan", info.planType);
                    ret.put("accountId", info.accountId);
                } catch (Exception e) {
                    ret.put("ok", false);
                    ret.put("error", e.getMessage() == null ? "导入凭据解析失败" : e.getMessage());
                }
                return jsonResponse(ret);
            }

            // 3.10.9 Codex 用量与配额查询 API: /api/codex/usage
            if ("/api/codex/usage".equals(uri)) {
                JSONObject prov = getProviderById("codex");
                if (prov == null) return errorResponse(404, "供应商 codex 不存在");
                JSONArray accs = prov.optJSONArray("accounts");
                JSONObject targetAcc = null;
                if (accs != null && accs.length() > 0) targetAcc = accs.optJSONObject(0);
                if (targetAcc == null) return errorResponse(400, "Codex 尚未配置账号");
                CodexAuth.SignInInfo info = new CodexAuth.SignInInfo();
                info.accessToken = targetAcc.optString("access", targetAcc.optString("token", ""));
                info.refreshToken = targetAcc.optString("refresh", "");
                info.idToken = targetAcc.optString("idToken", "");
                info.accountId = targetAcc.optString("accountId", "");
                info.authMode = targetAcc.optString("authMode", "chatgpt");
                info.expiresAt = targetAcc.optLong("expires", 0);
                try {
                    JSONObject u = CodexAuth.fetchUsage(info);
                    if (!info.accessToken.equals(targetAcc.optString("access", ""))) {
                        targetAcc.put("access", info.accessToken);
                        targetAcc.put("token", info.accessToken);
                        targetAcc.put("refresh", info.refreshToken);
                        targetAcc.put("expires", info.expiresAt);
                        saveConfig(configJson);
                    }
                    return jsonResponse(u);
                } catch (Exception e) {
                    // 上游 403/429 时常回整页 Cloudflare/OpenAI HTML，直接塞进 JSON 会撑到几 KB，
                    // 前端还会把整段 HTML 显示出来。这里压成一行、按上限截断，只留可读信息。
                    String msg = String.valueOf(e.getMessage());
                    String low = msg.toLowerCase();
                    if (low.contains("<html") || low.contains("<!doctype") || low.contains("<style")
                            || low.contains("cloudflare") || low.contains(".container{")) {
                        // 上游把 403/429 拦截图直接当正文回，里面全是 CSS/HTML，对用户毫无意义
                        String code = "403";
                        java.util.regex.Matcher cm = java.util.regex.Pattern
                                .compile("(\\d{3})").matcher(msg);
                        if (cm.find()) code = cm.group(1);
                        msg = "上游返回了拦截页面（HTTP " + code + "），通常是出口 IP 被风控或账号未订阅 Codex；"
                                + "请检查代理节点或重新登录后再试";
                    } else {
                        msg = msg.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
                        if (msg.length() > 220) msg = msg.substring(0, 220) + "…";
                    }
                    return errorResponse(500, "读取 Codex 用量失败: " + msg);
                }
            }

            // 3.10.4 ZCode gift 探测：按 ZCode 客户端完整形态发一次，判断 405 是形态问题还是账号被风控
            if ("/api/zcode/probe".equals(uri)) {
                JSONObject ret = new JSONObject();
                try {
                    JSONObject zc = getProviderById("zcode");
                    JSONObject acc = null;
                    JSONArray accs = zc == null ? null : zc.optJSONArray("accounts");
                    for (int i = 0; accs != null && i < accs.length(); i++) {
                        JSONObject a = accs.optJSONObject(i);
                        if (a != null && !a.optString("jwt", "").trim().isEmpty()) { acc = a; break; }
                    }
                    if (acc == null) {
                        ret.put("ok", false);
                        ret.put("error", "没有带 session 的 ZCode 账号");
                        return jsonResponse(ret);
                    }
                    String jwt = acc.optString("jwt", "");
                    String device = acc.optString("device", "");
                    String site = acc.optString("site", zc.optString("site", "zai"));

                    JSONArray msgs = new JSONArray();
                    JSONObject m0 = new JSONObject();
                    m0.put("role", "user");
                    m0.put("content", "只回复两个字：正常");
                    msgs.put(m0);

                    JSONObject body = new JSONObject();
                    body.put("model", "glm-5.3-flash");
                    body.put("max_tokens", 64);
                    body.put("messages", msgs);

                    // ① 完整 ZCode 形态（三块 system + 日期 turn + metadata.user_id + 整组替换的 header）
                    ZCodeGift.Answer full = ZCodeGift.send(jwt, device, site, "glm-5.3-flash",
                            new JSONObject(body.toString()), null, 60000);
                    JSONObject r1 = new JSONObject();
                    r1.put("label", "full-dress");
                    r1.put("status", full.status);
                    r1.put("refused", full.refused);
                    r1.put("edge", full.edge);
                    r1.put("body", full.body.length() > 600 ? full.body.substring(0, 600) : full.body);
                    if (!full.error.isEmpty()) r1.put("error", full.error);

                    // ② 裸请求作对照：不带 system，验证「形态」这一维是否影响
                    JSONObject bare = new JSONObject();
                    bare.put("model", "glm-5.3-flash");
                    bare.put("max_tokens", 16);
                    bare.put("messages", msgs);
                    ZCodeGift.Answer raw = ZCodeGift.send(jwt, device, site, "glm-5.3-flash",
                            new JSONObject(bare.toString()), null, 60000);
                    JSONObject r2 = new JSONObject();
                    r2.put("label", "bare");
                    r2.put("status", raw.status);
                    r2.put("refused", raw.refused);
                    r2.put("body", raw.body.length() > 300 ? raw.body.substring(0, 300) : raw.body);

                    JSONArray results = new JSONArray();
                    results.put(r1);
                    results.put(r2);

                    ret.put("ok", true);
                    ret.put("device", device);
                    ret.put("site", site);
                    ret.put("results", results);
                    return jsonResponse(ret);
                } catch (Exception e) {
                    try {
                        ret.put("ok", false);
                        ret.put("error", e.getMessage() == null ? "probe 失败" : e.getMessage());
                    } catch (Exception ignored) {}
                    return jsonResponse(ret);
                }
            }

            // 3.10.3 ZCode gift / Start Plan 额度：只有 session JWT 能花，API Key 花不到
            if ("/api/zcode/gift".equals(uri)) {
                Map<String, List<String>> params = session.getParameters();
                String action = (params.containsKey("action") && !params.get("action").isEmpty())
                        ? params.get("action").get(0) : "summary";
                JSONObject ret = new JSONObject();
                try {
                    JSONObject zc = getProviderById("zcode");
                    if (zc == null) {
                        ret.put("ok", false);
                        ret.put("error", "还没有 ZCode 供应商");
                        return jsonResponse(ret);
                    }
                    // 取第一个带 jwt 的账号
                    JSONObject acc = null;
                    JSONArray accs = zc.optJSONArray("accounts");
                    for (int i = 0; accs != null && i < accs.length(); i++) {
                        JSONObject a = accs.optJSONObject(i);
                        if (a != null && !a.optString("jwt", "").trim().isEmpty()) {
                            acc = a;
                            break;
                        }
                    }
                    if (acc == null) {
                        ret.put("ok", false);
                        ret.put("needSignIn", true);
                        ret.put("error", "这个 ZCode 账号只存了 API Key，没有 session。"
                                + "送的额度（gift / Start Plan）只认 session，请重新登录一次 ZCode 以补上。");
                        return jsonResponse(ret);
                    }
                    String jwt = acc.optString("jwt", "");
                    String device = acc.optString("device", "");
                    String site = acc.optString("site", zc.optString("site", "zai"));

                    if ("claim".equals(action)) {
                        String planId = (params.containsKey("planId") && !params.get("planId").isEmpty())
                                ? params.get("planId").get(0) : "";
                        if (planId.isEmpty()) {
                            ret.put("ok", false);
                            ret.put("error", "缺少 planId");
                            return jsonResponse(ret);
                        }
                        ZCodeAuth.giftClaim(jwt, device, planId);
                        ret.put("ok", true);
                        ret.put("claimed", planId);
                        // 领完立刻读一次余额，前端可以直接显示
                        ret.put("gift", ZCodeAuth.giftSummary(jwt, device));
                        return jsonResponse(ret);
                    }

                    if ("claimall".equals(action)) {
                        JSONObject sum = ZCodeAuth.giftSummary(jwt, device);
                        JSONArray claimable = sum.optJSONArray("claimable");
                        JSONArray done = new JSONArray();
                        JSONArray failed = new JSONArray();
                        for (int i = 0; claimable != null && i < claimable.length(); i++) {
                            JSONObject p = claimable.optJSONObject(i);
                            if (p == null) continue;
                            String pid = p.optString("planId", "");
                            if (pid.isEmpty()) continue;
                            try {
                                ZCodeAuth.giftClaim(jwt, device, pid);
                                done.put(pid);
                            } catch (Exception ce) {
                                JSONObject f = new JSONObject();
                                f.put("planId", pid);
                                f.put("error", ce.getMessage() == null ? "领取失败" : ce.getMessage());
                                failed.put(f);
                            }
                        }
                        ret.put("ok", true);
                        ret.put("claimed", done);
                        ret.put("failed", failed);
                        ret.put("gift", ZCodeAuth.giftSummary(jwt, device));
                        return jsonResponse(ret);
                    }

                    JSONObject sum = ZCodeAuth.giftSummary(jwt, device);
                    sum.put("account", acc.optString("name", ""));
                    sum.put("site", site);
                    sum.put("plan", acc.optString("plan", ""));
                    return jsonResponse(sum);
                } catch (Exception e) {
                    try {
                        ret.put("ok", false);
                        ret.put("error", e.getMessage() == null ? "gift 查询失败" : e.getMessage());
                    } catch (Exception ignored) {}
                    return jsonResponse(ret);
                }
            }

            // 3.10.2 供应商能力状态板：ported / passthrough / placeholder
            if ("/api/providers".equals(uri)) {
                return jsonResponse(buildProvidersBoard());
            }

            // 3.11 妙手 CatPaw 网页登录：问官方 login-config 取入口，挂 sid 后由服务端轮询
            if ("/api/auth/catpaw/init".equals(uri)) {
                JSONObject ret = new JSONObject();
                CatPawAuth.Flow flow = CatPawAuth.startLogin();
                if (flow.error != null && !flow.error.isEmpty()) {
                    ret.put("ok", false);
                    ret.put("error", flow.error);
                    return jsonResponse(ret);
                }
                long now = System.currentTimeMillis();
                java.util.Iterator<java.util.Map.Entry<String, Long>> it = catpawSids.entrySet().iterator();
                while (it.hasNext()) {
                    if (it.next().getValue().longValue() < now) it.remove();
                }
                catpawSids.put(flow.sid, Long.valueOf(now + 180000L));
                ret.put("ok", true);
                ret.put("url", flow.entryUrl);
                ret.put("sid", flow.sid);
                ret.put("intervalMs", 1000);
                return jsonResponse(ret);
            }

            // 3.11.1 妙手轮询：拿到 token 就入池（妙手没有 refresh，token 存账号里，过期重登）
            if ("/api/auth/catpaw/poll".equals(uri)) {
                Map<String, List<String>> params = session.getParameters();
                String sid = (params.containsKey("sid") && !params.get("sid").isEmpty())
                        ? params.get("sid").get(0) : "";
                JSONObject ret = new JSONObject();
                if (sid.isEmpty()) {
                    ret.put("ok", false);
                    ret.put("error", "缺少 sid");
                    return jsonResponse(ret);
                }
                Long deadline = catpawSids.get(sid);
                if (deadline == null) {
                    ret.put("ok", false);
                    ret.put("status", "failed");
                    ret.put("error", "登录流程已失效，请重新发起");
                    return jsonResponse(ret);
                }
                if (System.currentTimeMillis() > deadline.longValue()) {
                    catpawSids.remove(sid);
                    ret.put("ok", false);
                    ret.put("status", "failed");
                    ret.put("error", "登录超时（3 分钟内未取到登录态），请重试");
                    return jsonResponse(ret);
                }
                try {
                    String token = CatPawAuth.pollToken(sid);
                    if (token.isEmpty()) {
                        ret.put("ok", true);
                        ret.put("status", "pending");
                        return jsonResponse(ret);
                    }
                    catpawSids.remove(sid);
                    String[] who = CatPawAuth.currentUser(token);
                    String accName = who[1];
                    JSONObject dup = findDuplicateAccount(getProviderById("catpaw"), accName, token);
                    if (dup != null) {
                        ret.put("ok", false);
                        ret.put("status", "duplicate");
                        ret.put("duplicate", true);
                        ret.put("error", "妙手池内已经有「" + accName + "」了，本次未重复加入。");
                        return jsonResponse(ret);
                    }
                    String accId = addCatPawAccount(accName, token, who[0]);
                    ret.put("ok", true);
                    ret.put("status", "ready");
                    ret.put("account", accName);
                    ret.put("keyId", accId);
                    return jsonResponse(ret);
                } catch (Exception e) {
                    ret.put("ok", false);
                    ret.put("error", e.getMessage() == null ? "轮询失败" : e.getMessage());
                    return jsonResponse(ret);
                }
            }

            // 4. WorkBuddy 官方网页登录初始化 API: /api/auth/workbuddy/init-login
            if ("/api/auth/workbuddy/init-login".equals(uri)) {
                Map<String, List<String>> params = session.getParameters();
                String region = (params.containsKey("region") && !params.get("region").isEmpty())
                        ? params.get("region").get(0) : "cn";

                String baseUrl = "global".equalsIgnoreCase(region)
                        ? "https://www.workbuddy.ai"
                        : "https://copilot.tencent.com";

                String stateUrl = baseUrl + "/v2/plugin/auth/state?platform=CLI";
                HttpURLConnection conn = (HttpURLConnection) new URL(stateUrl).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Content-Type", "application/json");

                OutputStream os = conn.getOutputStream();
                os.write("{}".getBytes("UTF-8"));
                os.flush();
                os.close();

                if (conn.getResponseCode() >= 200 && conn.getResponseCode() < 400) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();

                    JSONObject upstreamRes = new JSONObject(sb.toString());
                    JSONObject data = upstreamRes.optJSONObject("data");
                    if (data != null) {
                        JSONObject ret = new JSONObject();
                        ret.put("ok", true);
                        ret.put("state", data.optString("state"));
                        ret.put("authUrl", data.optString("authUrl"));
                        ret.put("baseUrl", baseUrl);
                        return jsonResponse(ret);
                    }
                }
                return errorResponse(500, "获取官方授权链接失败");
            }

            // 5. WorkBuddy 登录状态轮询 API: /api/auth/workbuddy/poll-login
            if ("/api/auth/workbuddy/poll-login".equals(uri)) {
                Map<String, List<String>> params = session.getParameters();
                String state = params.containsKey("state") ? params.get("state").get(0) : "";
                String baseUrl = params.containsKey("baseUrl") ? params.get("baseUrl").get(0) : "https://copilot.tencent.com";

                if (state.isEmpty()) return errorResponse(400, "Missing state");

                String tokenUrl = baseUrl + "/v2/plugin/auth/token?state=" + URLEncoder.encode(state, "UTF-8");
                HttpURLConnection conn = (HttpURLConnection) new URL(tokenUrl).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                int respCode = conn.getResponseCode();
                InputStream is = (respCode >= 200 && respCode < 400) ? conn.getInputStream() : conn.getErrorStream();
                BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                JSONObject resJson = new JSONObject(sb.toString());
                int code = resJson.optInt("code", -1);

                // code == 0 说明用户已在网页扫码或登录成功！
                if (code == 0) {
                    JSONObject data = resJson.optJSONObject("data");
                    String token = "";
                    String refreshToken = "";
                    String username = "WorkBuddy用户";

                    if (data != null) {
                        token = data.optString("accessToken", "");
                        if (token.isEmpty()) token = data.optString("access_token", "");
                        if (token.isEmpty()) token = data.optString("token", "");

                        refreshToken = data.optString("refreshToken", "");
                        if (refreshToken.isEmpty()) refreshToken = data.optString("refresh_token", "");

                        username = data.optString("name", "");
                        if (username.isEmpty()) username = data.optString("nickname", "");
                        if (username.isEmpty()) username = data.optString("nickName", "");
                        if (username.isEmpty()) username = data.optString("userId", "WorkBuddy用户");
                    } else {
                        token = resJson.optString("accessToken", "");
                        if (token.isEmpty()) token = resJson.optString("access_token", "");
                        if (token.isEmpty()) token = resJson.optString("token", "");
                    }

                    if (!token.isEmpty()) {
                        // 自动加入账号池
                        addWorkBuddyAccount(username, token);

                        JSONObject ret = new JSONObject();
                        ret.put("ok", true);
                        ret.put("status", "success");
                        ret.put("username", username);
                        ret.put("token", token);
                        return jsonResponse(ret);
                    }
                }

                // 仍在等待用户登录
                JSONObject ret = new JSONObject();
                ret.put("ok", true);
                ret.put("status", "pending");
                ret.put("msg", resJson.optString("msg", "waiting"));
                return jsonResponse(ret);
            }

            // 6. 全平台通用账号入池 API: /api/auth/{provider}/add
            if (uri.startsWith("/api/auth/") && uri.endsWith("/add") && "POST".equalsIgnoreCase(method)) {
                String[] parts = uri.split("/");
                String targetProv = (parts.length >= 4) ? parts[3] : "workbuddy";

                String postData = rawBody;
                JSONObject req = new JSONObject(postData);
                String token = req.optString("token", "").trim();
                String name = req.optString("name", "免Key账号").trim();
                String customProv = req.optString("provider", targetProv);

                if ("deepseek_web".equals(customProv)) {
                    try {
                        token = deepCredential(req);
                        new DeepSeekWeb(deepSeekTransport,token).validateToken();
                        return jsonResponse(addDeepSeekAccount(name,token));
                    } catch (DeepSeekWeb.UpstreamException e) {
                        int status=DeepSeekWebChat.safeStatus(e);
                        return errorResponse(status,DeepSeekWebChat.safeErrorMessage(status));
                    }
                    catch (DeepSeekConfigFailure e) { return errorResponse(500,"网页账号保存失败"); }
                    catch (IOException e) { return errorResponse(502,"网页凭据验证失败，请检查网络"); }
                }
                if (token.isEmpty()) {
                    return errorResponse(400, "Token 凭据不能为空");
                }

                // placeholder 供应商只是配置壳（没有请求体构造与签名），
                // 让它入池会立刻把模型列出来、看起来能调，实际必然失败 —— 死配置装活。
                JSONObject guardProv = getProviderById(customProv);
                if (guardProv != null && "placeholder".equals(guardProv.optString("status", ""))) {
                    String pn = guardProv.optString("name", customProv);
                    return errorResponse(400, pn + " 尚未实装（placeholder）：它没有请求体构造与签名，"
                            + "加进去也调不通。对标关系见 README「与 magpie-community/plugins 的对标关系」。");
                }

                // 同名同账号防重复：默认拦下并让前端弹窗确认，force=true 时才真的重复入池
                boolean force = req.optBoolean("force", false);
                if (!force) {
                    JSONObject existProv = getProviderById(customProv);
                    JSONObject dup = findDuplicateAccount(existProv, name, token);
                    if (dup != null) {
                        String pName = (existProv != null && existProv.optString("name").length() > 0)
                                ? existProv.optString("name") : customProv;
                        return duplicateAccountResponse(pName, dup);
                    }
                }

                String accId = addProviderAccount(customProv, name, token);

                JSONObject res = new JSONObject();
                res.put("ok", true);
                res.put("id", accId);
                res.put("name", name);
                res.put("provider", customProv);
                res.put("message", "账号已成功加入网关池");
                return jsonResponse(res);
            }

            // 7. 全平台通用账号移除 API: /api/auth/{provider}/remove
            if (uri.startsWith("/api/auth/") && uri.endsWith("/remove") && "POST".equalsIgnoreCase(method)) {
                String[] parts = uri.split("/");
                String targetProv = (parts.length >= 4) ? parts[3] : "workbuddy";

                String postData = rawBody;
                JSONObject req = new JSONObject(postData);
                String removeId = req.optString("id", "");

                if ("deepseek_web".equals(targetProv)) {
                    try { removeDeepSeekAccount(removeId); }
                    catch(IOException e) { return errorResponse(500,"网页账号移除保存失败"); }
                    return jsonResponse(new JSONObject().put("ok",true));
                }
                JSONArray providers = configJson.optJSONArray("providers");
                if (providers != null) {
                    for (int i = 0; i < providers.length(); i++) {
                        JSONObject p = providers.getJSONObject(i);
                        // 只删目标供应商自己的账号。原来还 || "workbuddy"，等于删 catpaw 的账号时
                        // 也会进 workbuddy 里按同一个 id 找一遍 —— id 撞车就会误删别人的账号。
                        if (targetProv.equals(p.optString("id"))) {
                            JSONArray accounts = p.optJSONArray("accounts");
                            if (accounts != null) {
                                JSONArray newAccs = new JSONArray();
                                JSONArray newKeys = new JSONArray();
                                for (int j = 0; j < accounts.length(); j++) {
                                    JSONObject a = accounts.getJSONObject(j);
                                    if (!removeId.equals(a.optString("id"))) {
                                        newAccs.put(a);
                                        newKeys.put(a.optString("token"));
                                    }
                                }
                                p.put("accounts", newAccs);
                                p.put("keys", newKeys);
                                saveConfig(configJson);
                            }
                        }
                    }
                }
                JSONObject res = new JSONObject();
                res.put("ok", true);
                return jsonResponse(res);
            }

            // 7.2 全平台通用模型动态拉取 API: /api/auth/{provider}/sync-models
            if (uri.startsWith("/api/auth/") && (uri.endsWith("/sync-models") || uri.endsWith("/fetch-cloud-models"))) {
                String[] parts = uri.split("/");
                if (parts.length < 5) return errorResponse(400, "缺少供应商标识");
                String provId = parts[3];

                JSONObject prov = getProviderById(provId);
                if (prov == null) {
                    return errorResponse(404, "供应商不存在: " + provId);
                }

                if ("deepseek-web".equals(prov.optString("protocol"))) {
                    if (!providerHasCredential(prov)) return errorResponse(401,"请先添加网页账号");
                    JSONArray modes=new JSONArray(); for(String m:DeepSeekWeb.MODELS) modes.put(m);
                    prov.put("models",modes); saveConfig(configJson);
                    return jsonResponse(new JSONObject().put("ok",true).put("provider",provId).put("count",modes.length()).put("models",modes).put("message","网页模式别名，非云端模型目录"));
                }
                String token = "";
                JSONArray accounts = prov.optJSONArray("accounts");
                if (accounts != null && accounts.length() > 0) {
                    token = accounts.getJSONObject(0).optString("token", "");
                }
                if (token.isEmpty()) {
                    JSONArray keys = prov.optJSONArray("keys");
                    if (keys != null && keys.length() > 0) {
                        token = keys.optString(0, "");
                    }
                }
                if (token.isEmpty() && !"cline".equals(provId) && !"codex".equals(provId)) {
                    JSONArray existingModels = prov.optJSONArray("models");
                    if (existingModels != null && existingModels.length() > 0) {
                        JSONObject ret = new JSONObject();
                        ret.put("ok", true);
                        ret.put("provider", provId);
                        ret.put("count", existingModels.length());
                        ret.put("models", existingModels);
                        ret.put("message", prov.optString("name", provId) + " 预置 " + existingModels.length() + " 款模型已就绪（调用前请先配置账号入池）");
                        return jsonResponse(ret);
                    }
                    // 既没凭据也没预置清单（例如未实装的占位供应商）：不要抛错误弹窗，
                    // 返回 ok + 0 条 + 说明，面板会显示"暂无模型数据"并给出原因。
                    JSONObject ret0 = new JSONObject();
                    ret0.put("ok", true);
                    ret0.put("provider", provId);
                    ret0.put("count", 0);
                    ret0.put("models", new JSONArray());
                    ret0.put("message", prov.optString("name", provId) + " 尚未实装或暂无内置模型，请先配置账号/API Key");
                    return jsonResponse(ret0);
                }

                String provName = prov.optString("name", provId);
                List<String> fetched;
                JSONObject modelMeta = new JSONObject();

                if ("codex".equals(provId)) {
                    CodexAuth.SignInInfo cinfo = new CodexAuth.SignInInfo();
                    JSONArray caccs = prov.optJSONArray("accounts");
                    if (caccs != null && caccs.length() > 0) {
                        JSONObject ca = caccs.optJSONObject(0);
                        if (ca != null) {
                            cinfo.accessToken = ca.optString("access", ca.optString("token", ""));
                            cinfo.refreshToken = ca.optString("refresh", "");
                            cinfo.idToken = ca.optString("idToken", "");
                            cinfo.accountId = ca.optString("accountId", "");
                            cinfo.authMode = ca.optString("authMode", "chatgpt");
                            cinfo.expiresAt = ca.optLong("expires", 0);
                        }
                    }
                    fetched = CodexAuth.syncModels(cinfo);
                } else if ("cline".equals(provId)) {
                    fetched = new ArrayList<String>();
                    JSONObject cmeta = new JSONObject();
                    List<String> cms = ClineAuth.fetchFeed(cmeta);
                    for (String cm : cms) fetched.add(cm);
                    java.util.Iterator<String> ck = cmeta.keys();
                    while (ck.hasNext()) {
                        String kk = ck.next();
                        modelMeta.put(kk, cmeta.opt(kk));
                    }
                } else if ("raccoon".equals(provId)) {
                    fetched = fetchRaccoonModels(token, modelMeta);
                } else if ("qoder_cn".equals(provId)) {
                    JSONObject acc = null;
                    JSONArray qaccs = prov.optJSONArray("accounts");
                    if (qaccs != null && qaccs.length() > 0) acc = qaccs.optJSONObject(0);
                    if (acc == null) return errorResponse(400, "请先登录 Qoder CN 再同步模型");
                    QoderAuth.SignIn qsi = new QoderAuth.SignIn();
                    qsi.access = acc.optString("token", "");
                    qsi.deviceToken = acc.optString("deviceToken", "");
                    qsi.uid = acc.optString("uid", "");
                    qsi.email = acc.optString("email", "");
                    qsi.name = acc.optString("name", "");
                    qsi.machineId = acc.optString("machineId", "");
                    qsi.deviceChat = acc.optBoolean("deviceChat", false);
                    fetched = new ArrayList<String>();
                    JSONObject qmeta = new JSONObject();
                    JSONArray qms = QoderAuth.fetchModels(qsi, qmeta);
                    for (int qi = 0; qi < qms.length(); qi++) fetched.add(qms.optString(qi));
                    // 注意：这里必须合入公共的 modelMeta 变量，
                    // 因为尾部还有 prov.put("modelMeta", modelMeta) 会整体覆盖
                    java.util.Iterator<String> qk = qmeta.keys();
                    while (qk.hasNext()) {
                        String kk = qk.next();
                        modelMeta.put(kk, qmeta.opt(kk));
                    }
                } else if ("catpaw".equals(provId)) {
                    // 妙手的模型是桌面客户端内置表，没有远端目录接口
                    fetched = new ArrayList<String>();
                    JSONArray cm = CatPawAuth.modelList();
                    for (int ci = 0; ci < cm.length(); ci++) fetched.add(cm.optString(ci));
                    JSONObject cmm = CatPawAuth.modelMeta();
                    java.util.Iterator<String> ck = cmm.keys();
                    while (ck.hasNext()) {
                        String kk = ck.next();
                        modelMeta.put(kk, cmm.opt(kk));
                    }
                } else if ("github-copilot".equals(provId)) {
                    JSONObject cacc = null;
                    JSONArray caccs = prov.optJSONArray("accounts");
                    for (int ai = 0; caccs != null && ai < caccs.length(); ai++) {
                        JSONObject cand = caccs.optJSONObject(ai);
                        if (cand != null && !cand.optString("refresh", "").trim().isEmpty()) { cacc = cand; break; }
                    }
                    if (cacc == null) return errorResponse(400, "请先登录 GitHub Copilot 再同步模型");
                    String ctoken = CopilotAuth.ensureCopilotToken(cacc);
                    if (ctoken == null || ctoken.trim().isEmpty()) {
                        return errorResponse(401, "Copilot token 续期失败，请重新登录 GitHub 账号");
                    }
                    saveConfig(configJson);
                    fetched = CopilotAuth.models(ctoken);
                } else if ("minimax-code".equals(provId)) {
                    JSONObject macc = null;
                    JSONArray maccs = prov.optJSONArray("accounts");
                    for (int ai = 0; maccs != null && ai < maccs.length(); ai++) {
                        JSONObject cand = maccs.optJSONObject(ai);
                        if (cand != null && !cand.optString("refresh", "").trim().isEmpty()) { macc = cand; break; }
                    }
                    if (macc == null) return errorResponse(400, "请先登录 MiniMax Code 再同步模型");
                    if (MiniMaxAuth.ensureToken(macc)) saveConfig(configJson);
                    fetched = MiniMaxAuth.models(macc.optString("access", macc.optString("token", "")));
                } else if ("antigravity".equals(provId)) {
                    JSONObject aacc = null;
                    JSONArray aaccs = prov.optJSONArray("accounts");
                    for (int ai = 0; aaccs != null && ai < aaccs.length(); ai++) {
                        JSONObject cand = aaccs.optJSONObject(ai);
                        if (cand != null && !cand.optString("token", "").trim().isEmpty()) { aacc = cand; break; }
                    }
                    if (aacc == null) return errorResponse(400, "请先登录 Antigravity（Google 账号）再同步模型");
                    String atoken;
                    String aproject;
                    JSONArray ams;
                    try {
                        atoken = AntigravityAuth.ensureToken(aacc);
                        aproject = AntigravityAuth.ensureProject(aacc, atoken);
                        ams = AntigravityAuth.fetchModels(aacc, atoken, aproject);
                    } catch (Exception fe) {
                        return errorResponse(502, "Antigravity 拉模型失败：" + fe.getMessage());
                    }
                    // 把同一模型的思考档位（-low/-medium/-high）合并成一个模型，
                    // 真实 id 记进 modelMeta.variants，请求时按 reasoning_effort 选
                    JSONArray fam = AntigravityAuth.collapseFamilies(ams, modelMeta);
                    fetched = new ArrayList<String>();
                    for (int ai = 0; ai < fam.length(); ai++) {
                        String fid = fam.optString(ai, "");
                        if (!fid.isEmpty()) fetched.add(fid);
                    }
                    prov.put("models", fam);
                    prov.put("modelMeta", modelMeta);
                    saveConfig(configJson);
                    JSONObject r1 = new JSONObject();
                    r1.put("ok", true);
                    r1.put("provider", provId);
                    r1.put("count", fetched.size());
                    r1.put("models", fam);
                    r1.put("message", "成功从 Antigravity 拉到 " + fetched.size() + " 个模型（同系列思考档位已合并）");
                    return jsonResponse(r1);
                } else if ("zcode".equals(provId)) {
                    fetched = fetchZCodeModels(prov.optString("baseUrl", ""), token);
                } else if ("workbuddy".equals(provId)) {
                    fetched = fetchOfficialModels(token, modelMeta);
                } else if (isStandardProvider(provId)) {
                    fetched = fetchStandardModels(provId, prov.optString("baseUrl", ""), token, modelMeta);
                } else {
                    return errorResponse(400, provName + " 属于网页账号池，官方暂未提供公开模型列表 API，当前使用内置预置模型集");
                }

                if (fetched == null || fetched.isEmpty()) {
                    return errorResponse(500, "未能从云端解析到模型列表，可能账号 Token 已失效");
                }

                JSONArray arr = new JSONArray();
                for (String m : fetched) arr.put(m);
                prov.put("models", arr);
                prov.put("modelMeta", modelMeta);
                saveConfig(configJson);

                JSONObject ret = new JSONObject();
                ret.put("ok", true);
                ret.put("provider", provId);
                ret.put("count", fetched.size());
                ret.put("models", arr);
                ret.put("message", "成功从 " + provName + " 云端拉取到 " + fetched.size() + " 个最新可用模型");
                return jsonResponse(ret);
            }

            // 7.3 用户手动添加模型 API: /api/auth/workbuddy/add-model
            if ("/api/auth/workbuddy/add-model".equals(uri) && "POST".equalsIgnoreCase(method)) {
                String postData = rawBody;
                JSONObject req = (postData != null && !postData.isEmpty()) ? new JSONObject(postData) : new JSONObject();
                String newModelName = req.optString("model", "").trim();
                if (!newModelName.isEmpty()) {
                    JSONObject wbProv = getWorkBuddyProvider();
                    if (wbProv != null) {
                        JSONArray models = wbProv.optJSONArray("models");
                        if (models == null) {
                            models = new JSONArray();
                            wbProv.put("models", models);
                        }
                        boolean exists = false;
                        for (int i = 0; i < models.length(); i++) {
                            if (newModelName.equalsIgnoreCase(models.getString(i))) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) {
                            models.put(newModelName);
                            saveConfig(configJson);
                        }
                    }
                }
                JSONObject ret = new JSONObject();
                ret.put("ok", true);
                return jsonResponse(ret);
            }

            // 7.5 真实端到端模型连通性测试
            //
            // 这里不再自己拼 baseUrl + /chat/completions —— 那是「第二套 HTTP 实现」，
            // 一加新协议（catpaw / qoder）它就不认识，于是出现「网关能调、点测试却失败」的假阴性。
            // 现在测试直接走真实转发管线：测试是什么，调用就是什么。
            if ("/api/test-model".equals(uri) && "POST".equalsIgnoreCase(method)) {
                String postData = rawBody;
                JSONObject req = (postData != null && !postData.isEmpty()) ? new JSONObject(postData) : new JSONObject();
                String testModel = req.optString("model", "workbuddy/hy3");

                // zcode 多一层赠送额度信息，先单独问（能顺带把余额数带回来）
                if ("zcode".equals(providerIdOf(testModel))) {
                    JSONObject giftProbe = probeZCodeGift(testModel);
                    if (giftProbe != null) return jsonResponse(giftProbe);
                }

                List<RouteTarget> cands = resolveCandidates(testModel);
                if (cands.isEmpty()) {
                    JSONObject errRet = new JSONObject();
                    errRet.put("ok", false);
                    errRet.put("model", testModel);
                    errRet.put("error", "未配置有效密钥或账号未就绪 (" + testModel + ")");
                    return jsonResponse(errRet);
                }
                RouteTarget target = cands.get(0);

                JSONObject payload = new JSONObject();
                payload.put("model", testModel);
                // 思维链模型 16 个 token 会全烧在 reasoning 上、正文为空，看起来像失败
                if (!"deepseek-web".equals(target.protocol)) payload.put("max_tokens", 512);
                JSONArray pingMsgs = new JSONArray();
                JSONObject pingM0 = new JSONObject();
                pingM0.put("role", "user");
                pingM0.put("content", "1+1=?");
                pingMsgs.put(pingM0);
                payload.put("messages", pingMsgs);

                PrivacyMasker.Session pingMask = new PrivacyMasker.Session();
                ContextGuard.Config pingGuard = ContextGuard.Config.fromJson(configJson.optJSONObject("guard"));
                long testStart = System.currentTimeMillis();
                Attempt a = forwardAttempt(target, payload, false, testModel, pingMask, pingGuard, null);
                long latency = System.currentTimeMillis() - testStart;

                JSONObject ret = new JSONObject();
                ret.put("model", testModel);
                ret.put("latencyMs", latency);
                ret.put("statusCode", a.ok ? 200 : (a.status <= 0 ? 502 : a.status));
                ret.put("provider", target.providerId);
                if ("zcode".equals(target.providerId)) {
                    ret.put("billing", "plan");
                    ret.put("billingName", "GLM Coding Plan");
                }

                if (a.ok) {
                    String reply = a.textOut == null ? "" : a.textOut.trim();
                    if (reply.isEmpty() && a.reasoningOut != null) reply = a.reasoningOut.trim();
                    if (reply.isEmpty()) reply = "握手成功";
                    if (reply.length() > 80) reply = reply.substring(0, 80) + "...";
                    ret.put("ok", true);
                    ret.put("reply", reply);
                } else {
                    ret.put("ok", false);
                    String err = (a.body == null || a.body.isEmpty()) ? ("HTTP " + a.status) : a.body;
                    if (err.length() > 300) err = err.substring(0, 300);
                    ret.put("error", err);
                    if ("zcode".equals(target.providerId) && err.contains("1113")) {
                        ret.put("hint", "这个型号不在 ZCode 赠送额度的覆盖范围内，只能走 GLM Coding Plan；"
                                + "账号没有 Plan 或 Plan 额度用尽时就会返回 1113。");
                    }
                }
                return jsonResponse(ret);
            }

            // 8. 脱敏测试沙盒
            if ("/api/test-mask".equals(uri)) {
                String postData = rawBody;
                JSONObject req = new JSONObject(postData);
                String text = req.optString("text", "");

                PrivacyMasker.Session maskSession = new PrivacyMasker.Session();
                PrivacyMasker.Config mcfg = PrivacyMasker.Config.fromJson(configJson.optJSONObject("privacy"));
                String masked = PrivacyMasker.maskText(text, maskSession, mcfg);
                String unmasked = PrivacyMasker.unmaskText(masked, maskSession);

                JSONObject res = new JSONObject();
                res.put("original", text);
                res.put("masked", masked);
                res.put("unmasked", unmasked);
                // 明确告诉前端这次是否真的走了脱敏，避免"开关关了"被误读成"功能坏了"
                res.put("applied", mcfg.enabled);
                res.put("hits", maskSession.interceptedCount);
                JSONArray cats = new JSONArray();
                if (mcfg.maskApiKeys) cats.put("API Key");
                if (mcfg.maskEmails) cats.put("邮箱");
                if (mcfg.maskPhones) cats.put("手机号");
                res.put("categories", mcfg.enabled ? cats : new JSONArray());
                return jsonResponse(res);
            }

            // 9. 模型列表接口 (/v1/models)
            if ("/v1/models".equals(uri)) {
                // 默认只列出「真的能调通」的模型：没账号/没 key 的供应商一律不出现，
                // 否则 17 个从未配置的供应商会把 88 个模型全吐出来，把可用的 40 个淹掉。
                boolean listAll = false;
                Map<String, List<String>> mp = session.getParameters();
                if (mp.containsKey("all") && !mp.get("all").isEmpty()) {
                    String av = mp.get("all").get(0);
                    listAll = "1".equals(av) || "true".equalsIgnoreCase(av);
                }

                JSONObject res = new JSONObject();
                res.put("object", "list");
                JSONArray arr = new JSONArray();
                JSONArray skipped = new JSONArray();   // 被隐藏的供应商，便于排查

                // 用户挑过的白名单：开了就只暴露勾选的 provider/model（group 也按 group/<id> 匹配）
                JSONObject exposedCfg = configJson.optJSONObject("exposed");
                boolean onlyListed = exposedCfg != null && exposedCfg.optBoolean("enabled", false);
                final java.util.Set<String> allowSet = new java.util.HashSet<String>();
                if (onlyListed) {
                    JSONArray its = exposedCfg.optJSONArray("items");
                    for (int i = 0; its != null && i < its.length(); i++) {
                        String v = its.optString(i, "").trim();
                        if (!v.isEmpty()) allowSet.add(v);
                    }
                }

                JSONArray groups = configJson.optJSONArray("groups");
                if (groups != null && groupsEnabled()) {
                    for (int i = 0; i < groups.length(); i++) {
                        JSONObject g = groups.getJSONObject(i);
                        String gid = "group/" + g.optString("id");
                        if (!listAll && !groupHasLiveTarget(g)) {
                            skipped.put(gid);
                            continue;
                        }
                        if (onlyListed && !allowSet.contains(gid)) continue;
                        JSONObject m = new JSONObject();
                        m.put("id", gid);
                        m.put("object", "model");
                        m.put("owned_by", "magpie");
                        arr.put(m);
                    }
                }

                JSONArray providers = configJson.optJSONArray("providers");
                if (providers != null) {
                    for (int i = 0; i < providers.length(); i++) {
                        JSONObject p = providers.getJSONObject(i);
                        String pid = p.optString("id");
                        if (!listAll && !providerHasCredential(p)) {
                            JSONArray models0 = p.optJSONArray("models");
                            if (models0 != null && models0.length() > 0) skipped.put(pid);
                            continue;
                        }
                        JSONArray models = p.optJSONArray("models");
                        // zcode 账号如果只有赠送额度（没有 Coding Plan），就只列 gift 真正支持的模型，
                        // 否则客户端会挑到那些一调就 1113 的型号。
                        if ("zcode".equals(pid)) {
                            JSONObject zacc = zcodeSessionAccount();
                            if (zacc != null && zacc.optString("plan", "").isEmpty()) {
                                java.util.List<String> allowed = ZCodeGift.allowedModels(
                                        currentGift(zacc.optString("jwt", ""), zacc.optString("device", "")));
                                if (!allowed.isEmpty()) {
                                    JSONArray only = new JSONArray();
                                    for (String am : allowed) only.put(am);
                                    models = only;
                                }
                            }
                        }
                        if (models != null) {
                            for (int j = 0; j < models.length(); j++) {
                                String mName = models.getString(j);
                                String fullId = pid + "/" + mName;
                                if (onlyListed && !allowSet.contains(fullId)) continue;
                                JSONObject m = new JSONObject();
                                m.put("id", fullId);
                                m.put("object", "model");
                                m.put("owned_by", pid);
                                arr.put(m);
                            }
                        }
                    }
                }

                res.put("data", arr);
                if (!listAll) {
                    res.put("total_available", arr.length());
                    res.put("hidden_unconfigured", skipped);
                }
                if (onlyListed) res.put("exposed_only", true);
                return jsonResponse(res);
            }

            // 10. 核心转发接口：/v1/chat/completions 与 /v1/messages
            if ("/v1/chat/completions".equals(uri) || "/v1/messages".equals(uri)) {
                String postData = rawBody;
                if (postData == null || postData.isEmpty()) {
                    return errorResponse(400, "Empty payload");
                }

                totalRequests.incrementAndGet();

                PrivacyMasker.Session maskSession = new PrivacyMasker.Session();
                PrivacyMasker.Config privCfg = PrivacyMasker.Config.fromJson(configJson.optJSONObject("privacy"));
                String maskedPayloadStr = PrivacyMasker.maskText(postData, maskSession, privCfg);
                totalMasked.addAndGet(maskSession.interceptedCount);
                lastMaskedCount = maskSession.interceptedCount;
                lastMaskEnabled = privCfg.enabled;
                lastMaskAt = System.currentTimeMillis();

                JSONObject payload = new JSONObject(maskedPayloadStr);
                String requestedModel = payload.optString("model", "deepseek-chat");
                String validationError = validateRawMessages(payload.opt("messages"));
                if (validationError != null) return errorResponse(400, validationError);

                // ===== /v1/messages 的客户端（Claude Code 等）发的是 Anthropic 格式 =====
                // 以前把这种请求直接塞给 OpenAI 上游，system / tools 字段名对不上会被静默忽略，
                // 等于项目指令和工具定义全丢；而原生直通分支又绕过了上下文保护。
                // 现在统一转成 OpenAI 走完整管线（guard / 路由 / 回退都生效），
                // 响应回来再转回 Anthropic，客户端完全无感。
                // /v1/messages 是 Anthropic 协议端点，语义上就是 Anthropic 格式：
                // 以前靠 looksAnthropicPayload 启发式判断，像 {"model","max_tokens","messages":[{content:"字符串"}]}
                // 这种最常见的最小请求会被判成非 Anthropic，于是返回 OpenAI 格式给 Claude 客户端，解析直接失败。
                // 现在该端点无条件按 Anthropic 处理（AnthropicBridge 也能接受字符串 content），
                // 只有明确带 OpenAI 专属字段时才当作 OpenAI 请求。
                if ("/v1/messages".equals(uri) && !looksOpenAIPayload(payload)) {
                    try {
                        JSONObject nativeRequest = new JSONObject(payload.toString());
                        payload = AnthropicBridge.requestToOpenAI(payload);
                        NATIVE_REQUEST.set(nativeRequest);
                        NATIVE_BASELINE.set(new JSONObject(payload.toString()));
                        NATIVE_DIRECT_ALLOWED.set(Boolean.TRUE);
                        CLIENT_ANTHROPIC.set(Boolean.TRUE);
                    } catch (Exception convErr) {
                        return errorResponse(400, "Anthropic 请求转换失败: " + convErr.getMessage());
                    }
                }

                // ===== 网关中间件（model-map / param-override / system-prompt / word-guard）=====
                // 放在这里的原因：Anthropic 请求已经转成标准 OpenAI 形状，字段命名统一；
                // 又早于上下文保护，这样注入的 system 与封顶后的 max_tokens 都能被 guard 计入。
                JSONObject mwCfg = configJson.optJSONObject("middleware");
                if (mwCfg != null) {
                    try {
                        // model-map：别名换成真实模型。replyModel 留着给响应，客户端看到的仍是它请求的名字
                        String mapped = Middleware.mapModel(requestedModel, providerIdOf(requestedModel), mwCfg);
                        if (!mapped.equals(requestedModel)) {
                            payload.put("model", mapped);
                            CLIENT_MODEL.set(requestedModel);   // 回复要报原名
                            requestedModel = mapped;
                        }
                        java.util.List<String> mwApplied = Middleware.runRequest(payload, providerIdOf(requestedModel), mwCfg);
                        for (int mi = 0; mi < mwApplied.size(); mi++) {
                            String tag = mwApplied.get(mi);
                            if (tag.startsWith("word-guard-block:")) {
                                String hit = tag.substring("word-guard-block:".length());
                                return errorResponse(400,
                                        "请求被 word-guard 拦截：命中敏感词「" + hit + "」（可在「用量与治理」里改成打码模式）");
                            }
                        }
                    } catch (Throwable mwErr) {
                        // 中间件出错一律原样放行，绝不因此让请求失败
                        mwErr.printStackTrace();
                    }
                }

                try {
                    normalizeCoreMessages(payload);
                } catch (Exception invalidMessages) {
                    return errorResponse(400, "invalid_messages: " + invalidMessages.getMessage());
                }

                // ===== 上下文保护：按用户设置的「网关最大上下文 / 请求体上限 / 输出上限」主动裁剪与封顶 =====
                ContextGuard.Config guardCfg = ContextGuard.Config.fromJson(configJson.optJSONObject("guard"));
                ContextGuard.Result guardResult = null;
                try {
                    guardResult = ContextGuard.fit(payload, guardCfg);
                    if (guardResult.error != null || guardResult.budgetExceeded) {
                        String reason = guardResult.error != null ? guardResult.error : "budget_exceeded";
                        return errorResponse("budget_exceeded".equals(reason) ? 413 : 400, reason);
                    }
                    payload = guardResult.payload;
                    // Native content can be richer than the OpenAI representation. Verify the
                    // original masked body independently against the same guard budgets.
                    JSONObject nativeBody = NATIVE_REQUEST.get();
                    if (nativeBody != null) {
                        JSONObject nativeGuardInput = new JSONObject(nativeBody.toString());
                        if (payload.optInt("max_tokens", 0) > 0) nativeGuardInput.put("max_tokens", payload.optInt("max_tokens"));
                        ContextGuard.Result nativeGuard = ContextGuard.fit(nativeGuardInput, guardCfg);
                        if (nativeGuard.error != null || nativeGuard.budgetExceeded || nativeGuard.changed
                                || !jsonEquivalent(nativeGuardInput, nativeGuard.payload)) {
                            NATIVE_DIRECT_ALLOWED.set(Boolean.FALSE);
                        }
                    }
                    if (guardResult.changed) {
                        lastGuardStats = guardResult.toJson();
                        if (guardResult.dropped > 0) totalContextDropped.addAndGet(guardResult.dropped);
                    }
                } catch (Exception guardErr) {
                    guardErr.printStackTrace();
                    return errorResponse(500, "上下文保护处理失败");
                }

                // 记录客户端真实意图：WorkBuddy 上游强制流式，不能因此把非流式客户端也变成 SSE。
                //
                // 判定要同时看 body 和 Accept：
                //   - body 里 stream 明确写了 true / false → 以 body 为准（OpenAI 规范）；
                //   - body 没写 stream，但 Accept 含 text/event-stream → 按流式处理。
                // 之前只看 body，导致「用 Accept 声明要流式」的客户端拿到一整块 JSON，
                // 表现就是「模型生成完了才一次性返回」。
                boolean clientWantsStream = wantsStream(payload, session);

                // ===== OpenAI 形态的 zcode 请求优先走 gift：赠送额度只认 session，Key 花不到。
                // 模型不在赠送额度支持范围内时返回 null，继续走原来的 Coding Plan 路径。
                if ("zcode".equals(providerIdOf(requestedModel))) {
                    Response gift = tryZCodeGiftOpenAI(payload, requestedModel, clientWantsStream,
                            maskSession, guardResult);
                    if (gift != null) return gift;
                }

                // ===== 候选与降级：对齐 magpie 的 order 语义 =====
                // 用第一个候选直到它答不出来，再换下一个；
                // 且只在「还没有把任何回复字节写给客户端」之前换，
                // 所以客户端永远不会拿到半段 A、半段 B 的回复。
                List<RouteTarget> candidates = resolveCandidates(requestedModel);
                if (candidates.isEmpty()) {
                    if (requestedModel != null && requestedModel.startsWith("group/") && !groupsEnabled()) {
                        return errorResponse(403, "路由组未启用（默认关闭）：请直接使用 provider/model 选具体模型，"
                                + "或在面板「路由」页打开路由组再调用 " + requestedModel);
                    }
                    // 区分「供应商根本不存在（写错模型名）」和「供应商在但没有可用凭据」
                    String pid = providerIdOf(requestedModel);
                    if (pid != null && getProviderById(pid) == null) {
                        return errorResponse(404, "未知供应商: " + pid
                                + "（模型要写成 provider/model，供应商列表见 /api/providers）");
                    }
                    JSONObject missing=pid==null?null:getProviderById(pid);
                    if (missing!=null && "deepseek-web".equals(missing.optString("protocol")) && !providerHasCredential(missing))
                        return errorResponse(401,"请先添加网页账号: " + pid);
                    return errorResponse(400, "No available API Key or active account for model: " + requestedModel);
                }

                JSONObject foCfg = failoverJson();
                int maxTries = foCfg.optBoolean("enabled", true)
                        ? Math.max(1, foCfg.optInt("maxTargets", 3)) : 1;
                maxTries = Math.min(maxTries, candidates.size());

                Attempt last = null;
                for (int ci = 0; ci < maxTries; ci++) {
                    RouteTarget target = candidates.get(ci);
                    last = forwardAttempt(target, payload, clientWantsStream, requestedModel,
                            maskSession, guardCfg, guardResult);
                    if (last.ok) return last.response;
                    // 换谁都一样（如上下文超长）：不再换，直接把上游的话还给 agent 让它 compact
                    if (!retryable(last.status, last.body)) return last.response;
                    // 失败只让这个候选休息一会并排到队尾，不永久拉黑
                    rest(target, last.status);
                }
                if (last != null) return last.response;
                return errorResponse(502, "all candidates failed for model: " + requestedModel);
            }

        } catch (org.json.JSONException je) {
            // 空 body / 非法 JSON 是调用方的问题，不该报 500，也不该把 Java 异常信息回给客户端
            return errorResponse(400, "请求体不是合法 JSON: " + je.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            String m = e.getMessage() == null ? "内部错误" : e.getMessage();
            if (m.length() > 500) m = m.substring(0, 500) + "…";
            return errorResponse(500, m);
        }

        return errorResponse(404, "Not Found");
    }

    /**
     * 把 Qoder CN 登录得到的凭据写进供应商池。
     * 注意：Qoder 的聊天不走 OpenAI 兼容接口，必须过 COSY 签名，
     * 所以这里存的是"账号凭据"而不是普通 API Key。
     */
    /**
     * 把 Cline (cline.bot) 登录得到的凭据写进供应商池。
     */
    private synchronized String addClineAccount(ClineAuth.SignIn si, String accName) {
        try {
            JSONArray providers = configJson.optJSONArray("providers");
            if (providers == null) {
                providers = new JSONArray();
                configJson.put("providers", providers);
            }

            JSONObject cp = null;
            for (int i = 0; i < providers.length(); i++) {
                JSONObject p = providers.optJSONObject(i);
                if (p != null && "cline".equals(p.optString("id"))) {
                    cp = p;
                    break;
                }
            }
            if (cp == null) {
                cp = new JSONObject();
                cp.put("id", "cline");
                cp.put("name", "Cline (cline.bot)");
                cp.put("status", "ported");
                cp.put("plugin", "packages/cline");
                cp.put("baseUrl", "https://api.cline.bot/api/v1");
                cp.put("protocol", "cline");
                cp.put("note", "WorkOS 设备流 / API Key；OpenAI 兼容；推荐模型免登录");
                cp.put("enabled", true);
                cp.put("models", new JSONArray());
                cp.put("keys", new JSONArray());
                cp.put("accounts", new JSONArray());
                providers.put(cp);
            }

            JSONArray accounts = cp.optJSONArray("accounts");
            if (accounts == null) {
                accounts = new JSONArray();
                cp.put("accounts", accounts);
            }

            String accId = "cline_" + System.currentTimeMillis();
            JSONObject acc = new JSONObject();
            acc.put("id", accId);
            acc.put("type", "oauth");
            acc.put("name", accName);
            acc.put("token", si.access == null ? "" : si.access);
            acc.put("access", si.access == null ? "" : si.access);
            acc.put("refresh", si.refresh == null ? "" : si.refresh);
            acc.put("expires", si.expires);
            if (si.uid != null && !si.uid.isEmpty()) acc.put("uid", si.uid);
            if (si.email != null && !si.email.isEmpty()) acc.put("email", si.email);
            acc.put("addedAt", System.currentTimeMillis());
            accounts.put(acc);

            JSONArray keys = cp.optJSONArray("keys");
            if (keys == null) {
                keys = new JSONArray();
                cp.put("keys", keys);
            }
            keys.put(si.access);

            saveConfig(configJson);
            return accId;
        } catch (Exception e) {
            e.printStackTrace();
            return "err";
        }
    }

    /** GitHub Copilot 账号入池：refresh 存 GitHub 长期 token，access 存 Copilot 短期 token */
    private synchronized String addCopilotAccount(CopilotAuth.SignIn si, String accName) {
        try {
            JSONArray providers = configJson.optJSONArray("providers");
            if (providers == null) {
                providers = new JSONArray();
                configJson.put("providers", providers);
            }
            JSONObject cp = null;
            for (int i = 0; i < providers.length(); i++) {
                JSONObject p = providers.optJSONObject(i);
                if (p != null && "github-copilot".equals(p.optString("id"))) {
                    cp = p;
                    break;
                }
            }
            if (cp == null) {
                cp = new JSONObject();
                cp.put("id", "github-copilot");
                cp.put("name", "GitHub Copilot");
                cp.put("status", "ported");
                cp.put("plugin", "packages/copilot");
                cp.put("baseUrl", CopilotAuth.BASE);
                cp.put("protocol", "copilot");
                cp.put("note", "GitHub 官方设备流；Copilot token 30 分钟自动续；模型目录 /models");
                cp.put("enabled", true);
                cp.put("models", new JSONArray());
                cp.put("keys", new JSONArray());
                cp.put("accounts", new JSONArray());
                providers.put(cp);
            }
            JSONArray accounts = cp.optJSONArray("accounts");
            if (accounts == null) {
                accounts = new JSONArray();
                cp.put("accounts", accounts);
            }
            String accId = "copilot_" + System.currentTimeMillis();
            JSONObject acc = new JSONObject();
            acc.put("id", accId);
            acc.put("type", "oauth");
            acc.put("name", accName);
            acc.put("githubLogin", si.githubLogin == null ? "" : si.githubLogin);
            acc.put("refresh", si.githubToken == null ? "" : si.githubToken);
            acc.put("access", si.copilotToken == null ? "" : si.copilotToken);
            acc.put("token", si.copilotToken == null ? "" : si.copilotToken);
            acc.put("expires", si.expires);
            acc.put("addedAt", System.currentTimeMillis());
            accounts.put(acc);
            saveConfig(configJson);
            return accId;
        } catch (Exception e) {
            e.printStackTrace();
            return "err";
        }
    }

    /** 对单个 MiniMax 账号跑一次签到，并把结果写回账号（date/outcome/credit/uid） */
    private synchronized JSONObject minimaxCheckinAccount(JSONObject acc) {
        JSONObject out = new JSONObject();
        try {
            if (acc == null) {
                out.put("ok", false);
                out.put("error", "账号为空");
                return out;
            }
            if (MiniMaxAuth.ensureToken(acc)) saveConfig(configJson);
            String token = acc.optString("access", acc.optString("token", ""));
            if (token.isEmpty()) {
                out.put("ok", false);
                out.put("error", "MiniMax 登录态缺失，请重新登录");
                return out;
            }
            JSONObject r = MiniMaxAuth.checkin(token, acc.optString("uid", ""));
            if (r.optBoolean("ok", false)) {
                String outcome = r.optString("outcome", "");
                if (!"failed".equals(outcome)) {
                    acc.put("checkinDate", todayStamp());
                    acc.put("checkinOutcome", outcome);
                    acc.put("checkinCredit", r.optInt("credit", 0));
                }
                String uid = r.optString("uid", "");
                if (!uid.isEmpty()) acc.put("uid", uid);
                saveConfig(configJson);
            }
            return r;
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("error", e.getMessage() == null ? "签到失败" : e.getMessage());
            } catch (Exception ignored) {
            }
            return out;
        }
    }

    private static String todayStamp() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new java.util.Date());
    }

    /** 启动后异步跑一次：同一天只签一次，失败不打扰用户 */
    private void autoCheckinMiniMax() {
        try {
            JSONObject prov = getProviderById("minimax-code");
            if (prov == null) return;
            JSONArray accs = prov.optJSONArray("accounts");
            if (accs == null || accs.length() == 0) return;
            String today = todayStamp();
            for (int i = 0; i < accs.length(); i++) {
                JSONObject acc = accs.optJSONObject(i);
                if (acc == null || acc.optString("refresh", "").trim().isEmpty()) continue;
                if (today.equals(acc.optString("checkinDate", ""))) continue;
                minimaxCheckinAccount(acc);
            }
        } catch (Throwable ignored) {
        }
    }

    /** MiniMax Code 账号入池：access + refresh（refresh 一次性，刷新后必须立刻落盘） */
    private synchronized String addMiniMaxAccount(MiniMaxAuth.SignIn si, String accName) {
        try {
            JSONArray providers = configJson.optJSONArray("providers");
            if (providers == null) {
                providers = new JSONArray();
                configJson.put("providers", providers);
            }
            JSONObject mp = null;
            for (int i = 0; i < providers.length(); i++) {
                JSONObject p = providers.optJSONObject(i);
                if (p != null && "minimax-code".equals(p.optString("id"))) {
                    mp = p;
                    break;
                }
            }
            if (mp == null) {
                mp = new JSONObject();
                mp.put("id", "minimax-code");
                mp.put("name", "MiniMax Code");
                mp.put("status", "ported");
                mp.put("plugin", "packages/minimax");
                mp.put("baseUrl", MiniMaxAuth.API);
                mp.put("protocol", "minimax");
                mp.put("note", "设备流（PKCE）；Anthropic messages；花每日签到与 M Plan 的额度");
                mp.put("enabled", true);
                mp.put("models", new JSONArray());
                mp.put("keys", new JSONArray());
                mp.put("accounts", new JSONArray());
                providers.put(mp);
            }
            JSONArray accounts = mp.optJSONArray("accounts");
            if (accounts == null) {
                accounts = new JSONArray();
                mp.put("accounts", accounts);
            }
            String accId = "minimax_" + System.currentTimeMillis();
            JSONObject acc = new JSONObject();
            acc.put("id", accId);
            acc.put("type", "oauth");
            acc.put("name", accName);
            acc.put("access", si.access == null ? "" : si.access);
            acc.put("token", si.access == null ? "" : si.access);
            acc.put("refresh", si.refresh == null ? "" : si.refresh);
            acc.put("expires", si.expires);
            acc.put("addedAt", System.currentTimeMillis());
            accounts.put(acc);
            saveConfig(configJson);
            return accId;
        } catch (Exception e) {
            e.printStackTrace();
            return "err";
        }
    }

    private synchronized String addQoderAccount(QoderAuth.SignIn si, String accName) {
        try {
            JSONArray providers = configJson.optJSONArray("providers");
            if (providers == null) {
                providers = new JSONArray();
                configJson.put("providers", providers);
            }

            JSONObject qp = null;
            for (int i = 0; i < providers.length(); i++) {
                JSONObject p = providers.optJSONObject(i);
                if (p != null && "qoder_cn".equals(p.optString("id"))) {
                    qp = p;
                    break;
                }
            }
            if (qp == null) {
                qp = new JSONObject();
                qp.put("id", "qoder_cn");
                qp.put("name", "Qoder CN (通义千问编程)");
                qp.put("protocol", "qoder");
                qp.put("enabled", true);
                qp.put("models", new JSONArray());
                qp.put("keys", new JSONArray());
                qp.put("accounts", new JSONArray());
                providers.put(qp);
            }
            qp.put("protocol", "qoder");

            JSONArray accounts = qp.optJSONArray("accounts");
            if (accounts == null) {
                accounts = new JSONArray();
                qp.put("accounts", accounts);
            }

            String accId = "qd_" + System.currentTimeMillis();
            JSONObject acc = new JSONObject();
            acc.put("id", accId);
            acc.put("name", accName);
            acc.put("token", si.access == null ? "" : si.access);
            acc.put("refresh", si.refresh == null ? "" : si.refresh);
            acc.put("deviceToken", si.deviceToken == null ? "" : si.deviceToken);
            acc.put("deviceRefresh", si.deviceRefresh == null ? "" : si.deviceRefresh);
            acc.put("deviceChat", si.deviceChat);
            acc.put("uid", si.uid == null ? "" : si.uid);
            acc.put("email", si.email == null ? "" : si.email);
            acc.put("machineId", si.machineId == null ? "" : si.machineId);
            acc.put("expiresAt", si.expiresAt);
            acc.put("addedAt", System.currentTimeMillis());
            accounts.put(acc);

            // 登录成功后顺手拉一次账号自己的模型清单（连同每个模型的 config）
            try {
                JSONObject meta = new JSONObject();
                org.json.JSONArray ms = QoderAuth.fetchModels(si, meta);
                if (ms != null && ms.length() > 0) {
                    qp.put("models", ms);
                    qp.put("modelMeta", meta);
                }
            } catch (Exception ignored) {
            }

            saveConfig(configJson);
            return accId;
        } catch (Exception e) {
            e.printStackTrace();
            return "qd_err";
        }
    }

    /**
     * 把 ZCode 换出来的 API Key 写进供应商池。
     * 用 GLM Coding Plan 的 OpenAI 兼容地址（api.z.ai/api/coding/paas/v4），
     * 这样能直接复用现有的 OpenAI 通道，不需要请求体协议转换。
     */
    private synchronized String addZCodeKey(String site, String key, String plan, String account,
                                            String jwt, String device) {
        try {
            JSONArray providers = configJson.optJSONArray("providers");
            if (providers == null) {
                providers = new JSONArray();
                configJson.put("providers", providers);
            }

            JSONObject zc = null;
            for (int i = 0; i < providers.length(); i++) {
                JSONObject p = providers.optJSONObject(i);
                if (p != null && "zcode".equals(p.optString("id"))) {
                    zc = p;
                    break;
                }
            }
            if (zc == null) {
                zc = new JSONObject();
                zc.put("id", "zcode");
                zc.put("name", "ZCode (智谱 GLM Coding)");
                // 走 GLM Coding Plan 的 OpenAI 兼容入口
                zc.put("protocol", "openai");
                zc.put("enabled", true);
                JSONArray models = new JSONArray();
                models.put("GLM-5.3");
                models.put("GLM-5.3-Flash");
                models.put("GLM-5.2");
                models.put("GLM-5-Turbo");
                zc.put("models", models);
                zc.put("keys", new JSONArray());
                zc.put("accounts", new JSONArray());
                providers.put(zc);
            }
            // 每次都强制校正端点与协议：老版本配置里可能残留 anthropic 协议，
            // 而本项目没有 OpenAI→Anthropic 的请求体转换，留着会直接 404。
            // 先用登录站点对应的端点，随后由 calibrateZCodeBaseAsync 探测可达性并自动纠正
            zc.put("baseUrl", ZCodeAuth.baseOf(site));
            zc.put("protocol", "openai");
            zc.put("site", site);
            calibrateZCodeBaseAsync();

            JSONArray keys = zc.optJSONArray("keys");
            if (keys == null) {
                keys = new JSONArray();
                zc.put("keys", keys);
            }
            boolean exists = false;
            for (int i = 0; i < keys.length(); i++) {
                if (key.equals(keys.optString(i))) {
                    exists = true;
                    break;
                }
            }
            if (!exists) keys.put(key);

            JSONArray accounts = zc.optJSONArray("accounts");
            if (accounts == null) {
                accounts = new JSONArray();
                zc.put("accounts", accounts);
            }
            // ZCode 同一账号重复登录：不再重复入池，直接复用已有条目
            String zcName = (account == null || account.isEmpty())
                    ? ("ZCode " + ("bigmodel".equals(site) ? "BigModel" : "Z.ai")) : account;
            JSONObject zcDup = findDuplicateAccount(zc, zcName, key);
            if (zcDup != null) {
                // 老账号只存了 API Key：补上 session JWT，否则 gift 额度永远花不到
                boolean patched = false;
                if (jwt != null && !jwt.isEmpty() && !jwt.equals(zcDup.optString("jwt", ""))) {
                    zcDup.put("jwt", jwt);
                    patched = true;
                }
                if (device != null && !device.isEmpty()) {
                    zcDup.put("device", device);
                    patched = true;
                }
                if (patched) saveConfig(configJson);
                return zcDup.optString("id", "zc_dup");
            }

            String accId = "zc_" + System.currentTimeMillis();
            JSONObject acc = new JSONObject();
            acc.put("id", accId);
            acc.put("name", zcName);
            acc.put("token", key);
            acc.put("site", site);
            // session JWT 是花 gift / Start Plan 额度的唯一凭据，必须一起存
            if (jwt != null && !jwt.isEmpty()) acc.put("jwt", jwt);
            if (device != null && !device.isEmpty()) acc.put("device", device);
            if (plan != null && !plan.isEmpty()) acc.put("plan", plan);
            acc.put("addedAt", System.currentTimeMillis());
            accounts.put(acc);

            saveConfig(configJson);
            return accId;
        } catch (Exception e) {
            e.printStackTrace();
            return "zc_err";
        }
    }

    private synchronized String addWorkBuddyAccount(String name, String token) {
        try {
            JSONArray providers = configJson.optJSONArray("providers");
            if (providers == null) {
                providers = new JSONArray();
                configJson.put("providers", providers);
            }

            JSONObject wbProvider = null;
            for (int i = 0; i < providers.length(); i++) {
                JSONObject p = providers.getJSONObject(i);
                if ("workbuddy".equals(p.optString("id"))) {
                    wbProvider = p;
                    break;
                }
            }

            if (wbProvider == null) {
                wbProvider = new JSONObject();
                wbProvider.put("id", "workbuddy");
                wbProvider.put("name", "WorkBuddy (免Key订阅池)");
                wbProvider.put("baseUrl", "https://copilot.tencent.com/v1");
                wbProvider.put("protocol", "openai");
                wbProvider.put("enabled", true);
                JSONArray models = new JSONArray();
                models.put("deepseek-v3");
                models.put("deepseek-r1");
                models.put("claude-3-5-sonnet");
                models.put("claude-3-7-sonnet");
                models.put("gpt-4o");
                models.put("gpt-4o-mini");
                models.put("hunyuan-t1");
                models.put("hunyuan-standard");
                models.put("hunyuan-code");
                models.put("default");
                wbProvider.put("models", models);
                wbProvider.put("keys", new JSONArray());
                wbProvider.put("accounts", new JSONArray());
                providers.put(wbProvider);
            }

            JSONArray accounts = wbProvider.optJSONArray("accounts");
            if (accounts == null) {
                accounts = new JSONArray();
                wbProvider.put("accounts", accounts);
            }

            // 网页登录自动入池：同一个账号重复登录时不再重复写池，直接复用已有条目
            JSONObject wbDup = findDuplicateAccount(wbProvider, name, token);
            if (wbDup != null) return wbDup.optString("id", "wb_dup");

            String accId = "wb_" + System.currentTimeMillis();
            JSONObject acc = new JSONObject();
            acc.put("id", accId);
            acc.put("name", name);
            acc.put("token", token);
            acc.put("addedAt", System.currentTimeMillis());
            accounts.put(acc);

            JSONArray keys = wbProvider.optJSONArray("keys");
            if (keys == null) {
                keys = new JSONArray();
                wbProvider.put("keys", keys);
            }
            keys.put(token);

            saveConfig(configJson);
            return accId;
        } catch (Exception e) {
            e.printStackTrace();
            return "wb_err";
        }
    }

    /**
     * 读取请求体并按 UTF-8 解码。
     * NanoHTTPD 默认用 Content-Type 里的 charset（缺省 US-ASCII）解码 postData，
     * 中文/emoji 会被破坏成 U+FFFD 且不可逆，所以必须自己从原始流读字节。
     */
    /** 请求体上限，默认 8 MiB；够长上下文用，又不至于被一个大 body 打爆 */
    private int bodyLimitBytes() {
        try {
            JSONObject server = configJson == null ? null : configJson.optJSONObject("server");
            if (server != null) {
                int v = server.optInt("maxBodyBytes", 0);
                if (v >= 65536) return v;
            }
        } catch (Exception ignored) {
        }
        return 8 * 1024 * 1024;
    }

    private String readUtf8Body(IHTTPSession session) {
        BODY_ERROR.set(null);
        try {
            if (session.getMethod() == null) return "";
            String m = session.getMethod().name();
            if (!"POST".equalsIgnoreCase(m) && !"PUT".equalsIgnoreCase(m) && !"PATCH".equalsIgnoreCase(m)) {
                return "";
            }
            Map<String, String> headers = session.getHeaders();
            String cl = (headers == null) ? null : headers.get("content-length");
            int len = -1;
            if (cl != null) {
                try {
                    len = Integer.parseInt(cl.trim());
                } catch (Exception ignored) {
                }
            }
            if (len == 0) return "";

            // 没有 Content-Length 也没有 Transfer-Encoding: chunked 的请求，
            // 按 HTTP/1.1 规范就是「没有 body」，必须立刻返回空串。
            //
            // 以前这里只有 len==0 才提前返回，len==-1 会掉到下面的「一直读到 EOF」分支；
            // 而 curl -X POST 不带 -d 这种请求根本不会有 body，读操作就一路阻塞到
            // readTimeout（面板上表现为 30 秒后返回 {"error":"Read timed out"}）。
            // 前端当时是靠给每个无 body 的 POST 补 '{}' 绕过去的，但任何第三方客户端
            // 依然会踩；根因应该在这里修掉。
            String te = (headers == null) ? null : headers.get("transfer-encoding");
            boolean chunked = te != null && te.toLowerCase().contains("chunked");
            if (len < 0 && !chunked) return "";

            // 上限：超过 config.server.maxBodyBytes（默认 8 MiB）直接拒绝读入，
            // 否则一个超大 body 就能把 App OOM 掉（/api/config 之前完全没有大小检查）。
            int limit = bodyLimitBytes();
            if (len > limit) {
                throw new java.io.IOException("请求体 " + len + " 字节，超过上限 " + limit
                        + "（可在 config.server.maxBodyBytes 调整）");
            }

            InputStream is = session.getInputStream();
            if (is == null) return "";
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            if (len > 0) {
                int remaining = len;
                while (remaining > 0) {
                    int n = is.read(buf, 0, Math.min(buf.length, remaining));
                    if (n <= 0) break;
                    bos.write(buf, 0, n);
                    remaining -= n;
                }
            } else {
                int n;
                long total = 0;
                while ((n = is.read(buf)) > 0) {
                    total += n;
                    if (total > limit) {
                        throw new java.io.IOException("请求体超过上限 " + limit + " 字节");
                    }
                    bos.write(buf, 0, n);
                }
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            BODY_ERROR.set(e.getMessage() == null ? "读取请求体失败" : e.getMessage());
            return "";
        }
    }

    /** 把本次裁剪情况回传给客户端，便于 agent 侧感知上下文被压缩 */
    private void applyGuardHeaders(Response resp, ContextGuard.Result r) {
        if (resp == null || r == null) return;
        resp.addHeader("X-Magpie-Context-Dropped", String.valueOf(r.dropped));
        resp.addHeader("X-Magpie-Context-Truncated", String.valueOf(r.truncated));
        resp.addHeader("X-Magpie-Context-Original-Bytes", String.valueOf(r.originalBytes));
        resp.addHeader("X-Magpie-Context-Final-Bytes", String.valueOf(r.finalBytes));
        resp.addHeader("X-Magpie-Context-Original-Tokens", String.valueOf(r.originalTokens));
        resp.addHeader("X-Magpie-Context-Final-Tokens", String.valueOf(r.finalTokens));
        if (r.appliedMaxOutputTokens != null) {
            resp.addHeader("X-Magpie-Max-Output-Tokens", String.valueOf(r.appliedMaxOutputTokens));
        }
    }

    /** 当前请求的客户端要 Anthropic 格式回复（/v1/messages 且请求体是 Anthropic 形状） */
    private static final ThreadLocal<JSONObject> NATIVE_REQUEST = new ThreadLocal<JSONObject>();
    private static final ThreadLocal<JSONObject> NATIVE_BASELINE = new ThreadLocal<JSONObject>();
    private static final ThreadLocal<Boolean> NATIVE_DIRECT_ALLOWED = new ThreadLocal<Boolean>();

    static String validateRawMessages(Object value) {
        if (!(value instanceof JSONArray)) return "messages must be an array";
        JSONArray messages = (JSONArray) value;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) return "messages[" + i + "] must be an object";
            Object role = message.opt("role");
            if (!(role instanceof String) || ((String) role).trim().isEmpty()) return "messages[" + i + "].role must be a string";
            Object content = message.opt("content");
            if (content instanceof JSONArray) {
                JSONArray blocks = (JSONArray) content;
                for (int j = 0; j < blocks.length(); j++)
                    if (!(blocks.opt(j) instanceof JSONObject)) return "messages[" + i + "].content[" + j + "] must be an object";
            } else if (!(content instanceof String) && content != null && !JSONObject.NULL.equals(content)) {
                return "messages[" + i + "].content must be a string or array";
            }
        }
        return null;
    }

    static void normalizeCoreMessages(JSONObject payload) throws Exception {
        String error = validateRawMessages(payload.opt("messages"));
        if (error != null) throw new IllegalArgumentException(error);
        payload.put("messages", AnthropicBridge.normalizeOpenAiMessages(payload.optJSONArray("messages")));
    }

    // Strictly accept native tool pairs only when each call is answered exactly once
    // by the immediately following user turn. Other transcripts use the normalized path.
    static boolean nativeToolPairsValid(JSONArray messages) {
        if (messages == null) return false;
        java.util.Set<String> pending = new java.util.HashSet<String>();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) return false;
            String role = message.optString("role", "");
            Object raw = message.opt("content");
            JSONArray blocks = raw instanceof JSONArray ? (JSONArray) raw : null;
            if (!pending.isEmpty() && !"user".equals(role)) return false;
            java.util.Set<String> found = new java.util.HashSet<String>();
            for (int j = 0; blocks != null && j < blocks.length(); j++) {
                JSONObject block = blocks.optJSONObject(j);
                if (block == null) return false;
                String type = block.optString("type", "");
                if ("tool_result".equals(type)) {
                    String id = block.optString("tool_use_id", "");
                    if (!"user".equals(role) || !pending.contains(id) || !found.add(id)) return false;
                } else if ("tool_use".equals(type)) {
                    String id = block.optString("id", "");
                    if (!"assistant".equals(role) || id.isEmpty() || !pending.add(id)) return false;
                }
            }
            if (!found.isEmpty()) {
                if (!found.equals(pending)) return false;
                pending.clear();
            } else if ("user".equals(role) && !pending.isEmpty()) return false;
        }
        return pending.isEmpty();
    }

    static JSONObject nativeAnthropicRequest(JSONObject original, JSONObject baseline, JSONObject current,
                                               String model, boolean stream) throws Exception {
        if (original == null || baseline == null || current == null || !nativeToolPairsValid(original.optJSONArray("messages"))) return null;
        JSONObject before = new JSONObject(baseline.toString());
        JSONObject after = new JSONObject(current.toString());
        // Compare every field except explicitly permitted routing and output-limit changes.
        for (String key : new String[]{"model", "stream", "max_tokens", "max_completion_tokens"}) {
            before.remove(key); after.remove(key);
        }
        if (!jsonEquivalent(before, after)) return null;
        JSONObject nativePayload = new JSONObject(original.toString());
        nativePayload.put("model", model);
        nativePayload.put("stream", stream);
        int limit = current.optInt("max_tokens", 0);
        int completion = current.optInt("max_completion_tokens", 0);
        if (completion > 0 && (limit <= 0 || completion < limit)) limit = completion;
        if (limit > 0) nativePayload.put("max_tokens", limit);
        return nativePayload;
    }

    /** Reattach native tool structure only from the masked original, never from
     * textual prefixes. A full unchanged transcript also restores image ownership. */
    static JSONObject restoreNativeToolResults(JSONObject original, JSONObject baseline, JSONObject current,
                                                JSONObject converted, ContextGuard.Config guard) throws Exception {
        if (original == null || baseline == null || current == null || converted == null) return converted;
        JSONArray originalMessages = original.optJSONArray("messages");
        JSONArray before = baseline.optJSONArray("messages"), after = current.optJSONArray("messages");
        if (originalMessages == null || before == null || after == null) return converted;
        JSONObject candidate = new JSONObject(converted.toString());
        if (jsonEquivalent(before, after) && nativeToolPairsValid(originalMessages)) {
            candidate.put("messages", new JSONArray(originalMessages.toString()));
        } else {
            java.util.Map<String, JSONObject> nativeBlocks = new java.util.HashMap<>();
            java.util.Set<String> duplicates = new java.util.HashSet<>();
            for (int i = 0; i < originalMessages.length(); i++) {
                JSONObject m = originalMessages.optJSONObject(i);
                JSONArray blocks = m == null ? null : m.optJSONArray("content");
                for (int j = 0; blocks != null && j < blocks.length(); j++) {
                    JSONObject block = blocks.optJSONObject(j);
                    if (block == null || !"tool_result".equals(block.optString("type"))) continue;
                    String id = block.optString("tool_use_id", "");
                    if (id.isEmpty() || nativeBlocks.put(id, block) != null) duplicates.add(id);
                }
            }
            java.util.Map<String, JSONObject> baselineTools = uniqueToolMessages(before);
            java.util.Map<String, JSONObject> currentTools = uniqueToolMessages(after);
            JSONArray msgs = candidate.optJSONArray("messages");
            java.util.Set<String> restored = new java.util.HashSet<>();
            for (int i = 0; msgs != null && i < msgs.length(); i++) {
                JSONObject m = msgs.optJSONObject(i);
                JSONArray blocks = m == null ? null : m.optJSONArray("content");
                for (int j = 0; blocks != null && j < blocks.length(); j++) {
                    JSONObject block = blocks.optJSONObject(j);
                    if (block == null || !"tool_result".equals(block.optString("type"))) continue;
                    String id = block.optString("tool_use_id", "");
                    JSONObject src = nativeBlocks.get(id), prior = baselineTools.get(id), now = currentTools.get(id);
                    if (duplicates.contains(id) || !restored.add(id) || src == null || prior == null || now == null
                            || !jsonEquivalent(prior, now) || !jsonEquivalent(now.opt("content"), block.opt("content"))) continue;
                    // Image attribution is restored only for an entirely unchanged transcript,
                    // where the synthetic image messages can be removed without ambiguity.
                    Object raw = src.opt("content");
                    if (!(raw instanceof String)) continue;
                    block.put("content", raw);
                    if (src.has("is_error")) block.put("is_error", src.opt("is_error"));
                }
            }
        }
        // Restored native blocks can be larger than their OpenAI form. Never
        // reintroduce content that exceeds or would be changed by the guard.
        ContextGuard.Result checked = ContextGuard.fit(candidate, guard);
        return checked.error == null && !checked.budgetExceeded && !checked.changed
                && jsonEquivalent(candidate, checked.payload) ? candidate : converted;
    }

    private static java.util.Map<String, JSONObject> uniqueToolMessages(JSONArray messages) {
        java.util.Map<String, JSONObject> found = new java.util.HashMap<>();
        java.util.Set<String> duplicate = new java.util.HashSet<>();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null || !"tool".equals(m.optString("role"))) continue;
            String id = m.optString("tool_call_id", "");
            if (id.isEmpty() || found.put(id, m) != null) duplicate.add(id);
        }
        for (String id : duplicate) found.remove(id);
        return found;
    }

    static boolean jsonEquivalent(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (JSONObject.NULL.equals(a) && JSONObject.NULL.equals(b)) return true;
        if (a instanceof Number && b instanceof Number) {
            try { return new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString())) == 0; }
            catch (NumberFormatException ignored) { return a.equals(b); }
        }
        if (a instanceof JSONObject && b instanceof JSONObject) {
            JSONObject x = (JSONObject) a, y = (JSONObject) b;
            if (x.length() != y.length()) return false;
            java.util.Iterator<String> keys = x.keys();
            while (keys.hasNext()) { String key = keys.next(); if (!y.has(key) || !jsonEquivalent(x.opt(key), y.opt(key))) return false; }
            return true;
        }
        if (a instanceof JSONArray && b instanceof JSONArray) {
            JSONArray x = (JSONArray) a, y = (JSONArray) b;
            if (x.length() != y.length()) return false;
            for (int i = 0; i < x.length(); i++) if (!jsonEquivalent(x.opt(i), y.opt(i))) return false;
            return true;
        }
        return a.equals(b);
    }

    private static final ThreadLocal<Boolean> CLIENT_ANTHROPIC = new ThreadLocal<Boolean>();

    /**
     * 客户端请求的原始模型名（model-map 生效时与真正路由的模型不同）。
     * 回复要报客户端请求的那个名字 —— 这是 model-map 的语义：
     * 别名是给用户看的，真实模型是多少、花了谁的额度，看响应头 X-Magpie-Served-By。
     */
    private static final ThreadLocal<String> CLIENT_MODEL = new ThreadLocal<String>();

    private static boolean wantsAnthropicOut() {
        return Boolean.TRUE.equals(CLIENT_ANTHROPIC.get());
    }

    /**
     * 给「非 OpenAI 兼容」的几条路径（Qoder / CatPaw / ZCode gift）用的统一出口：
     * 它们本来就先把整段回复拿到内存，所以这里直接产出 Anthropic 格式，
     * 不必先拼 OpenAI 再转回去。
     *
     * @param stream 客户端是否要流式；true 时发一条完整的 Anthropic 事件流
     */
    private Response anthropicFromText(String text, String reasoning, String model, boolean stream,
                                       ContextGuard.Result guardResult) {
        try {
            if (!stream) {
                JSONObject msg = new JSONObject();
                msg.put("id", "msg_" + System.currentTimeMillis());
                msg.put("type", "message");
                msg.put("role", "assistant");
                msg.put("model", model);
                JSONArray content = new JSONArray();
                if (reasoning != null && !reasoning.isEmpty()) {
                    JSONObject th = new JSONObject();
                    th.put("type", "thinking");
                    th.put("thinking", reasoning);
                    th.put("signature", "");
                    content.put(th);
                }
                JSONObject t = new JSONObject();
                t.put("type", "text");
                t.put("text", text == null ? "" : text);
                content.put(t);
                msg.put("content", content);
                msg.put("stop_reason", "end_turn");
                msg.put("stop_sequence", JSONObject.NULL);
                JSONObject usage = new JSONObject();
                usage.put("input_tokens", 0);
                usage.put("output_tokens", 0);
                msg.put("usage", usage);
                Response resp = newFixedLengthResponse(Status.OK, "application/json; charset=UTF-8", msg.toString());
                addCorsHeaders(resp);
                applyGuardHeaders(resp, guardResult);
                return resp;
            }

            StringBuilder sse = new StringBuilder();
            JSONObject startMsg = new JSONObject();
            startMsg.put("id", "msg_" + System.currentTimeMillis());
            startMsg.put("type", "message");
            startMsg.put("role", "assistant");
            startMsg.put("model", model);
            startMsg.put("content", new JSONArray());
            startMsg.put("stop_reason", JSONObject.NULL);
            startMsg.put("stop_sequence", JSONObject.NULL);
            JSONObject u0 = new JSONObject();
            u0.put("input_tokens", 0);
            u0.put("output_tokens", 0);
            startMsg.put("usage", u0);
            JSONObject ev0 = new JSONObject();
            ev0.put("type", "message_start");
            ev0.put("message", startMsg);
            sse.append("event: message_start\ndata: ").append(ev0.toString()).append("\n\n");

            int idx = 0;
            if (reasoning != null && !reasoning.isEmpty()) {
                JSONObject b = new JSONObject();
                b.put("type", "thinking");
                b.put("thinking", "");
                b.put("signature", "");
                JSONObject d0 = new JSONObject();
                d0.put("type", "content_block_start");
                d0.put("index", idx);
                d0.put("content_block", b);
                sse.append("event: content_block_start\ndata: ").append(d0.toString()).append("\n\n");
                JSONObject dd = new JSONObject();
                dd.put("type", "thinking_delta");
                dd.put("thinking", reasoning);
                JSONObject d1 = new JSONObject();
                d1.put("type", "content_block_delta");
                d1.put("index", idx);
                d1.put("delta", dd);
                sse.append("event: content_block_delta\ndata: ").append(d1.toString()).append("\n\n");
                JSONObject d2 = new JSONObject();
                d2.put("type", "content_block_stop");
                d2.put("index", idx);
                sse.append("event: content_block_stop\ndata: ").append(d2.toString()).append("\n\n");
                idx++;
            }

            JSONObject tb = new JSONObject();
            tb.put("type", "text");
            tb.put("text", "");
            JSONObject s0 = new JSONObject();
            s0.put("type", "content_block_start");
            s0.put("index", idx);
            s0.put("content_block", tb);
            sse.append("event: content_block_start\ndata: ").append(s0.toString()).append("\n\n");
            JSONObject td = new JSONObject();
            td.put("type", "text_delta");
            td.put("text", text == null ? "" : text);
            JSONObject s1 = new JSONObject();
            s1.put("type", "content_block_delta");
            s1.put("index", idx);
            s1.put("delta", td);
            sse.append("event: content_block_delta\ndata: ").append(s1.toString()).append("\n\n");
            JSONObject s2 = new JSONObject();
            s2.put("type", "content_block_stop");
            s2.put("index", idx);
            sse.append("event: content_block_stop\ndata: ").append(s2.toString()).append("\n\n");

            JSONObject md = new JSONObject();
            md.put("type", "message_delta");
            JSONObject mdDelta = new JSONObject();
            mdDelta.put("stop_reason", "end_turn");
            mdDelta.put("stop_sequence", JSONObject.NULL);
            md.put("delta", mdDelta);
            JSONObject u1 = new JSONObject();
            u1.put("output_tokens", 0);
            md.put("usage", u1);
            sse.append("event: message_delta\ndata: ").append(md.toString()).append("\n\n");
            JSONObject ms = new JSONObject();
            ms.put("type", "message_stop");
            sse.append("event: message_stop\ndata: ").append(ms.toString()).append("\n\n");

            Response resp = newFixedLengthResponse(Status.OK, "text/event-stream", sse.toString());
            resp.addHeader("Cache-Control", "no-cache");
            addCorsHeaders(resp);
            applyGuardHeaders(resp, guardResult);
            return resp;
        } catch (Exception e) {
            return errorResponse(500, "构造 Anthropic 响应失败: " + e.getMessage());
        }
    }

    /**
     * 把 OpenAI 的 SSE 流实时改写成 Anthropic 的 SSE 事件流。
     * 客户端（Claude Code 等）只认 message_start / content_block_delta / message_stop 这一套。
     */
    private Response streamForwardAnthropicResponse(final InputStream upstreamIs, final String model,
                                                    final PrivacyMasker.Session maskSession,
                                                    final ContextGuard.Result guardResult,
                                                    final HttpURLConnection conn) {
        InputStream converted = openAiToAnthropicStream(upstreamIs, model, maskSession, conn);
        Response resp = newFixedLengthResponse(Status.OK, "text/event-stream", converted, -1);
        resp.addHeader("Cache-Control", "no-cache");
        addCorsHeaders(resp);
        applyGuardHeaders(resp, guardResult);
        return resp;
    }

    static InputStream openAiToAnthropicStream(final InputStream upstreamIs, String model,
                                                PrivacyMasker.Session maskSession, final HttpURLConnection conn) {
        final AnthropicBridge.StreamState state = new AnthropicBridge.StreamState(model);
        final OpenAiUnmask unmask = new OpenAiUnmask(maskSession);
        return new InputStream() {
            private final java.io.BufferedReader reader =
                    new java.io.BufferedReader(new java.io.InputStreamReader(upstreamIs,
                            java.nio.charset.Charset.forName("UTF-8")));
            private byte[] buf = new byte[0];
            private int pos = 0;
            private boolean done = false;
            private boolean emit(java.util.List<String> events) {
                StringBuilder sb = new StringBuilder();
                for (String event : events) sb.append(event);
                buf = sb.toString().getBytes(java.nio.charset.Charset.forName("UTF-8"));
                pos = 0;
                return buf.length > 0;
            }

            private boolean fill() {
                while (pos >= buf.length) {
                    if (done) return false;
                    String line;
                    try { line = reader.readLine(); }
                    catch (IOException e) {
                        done = true;
                        return emit(state.onError("Upstream stream read failed"));
                    }
                    if (line == null) {
                        done = true;
                        return emit(state.onEnd());
                    }
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) continue;
                    if ("[DONE]".equals(data)) {
                        done = true;
                        return emit(state.onEnd());
                    }
                    JSONObject chunk;
                    try {
                        chunk = new JSONObject(data);
                        unmask.apply(chunk);
                    } catch (Exception e) {
                        done = true;
                        return emit(state.onError("Invalid upstream stream data"));
                    }
                    java.util.List<String> events = state.onChunk(chunk);
                    if (state.isClosed()) done = true;
                    if (emit(events)) return true;
                }
                return true;
            }

            @Override
            public int read() throws IOException {
                if (!fill()) return -1;
                return buf[pos++] & 0xff;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) return 0;
                if (!fill()) return -1;
                int n = Math.min(len, buf.length - pos);
                System.arraycopy(buf, pos, b, off, n);
                pos += n;
                return n;
            }

            @Override
            public void close() throws java.io.IOException {
                try { reader.close(); } finally { if (conn != null) conn.disconnect(); }
            }
        };
    }

    /** 非流式：OpenAI JSON → Anthropic message */
    private String toAnthropicBodyIfNeeded(String openAiJson, String model) {
        if (!wantsAnthropicOut()) return openAiJson;
        try {
            return AnthropicBridge.responseToAnthropic(new JSONObject(openAiJson), model).toString();
        } catch (Exception e) {
            return openAiJson;
        }
    }

    private Response streamForwardResponse(final InputStream upstreamIs, final PrivacyMasker.Session maskSession,
                                           ContextGuard.Result guardResult, final HttpURLConnection upstreamConn) {
        return streamForwardResponse(upstreamIs, maskSession, guardResult, upstreamConn, null);
    }

    private Response streamForwardResponse(final InputStream upstreamIs, final PrivacyMasker.Session maskSession,
                                           ContextGuard.Result guardResult, final HttpURLConnection upstreamConn,
                                           String servedBy) {
        final JSONObject mwStream = configJson.optJSONObject("middleware");
        final Middleware.ThinkStream thinkStream = mwStream == null ? null
                : new Middleware.ThinkStream(mwStream);
        final boolean mwMask = mwStream != null && Middleware.wordGuardEnabled(mwStream);

        // 账本状态在这里「快照」成局部变量：NanoHTTPD 读这个流时未必还在原线程，
        // 依赖 ThreadLocal 会丢记录。首/末内容的时间戳也一起带上。
        final UsageLedger.Row uRow = U_ROW.get();
        Long uStartBox = U_START.get();
        final long uStart = uStartBox == null ? System.currentTimeMillis() : uStartBox.longValue();
        final long[] uFirst = new long[]{-1L};
        final long[] uLast = new long[]{-1L};
        // read() 到 EOF 后会被反复调用，必须保证只记一次
        final boolean[] uCommitted = new boolean[]{false};

        InputStream pipedStream = new InputStream() {
            private final BufferedReader reader = new BufferedReader(new InputStreamReader(upstreamIs));
            private ByteArrayInputStream currentChunkIs = null;
            private boolean flushedTail = false;
            private boolean sawDone = false;
            private boolean sawFinish = false;
            private boolean sawError = false;
            // 占位符可能被切成两半，content / reasoning / 工具参数各自一个缓冲，别互相串
            private final OpenAiUnmask unmask = new OpenAiUnmask(maskSession);
            private boolean emittedTerminalError = false;

            @Override
            public int read() throws IOException {
                if (currentChunkIs != null && currentChunkIs.available() > 0) {
                    return currentChunkIs.read();
                }
                if (emittedTerminalError) return -1;
                String line;
                try { line = reader.readLine(); }
                catch (IOException e) {
                    sawError = true;
                    emittedTerminalError = true;
                    currentChunkIs = new ByteArrayInputStream(openAiStreamError("Upstream stream read failed").getBytes("UTF-8"));
                    return currentChunkIs.read();
                }
                if (line == null) {
                    if (!sawDone && !sawError && !sawFinish) {
                        sawError = true;
                        emittedTerminalError = true;
                        currentChunkIs = new ByteArrayInputStream(openAiStreamError("Upstream stream ended before a finish reason").getBytes("UTF-8"));
                        return currentChunkIs.read();
                    }
                    if (uRow != null && !uCommitted[0]) {
                        uCommitted[0] = true;
                        uRow.ms = System.currentTimeMillis() - uStart;
                        uRow.status = sawError ? 502 : 200;
                        if (uFirst[0] > 0) {
                            uRow.ttft = uFirst[0] - uStart;
                            if (uLast[0] >= uFirst[0]) {
                                uRow.decMs = uLast[0] - uFirst[0];
                                uRow.decOut = uRow.out;
                            }
                        }
                        try {
                            UsageLedger.record(uRow);
                        } catch (Throwable ignored) {
                        }
                        U_DONE.set(Boolean.TRUE);
                        U_ROW.remove();
                    }
                    // 上游结束：把 think-tags 状态机里挂着的尾巴吐出来，避免吞字
                    if (!flushedTail) {
                        flushedTail = true;
                        String tail = thinkStream == null ? "" : thinkStream.flush();
                        if (!tail.isEmpty() && mwMask) tail = Middleware.maskWords(tail, mwStream);
                        if (!tail.isEmpty()) {
                            currentChunkIs = new ByteArrayInputStream(tail.getBytes("UTF-8"));
                            return currentChunkIs.read();
                        }
                    }
                    return -1;
                }
                String restored = line;
                if (restored.startsWith("data:")) {
                    String eventData = restored.substring(5).trim();
                    if ("[DONE]".equals(eventData)) {
                        if (sawError) { emittedTerminalError = true; return -1; }
                        if (!sawFinish) {
                            emittedTerminalError = true;
                            currentChunkIs = new ByteArrayInputStream(openAiStreamError("Upstream stream ended before a finish reason").getBytes("UTF-8"));
                            return currentChunkIs.read();
                        }
                        sawDone = true;
                    } else if (!eventData.isEmpty()) {
                        try {
                            JSONObject event = new JSONObject(eventData);
                            if (event.has("error")) sawError = true;
                            JSONArray choices = event.optJSONArray("choices");
                            for (int i = 0; choices != null && i < choices.length(); i++) {
                                JSONObject choice = choices.optJSONObject(i);
                                if (choice != null && choice.has("finish_reason") && !choice.isNull("finish_reason")
                                        && !choice.optString("finish_reason", "").isEmpty()
                                        && !"null".equals(choice.optString("finish_reason", ""))) sawFinish = true;
                            }
                        } catch (org.json.JSONException ignored) {}
                    }
                }

                // ===== 用量统计：usage 取上游真值，TTFT 取首个内容块 =====
                if (uRow != null && restored.startsWith("data:")) {
                    try {
                        String ud = restored.substring(5).trim();
                        if (!ud.isEmpty() && !"[DONE]".equals(ud)) {
                            JSONObject uj = new JSONObject(ud);
                            boolean hasContent = false;
                            JSONArray uchs = uj.optJSONArray("choices");
                            if (uchs != null && uchs.length() > 0) {
                                JSONObject uc0 = uchs.optJSONObject(0);
                                JSONObject udelta = uc0 == null ? null : uc0.optJSONObject("delta");
                                if (udelta != null && (!udelta.optString("content", "").isEmpty()
                                        || !udelta.optString("reasoning_content", "").isEmpty()
                                        || udelta.has("tool_calls"))) {
                                    hasContent = true;
                                }
                                JSONObject uu = uc0 == null ? null : uc0.optJSONObject("usage");
                                if (uu != null) absorbUsage(uRow, uu);
                            }
                            String utype = uj.optString("type", "");
                            if ("content_block_delta".equals(utype)) hasContent = true;
                            if ("message_start".equals(utype)) {
                                JSONObject um = uj.optJSONObject("message");
                                if (um != null) absorbUsage(uRow, um.optJSONObject("usage"));
                            }
                            JSONObject uu2 = uj.optJSONObject("usage");
                            if (uu2 != null) absorbUsage(uRow, uu2);
                            if (hasContent) {
                                long unow = System.currentTimeMillis();
                                if (uFirst[0] < 0) uFirst[0] = unow;
                                uLast[0] = unow;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }

                // ===== 流式中间件：think-tags 拆 <think>，word-guard 打码 =====
                // 只改 OpenAI 的 delta.content；reasoning_content 原样透传。
                if ((thinkStream != null || mwMask || maskSession != null) && restored.startsWith("data:")) {
                    String data = restored.substring(5).trim();
                    if (!data.isEmpty() && !"[DONE]".equals(data)) {
                        try {
                            JSONObject chunk = new JSONObject(data);
                            JSONArray chs = chunk.optJSONArray("choices");
                            boolean touched = false;
                            for (int ci = 0; chs != null && ci < chs.length(); ci++) {
                                JSONObject c0 = chs.optJSONObject(ci);
                                JSONObject delta = c0 == null ? null : c0.optJSONObject("delta");
                                if (delta == null) continue;
                                Object curC = delta.opt("content");
                                if (curC instanceof String) {
                                    String txt = (String) curC;
                                    if (thinkStream != null) txt = thinkStream.feed(txt);
                                    if (mwMask) txt = Middleware.maskWords(txt, mwStream);
                                    if (!txt.equals(curC)) { delta.put("content", txt); touched = true; }
                                }
                            }
                            if (maskSession != null) { unmask.apply(chunk); touched = true; }
                            // model-map：每个 chunk 的 model 也报客户端请求的名字
                            String aliasChunk = CLIENT_MODEL.get();
                            if (aliasChunk != null && !aliasChunk.isEmpty() && chunk.has("model")) {
                                chunk.put("model", aliasChunk);
                                touched = true;
                            }
                            if (touched) restored = "data: " + chunk.toString();
                        } catch (Throwable ignored) {
                            // 解析不了就按原样发，别把流弄断
                        }
                    }
                }

                currentChunkIs = new ByteArrayInputStream((restored + "\n").getBytes("UTF-8"));
                return currentChunkIs.read();
            }

            @Override
            public void close() throws IOException {
                try { upstreamIs.close(); } finally {
                    if (upstreamConn != null) upstreamConn.disconnect();
                }
            }
        };

        Response resp = newChunkedResponse(Status.OK, "text/event-stream", pipedStream);
        if (servedBy != null && !servedBy.isEmpty()) resp.addHeader("X-Magpie-Served-By", servedBy);
        resp.addHeader("Cache-Control", "no-cache");
        resp.addHeader("Connection", "keep-alive");
        addCorsHeaders(resp);
        applyGuardHeaders(resp, guardResult);
        return resp;
    }

    /** 合并当前 guard 配置与前端提交的局部修改 */
    private ContextGuard.Config mergedGuardConfig(JSONObject patch) {
        ContextGuard.Config base = ContextGuard.Config.fromJson(configJson.optJSONObject("guard"));
        JSONObject merged = base.toJson();
        if (patch != null) {
            java.util.Iterator<String> keys = patch.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                try {
                    Object v = patch.get(k);
                    if (v != null && v != JSONObject.NULL) merged.put(k, v);
                } catch (Exception ignored) {
                }
            }
        }
        return ContextGuard.Config.fromJson(merged);
    }

    private JSONObject failoverJson() {
        JSONObject fo = configJson.optJSONObject("failover");
        if (fo == null) {
            fo = new JSONObject();
            fo.put("enabled", true);
            fo.put("maxTargets", 3);
            fo.put("targets", new JSONArray());
        }
        return fo;
    }

    /** 预览用的样例长对话（system + 30 轮历史） */
    private JSONObject buildGuardSamplePayload() {
        JSONObject p = new JSONObject();
        p.put("model", "group/smart-coding");
        JSONArray msgs = new JSONArray();
        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content", "你是编程助手，回答保持简洁。");
        msgs.put(sys);
        StringBuilder filler = new StringBuilder();
        for (int i = 0; i < 900; i++) filler.append("样例历史内容用于测试上下文裁剪效果。");
        for (int i = 0; i < 30; i++) {
            JSONObject m = new JSONObject();
            m.put("role", i % 2 == 0 ? "user" : "assistant");
            int len = Math.min(filler.length(), 600 + (i * 37) % 900);
            m.put("content", "第" + (i + 1) + "轮：" + filler.substring(0, len));
            msgs.put(m);
        }
        p.put("messages", msgs);
        return p;
    }

    /**
     * 客户端到底要不要流式：body 优先，其次 Accept。
     * 只有 body 里显式出现 stream 字段时才完全以它为准则。
     */
    private static boolean wantsStream(JSONObject payload, NanoHTTPD.IHTTPSession session) {
        if (payload != null && payload.has("stream")) {
            Object v = payload.opt("stream");
            if (v instanceof Boolean) return (Boolean) v;
            String sv = String.valueOf(v).trim();
            return "true".equalsIgnoreCase(sv) || "1".equals(sv);
        }
        try {
            Map<String, String> h = session == null ? null : session.getHeaders();
            if (h != null) {
                for (Map.Entry<String, String> e : h.entrySet()) {
                    if (e.getKey() == null || !"accept".equalsIgnoreCase(e.getKey())) continue;
                    String v = e.getValue();
                    if (v != null && v.toLowerCase(java.util.Locale.ROOT).contains("text/event-stream")) {
                        return true;
                    }
                }
            }
            Map<String, List<String>> p = session == null ? null : session.getParameters();
            if (p != null && p.containsKey("stream")) {
                List<String> vs = p.get("stream");
                if (vs != null && !vs.isEmpty()) {
                    String sv = vs.get(0);
                    return "true".equalsIgnoreCase(sv) || "1".equals(sv);
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    // ==================================================================================
    // 用量账本挂钩：每次转发在同一线程里记一行（口径见 UsageLedger 注释）
    // 用 ThreadLocal，避免改所有 streamForwardResponse 的调用点。
    // ==================================================================================
    private static final ThreadLocal<UsageLedger.Row> U_ROW = new ThreadLocal<UsageLedger.Row>();
    private static final ThreadLocal<Long> U_START = new ThreadLocal<Long>();
    private static final ThreadLocal<Long> U_FIRST = new ThreadLocal<Long>();
    private static final ThreadLocal<Long> U_LAST = new ThreadLocal<Long>();
    private static final ThreadLocal<Boolean> U_DONE = new ThreadLocal<Boolean>();

    private static final ThreadLocal<String> U_SESSION = new ThreadLocal<String>();

    /** 从请求头里认会话：各家客户端用的名字不一样，按优先级取第一个非空的 */
    private static String pickSession(Map<String, String> h) {
        if (h == null) return "";
        String[] keys = new String[]{"x-magpie-session", "x-claude-code-session-id",
                "x-session-id", "session_id", "x-conversation-id"};
        for (String k : keys) {
            for (Map.Entry<String, String> e : h.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(k)) {
                    String v = e.getValue();
                    if (v != null && !v.trim().isEmpty()) return v.trim();
                }
            }
        }
        return "";
    }

    private static void beginUsage(String prov, String reqModel, String upModel) {
        UsageLedger.Row r = new UsageLedger.Row();
        r.prov = prov == null ? "" : prov;
        r.req = reqModel == null ? "" : reqModel;
        r.model = upModel == null ? "" : upModel;
        String sess = U_SESSION.get();
        r.session = sess == null ? "" : sess;
        U_ROW.set(r);
        U_START.set(Long.valueOf(System.currentTimeMillis()));
        U_FIRST.set(Long.valueOf(-1L));
        U_LAST.set(Long.valueOf(-1L));
        U_DONE.set(Boolean.FALSE);
    }

    /** 各种上游的 usage 形状：OpenAI / Anthropic / Gemini(Code Assist) */
    private static void absorbUsage(UsageLedger.Row r, JSONObject u) {
        if (r == null || u == null) return;
        if (u.has("prompt_tokens") || u.has("completion_tokens")) {
            r.in = u.optInt("prompt_tokens", r.in);
            r.out = u.optInt("completion_tokens", r.out);
            JSONObject det = u.optJSONObject("prompt_tokens_details");
            if (det != null && det.has("cached_tokens")) r.cacheRead = det.optInt("cached_tokens", 0);
        }
        if (u.has("input_tokens") || u.has("output_tokens")) {
            r.in = u.optInt("input_tokens", r.in);
            r.out = u.optInt("output_tokens", r.out);
            if (u.has("cache_read_input_tokens")) r.cacheRead = u.optInt("cache_read_input_tokens", 0);
            if (u.has("cache_creation_input_tokens")) r.cacheWrite = u.optInt("cache_creation_input_tokens", 0);
        }
        if (u.has("promptTokenCount") || u.has("candidatesTokenCount")) {
            r.in = u.optInt("promptTokenCount", r.in);
            r.out = u.optInt("candidatesTokenCount", r.out);
            if (u.has("cachedContentTokenCount")) r.cacheRead = u.optInt("cachedContentTokenCount", 0);
            if (u.has("thoughtsTokenCount")) r.reasoning = u.optInt("thoughtsTokenCount", 0);
        }
    }

    /** 流式逐行统计：usage 取上游真值，TTFT 取首个内容块，解码窗口取首字→末字 */
    private static void absorbStreamLine(String line) {
        if (line == null || !line.startsWith("data:")) return;
        UsageLedger.Row r = U_ROW.get();
        if (r == null) return;
        String d = line.substring(5).trim();
        if (d.isEmpty() || "[DONE]".equals(d)) return;
        JSONObject j;
        try {
            j = new JSONObject(d);
        } catch (Exception e) {
            return;
        }
        boolean content = false;
        JSONArray chs = j.optJSONArray("choices");
        if (chs != null && chs.length() > 0) {
            JSONObject c0 = chs.optJSONObject(0);
            JSONObject delta = c0 == null ? null : c0.optJSONObject("delta");
            if (delta != null && (!delta.optString("content", "").isEmpty()
                    || !delta.optString("reasoning_content", "").isEmpty()
                    || delta.has("tool_calls"))) {
                content = true;
            }
            JSONObject cu = c0 == null ? null : c0.optJSONObject("usage");
            if (cu != null) absorbUsage(r, cu);
        }
        String type = j.optString("type", "");
        if ("content_block_delta".equals(type)) content = true;
        if ("message_start".equals(type)) {
            JSONObject msg = j.optJSONObject("message");
            if (msg != null) absorbUsage(r, msg.optJSONObject("usage"));
        }
        JSONObject usage = j.optJSONObject("usage");
        if (usage != null) absorbUsage(r, usage);
        if (content) {
            long now = System.currentTimeMillis();
            Long f = U_FIRST.get();
            if (f == null || f.longValue() < 0) U_FIRST.set(Long.valueOf(now));
            U_LAST.set(Long.valueOf(now));
        }
    }

    /** 收尾写一行；失败也记（请求数、错误原因要准） */
    private void commitUsage(int status, String err) {
        if (Boolean.TRUE.equals(U_DONE.get())) return;
        U_DONE.set(Boolean.TRUE);
        UsageLedger.Row r = U_ROW.get();
        Long t0 = U_START.get();
        if (r == null || t0 == null) {
            U_ROW.remove(); U_START.remove(); U_FIRST.remove(); U_LAST.remove(); U_DONE.remove();
            return;
        }
        r.ms = System.currentTimeMillis() - t0.longValue();
        r.status = status;
        r.err = err == null ? "" : err;
        Long first = U_FIRST.get();
        Long last = U_LAST.get();
        if (first != null && first.longValue() > 0) {
            r.ttft = first.longValue() - t0.longValue();
            if (last != null && last.longValue() >= first.longValue()) {
                r.decMs = last.longValue() - first.longValue();
                r.decOut = r.out;
            }
        }
        try {
            UsageLedger.record(r);
        } catch (Throwable ignored) {
        }
        U_ROW.remove(); U_START.remove(); U_FIRST.remove(); U_LAST.remove(); U_DONE.remove();
    }

    private static boolean middlewareCanSetLimit(JSONObject mw, String provider) {
        if (mw==null) return false;
        JSONObject global=mw.optJSONObject("paramOverride"), by=mw.optJSONObject("paramOverrideByProvider");
        JSONObject local=by==null?null:by.optJSONObject(provider);
        for (JSONObject rule:new JSONObject[]{global,local}) {
            if (rule==null) continue;
            JSONObject set=rule.optJSONObject("set");
            if (rule.has("maxTokensCap") || (set!=null && (set.has("max_tokens") || set.has("max_completion_tokens") || set.has("maxTokens")))) return true;
        }
        return false;
    }

    static String deepAccountToken(JSONObject account) {
        String token=account.optString("access", "").trim();
        return token.isEmpty() ? account.optString("token", "").trim() : token;
    }

    static String deepCredential(JSONObject req) throws IOException {
        Object raw=req.opt("credential"); if (raw==null || raw==JSONObject.NULL) raw=req.opt("token");
        JSONObject obj=null;
        if ((raw==null || raw==JSONObject.NULL || (raw instanceof String && ((String)raw).trim().isEmpty())) && req.has("access_token")) raw=req;
        if (raw instanceof JSONObject) obj=(JSONObject)raw;
        else if (raw instanceof String) {
            String value=((String)raw).trim();
            if (!value.startsWith("{")) { if (!value.isEmpty()) return value; }
            else try { obj=new JSONObject(value); } catch(Exception e) { throw new IOException("Invalid credential JSON"); }
        } else if (raw!=null && raw!=JSONObject.NULL) throw new IOException("Invalid credential");
        if (obj==null) obj=req;
        Object token=obj.opt("access_token");
        if (!(token instanceof String) || ((String)token).trim().isEmpty()) throw new IOException("Missing access_token");
        return ((String)token).trim();
    }

    private static final class DeepSeekConfigFailure extends IOException {}
    private synchronized void persistConfigStrict(JSONObject value) throws IOException {
        File tmp=new File(configFile.getParentFile(),"config.deepseek.tmp");
        try {
            try (FileOutputStream f=new FileOutputStream(tmp,false)) { f.write(value.toString(2).getBytes("UTF-8")); f.flush(); f.getFD().sync(); }
            if (!tmp.renameTo(configFile)) throw new IOException("rename failed");
            configJson=value;
        } catch(Exception e) { tmp.delete(); throw new IOException("config persistence failed",e); }
    }
    static void preserveDeepSeekAccounts(JSONObject current,JSONObject incoming) throws Exception {
        JSONObject old=null, fresh=null;
        JSONArray oldProviders=current.optJSONArray("providers"), newProviders=incoming.optJSONArray("providers");
        for(int i=0;oldProviders!=null && i<oldProviders.length();i++) { JSONObject p=oldProviders.optJSONObject(i); if(p!=null && "deepseek_web".equals(p.optString("id"))) { old=p; break; } }
        if(old==null) return;
        for(int i=0;i<newProviders.length();i++) { JSONObject p=newProviders.optJSONObject(i); if(p!=null && "deepseek_web".equals(p.optString("id"))) { fresh=p; break; } }
        if(fresh==null) { newProviders.put(new JSONObject(old.toString())); return; }
        JSONArray accounts=old.optJSONArray("accounts");
        fresh.put("accounts",accounts==null?new JSONArray():new JSONArray(accounts.toString()));
        // A stale placeholder snapshot must not undo migration while preserving its pool.
        if ("deepseek-web".equals(old.optString("protocol")) && "placeholder".equals(fresh.optString("status"))) {
            fresh.put("protocol","deepseek-web"); fresh.put("status","ported");
            JSONArray models=old.optJSONArray("models"); if(models!=null) fresh.put("models",new JSONArray(models.toString()));
        }
    }
    private synchronized JSONObject addDeepSeekAccount(String name,String token) throws IOException {
        try {
            JSONObject provider=getProviderById("deepseek_web");
            if (provider==null || !"deepseek-web".equals(provider.optString("protocol"))) throw new DeepSeekConfigFailure();
            JSONArray accounts=provider.optJSONArray("accounts");
            for(int i=0;accounts!=null && i<accounts.length();i++) {
                JSONObject a=accounts.optJSONObject(i);
                if(a!=null && (token.equals(a.optString("token", "").trim()) || token.equals(a.optString("access", "").trim()))) {
                    // Re-persist before claiming the previously validated account is durable.
                    persistConfigStrict(configJson);
                    return new JSONObject().put("ok",true).put("validated",true).put("existing",true).put("id",a.optString("id")).put("provider","deepseek_web");
                }
            }
            JSONObject copy=new JSONObject(configJson.toString());
            JSONArray providers=copy.optJSONArray("providers"); JSONObject target=null;
            for(int i=0;i<providers.length();i++) {JSONObject p=providers.optJSONObject(i);if(p!=null && "deepseek_web".equals(p.optString("id"))) {target=p;break;}}
            JSONArray pool=target.optJSONArray("accounts"); if(pool==null) {pool=new JSONArray();target.put("accounts",pool);}
            String id="acc_"+java.util.UUID.randomUUID();
            pool.put(new JSONObject().put("id",id).put("name",name).put("token",token).put("type","web").put("addedAt",System.currentTimeMillis()));
            persistConfigStrict(copy);
            return new JSONObject().put("ok",true).put("validated",true).put("existing",false).put("id",id).put("provider","deepseek_web").put("name",name);
        } catch(Exception e) { throw new DeepSeekConfigFailure(); }
    }

    private synchronized void removeDeepSeekAccount(String id) throws IOException {
        try {
            JSONObject copy=new JSONObject(configJson.toString());
            JSONArray providers=copy.optJSONArray("providers");
            for(int i=0;providers!=null && i<providers.length();i++) {
                JSONObject p=providers.optJSONObject(i); if(p==null || !"deepseek_web".equals(p.optString("id"))) continue;
                JSONArray accounts=p.optJSONArray("accounts"), kept=new JSONArray();
                for(int j=0;accounts!=null && j<accounts.length();j++) {JSONObject a=accounts.optJSONObject(j);if(a!=null && !"all".equals(id) && !id.equals(a.optString("id"))) kept.put(a);}
                p.put("accounts",kept); break;
            }
            persistConfigStrict(copy);
        } catch(Exception e) { throw new IOException("Could not remove account",e); }
    }

    private Attempt forwardDeepSeekWeb(RouteTarget target, JSONObject payload, boolean stream, String requested,
                                       PrivacyMasker.Session mask, ContextGuard.Result guard) {
        Attempt out=new Attempt(); out.providerId=target.providerId; out.modelRef=target.providerId+"/"+target.upstreamModel;
        DeepSeekWebChat web=new DeepSeekWebChat(deepAccountToken(target.account),deepSeekTransport);
        try {
            web.validate(payload);
        }
        catch(IOException e) { out.status=400; out.body=e.getMessage(); out.response=errorResponse(400,out.body); return out; }
        try {
            if (stream) {
                final UsageLedger.Row row=U_ROW.get(); final Long start=U_START.get();
                InputStream input=web.stream(payload,requested,(success,status,error)->{
                    if (row!=null) { row.status=success?200:status; row.err=error; row.ms=System.currentTimeMillis()-(start==null?System.currentTimeMillis():start.longValue()); try {UsageLedger.record(row);} catch(Throwable ignored) {} }
                });
                // The web worker owns the ledger row for both output formats.
                U_ROW.remove();
                try { out.response=wantsAnthropicOut()?streamForwardAnthropicResponse(input,requested,mask,guard,null):streamForwardResponse(input,mask,guard,null,out.modelRef); }
                finally { if(row!=null) U_ROW.set(row); }
                out.response.addHeader("X-AllIn-Output-Limit","unsupported");
                out.response.addHeader("Access-Control-Expose-Headers","X-AllIn-Output-Limit");
                out.ok=true; out.status=200; return out;
            }
            DeepSeekWebChat.Result result=web.run(payload,null);
            JSONObject message=new JSONObject().put("role","assistant").put("content",result.text);
            if (!result.reasoning.isEmpty()) message.put("reasoning_content",result.reasoning);
            if (result.toolCalls.length()>0) message.put("tool_calls",result.toolCalls);
            JSONObject body=new JSONObject().put("object","chat.completion").put("model",requested).put("choices",new JSONArray().put(new JSONObject().put("index",0).put("message",message).put("finish_reason",result.toolCalls.length()>0?"tool_calls":"stop")));
            String response=toAnthropicBodyIfNeeded(PrivacyMasker.unmaskText(body.toString(),mask),requested);
            out.response=newFixedLengthResponse(Status.OK,"application/json; charset=UTF-8",response); addCorsHeaders(out.response); applyGuardHeaders(out.response,guard);
            out.response.addHeader("X-AllIn-Output-Limit","unsupported");
            out.response.addHeader("Access-Control-Expose-Headers","X-AllIn-Output-Limit");
            out.textOut=result.text; out.reasoningOut=result.reasoning; out.ok=true; out.status=200; return out;
        } catch(DeepSeekWeb.UpstreamException e) {
            out.status=DeepSeekWebChat.safeStatus(e); out.body=DeepSeekWebChat.safeErrorMessage(out.status);
            out.response=errorResponse(out.status,out.body); return out;
        } catch(Exception e) { out.status=502; out.body="DeepSeek web request failed"; out.response=errorResponse(502,out.body); return out; }
    }

    private static class RouteTarget {
        String providerId;
        String baseUrl;
        String apiKey;
        String upstreamModel;
        boolean isAnthropic;
        String protocol;      // "openai" / "anthropic" / "qoder"
        JSONObject account;   // qoder 等账号池供应商的完整账号
    }

    /** 供应商是否至少有 1 个可用凭据（账号或 key）；没有则它的模型一定调不通 */
    private boolean providerHasCredential(JSONObject p) {
        if (p == null) return false;
        if (!p.optBoolean("enabled", true)) return false;
        JSONArray accs = p.optJSONArray("accounts");
        if ("deepseek-web".equals(p.optString("protocol"))) {
            for (int i=0;accs!=null && i<accs.length();i++) {
                JSONObject a=accs.optJSONObject(i); if (a!=null && a.optBoolean("enabled",true) && !deepAccountToken(a).isEmpty()) return true;
            }
            return false;
        }
        if (accs != null) {
            for (int i = 0; i < accs.length(); i++) {
                JSONObject a = accs.optJSONObject(i);
                if (a != null && !a.optString("token", "").trim().isEmpty()) return true;
            }
        }
        JSONArray keys = p.optJSONArray("keys");
        if (keys != null) {
            for (int i = 0; i < keys.length(); i++) {
                if (!keys.optString(i, "").trim().isEmpty()) return true;
            }
        }
        return false;
    }

    /** 路由组内是否至少还有一个目标指向有凭据的供应商；全死则整组没意义 */
    private boolean groupHasLiveTarget(JSONObject g) {
        if (g == null) return false;
        JSONArray targets = g.optJSONArray("targets");
        if (targets == null || targets.length() == 0) return false;
        for (int i = 0; i < targets.length(); i++) {
            String t = targets.optString(i, "");
            int slash = t.indexOf('/');
            if (slash <= 0) continue;
            JSONObject prov = getProviderById(t.substring(0, slash));
            if (providerHasCredential(prov)) return true;
        }
        return false;
    }

    /** 是否是会先输出思维链的模型（这类模型的探活需要更大 max_tokens 才能看到正文） */
    private boolean isReasoningModel(String model, String providerId) {
        if (model == null) return false;
        String m = model.toLowerCase();
        if ("zcode".equals(providerId)) return true;          // GLM-5.x 全系带 reasoning
        if (m.contains("reasoner")) return true;               // deepseek-reasoner
        if (m.contains("glm-5") || m.contains("glm-4.5")) return true;
        if (m.contains("-r1") || m.contains("r1-")) return true;
        if (m.contains("thinking")) return true;
        if (m.contains("qwq") || m.contains("o1") || m.contains("o3")) return true;
        return false;
    }

    /** 从 "provider/model" 里取供应商 id；无法解析返回 null */
    private static String providerIdOf(String modelRef) {
        if (modelRef == null) return null;
        int slash = modelRef.indexOf('/');
        if (slash <= 0) return null;
        return modelRef.substring(0, slash);
    }

    /** 单个模型引用是否当前真的可用：其供应商有凭据，且（若有模型清单）模型在清单内 */
    private boolean isModelRefLive(String modelRef) {
        String pid = providerIdOf(modelRef);
        if (pid == null) return false;
        JSONObject p = getProviderById(pid);
        if (!providerHasCredential(p)) return false;
        JSONArray models = p.optJSONArray("models");
        if (models == null || models.length() == 0) return true;   // 未同步清单时不做模型名校验
        String m = modelRef.substring(pid.length() + 1);
        for (int i = 0; i < models.length(); i++) {
            if (m.equals(models.optString(i))) return true;
        }
        return false;
    }

    /**
     * 按 order 顺序挑出组内第一个真正可用的目标。
     * 原实现写死取 targets[0]，只要第一个目标没配凭据，整个组就 100% 死，
     * 且"A 挂了自动换 B"这个路由组本该有的语义完全没实现。
     */
    private String pickGroupTarget(JSONObject g) {
        if (g == null) return null;
        JSONArray targets = g.optJSONArray("targets");
        if (targets == null || targets.length() == 0) return null;
        for (int i = 0; i < targets.length(); i++) {
            String t = targets.optString(i, "");
            if (isModelRefLive(t)) return t;
        }
        // 全都不可用时退回第一个，至少让错误信息指向用户预期的模型
        return targets.optString(0);
    }

    // ===== 运行时降级（对齐 yetone/magpie 的 fallback 语义）=====
    // 失败只让候选"休息"一段时间并排到队尾，不永久拉黑；
    // 上下文超长这类换谁都一样的原因不重试，直接还给 agent 让它自己 compact。

    /** 休息表：key = provider/model，value = 解禁时间戳与状态码 */
    private static final java.util.concurrent.ConcurrentHashMap<String, long[]> RESTS =
            new java.util.concurrent.ConcurrentHashMap<String, long[]>();
    /** 配额类（429/402）：冷却久一点，避免刚被拒就再撞 */
    private static final long REST_QUOTA_MS = 60L * 1000L;
    /** 其它失败（5xx/网络/403/404/408）：短暂回避 */
    private static final long REST_FAIL_MS = 30L * 1000L;

    private static String restKeyOf(RouteTarget t) {
        if (t == null) return "";
        return t.providerId + "/" + t.upstreamModel;
    }

    private boolean isResting(RouteTarget t) {
        long[] r = RESTS.get(restKeyOf(t));
        if (r == null) return false;
        if (System.currentTimeMillis() >= r[0]) {
            RESTS.remove(restKeyOf(t));
            return false;
        }
        return true;
    }

    private void rest(RouteTarget t, int status) {
        if (t == null) return;
        long ms = (status == 429 || status == 402) ? REST_QUOTA_MS : REST_FAIL_MS;
        RESTS.put(restKeyOf(t), new long[]{System.currentTimeMillis() + ms, status});
    }

    /**
     * 另一个供应商/账号是否可能做得更好。
     * 移植 magpie 的 retryable：厂商繁忙、额度用尽、模型下架、被防火墙拦、
     * 手里没余额——换一个还有戏；而是请求本身的问题（尤其上下文超长）就不要再换。
     */
    private static boolean retryable(int status, String body) {
        String b = body == null ? "" : body.toLowerCase();
        // 0 表示连接/读取异常（DNS、超时、TLS 被切），换一家可能就通了
        if (status == 0) return true;
        // 上下文超长：每个账号、每个模型都一样长，重试只会更慢
        if (overflowed(status, b)) return false;
        if (status == 401 || status == 402 || status == 403 || status == 404
                || status == 408 || status == 429 || status >= 500) {
            return true;
        }
        if (status == 400 || status == 422) {
            // 400/422 要按措辞判断：额度、不支持、被拒、地区限制才算可换
            return containsAny(b, "quota", "insufficient", "balance", "credit", "rate limit",
                    "too many requests", "not support", "unsupported", "not found",
                    "no available", "region", "blocked", "banned", "suspended")
                    || containsAny(b, "额度", "余额", "不足", "限流", "不支持", "已下架", "风控");
        }
        return false;
    }

    /** 上下文/输入超长，换谁都一样 */
    private static boolean overflowed(int status, String b) {
        if (status != 400 && status != 413 && status != 422) return false;
        return containsAny(b, "context length", "context_length", "too long", "maximum context",
                "context window", "prompt is too long", "input is too long", "token limit")
                || containsAny(b, "上下文", "超长", "过长", "超出");
    }

    private static boolean containsAny(String hay, String... needles) {
        if (hay == null) return false;
        for (int i = 0; i < needles.length; i++) {
            if (hay.contains(needles[i])) return true;
        }
        return false;
    }

    /**
     * 对单个候选发起一次真实转发。
     * 成功（2xx/3xx）直接把响应包装好返回；失败把上游状态码与错误体带回，
     * 由调用方用 retryable 决定换不换下一个。
     * 注意：这里只构造 Response，还没有任何字节发给客户端，所以此时换候选是安全的。
     */
    /**
     * 把子方法返回的 Attempt 回填到父级的 out 上。
     * 为什么需要：forwardAttempt 的 finally 用本地 out 记用量，而 qoder/catpaw/minimax/codex/
     * antigravity 五条分支是直接 `return forwardXxx(...)`，父级 out 一直是空壳（ok=false,status=0），
     * 结果每个成功请求都被记成 502 失败、token 为 0。这里统一回填，一处修好五家。
     */
    private static Attempt adopt(Attempt out, Attempt r) {
        if (r == null) return out;
        out.ok = r.ok;
        out.status = r.status;
        out.body = r.body;
        // 子方法成功时不一定写 status（它们只 set ok/response），这里补正，
        // 否则账本里会出现「成功但状态码 0」这种既不像失败也查不出来的记录
        if (r.ok && (out.status < 200 || out.status >= 400)) out.status = 200;
        return r;
    }

    private Attempt forwardAttempt(RouteTarget target, JSONObject payloadIn, boolean clientWantsStream,
                                   String requestedModel, PrivacyMasker.Session maskSession,
                                   ContextGuard.Config guardCfg, ContextGuard.Result guardResult) {
        Attempt out = new Attempt();
        out.providerId = target.providerId;
        out.modelRef = target.providerId + "/" + target.upstreamModel;
        // Anthropic 客户端把会话放在 metadata.user_id（字符串化 JSON）里，header 没有时用它兜底
        try {
            String sessNow = U_SESSION.get();
            if (sessNow == null || sessNow.isEmpty()) {
                JSONObject meta = payloadIn.optJSONObject("metadata");
                String uid = meta == null ? "" : meta.optString("user_id", "");
                if (!uid.isEmpty()) {
                    try {
                        JSONObject uj = new JSONObject(uid);
                        String sid = uj.optString("session_id", "");
                        if (!sid.isEmpty()) U_SESSION.set(sid);
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        beginUsage(target.providerId, requestedModel, target.upstreamModel);

        HttpURLConnection conn = null;
        boolean handedOff = false;   // 流式响应已交给 NanoHTTPD 延迟读取，连接不能在此关闭
        try {
            // 每个候选独立一份请求体，避免改动互相污染
            JSONObject payload = new JSONObject(payloadIn.toString());
            payload.put("model", target.upstreamModel);

            if ("deepseek-web".equals(target.protocol)) {
                // A configured output cap cannot be enforced by the website, even if
                // middleware happened to remove the corresponding request field.
                if (guardCfg.enabled && guardCfg.maxOutputTokens>0) {
                    out.status=400; out.body="DeepSeek web cannot enforce guard maxOutputTokens";
                    out.response=errorResponse(400,out.body); return out;
                }
                JSONObject original=NATIVE_REQUEST.get(), baseline=NATIVE_BASELINE.get();
                JSONObject mw=configJson.optJSONObject("middleware");
                if (original!=null && !original.has("max_tokens") && baseline!=null
                        && payload.has("max_tokens") && payload.optInt("max_tokens",-1)==baseline.optInt("max_tokens",-2)
                        && !middlewareCanSetLimit(mw,target.providerId)) payload.remove("max_tokens");
                Attempt web=forwardDeepSeekWeb(target,payload,clientWantsStream,requestedModel,maskSession,guardResult);
                if (web.ok && clientWantsStream) handedOff=true;
                return adopt(out,web);
            }

            // ===== Qoder：不是 OpenAI 兼容接口，要过 COSY 签名 + 自定义响应解码 =====
            if ("qoder".equals(target.protocol)) {
                return adopt(out, forwardQoder(target, payload, clientWantsStream, requestedModel, maskSession, guardResult));
            }

            // ===== 妙手 CatPaw：round → event → turn 三步会话机，非 OpenAI 兼容 =====
            if ("catpaw".equals(target.protocol)) {
                return adopt(out, forwardCatPaw(target, payload, clientWantsStream, requestedModel, maskSession, guardResult));
            }

            // ===== MiniMax Code：上游是 Anthropic messages，要做双向转换 =====
            if ("minimax".equals(target.protocol) || "minimax-code".equals(target.providerId)) {
                return adopt(out, forwardMiniMax(target, payload, clientWantsStream, requestedModel, maskSession, guardResult, guardCfg));
            }

            if (target.isAnthropic) {
                return adopt(out, forwardMiniMax(target, payload, clientWantsStream, requestedModel, maskSession, guardResult, guardCfg));
            }

            if ("codex".equals(target.providerId)) {
                return adopt(out, forwardCodex(target, payload, clientWantsStream, requestedModel, maskSession, guardResult, 0));
            }
            // ===== Antigravity：Google Code Assist 信封协议，非 OpenAI 兼容 =====
            if ("antigravity".equals(target.providerId) || "antigravity".equals(target.protocol)) {
                return adopt(out, forwardAntigravity(target, payload, clientWantsStream, requestedModel, maskSession, guardResult, 0));
            }

            String targetUrl = target.baseUrl.replaceAll("/+$", "")
                    + (target.isAnthropic ? "/messages" : "/chat/completions");
            if ("workbuddy".equals(target.providerId)) {
                targetUrl = "https://copilot.tencent.com/v2/chat/completions";
                payload.put("stream", true);            // 腾讯上游强制要求流式
            } else if ("workbuddy-ai".equals(target.providerId)) {
                // 国际版是另一个 build、另一个域，聊天接口同形
                targetUrl = "https://www.workbuddy.ai/v2/chat/completions";
                payload.put("stream", true);
            } else if ("raccoon".equals(target.providerId)) {
                targetUrl = "https://xiaohuanxiong.com/api/web/llm/v2/chat/completions";
            }

            if ("cline".equals(target.providerId)) {
                conn = ClineAuth.openHttp(targetUrl);
            } else {
                conn = (HttpURLConnection) new URL(targetUrl).openConnection();
            }
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(guardCfg.connectTimeoutSeconds * 1000);
            conn.setReadTimeout(guardCfg.requestTimeoutSeconds * 1000);
            conn.setRequestProperty("Content-Type", "application/json");

            if (target.isAnthropic) {
                conn.setRequestProperty("x-api-key", target.apiKey);
                conn.setRequestProperty("anthropic-version", "2023-06-01");
            } else if ("cline".equals(target.providerId)) {
                String bearer = target.apiKey;
                boolean isOauth = target.account != null && "oauth".equals(target.account.optString("type"));
                if (isOauth && !bearer.toLowerCase().startsWith("workos:")) {
                    bearer = "workos:" + bearer;
                }
                conn.setRequestProperty("Authorization", "Bearer " + bearer);
                java.util.Map<String, String> clH = ClineAuth.clientHeaders(null);
                for (java.util.Map.Entry<String, String> entry : clH.entrySet()) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
                conn.setRequestProperty("Accept", "text/event-stream, application/json");
                if (target.upstreamModel != null && target.upstreamModel.startsWith("cline-pass/deepseek")) {
                    JSONObject provOpts = payload.optJSONObject("providerOptions");
                    if (provOpts == null) {
                        provOpts = new JSONObject();
                        JSONObject gw = new JSONObject();
                        JSONArray only = new JSONArray();
                        only.put("deepseek");
                        gw.put("only", only);
                        JSONArray has = new JSONArray();
                        has.put("implicit-caching");
                        gw.put("has", has);
                        provOpts.put("gateway", gw);
                        payload.put("providerOptions", provOpts);
                    }
                }
            } else if ("github-copilot".equals(target.providerId)) {
                // Copilot 只认 Bearer + 一整套客户端指纹头，缺一个就是 401
                conn.setRequestProperty("Authorization", "Bearer " + target.apiKey);
                java.util.Map<String, String> cpH = CopilotAuth.clientHeaders();
                for (java.util.Map.Entry<String, String> e : cpH.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
                conn.setRequestProperty("X-Initiator", CopilotAuth.isAgentCall(payload) ? "agent" : "user");
                if (CopilotAuth.isVisionRequest(payload)) {
                    conn.setRequestProperty("Copilot-Vision-Request", "true");
                }
            } else {
                conn.setRequestProperty("Authorization", "Bearer " + target.apiKey);
            }

            // WorkBuddy 与小浣熊协议头部注入
            if ("workbuddy".equals(target.providerId) || "workbuddy-ai".equals(target.providerId)) {
                boolean ai = "workbuddy-ai".equals(target.providerId);
                conn.setRequestProperty("User-Agent", "CLI/2.63.2 CodeBuddy/2.63.2");
                conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
                conn.setRequestProperty("X-Product", "SaaS");
                conn.setRequestProperty("X-Agent-Intent", "craft");
                conn.setRequestProperty("X-Agent-Purpose", "conversation");
                conn.setRequestProperty("X-Conversation-ID", "00000000-0000-0000-0000-000000000000");
                conn.setRequestProperty("X-Agent-Type", "main");
                conn.setRequestProperty("X-Auth-Refresh-Source", ai ? "workbuddy-ai" : "workbuddy");
                if (ai) {
                    // 国际版的 API Key 通道：三头同值，X-User-Id 用 key 后 8 位
                    conn.setRequestProperty("X-API-Key", target.apiKey);
                    String tail = target.apiKey.length() > 8
                            ? target.apiKey.substring(target.apiKey.length() - 8) : target.apiKey;
                    conn.setRequestProperty("X-User-Id", "anonymous_" + tail);
                }
            } else if ("raccoon".equals(target.providerId)) {
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                conn.setRequestProperty("Accept", "text/event-stream, application/json");
            }

            OutputStream os = conn.getOutputStream();
            os.write(payload.toString().getBytes("UTF-8"));
            os.flush();
            os.close();

            int statusCode = conn.getResponseCode();
            boolean okStatus = statusCode >= 200 && statusCode < 400;
            InputStream is = okStatus ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) is = new ByteArrayInputStream(new byte[0]);

            out.status = statusCode;

            // ---------- 失败：把上游错误体读全，交给 retryable 判定 ----------
            if (!okStatus) {
                ByteArrayOutputStream eb = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) eb.write(buf, 0, n);
                is.close();
                String errBody = PrivacyMasker.unmaskText(eb.toString("UTF-8"), maskSession);
                if ("cline".equals(target.providerId)) {
                    try {
                        JSONObject ej = new JSONObject(errBody);
                        String em = ej.optString("message", "");
                        if (em.isEmpty()) {
                            JSONObject inner = ej.optJSONObject("error");
                            if (inner != null) em = inner.optString("message", "");
                        }
                        if (statusCode == 403 && (em.contains("not subscribed") || em.contains("ENTITLEMENT_ERROR"))) {
                            JSONObject ew = new JSONObject();
                            JSONObject in = new JSONObject();
                            in.put("message", "该模型需要 ClinePass 订阅（可在 app.cline.bot 订阅，或选用免费/按量模型）");
                            in.put("type", "cline_error");
                            in.put("code", 403);
                            ew.put("error", in);
                            errBody = ew.toString();
                        } else if (statusCode == 402 || em.contains("insufficient_credits")) {
                            JSONObject ew = new JSONObject();
                            JSONObject in = new JSONObject();
                            in.put("message", "Cline 账号余额不足，请前往 app.cline.bot/credits 充值");
                            in.put("type", "cline_error");
                            in.put("code", 429);
                            ew.put("error", in);
                            errBody = ew.toString();
                        } else if (statusCode == 401) {
                            JSONObject ew = new JSONObject();
                            JSONObject in = new JSONObject();
                            in.put("message", "Cline 登录凭据已失效，请重新登录");
                            in.put("type", "cline_error");
                            in.put("code", 401);
                            ew.put("error", in);
                            errBody = ew.toString();
                        }
                    } catch (Throwable ignored) {}
                }
                out.ok = false;
                out.body = errBody;
                Status st = Status.lookup(statusCode);
                if (st == null) st = Status.INTERNAL_ERROR;
                Response resp = newFixedLengthResponse(st, "application/json; charset=UTF-8", errBody);
                addCorsHeaders(resp);
                applyGuardHeaders(resp, guardResult);
                out.response = resp;
                return out;
            }

            // ---------- 成功 ----------
            out.ok = true;

            // WorkBuddy 上游强制流式，客户端要非流式时由网关聚合成一个完整回复
            if (("workbuddy".equals(target.providerId) || "workbuddy-ai".equals(target.providerId))
                    && !clientWantsStream) {
                BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                StringBuilder contentAcc = new StringBuilder();
                StringBuilder reasonAcc = new StringBuilder();
                // 工具调用是分片增量（index + id/name 在首片，arguments 逐片累加），
                // 之前这里只累加 content/reasoning，等于把 tool_calls 整个丢掉 ——
                // 非流式客户端带 tools 时会拿到空回复。
                java.util.Map<Integer, JSONObject> toolAcc =
                        new java.util.LinkedHashMap<Integer, JSONObject>();
                String aggFinish = "stop";
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("data: ") && !line.contains("[DONE]")) {
                        try {
                            JSONObject chunk = new JSONObject(line.substring(6));
                            {   // 用量：WorkBuddy 在最后一个 chunk 里给 usage
                                UsageLedger.Row ur = U_ROW.get();
                                if (ur != null) absorbUsage(ur, chunk.optJSONObject("usage"));
                            }
                            JSONArray choices = chunk.optJSONArray("choices");
                            if (choices != null && choices.length() > 0) {
                                JSONObject c0 = choices.getJSONObject(0);
                                String fr = c0.optString("finish_reason", "");
                                if (fr != null && !fr.isEmpty() && !"null".equals(fr)) aggFinish = fr;
                                JSONObject delta = c0.optJSONObject("delta");
                                if (delta != null) {
                                    contentAcc.append(delta.optString("content", ""));
                                    reasonAcc.append(delta.optString("reasoning_content", ""));
                                    JSONArray tcs = delta.optJSONArray("tool_calls");
                                    for (int ti = 0; tcs != null && ti < tcs.length(); ti++) {
                                        JSONObject tc = tcs.optJSONObject(ti);
                                        if (tc == null) continue;
                                        int idx = tc.optInt("index", ti);
                                        JSONObject acc = toolAcc.get(Integer.valueOf(idx));
                                        if (acc == null) {
                                            acc = new JSONObject();
                                            acc.put("index", idx);
                                            acc.put("type", "function");
                                            acc.put("function", new JSONObject());
                                            toolAcc.put(Integer.valueOf(idx), acc);
                                        }
                                        if (tc.has("id")) acc.put("id", tc.optString("id", ""));
                                        JSONObject fn = tc.optJSONObject("function");
                                        if (fn != null) {
                                            JSONObject afn = acc.optJSONObject("function");
                                            if (afn == null) afn = new JSONObject();
                                            // 上游只在首片给 name，后续片带的是空串，
                                            // 无条件覆盖会把工具名抹成 ""
                                            String fnName = fn.optString("name", "");
                                            if (!fnName.isEmpty()) afn.put("name", fnName);
                                            if (fn.has("arguments")) {
                                                afn.put("arguments", afn.optString("arguments", "")
                                                        + fn.optString("arguments", ""));
                                            }
                                            acc.put("function", afn);
                                        }
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
                br.close();

                String finalContent = contentAcc.toString();
                if (finalContent.isEmpty()) finalContent = reasonAcc.toString();
                finalContent = PrivacyMasker.unmaskText(finalContent, maskSession);

                JSONObject simulatedResp = new JSONObject();
                simulatedResp.put("id", "chatcmpl-" + System.currentTimeMillis());
                simulatedResp.put("object", "chat.completion");
                simulatedResp.put("created", System.currentTimeMillis() / 1000);
                simulatedResp.put("model", requestedModel);

                JSONArray choices = new JSONArray();
                JSONObject choice = new JSONObject();
                choice.put("index", 0);
                JSONObject msg = new JSONObject();
                msg.put("role", "assistant");
                msg.put("content", finalContent);
                if (reasonAcc.length() > 0) msg.put("reasoning_content", reasonAcc.toString());
                if (!toolAcc.isEmpty()) {
                    JSONArray tcsOut = new JSONArray();
                    for (JSONObject acc : toolAcc.values()) {
                        acc.remove("index");
                        tcsOut.put(acc);
                    }
                    msg.put("tool_calls", tcsOut);
                    aggFinish = "tool_calls";
                }
                choice.put("message", msg);
                choice.put("finish_reason", aggFinish);
                choices.put(choice);
                simulatedResp.put("choices", choices);

                // 上游聚合流没给 usage：这里只是按字符数估的，标 estimated 让客户端自己决定信不信，
                // 并且不再往 totalTokens 计数器里加（真值以用量账本为准，否则两套口径永远对不上）
                JSONObject usage = new JSONObject();
                usage.put("prompt_tokens", 10);
                usage.put("completion_tokens", finalContent.length() / 2);
                usage.put("total_tokens", 10 + finalContent.length() / 2);
                usage.put("estimated", true);
                simulatedResp.put("usage", usage);

                // 这条聚合路径是独立拼装的，不走下面普通非流式那段，
                // 所以响应侧的 think-tags / word-guard / model-map 别名都得在这里补一遍。
                try {
                    JSONObject mwAgg = configJson.optJSONObject("middleware");
                    if (mwAgg != null) {
                        JSONObject aggMsg = msg;
                        String aggTxt = aggMsg.optString("content", "");
                        String aggRsn = aggMsg.optString("reasoning_content", "");
                        String[] aggFixed = Middleware.applyThinkTags(aggTxt, aggRsn, mwAgg);
                        if (!aggFixed[0].equals(aggTxt) || !aggFixed[1].equals(aggRsn)) {
                            aggMsg.put("content", aggFixed[0]);
                            if (aggFixed[1].isEmpty()) aggMsg.remove("reasoning_content");
                            else aggMsg.put("reasoning_content", aggFixed[1]);
                        }
                        if (Middleware.wordGuardEnabled(mwAgg)) {
                            aggMsg.put("content", Middleware.maskWords(aggMsg.optString("content", ""), mwAgg));
                        }
                    }
                    String aliasAgg = CLIENT_MODEL.get();
                    if (aliasAgg != null && !aliasAgg.isEmpty()) simulatedResp.put("model", aliasAgg);
                } catch (Throwable ignored) {
                }

                out.ok = true;
                out.textOut = finalContent;
                out.reasoningOut = reasonAcc.toString();
                if (wantsAnthropicOut()) {
                    // 这条聚合路径也要转格式，否则 /v1/messages 打到 WorkBuddy 会拿到 OpenAI 形状。
                    // 必须用 responseToAnthropic 走完整互转（含 tool_calls），
                    // 不能只拿 finalContent —— 那样会把工具调用丢掉。
                    JSONObject anth = AnthropicBridge.responseToAnthropic(simulatedResp, requestedModel);
                    Response resp = newFixedLengthResponse(Status.OK, "application/json; charset=UTF-8", anth.toString());
                    addCorsHeaders(resp);
                    applyGuardHeaders(resp, guardResult);
                    out.response = resp;
                    return out;
                }
                Response resp = newFixedLengthResponse(Status.OK, "application/json; charset=UTF-8", simulatedResp.toString());
                resp.addHeader("X-Magpie-Served-By", out.modelRef == null ? target.providerId : out.modelRef);
                addCorsHeaders(resp);
                applyGuardHeaders(resp, guardResult);
                out.response = resp;
                return out;
            }

            if (clientWantsStream) {
                out.response = wantsAnthropicOut()
                        ? streamForwardAnthropicResponse(is, requestedModel, maskSession, guardResult, conn)
                        : streamForwardResponse(is, maskSession, guardResult, conn,
                                out.modelRef == null ? target.providerId : out.modelRef);
                handedOff = true;
                return out;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) != -1) baos.write(buffer, 0, len);
            is.close();

            String rawResponse = baos.toString("UTF-8");
            String unmaskedResponse = PrivacyMasker.unmaskText(rawResponse, maskSession);

            // 用量：非流式响应里一次带走整个 usage
            try {
                UsageLedger.Row ur = U_ROW.get();
                if (ur != null) {
                    JSONObject uj = new JSONObject(unmaskedResponse);
                    absorbUsage(ur, uj.optJSONObject("usage"));
                }
            } catch (Throwable ignored) {
            }

            if ("cline".equals(target.providerId)) {
                try {
                    JSONObject cWrap = new JSONObject(unmaskedResponse);
                    if (cWrap.has("data") && cWrap.optJSONObject("data") != null) {
                        JSONObject cData = cWrap.getJSONObject("data");
                        if (cData.has("choices")) {
                            unmaskedResponse = cData.toString();
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            // ===== 响应侧中间件：think-tags 与 word-guard =====
            // 只在 OpenAI 形状（有 choices）上动手；拿不到结构就原样放行，
            // 绝不因为中间件把正常回复弄坏。
            try {
                JSONObject mwOut = configJson.optJSONObject("middleware");
                if (mwOut != null && (Middleware.thinkTagsEnabled(mwOut) || Middleware.wordGuardEnabled(mwOut))) {
                    JSONObject oj = new JSONObject(unmaskedResponse);
                    JSONArray chs = oj.optJSONArray("choices");
                    if (chs != null) {
                        boolean touched = false;
                        for (int ci = 0; ci < chs.length(); ci++) {
                            JSONObject ch0 = chs.optJSONObject(ci);
                            if (ch0 == null) continue;
                            JSONObject msg = ch0.optJSONObject("message");
                            if (msg == null) continue;
                            String txt = msg.optString("content", "");
                            String rsn = msg.optString("reasoning_content", "");
                            String[] fixed = Middleware.applyThinkTags(txt, rsn, mwOut);
                            if (!fixed[0].equals(txt) || !fixed[1].equals(rsn)) {
                                if (fixed[0].isEmpty()) msg.put("content", "");
                                else msg.put("content", fixed[0]);
                                if (fixed[1].isEmpty()) msg.remove("reasoning_content");
                                else msg.put("reasoning_content", fixed[1]);
                                touched = true;
                            }
                            if (Middleware.wordGuardEnabled(mwOut)) {
                                Object cur = msg.opt("content");
                                if (cur instanceof String) {
                                    String maskedTxt = Middleware.maskWords((String) cur, mwOut);
                                    if (!maskedTxt.equals(cur)) { msg.put("content", maskedTxt); touched = true; }
                                }
                            }
                        }
                        if (touched) unmaskedResponse = oj.toString();
                    }
                    // model-map 的语义：回复报客户端请求的名字
                    String aliasOut = CLIENT_MODEL.get();
                    if (aliasOut != null && !aliasOut.isEmpty()) {
                        JSONObject oj2 = new JSONObject(unmaskedResponse);
                        if (oj2.has("model")) {
                            oj2.put("model", aliasOut);
                            unmaskedResponse = oj2.toString();
                        }
                    }
                }
            } catch (Throwable mwOutErr) {
                mwOutErr.printStackTrace();
            }

            try {
                if (wantsAnthropicOut()) {
                    JSONObject anth = AnthropicBridge.responseToAnthropic(
                            new JSONObject(unmaskedResponse), requestedModel);
                    JSONArray ac = anth.optJSONArray("content");
                    StringBuilder tb = new StringBuilder();
                    StringBuilder rb = new StringBuilder();
                    for (int i = 0; ac != null && i < ac.length(); i++) {
                        JSONObject blk = ac.optJSONObject(i);
                        if (blk == null) continue;
                        if ("text".equals(blk.optString("type"))) tb.append(blk.optString("text", ""));
                        else if ("thinking".equals(blk.optString("type"))) rb.append(blk.optString("thinking", ""));
                    }
                    out.textOut = tb.toString();
                    out.reasoningOut = rb.toString();
                } else {
                    JSONObject oj = new JSONObject(unmaskedResponse);
                    out.textOut = extractReplyText(oj);
                }
            } catch (Exception ignored) {}
            unmaskedResponse = toAnthropicBodyIfNeeded(unmaskedResponse, requestedModel);

            Status status = Status.lookup(statusCode);
            if (status == null) status = Status.OK;
            Response resp = newFixedLengthResponse(status, "application/json; charset=UTF-8", unmaskedResponse);
            // 这次请求实际由哪个供应商答的：路由组/rotate 下排查问题全靠它
            resp.addHeader("X-Magpie-Served-By", out.modelRef == null ? target.providerId : out.modelRef);
            addCorsHeaders(resp);
            applyGuardHeaders(resp, guardResult);
            out.response = resp;
            return out;

        } catch (Exception e) {
            // 连接/DNS/TLS/超时：状态记 0，让 retryable 认为"换一家可能就通了"
            e.printStackTrace();
            out.ok = false;
            out.status = 0;
            out.body = String.valueOf(e.getMessage());
            String msg = e.getMessage() == null ? "connection failed" : e.getMessage();
            out.response = errorResponse(502, "upstream " + target.providerId + " failed: " + msg);
            return out;
        } finally {
            if (conn != null && !handedOff) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
            // 流式由 streamForwardResponse 的 EOF 记账，这里只管非流式与失败
            if (!handedOff) {
                int st = out.status;
                String er = out.ok ? "" : (out.body == null ? "" : out.body);
                // 有些分支失败时没设 status，会记成 0 而看不出是失败；这里兜底
                if (!out.ok && st < 400) {
                    st = 502;
                    if (er.isEmpty()) er = "请求失败：上游无响应或路由无可用候选";
                }
                commitUsage(st, er);
            }
        }
    }

    /**
     * Qoder 的一次转发。
     * Qoder 只吐 SSE，所以这里一律先把事件读完再组装：
     * 客户端要流式就给一个完整的 SSE（首帧带全部内容 + finish + [DONE]），
     * 否则给普通 JSON。这样既满足客户端协议，又不用先发字节再遇到错误。
     */
    private Attempt forwardQoder(RouteTarget target, JSONObject payload, boolean clientWantsStream,
                                String requestedModel, PrivacyMasker.Session maskSession,
                                ContextGuard.Result guardResult) {
        Attempt out = new Attempt();
        out.providerId = target.providerId;
        out.modelRef = target.providerId + "/" + target.upstreamModel;

        try {
            JSONObject acc = target.account;
            QoderAuth.SignIn si = new QoderAuth.SignIn();
            si.access = acc.optString("token", "");
            si.deviceToken = acc.optString("deviceToken", "");
            si.uid = acc.optString("uid", "");
            si.email = acc.optString("email", "");
            si.name = acc.optString("name", "");
            si.machineId = acc.optString("machineId", "");
            si.deviceChat = acc.optBoolean("deviceChat", false);

            // 该模型的 model_config 来自账号自己的模型清单
            JSONObject meta = null;
            JSONObject prov = getProviderById("qoder_cn");
            if (prov != null) {
                JSONObject mm = prov.optJSONObject("modelMeta");
                if (mm != null) meta = mm.optJSONObject(target.upstreamModel);
            }

            org.json.JSONArray msgs = payload.optJSONArray("messages");
            org.json.JSONArray tools = payload.optJSONArray("tools");
            Integer mt = null;
            int maxTokens = payload.optInt("max_tokens", 0);
            int maxCompletionTokens = payload.optInt("max_completion_tokens", 0);
            if (maxTokens > 0 && maxCompletionTokens > 0) mt = Math.min(maxTokens, maxCompletionTokens);
            else if (maxTokens > 0) mt = maxTokens;
            else if (maxCompletionTokens > 0) mt = maxCompletionTokens;
            JSONObject qb = QoderAuth.buildChatBody(si, msgs == null ? new org.json.JSONArray() : msgs,
                    tools, mt, target.upstreamModel, meta);
            org.json.JSONArray pieces = QoderAuth.chatEvents(si, qb);

            StringBuilder textAcc = new StringBuilder();
            StringBuilder reasonAcc = new StringBuilder();
            String err = null;
            for (int i = 0; i < pieces.length(); i++) {
                JSONObject pc = pieces.optJSONObject(i);
                if (pc == null) continue;
                if (pc.has("error")) {
                    err = pc.optString("error", "qoder error");
                    break;
                }
                if (pc.has("reasoning")) reasonAcc.append(pc.optString("reasoning", ""));
                if (pc.has("text")) textAcc.append(pc.optString("text", ""));
            }

            if (err != null) {
                out.ok = false;
                // Qoder 的错误体是文本，交给 retryable 的措辞判断
                out.status = err.contains("401") || err.contains("403") ? 401
                        : err.contains("429") ? 429
                        : err.contains("HTTP 5") ? 502 : 400;
                out.body = err;
                Response resp = errorResponse(out.status >= 500 ? 502 : out.status,
                        "qoder: " + err);
                out.response = resp;
                return out;
            }

            String finalText = PrivacyMasker.unmaskText(textAcc.toString(), maskSession);
            String finalReason = reasonAcc.toString();
            out.ok = true;
            out.textOut = finalText;
            out.reasoningOut = finalReason;
            if (wantsAnthropicOut()) {
                out.response = anthropicFromText(finalText, finalReason, requestedModel,
                        clientWantsStream, guardResult);
                return out;
            }

            if (clientWantsStream) {
                StringBuilder sse = new StringBuilder();
                JSONObject chunk = new JSONObject();
                chunk.put("id", "chatcmpl-" + System.currentTimeMillis());
                chunk.put("object", "chat.completion.chunk");
                chunk.put("created", System.currentTimeMillis() / 1000);
                chunk.put("model", requestedModel);
                org.json.JSONArray choices = new org.json.JSONArray();
                JSONObject ch = new JSONObject();
                ch.put("index", 0);
                JSONObject delta = new JSONObject();
                delta.put("role", "assistant");
                if (finalReason.length() > 0) delta.put("reasoning_content", finalReason);
                delta.put("content", finalText);
                ch.put("delta", delta);
                ch.put("finish_reason", JSONObject.NULL);
                choices.put(ch);
                chunk.put("choices", choices);
                sse.append("data: ").append(chunk.toString()).append("\n\n");

                JSONObject done = new JSONObject();
                done.put("id", chunk.optString("id"));
                done.put("object", "chat.completion.chunk");
                done.put("created", chunk.optLong("created"));
                done.put("model", requestedModel);
                org.json.JSONArray c2 = new org.json.JSONArray();
                JSONObject ch2 = new JSONObject();
                ch2.put("index", 0);
                ch2.put("delta", new JSONObject());
                ch2.put("finish_reason", "stop");
                c2.put(ch2);
                done.put("choices", c2);
                sse.append("data: ").append(done.toString()).append("\n\n");
                sse.append("data: [DONE]\n\n");

                Response resp = newFixedLengthResponse(Status.OK, "text/event-stream", sse.toString());
                resp.addHeader("Cache-Control", "no-cache");
                addCorsHeaders(resp);
                applyGuardHeaders(resp, guardResult);
                out.response = resp;
                return out;
            }

            JSONObject respJson = new JSONObject();
            respJson.put("id", "chatcmpl-" + System.currentTimeMillis());
            respJson.put("object", "chat.completion");
            respJson.put("created", System.currentTimeMillis() / 1000);
            respJson.put("model", requestedModel);
            org.json.JSONArray choices = new org.json.JSONArray();
            JSONObject ch = new JSONObject();
            ch.put("index", 0);
            JSONObject msg = new JSONObject();
            msg.put("role", "assistant");
            msg.put("content", finalText);
            if (finalReason.length() > 0) msg.put("reasoning_content", finalReason);
            ch.put("message", msg);
            ch.put("finish_reason", "stop");
            choices.put(ch);
            respJson.put("choices", choices);
            JSONObject usage = new JSONObject();
            usage.put("prompt_tokens", 0);
            usage.put("completion_tokens", 0);
            usage.put("total_tokens", 0);
            respJson.put("usage", usage);

            Response resp = newFixedLengthResponse(Status.OK, "application/json; charset=UTF-8", respJson.toString());
            addCorsHeaders(resp);
            applyGuardHeaders(resp, guardResult);
            out.response = resp;
            return out;
        } catch (Throwable e) {
            e.printStackTrace();
            out.ok = false;
            out.status = 0;
            out.body = String.valueOf(e.getMessage());
            return out;
        }
    }

    /**
     * 判断请求体是不是 Anthropic Messages 原生格式。
     * /v1/messages 的客户端（Claude Code 等）发的是这个，和我们内部的 OpenAI 形态完全不同：
     * 它有顶层 system、tools[].input_schema、content 是 block 数组。
     */
    /** 明确带 OpenAI 专属字段的 payload（极少见于 /v1/messages，但保留兼容） */
    private static boolean looksOpenAIPayload(JSONObject p) {
        if (p == null) return false;
        String[] openaiOnly = {"max_completion_tokens", "response_format", "functions", "function_call",
                "presence_penalty", "frequency_penalty", "logit_bias", "n", "seed", "stop",
                "logprobs", "top_logprobs", "service_tier", "parallel_tool_calls"};
        for (String k : openaiOnly) {
            if (p.has(k) && !p.isNull(k)) return true;
        }
        return false;
    }

    private static boolean looksAnthropicPayload(JSONObject p) {
        if (p == null) return false;
        if (p.has("system") && !p.isNull("system")) return true;
        JSONArray tools = p.optJSONArray("tools");
        if (tools != null && tools.length() > 0) {
            JSONObject t = tools.optJSONObject(0);
            if (t != null && t.has("input_schema")) return true;
        }
        JSONArray msgs = p.optJSONArray("messages");
        if (msgs != null && msgs.length() > 0) {
            JSONObject m = msgs.optJSONObject(0);
            if (m != null) {
                Object c = m.opt("content");
                if (c instanceof JSONArray) {
                    JSONArray arr = (JSONArray) c;
                    JSONObject b0 = arr.optJSONObject(0);
                    if (b0 != null && b0.has("type") && !b0.has("role")) return true;
                }
            }
        }
        return false;
    }

    /** 取第一个带 session JWT 的 ZCode 账号（只有它能花 gift 额度） */
    private JSONObject zcodeSessionAccount() {
        JSONObject zc = getProviderById("zcode");
        JSONArray accs = zc == null ? null : zc.optJSONArray("accounts");
        for (int i = 0; accs != null && i < accs.length(); i++) {
            JSONObject a = accs.optJSONObject(i);
            if (a != null && !a.optString("jwt", "").trim().isEmpty()) return a;
        }
        return null;
    }

    /** gift 余额缓存（10 分钟）：每次转发都查两个接口太浪费，且变化不频繁 */
    private volatile JSONObject cachedGift = null;
    private volatile long cachedGiftAt = 0;

    private JSONObject currentGift(String jwt, String device) {
        long now = System.currentTimeMillis();
        JSONObject c = cachedGift;
        if (c != null && now - cachedGiftAt < 10 * 60 * 1000L) return c;
        JSONObject s = ZCodeAuth.giftSummary(jwt, device);
        if (!s.optBoolean("ok", false)) {
            // 冷启动/网络抖动时首查常失败；静默回退到 Plan 会让用户以为「额度没了」，
            // 这里重试一次再下结论。
            try { Thread.sleep(300); } catch (Exception ignored) {}
            JSONObject again = ZCodeAuth.giftSummary(jwt, device);
            if (again.optBoolean("ok", false)) s = again;
        }
        if (s.optBoolean("ok", false)) {
            cachedGift = s;
            cachedGiftAt = now;
        }
        return s;
    }

    /**
     * OpenAI 形态的请求走 ZCode gift。
     * 返回 null 表示「这个模型不在赠送额度的支持范围内」，调用方继续走原来的 Coding Plan 路径。
     */
    private Response tryZCodeGiftOpenAI(JSONObject payload, String requestedModel, boolean clientWantsStream,
                                        PrivacyMasker.Session maskSession, ContextGuard.Result guardResult) {
        JSONObject acc = zcodeSessionAccount();
        if (acc == null) return null;
        String jwt = acc.optString("jwt", "");
        String device = acc.optString("device", "");
        String site = acc.optString("site", "zai");

        String upstream = requestedModel.startsWith("zcode/")
                ? requestedModel.substring(6) : requestedModel;

        JSONObject summary = currentGift(jwt, device);
        java.util.List<String> allowed = ZCodeGift.allowedModels(summary);
        if (allowed.isEmpty()) return null;
        boolean supported = false;
        for (String m : allowed) if (m.equalsIgnoreCase(upstream)) { supported = true; break; }
        if (!supported) return null;

        JSONObject anthropic;
        try {
            JSONObject src = new JSONObject(payload.toString());
            src.put("model", upstream);
            if (clientWantsStream) src.put("stream", true);
            anthropic = ZCodeAnthropic.toAnthropic(src);
        } catch (Exception e) {
            return errorResponse(400, "请求体转换成 Anthropic 格式失败: " + e.getMessage());
        }

        ZCodeGift.Stream st = ZCodeGift.openStream(jwt, device, site, upstream, anthropic,
                null, 15 * 60 * 1000, false);
        if (st.status == 405 || st.refused) {
            if (st.conn != null) st.conn.disconnect();
            st = ZCodeGift.openStream(jwt, device, site, upstream, anthropic,
                    null, 15 * 60 * 1000, true);
        }
        if (st.status < 200 || st.status >= 400) {
            if (st.conn != null) st.conn.disconnect();
            if (st.refused) {
                return errorResponse(405, st.edge
                        ? "ZCode 边缘防火墙拦了这个网络的出口 IP，换个网络或稍后再试。"
                        : "ZCode 风控把这个账号判成异常活动（code 3012）。插件文档说 ZCode 自己的客户端也会被这样拦，"
                          + "只有 ZCode 客服能解除。原始返回：" + st.errorBody);
            }
            return errorResponse(st.status <= 0 ? 502 : st.status,
                    "ZCode gift: " + (st.errorBody.isEmpty() ? st.error : st.errorBody));
        }

        if (clientWantsStream) {
            InputStream converted = new AnthropicSseToOpenAI(st.is, new ZCodeAnthropic.StreamState(requestedModel));
            Response resp = wantsAnthropicOut()
                    ? streamForwardAnthropicResponse(converted, requestedModel, maskSession, guardResult, st.conn)
                    : streamForwardResponse(converted, maskSession, guardResult, st.conn, "zcode-gift");
            resp.addHeader("X-Magpie-Served-By", "zcode-gift");
            return resp;
        }

        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = st.is.read(buf)) != -1) bo.write(buf, 0, n);
            st.is.close();
            String text = new String(bo.toByteArray(), "UTF-8");
            if (maskSession != null) text = PrivacyMasker.unmaskText(text, maskSession);
            JSONObject msg = new JSONObject(text);
            if (wantsAnthropicOut()) {
                Response resp = newFixedLengthResponse(Status.OK, "application/json; charset=UTF-8", text);
                resp.addHeader("X-Magpie-Served-By", "zcode-gift");
                addCorsHeaders(resp);
                applyGuardHeaders(resp, guardResult);
                return resp;
            }
            JSONObject out = ZCodeAnthropic.toOpenAI(msg, requestedModel);
            Response resp = newFixedLengthResponse(Status.OK, "application/json; charset=UTF-8", out.toString());
            resp.addHeader("X-Magpie-Served-By", "zcode-gift");
            addCorsHeaders(resp);
            applyGuardHeaders(resp, guardResult);
            return resp;
        } catch (Exception e) {
            return errorResponse(502, "读取 ZCode gift 响应失败: " + e.getMessage());
        } finally {
            if (st.conn != null) st.conn.disconnect();
        }
    }

    /** 边读 Anthropic SSE 边改写成 OpenAI chunk 的字节流 */
    /**
     * 流式脱敏还原器。
     *
     * 占位符形如 {{MASK_PHONE_ab12cd34}}，模型逐 token 输出时它完全可能被切成
     * 「{{MASK_PHO」+「NE_ab12cd34}}」两个 delta。原来每个 delta 各自 unmaskText，
     * 切开的那一半永远匹配不上，客户端就会看到内部占位符残片。
     *
     * 仅缓存仍可能成为已知占位符的后缀；结束时输出未匹配的原文。
     */
    static final class StreamUnmask {
        private final PrivacyMasker.Session session;
        private final StringBuilder pending = new StringBuilder();

        StreamUnmask(PrivacyMasker.Session session) {
            this.session = session;
        }

        String feed(String chunk) {
            if (chunk == null || chunk.isEmpty()) return "";
            if (session == null || session.placeholderToReal.isEmpty()) return chunk;
            pending.append(chunk);
            // Hold only a suffix that could still become a known placeholder.
            int hold = 0;
            for (String key : session.placeholderToReal.keySet()) {
                int max = Math.min(key.length() - 1, pending.length());
                for (int n = max; n > hold; n--) {
                    if (key.regionMatches(0, pending.toString(), pending.length() - n, n)) {
                        hold = n; break;
                    }
                }
            }
            int readyLength = pending.length() - hold;
            String ready = pending.substring(0, readyLength);
            pending.delete(0, readyLength);
            return PrivacyMasker.unmaskText(ready, session);
        }

        String flush() {
            String tail = pending.toString();
            pending.setLength(0);
            return tail;
        }

        boolean hasPending() { return pending.length() > 0; }
    }

    static String openAiStreamError(String message) {
        try {
            JSONObject error = new JSONObject();
            error.put("message", message);
            error.put("type", "upstream_error");
            JSONObject envelope = new JSONObject();
            envelope.put("error", error);
            return "\ndata: " + envelope.toString() + "\n\n";
        } catch (Exception e) {
            return "data: {\"error\":{\"type\":\"upstream_error\"}}\n\n";
        }
    }

    static final class OpenAiUnmask {
        final PrivacyMasker.Session session;
        final java.util.Map<Integer, StreamUnmask> content = new java.util.HashMap<>();
        final java.util.Map<Integer, StreamUnmask> reasoning = new java.util.HashMap<>();
        final java.util.Map<String, StreamUnmask> tools = new java.util.HashMap<>();
        OpenAiUnmask(PrivacyMasker.Session session) { this.session = session; }
        StreamUnmask get(java.util.Map<Integer, StreamUnmask> map, int index) {
            StreamUnmask item = map.get(index);
            if (item == null) { item = new StreamUnmask(session); map.put(index, item); }
            return item;
        }
        void apply(JSONObject chunk) throws Exception {
            if (session == null) return;
            JSONArray choices = chunk.optJSONArray("choices");
            for (int i = 0; choices != null && i < choices.length(); i++) {
                JSONObject choice = choices.optJSONObject(i);
                if (choice == null) continue;
                int ci = choice.optInt("index", i);
                JSONObject delta = choice.optJSONObject("delta");
                if (delta == null) { delta = new JSONObject(); choice.put("delta", delta); }
                StreamUnmask c = get(content, ci), r = get(reasoning, ci);
                if (delta.opt("content") instanceof String) delta.put("content", c.feed(delta.optString("content")));
                if (delta.opt("reasoning_content") instanceof String) delta.put("reasoning_content", r.feed(delta.optString("reasoning_content")));
                JSONArray calls = delta.optJSONArray("tool_calls");
                for (int j = 0; calls != null && j < calls.length(); j++) {
                    JSONObject call = calls.optJSONObject(j);
                    JSONObject fn = call == null ? null : call.optJSONObject("function");
                    if (fn == null || !(fn.opt("arguments") instanceof String)) continue;
                    String key = ci + ":" + call.optInt("index", j);
                    StreamUnmask u = tools.get(key);
                    if (u == null) { u = new StreamUnmask(session); tools.put(key, u); }
                    fn.put("arguments", u.feed(fn.optString("arguments")));
                }
                if (choice.has("finish_reason") && !choice.isNull("finish_reason")) {
                    String ct = c.flush(), rt = r.flush();
                    if (!ct.isEmpty()) delta.put("content", (delta.opt("content") instanceof String ? delta.optString("content") : "") + ct);
                    if (!rt.isEmpty()) delta.put("reasoning_content", (delta.opt("reasoning_content") instanceof String ? delta.optString("reasoning_content") : "") + rt);
                    if (calls == null) calls = new JSONArray();
                    String prefix = ci + ":";
                    for (java.util.Map.Entry<String, StreamUnmask> entry : tools.entrySet()) {
                        if (!entry.getKey().startsWith(prefix)) continue;
                        String tail = entry.getValue().flush();
                        if (tail.isEmpty()) continue;
                        int index = Integer.parseInt(entry.getKey().substring(prefix.length()));
                        JSONObject target = null;
                        for (int j = 0; j < calls.length(); j++) {
                            JSONObject call = calls.optJSONObject(j);
                            if (call != null && call.optInt("index", j) == index) { target = call; break; }
                        }
                        if (target == null) { target = new JSONObject(); target.put("index", index); calls.put(target); }
                        JSONObject fn = target.optJSONObject("function");
                        if (fn == null) { fn = new JSONObject(); target.put("function", fn); }
                        fn.put("arguments", (fn.opt("arguments") instanceof String ? fn.optString("arguments") : "") + tail);
                    }
                    if (calls.length() > 0) delta.put("tool_calls", calls);
                }
            }
        }
    }

    private static InputStream disconnectOnClose(InputStream in, final HttpURLConnection conn) {
        return new java.io.FilterInputStream(in) {
            @Override public void close() throws IOException {
                try { super.close(); } finally { if (conn != null) conn.disconnect(); }
            }
        };
    }

    private static class AnthropicSseToOpenAI extends InputStream {
        private final java.io.BufferedReader reader;
        private final ZCodeAnthropic.StreamState state;
        private byte[] buf = new byte[0];
        private int pos = 0;
        private boolean done = false;
        private String pendingEvent = null;
        private boolean started = false;
        private boolean finished = false;

        private boolean fail(String message) {
            done = true;
            buf = openAiStreamError(message).getBytes(java.nio.charset.Charset.forName("UTF-8"));
            pos = 0;
            return true;
        }

        AnthropicSseToOpenAI(InputStream upstream, ZCodeAnthropic.StreamState state) {
            this.reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(upstream, java.nio.charset.Charset.forName("UTF-8")));
            this.state = state;
        }

        private boolean fill() throws IOException {
            while (pos >= buf.length) {
                if (done) return false;
                String line;
                try { line = reader.readLine(); }
                catch (IOException e) { return fail("Anthropic upstream stream read failed"); }
                if (line == null) return fail("Anthropic upstream ended before message_stop");
                if (line.startsWith("event:")) {
                    pendingEvent = line.substring(6).trim();
                    continue;
                }
                if (line.startsWith("data:")) {
                    String d = line.substring(5).trim();
                    if ("[DONE]".equals(d)) {
                        return fail("Anthropic upstream sent [DONE] before message_stop");
                    }
                    JSONObject obj = null;
                    try {
                        obj = new JSONObject(d);
                    } catch (Exception ignored) {
                    }
                    if (obj == null) return fail("Invalid Anthropic upstream stream data");
                    String type = obj.optString("type", pendingEvent == null ? "" : pendingEvent);
                    if ("error".equals(type) || obj.has("error")) {
                        JSONObject error = obj.optJSONObject("error");
                        return fail(error == null ? "Anthropic upstream stream error" : error.optString("message", "Anthropic upstream stream error"));
                    }
                    if ("message_start".equals(type)) started = true;
                    if ("message_delta".equals(type)) {
                        JSONObject delta = obj.optJSONObject("delta");
                        if (delta != null && delta.has("stop_reason") && !delta.isNull("stop_reason")) finished = true;
                    }
                    if ("message_stop".equals(type)) {
                        if (!started || !finished) return fail("Anthropic upstream ended without a complete message");
                        done = true;
                        buf = "data: [DONE]\n\n".getBytes(java.nio.charset.Charset.forName("UTF-8"));
                        pos = 0;
                        return true;
                    }
                    java.util.List<String> outs = state.onEvent(pendingEvent, obj);
                    pendingEvent = null;
                    if (outs.isEmpty()) continue;
                    StringBuilder sb = new StringBuilder();
                    for (String o : outs) sb.append("data: ").append(o).append("\n\n");
                    buf = sb.toString().getBytes(java.nio.charset.Charset.forName("UTF-8"));
                    pos = 0;
                    return true;
                }
            }
            return true;
        }

        @Override
        public int read() throws IOException {
            if (!fill()) return -1;
            return buf[pos++] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (!fill()) return -1;
            int n = Math.min(len, buf.length - pos);
            System.arraycopy(buf, pos, b, off, n);
            pos += n;
            return n;
        }

        @Override
        public void close() throws java.io.IOException {
            reader.close();
        }
    }

    /**
     * 连通性测试的 zcode gift 分支。
     *
     * 为什么必须有：/api/test-model 是独立实现，自己拼 baseUrl + /chat/completions，
     * 那条永远走 Coding Plan 账本（open.bigmodel.cn/api/coding/paas/v4），
     * 所以「赠送额度明明还有，测试却报 1113」。测试必须和真实调用走同一条路，
     * 否则它测的根本不是用户会用的那个东西。
     *
     * 返回 null 表示 gift 覆盖不了这个模型，让调用方继续走原来的 Plan 路径。
     */
    private JSONObject probeZCodeGift(String testModel) {
        JSONObject acc = zcodeSessionAccount();
        if (acc == null) return null;

        String jwt = acc.optString("jwt", "");
        String device = acc.optString("device", "");
        String site = acc.optString("site", "zai");
        String upstream = testModel.startsWith("zcode/") ? testModel.substring(6) : testModel;

        JSONObject summary = currentGift(jwt, device);
        if (!summary.optBoolean("ok", false)) {
            // 查不到额度状态≠额度没了：明确报错，别伪装成「走 Plan」再给一个 1113
            JSONObject err = new JSONObject();
            try {
                err.put("ok", false);
                err.put("model", testModel);
                err.put("billing", "gift");
                err.put("error", "赠送额度状态查询失败：" + summary.optString("error", "未知原因")
                        + "（已重试一次，这不代表额度没了）");
            } catch (Exception ignored) {}
            return err;
        }
        java.util.List<String> allowed = ZCodeGift.allowedModels(summary);
        if (allowed.isEmpty()) return null;

        String useModel = null;
        boolean exact = false;
        for (String m : allowed) {
            if (m.equalsIgnoreCase(upstream)) {
                useModel = m;
                exact = true;
                break;
            }
        }
        // 型号不在赠送范围时不冒充：让调用方走 Plan 路径，如实报 1113 更诚实
        if (!exact) return null;

        long start = System.currentTimeMillis();
        JSONObject ret = new JSONObject();
        try {
            JSONArray msgs = new JSONArray();
            JSONObject m0 = new JSONObject();
            m0.put("role", "user");
            m0.put("content", "1+1=?");
            msgs.put(m0);

            JSONObject body = new JSONObject();
            body.put("model", useModel);
            // 思维链模型 16 个 token 全烧在 thinking 上，正文为空会误判成失败
            body.put("max_tokens", 512);
            body.put("messages", msgs);

            ZCodeGift.Answer ans = ZCodeGift.send(jwt, device, site, useModel, body, null, 60000);
            long latency = System.currentTimeMillis() - start;

            ret.put("model", testModel);
            ret.put("latencyMs", latency);
            ret.put("statusCode", ans.status);
            ret.put("billing", "gift");
            ret.put("billingName", "ZCode 赠送额度");
            ret.put("giftRemaining", summary.optLong("remainingUnits", 0));

            if (ans.status >= 200 && ans.status < 400) {
                JSONObject msg = null;
                try {
                    msg = new JSONObject(ans.body);
                } catch (Exception ignored) {
                }
                String reply = ZCodeGift.textOf(msg);
                if (reply.trim().isEmpty()) reply = ZCodeGift.thinkingOf(msg);
                reply = reply.trim();
                if (reply.isEmpty()) reply = "握手成功";
                if (reply.length() > 80) reply = reply.substring(0, 80) + "...";
                ret.put("ok", true);
                ret.put("reply", reply);
            } else {
                ret.put("ok", false);
                String err;
                if (ans.refused) {
                    err = ans.edge
                            ? "ZCode 边缘防火墙拦了这个网络的出口 IP，换个网络再试"
                            : "ZCode 风控把这个账号判成异常活动（code 3012），只有 ZCode 客服能解除";
                } else {
                    err = "HTTP " + ans.status + ": " + (ans.body.length() > 200
                            ? ans.body.substring(0, 200) : ans.body);
                }
                ret.put("error", err);
            }
            return ret;
        } catch (Exception e) {
            ret.put("model", testModel);
            ret.put("latencyMs", System.currentTimeMillis() - start);
            ret.put("billing", "gift");
            ret.put("ok", false);
            ret.put("error", "连接异常: " + (e.getMessage() == null ? "未知" : e.getMessage()));
            return ret;
        }
    }


    // ===== 妙手 CatPaw 转发：三步会话机，累积帧差分后按 OpenAI 形态回包 =====
    /** 把上游响应流读成字符串（MiniMax 非流式回包 / 错误体用） */
    private static String drainText(InputStream is) throws Exception {
        if (is == null) return "";
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) bo.write(buf, 0, n);
        is.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    /**
     * MiniMax Code 转发。
     *
     * 上游是 Anthropic Messages（{base}/messages），而进入管线的请求体已经被统一成
     * OpenAI 形态（/v1/messages 的客户端在入口处就转过了），所以这里做双向转换：
     *   请求  OpenAI → Anthropic（ZCodeAnthropic.toAnthropic）
     *   响应  Anthropic → 客户端要的形态（Anthropic 直接透传 / OpenAI 走 StreamState 或 toOpenAI）
     *
     * 401 一次重试：token 可能被上游提前作废，MiniMax 的 refresh 是一次性的，
     * 刷新成功必须立刻 saveConfig，否则下次重启就用旧 refresh 把自己登出。
     */
    private Attempt forwardMiniMax(RouteTarget target, JSONObject payload, boolean clientWantsStream,
                                   String requestedModel, PrivacyMasker.Session maskSession,
                                   ContextGuard.Result guardResult, ContextGuard.Config guardCfg) {
        Attempt out = new Attempt();
        out.providerId = target.providerId;
        out.modelRef = target.providerId + "/" + target.upstreamModel;
        HttpURLConnection conn = null;
        boolean handedOff = false;
        try {
            boolean genericAnthropic = target.isAnthropic && !"minimax".equals(target.protocol)
                    && !"minimax-code".equals(target.providerId);
            String token = target.apiKey == null ? "" : target.apiKey;
            if (token.trim().isEmpty()) {
                out.ok = false;
                out.status = 401;
                out.body = "MiniMax Code 登录态缺失：请重新登录";
                out.response = errorResponse(401, out.body);
                return out;
            }

            JSONObject anth = Boolean.TRUE.equals(NATIVE_DIRECT_ALLOWED.get())
                    ? nativeAnthropicRequest(NATIVE_REQUEST.get(), NATIVE_BASELINE.get(), payload,
                            target.upstreamModel, clientWantsStream) : null;
            if (anth == null) {
                anth = ZCodeAnthropic.toAnthropic(new JSONObject(payload.toString()));
                anth.put("model", target.upstreamModel);
                anth.put("stream", clientWantsStream);
                anth = restoreNativeToolResults(NATIVE_REQUEST.get(), NATIVE_BASELINE.get(), payload, anth, guardCfg);
            }

            String base = target.baseUrl == null || target.baseUrl.isEmpty()
                    ? (genericAnthropic ? "https://api.anthropic.com/v1" : MiniMaxAuth.API) : target.baseUrl;
            String url = base.replaceAll("/+$", "") + "/messages";
            String sessionId = U_SESSION.get();

            int status = 0;
            String errBody = "";
            for (int attempt = 0; attempt < 2; attempt++) {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(15 * 60 * 1000);
                conn.setRequestProperty("Content-Type", "application/json");
                if (genericAnthropic) {
                    conn.setRequestProperty("x-api-key", token);
                    conn.setRequestProperty("anthropic-version", "2023-06-01");
                } else {
                    java.util.Map<String, String> h = MiniMaxAuth.chatHeaders(sessionId);
                    for (java.util.Map.Entry<String, String> e : h.entrySet()) {
                        conn.setRequestProperty(e.getKey(), e.getValue());
                    }
                    conn.setRequestProperty("Authorization", "Bearer " + token);
                }
                OutputStream os = conn.getOutputStream();
                os.write(anth.toString().getBytes("UTF-8"));
                os.flush();
                os.close();

                status = conn.getResponseCode();
                if (!genericAnthropic && status == 401 && attempt == 0 && target.account != null
                        && MiniMaxAuth.forceRefresh(target.account)) {
                    token = target.account.optString("access", target.account.optString("token", ""));
                    try {
                        saveConfig(configJson);
                    } catch (Throwable ignored) {
                    }
                    try {
                        conn.disconnect();
                    } catch (Throwable ignored) {
                    }
                    conn = null;
                    continue;
                }
                break;
            }

            if (status < 200 || status >= 400) {
                try {
                    errBody = drainText(conn.getErrorStream());
                } catch (Throwable ignored) {
                }
                out.ok = false;
                out.status = status;
                out.body = errBody;
                out.response = errorResponse(status <= 0 ? 502 : status,
                        (genericAnthropic ? "Anthropic: " : "MiniMax Code: ") + (errBody.isEmpty() ? "上游没有返回响应体" : errBody));
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
                return out;
            }

            if (clientWantsStream) {
                InputStream is = conn.getInputStream();
                InputStream openAi = new AnthropicSseToOpenAI(is, new ZCodeAnthropic.StreamState(requestedModel));
                Response resp = wantsAnthropicOut()
                        ? streamForwardAnthropicResponse(openAi, requestedModel, maskSession, guardResult, conn)
                        : streamForwardResponse(openAi, maskSession, guardResult, conn, target.providerId);
                resp.addHeader("Cache-Control", "no-cache");
                resp.addHeader("X-Magpie-Served-By", target.providerId);
                addCorsHeaders(resp);
                applyGuardHeaders(resp, guardResult);
                out.ok = true;
                out.response = resp;
                handedOff = true;   // 流交给 NanoHTTPD 延迟读，连接不能在这里关
                return out;
            }

            String text = drainText(conn.getInputStream());
            if (maskSession != null) text = PrivacyMasker.unmaskText(text, maskSession);
            JSONObject msg = new JSONObject(text);
            StringBuilder sb = new StringBuilder();
            JSONArray blocks = msg.optJSONArray("content");
            for (int i = 0; blocks != null && i < blocks.length(); i++) {
                JSONObject b = blocks.optJSONObject(i);
                if (b != null && "text".equals(b.optString("type"))) sb.append(b.optString("text", ""));
            }
            out.textOut = sb.toString();
            out.ok = true;

            if (wantsAnthropicOut()) {
                Response resp = newFixedLengthResponse(Status.OK, "application/json; charset=UTF-8", text);
                resp.addHeader("X-Magpie-Served-By", target.providerId);
                addCorsHeaders(resp);
                applyGuardHeaders(resp, guardResult);
                out.response = resp;
                return out;
            }
            JSONObject openai = ZCodeAnthropic.toOpenAI(msg, requestedModel);
            Response resp = newFixedLengthResponse(Status.OK, "application/json; charset=UTF-8", openai.toString());
            resp.addHeader("X-Magpie-Served-By", target.providerId);
            addCorsHeaders(resp);
            applyGuardHeaders(resp, guardResult);
            out.response = resp;
            return out;
        } catch (Throwable e) {
            e.printStackTrace();
            out.ok = false;
            out.status = 0;
            out.body = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
            out.response = errorResponse(502, "Anthropic 上游转发失败: " + out.body);
            return out;
        } finally {
            if (conn != null && !handedOff) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private Attempt forwardCatPaw(RouteTarget target, JSONObject payload, boolean clientWantsStream,
                                  String requestedModel, PrivacyMasker.Session maskSession,
                                  ContextGuard.Result guardResult) {
        Attempt out = new Attempt();
        out.providerId = target.providerId;
        out.modelRef = target.providerId + "/" + target.upstreamModel;

        try {
            String token = target.apiKey;
            if (token == null || token.trim().isEmpty()) {
                out.ok = false;
                out.status = 401;
                out.body = "妙手登录态缺失：请重新登录妙手";
                out.response = errorResponse(401, out.body);
                return out;
            }

            org.json.JSONArray msgs = payload.optJSONArray("messages");
            if (msgs == null) msgs = new org.json.JSONArray();
            org.json.JSONArray tools = payload.optJSONArray("tools");

            // 会话协议只收扁平文本：工具/图片必须明确拒绝，否则会静默丢内容
            String unsupported = CatPawAuth.unsupportedReason(msgs, tools);
            if (unsupported != null) {
                out.ok = false;
                out.status = 400;
                out.body = unsupported;
                out.response = errorResponse(400, unsupported);
                return out;
            }

            JSONObject res = CatPawAuth.chat(token, target.upstreamModel, msgs);
            String finalText = PrivacyMasker.unmaskText(res.optString("text", ""), maskSession);
            String finalReason = res.optString("reasoning", "");
            out.ok = true;
            out.textOut = finalText;
            out.reasoningOut = finalReason;
            if (wantsAnthropicOut()) {
                out.response = anthropicFromText(finalText, finalReason, requestedModel,
                        clientWantsStream, guardResult);
                return out;
            }

            long created = System.currentTimeMillis() / 1000L;
            String id = "chatcmpl-" + System.currentTimeMillis();

            if (clientWantsStream) {
                StringBuilder sse = new StringBuilder();
                JSONObject head = new JSONObject();
                head.put("id", id);
                head.put("object", "chat.completion.chunk");
                head.put("created", created);
                head.put("model", requestedModel);
                org.json.JSONArray c0 = new org.json.JSONArray();
                JSONObject ch0 = new JSONObject();
                ch0.put("index", 0);
                JSONObject d0 = new JSONObject();
                d0.put("role", "assistant");
                ch0.put("delta", d0);
                ch0.put("finish_reason", JSONObject.NULL);
                c0.put(ch0);
                head.put("choices", c0);
                sse.append("data: ").append(head.toString()).append("\n\n");

                JSONObject chunk = new JSONObject();
                chunk.put("id", id);
                chunk.put("object", "chat.completion.chunk");
                chunk.put("created", created);
                chunk.put("model", requestedModel);
                org.json.JSONArray choices = new org.json.JSONArray();
                JSONObject ch = new JSONObject();
                ch.put("index", 0);
                JSONObject delta = new JSONObject();
                if (finalReason.length() > 0) delta.put("reasoning_content", finalReason);
                delta.put("content", finalText);
                ch.put("delta", delta);
                ch.put("finish_reason", JSONObject.NULL);
                choices.put(ch);
                chunk.put("choices", choices);
                sse.append("data: ").append(chunk.toString()).append("\n\n");

                JSONObject done = new JSONObject();
                done.put("id", id);
                done.put("object", "chat.completion.chunk");
                done.put("created", created);
                done.put("model", requestedModel);
                org.json.JSONArray c2 = new org.json.JSONArray();
                JSONObject ch2 = new JSONObject();
                ch2.put("index", 0);
                ch2.put("delta", new JSONObject());
                ch2.put("finish_reason", "stop");
                c2.put(ch2);
                done.put("choices", c2);
                sse.append("data: ").append(done.toString()).append("\n\n");
                sse.append("data: [DONE]\n\n");

                Response resp = newFixedLengthResponse(Status.OK, "text/event-stream", sse.toString());
                resp.addHeader("Cache-Control", "no-cache");
                addCorsHeaders(resp);
                applyGuardHeaders(resp, guardResult);
                out.response = resp;
                return out;
            }

            JSONObject respJson = new JSONObject();
            respJson.put("id", id);
            respJson.put("object", "chat.completion");
            respJson.put("created", created);
            respJson.put("model", requestedModel);
            org.json.JSONArray choices = new org.json.JSONArray();
            JSONObject ch = new JSONObject();
            ch.put("index", 0);
            JSONObject msg = new JSONObject();
            msg.put("role", "assistant");
            msg.put("content", finalText);
            if (finalReason.length() > 0) msg.put("reasoning_content", finalReason);
            ch.put("message", msg);
            ch.put("finish_reason", "stop");
            choices.put(ch);
            respJson.put("choices", choices);
            // 会话协议不返回 token 计数，这里不给 usage，而不是编一串 0

            Response resp = newFixedLengthResponse(Status.OK, "application/json; charset=UTF-8", respJson.toString());
            addCorsHeaders(resp);
            applyGuardHeaders(resp, guardResult);
            out.response = resp;
            return out;
        } catch (Throwable e) {
            e.printStackTrace();
            out.ok = false;
            String m = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
            out.body = m;
            out.status = m.contains("401") || m.contains("403") ? 401
                    : m.contains("429") ? 429
                    : m.contains("HTTP 5") ? 502 : 400;
            out.response = errorResponse(out.status >= 500 ? 502 : out.status, "妙手: " + m);
            return out;
        }
    }

    /** 单次转发的结果：成功就直接是响应；失败带上状态码与错误体供 retryable 判定 */
    private static class Attempt {
        boolean ok;
        Response response;
        int status;
        String body;
        String providerId;
        String modelRef;
        /** 成功时把回复正文留一份，供 /api/test-model 显示（免得再解析一遍 Response 流） */
        String textOut;
        String reasoningOut;
    }

    /** 从 OpenAI 形态的回包里抠出正文，探测测试用 */
    private static String extractReplyText(JSONObject openai) {
        try {
            JSONArray ch = openai.optJSONArray("choices");
            if (ch == null || ch.length() == 0) return "";
            JSONObject m = ch.optJSONObject(0).optJSONObject("message");
            if (m == null) {
                JSONObject d = ch.optJSONObject(0).optJSONObject("delta");
                if (d != null) return d.optString("content", "");
                return "";
            }
            String c = m.optString("content", "");
            if (!c.trim().isEmpty()) return c;
            return m.optString("reasoning_content", "");
        } catch (Exception e) {
            return "";
        }
    }

    /** 把 "provider/model" 解析成一个可发包的目标；解析不出返回 null */
    private RouteTarget buildTarget(String targetModel) {
        String provId = null;
        String mName = targetModel;
        if (targetModel.contains("/")) {
            int slash = targetModel.indexOf('/');
            provId = targetModel.substring(0, slash);
            mName = targetModel.substring(slash + 1);
        }

        JSONArray providers = configJson.optJSONArray("providers");
        if (providers == null) return null;

        JSONObject chosenProvider = null;
        if (provId != null) {
            for (int i = 0; i < providers.length(); i++) {
                JSONObject p = providers.optJSONObject(i);
                if (p != null && provId.equals(p.optString("id"))) {
                    chosenProvider = p;
                    break;
                }
            }
        }

        if (chosenProvider == null) {
            for (int i = 0; i < providers.length(); i++) {
                JSONObject p = providers.optJSONObject(i);
                if (p == null) continue;
                JSONArray ms = p.optJSONArray("models");
                if (ms != null) {
                    for (int mi = 0; mi < ms.length(); mi++) {
                        if (targetModel.equals(ms.optString(mi))) {
                            chosenProvider = p;
                            mName = targetModel;
                            break;
                        }
                    }
                }
                if (chosenProvider != null) break;
            }
        }

        // 兜底只给「不带 provider/ 前缀」的老式裸模型名（例如 "gpt-4o"）用。
        // 带前缀却找不到供应商，说明客户端把模型名写错了，必须报错；
        // 原来会随便塞给第一家「有 keys 的供应商」，结果用别人的 key 打不相干的域名，还拿不到错误。
        if (chosenProvider == null && targetModel.indexOf('/') < 0) {
            for (int i = 0; i < providers.length(); i++) {
                JSONObject p = providers.optJSONObject(i);
                if (p != null) {
                    JSONArray keys = p.optJSONArray("keys");
                    if (keys != null && keys.length() > 0) {
                        chosenProvider = p;
                        break;
                    }
                }
            }
        }

        if (chosenProvider == null) return null;

        String selectedKey = "";
        JSONArray keys = chosenProvider.optJSONArray("keys");
        if (keys != null && keys.length() > 0) {
            int idx = Math.abs(wbRoundRobin.getAndIncrement() % keys.length());
            selectedKey = keys.optString(idx);
        }

        RouteTarget route = new RouteTarget();
        route.providerId = chosenProvider.optString("id");
        route.baseUrl = chosenProvider.optString("baseUrl", "https://api.deepseek.com/v1");
        route.apiKey = selectedKey;
        route.upstreamModel = mName;
        route.isAnthropic = "anthropic".equalsIgnoreCase(chosenProvider.optString("protocol"));
        route.protocol = chosenProvider.optString("protocol", route.isAnthropic ? "anthropic" : "openai");
        if ("deepseek-web".equals(route.protocol)) {
            if (!java.util.Arrays.asList(DeepSeekWeb.MODELS).contains(mName)) return null;
            JSONArray accounts=chosenProvider.optJSONArray("accounts"); ArrayList<JSONObject> valid=new ArrayList<JSONObject>();
            for (int i=0;accounts!=null && i<accounts.length();i++) { JSONObject a=accounts.optJSONObject(i); if (a!=null && a.optBoolean("enabled",true) && !deepAccountToken(a).isEmpty()) valid.add(a); }
            if (valid.isEmpty()) return null;
            route.account=valid.get((wbRoundRobin.getAndIncrement() & 0x7fffffff)%valid.size());
            route.apiKey=deepAccountToken(route.account); return route;
        }
        if ("cline".equals(route.providerId) || "cline".equals(route.protocol)) {
            JSONArray accs = chosenProvider.optJSONArray("accounts");
            if (selectedKey.isEmpty() && accs != null && accs.length() > 0) {
                int idx = Math.abs(wbRoundRobin.getAndIncrement() % accs.length());
                JSONObject a = accs.optJSONObject(idx);
                if (a != null) {
                    route.account = a;
                    route.apiKey = a.optString("token", "");
                }
            } else if (accs != null && accs.length() > 0) {
                for (int i = 0; i < accs.length(); i++) {
                    JSONObject a = accs.optJSONObject(i);
                    if (a != null && selectedKey.equals(a.optString("token"))) {
                        route.account = a;
                        break;
                    }
                }
            }
            if (route.account != null && !"api".equals(route.account.optString("type", "oauth"))) {
                long expires = route.account.optLong("expires", 0L);
                if (expires > 0 && System.currentTimeMillis() > expires - 5 * 60 * 1000) {
                    if (ClineAuth.refreshAccount(route.account)) {
                        saveConfig(configJson);
                        route.apiKey = route.account.optString("token", route.apiKey);
                    }
                }
            }
            if (selectedKey.isEmpty() && route.account == null) return null;
        }

        if ("qoder".equals(route.protocol) || "catpaw".equals(route.protocol)
                || "antigravity".equals(route.protocol)) {
            // Qoder 的凭据在 accounts 里（token + uid + machineId），普通 key 不够
            JSONArray accs = chosenProvider.optJSONArray("accounts");
            for (int i = 0; accs != null && i < accs.length(); i++) {
                JSONObject a = accs.optJSONObject(i);
                if (a != null && !a.optString("token", "").trim().isEmpty()) {
                    route.account = a;
                    route.apiKey = a.optString("token", "");
                    break;
                }
            }
            if (route.account == null) return null;
        }

        // GitHub Copilot / MiniMax Code：凭据在 accounts 里，短期 token 会滚动，取用时就地续期
        if ("copilot".equals(route.protocol) || "minimax".equals(route.protocol)) {
            JSONArray accs = chosenProvider.optJSONArray("accounts");
            JSONObject acc = null;
            for (int i = 0; accs != null && i < accs.length(); i++) {
                JSONObject a = accs.optJSONObject(i);
                if (a == null) continue;
                if (a.optString("refresh", "").trim().isEmpty()
                        && a.optString("token", "").trim().isEmpty()) continue;
                acc = a;
                break;
            }
            if (acc == null) return null;
            String before = acc.optString("access", acc.optString("token", ""));
            String now;
            if ("copilot".equals(route.protocol)) {
                now = CopilotAuth.ensureCopilotToken(acc);
            } else {
                MiniMaxAuth.ensureToken(acc);
                now = acc.optString("access", acc.optString("token", ""));
            }
            if (now == null || now.trim().isEmpty()) return null;
            // Copilot token 30 分钟一换、MiniMax refresh 一次性，续过就必须落盘
            if (!now.equals(before)) saveConfig(configJson);
            route.account = acc;
            route.apiKey = now;
        }
        return route;
    }

    private RouteTarget resolveTarget(String model) {
        return pickCandidate(model, resolveCandidates(model));
    }

    /**
     * 解析出这次请求的全部候选，按 order 顺序排列。
     * 单模型只有一个候选；路由组是它的所有活目标（对齐 magpie 的 order 语义：
     * 用第一个直到它答不出来，再换下一个）。
     * 正在"休息"的候选排到最后而不是删掉，全组休息时仍可兜底。
     */
    // ===== 路由组：策略排序 =====
    //
    // 参考 yetone/magpie 的 group 设计，但按手机版取舍：
    //   order   —— 按用户排的顺序，第一个用到调不动再换下一个（默认）
    //   rotate  —— 每个请求轮到下一个目标（多账号摊平）
    //   manual  —— 只走用户选定的那一个（组卡片上一点就切）
    //
    // 不实现 smart / usage / pace / fast / cheap / free / weight / drain：
    // 它们要么依赖后台轮询额度窗口、要么依赖价格目录、要么依赖流式首 token 计时表，
    // 在手机上都是「一直有后台任务 + 常驻内存」，收益却不明显。
    // 而且 weight / drain / rotate 的计数在 magpie 里本身就是内存态、重启即忘，
    // 手机进程随时可能被杀，承诺这种跨重启语义不诚实。

    /** rotate 策略的轮转计数（group id -> 次数），内存态，重启归零 */
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> groupTurns =
            new java.util.concurrent.ConcurrentHashMap<String, Integer>();

    private static boolean isKnownStrategy(String s) {
        return "order".equals(s) || "rotate".equals(s) || "manual".equals(s);
    }

    /**
     * 按组策略把 target 列表排成这次请求的候选顺序。
     * 策略只决定「顺序」，失败回退（rest）仍由调用方统一处理 —— 与 magpie 的分层一致。
     */
    private List<String> orderByStrategy(JSONObject group, List<String> refs) {
        String strategy = group == null ? "order" : group.optString("strategy", "order");
        if (!isKnownStrategy(strategy)) strategy = "order";
        if (refs.size() <= 1) return refs;

        if ("rotate".equals(strategy)) {
            String gid = group.optString("id", "");
            int turn;
            try {
                turn = groupTurns.getOrDefault(gid, Integer.valueOf(0)).intValue();
            } catch (Exception e) {
                turn = 0;
            }
            groupTurns.put(gid, Integer.valueOf(turn + 1));
            int n = refs.size();
            int start = ((turn % n) + n) % n;
            List<String> out = new ArrayList<String>();
            for (int i = 0; i < n; i++) out.add(refs.get((start + i) % n));
            return out;
        }

        if ("manual".equals(strategy)) {
            String pick = group.optString("pick", "");
            if (!pick.isEmpty() && refs.contains(pick)) {
                List<String> out = new ArrayList<String>();
                out.add(pick);
                for (String r : refs) if (!r.equals(pick)) out.add(r);
                return out;
            }
            // pick 失效（模型被删/账号掉了）时退回顺序，别让整组不可用
            return refs;
        }

        return refs;   // order
    }

    private List<RouteTarget> resolveCandidates(String model) {
        List<String> refs = new ArrayList<String>();
        if (model != null && model.startsWith("group/") && !groupsEnabled()) {
            // 组是「按需开启」的：关着的时候不解析，交给调用方回 403 并提示直接用 provider/model
            return new ArrayList<RouteTarget>();
        }
        if (model != null && model.startsWith("group/")) {
            String grpId = model.substring(6);
            JSONArray groups = configJson.optJSONArray("groups");
            if (groups != null) {
                for (int i = 0; i < groups.length(); i++) {
                    JSONObject g = groups.optJSONObject(i);
                    if (g == null || !grpId.equals(g.optString("id"))) continue;
                    JSONArray targets = g.optJSONArray("targets");
                    if (targets != null) {
                        for (int j = 0; j < targets.length(); j++) {
                            String t = targets.optString(j, "");
                            if (!t.isEmpty() && isModelRefLive(t)) refs.add(t);
                        }
                    }
                    // 全都不活时保留第一个，让报错指向用户预期的模型
                    if (refs.isEmpty() && targets != null && targets.length() > 0) {
                        refs.add(targets.optString(0, ""));
                    } else {
                        // 活着才按策略排序；全死的情况下保持原样，报错信息才指向用户预期的那个
                        refs = orderByStrategy(g, refs);
                    }
                    break;
                }
            }
        } else if (model != null && !model.isEmpty()) {
            refs.add(model);
        }

        List<RouteTarget> awake = new ArrayList<RouteTarget>();
        List<RouteTarget> resting = new ArrayList<RouteTarget>();
        for (int i = 0; i < refs.size(); i++) {
            RouteTarget t = buildTarget(refs.get(i));
            if (t == null) continue;
            if (isResting(t)) resting.add(t);
            else awake.add(t);
        }
        awake.addAll(resting);          // 休息的排后面，但仍作为兜底
        return awake;
    }

    private RouteTarget pickCandidate(String model, List<RouteTarget> list) {
        if (list != null && !list.isEmpty()) return list.get(0);
        return null;
    }

    private Response jsonResponse(JSONObject obj) {
        Response resp = newFixedLengthResponse(Status.OK, "application/json; charset=UTF-8", obj.toString());
        addCorsHeaders(resp);
        return resp;
    }

    private Response errorResponse(int code, String msg) {
        JSONObject err = new JSONObject();
        try {
            JSONObject detail = new JSONObject();
            detail.put("message", msg);
            detail.put("type", "gateway_error");
            err.put("error", detail);
        } catch (Exception ignored) {}
        Response.IStatus status = Status.lookup(code);
        if (status == null && code == 502) status = new Response.IStatus() {
            public int getRequestStatus() { return 502; }
            public String getDescription() { return "502 Bad Gateway"; }
        };
        if (status == null) status = Status.INTERNAL_ERROR;
        Response resp = newFixedLengthResponse(status, "application/json; charset=UTF-8", err.toString());
        addCorsHeaders(resp);
        return resp;
    }

    private void addCorsHeaders(Response resp) {
        String origin = CURRENT_ORIGIN.get();
        if (origin == null || origin.trim().isEmpty() || "null".equalsIgnoreCase(origin.trim())) {
            resp.addHeader("Access-Control-Allow-Origin", "*");
        } else {
            resp.addHeader("Access-Control-Allow-Origin", origin.trim());
        }
        resp.addHeader("Vary", "Origin");
        resp.addHeader("Access-Control-Allow-Headers", "*, Authorization, Content-Type, x-api-key, anthropic-version, Accept, Origin, Cache-Control, X-Requested-With, Access-Control-Request-Headers, Access-Control-Request-Method");
        resp.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, HEAD");
        resp.addHeader("Access-Control-Allow-Private-Network", "true");
        resp.addHeader("Access-Control-Max-Age", "86400");
    }

    private JSONObject getProviderById(String providerId) {
        JSONArray providers = configJson.optJSONArray("providers");
        if (providers == null) return null;
        for (int i = 0; i < providers.length(); i++) {
            JSONObject p = providers.optJSONObject(i);
            if (p != null && providerId.equals(p.optString("id"))) {
                return p;
            }
        }
        return null;
    }

    private boolean isStandardProvider(String providerId) {
        return "deepseek".equals(providerId) || "siliconflow".equals(providerId)
            || "openai".equals(providerId) || "openrouter".equals(providerId)
            || "tencent".equals(providerId) || "anthropic".equals(providerId);
    }

    /** ZCode（GLM Coding）的 OpenAI 兼容 /models，直接列出该 key 可用的模型 */
    private List<String> fetchZCodeModels(String baseUrl, String token) {
        List<String> list = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            String url = (baseUrl == null ? "" : baseUrl).replaceAll("/+$", "") + "/models";
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            JSONObject root = new JSONObject(sb.toString());
            JSONArray data = root.optJSONArray("data");
            if (data != null) {
                for (int i = 0; i < data.length(); i++) {
                    JSONObject m = data.optJSONObject(i);
                    if (m == null) continue;
                    String id = m.optString("id", "");
                    if (!id.isEmpty() && !list.contains(id)) list.add(id);
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
        return list;
    }

    private List<String> fetchRaccoonModels(String token, JSONObject metaOut) {
        List<String> list = new ArrayList<>();
        try {
            String urlStr = "https://xiaohuanxiong.com/api/web/llm/v2/model_catalog";
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Accept-Language", "zh-Hans");

            if (conn.getResponseCode() >= 200 && conn.getResponseCode() < 400) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                JSONObject res = new JSONObject(sb.toString());
                JSONObject data = res.optJSONObject("data");
                if (data != null) {
                    JSONArray categories = data.optJSONArray("categories");
                    if (categories != null) {
                        for (int i = 0; i < categories.length(); i++) {
                            JSONObject cat = categories.optJSONObject(i);
                            if (cat == null) continue;
                            JSONArray models = cat.optJSONArray("models");
                            if (models == null) continue;
                            for (int j = 0; j < models.length(); j++) {
                                JSONObject m = models.optJSONObject(j);
                                if (m == null) continue;
                                String name = m.optString("name", "");
                                if (name.isEmpty()) continue;
                                if (!list.contains(name)) list.add(name);

                                // 官方计费倍率：billing_effective_multiplier 优先
                                double mult = m.optDouble("billing_effective_multiplier", m.optDouble("billing_multiplier", -1));
                                String status = m.optString("billing_status", "normal");
                                String category = m.optString("billing_category", "");
                                JSONObject info = new JSONObject();
                                if (mult >= 0) {
                                    info.put("multiplier", mult);
                                    boolean isFree = (mult == 0)
                                            || "free".equalsIgnoreCase(status)
                                            || "free".equalsIgnoreCase(category);
                                    info.put("free", isFree);
                                }
                                String desc = m.optString("display_description", m.optString("description", ""));
                                if (!desc.isEmpty()) info.put("desc", desc);
                                JSONObject params = m.optJSONObject("params");
                                if (params != null) {
                                    int ctx = params.optInt("context_window", 0);
                                    int maxTok = params.optInt("max_tokens", 0);
                                    if (ctx > 0) info.put("contextWindow", ctx);
                                    if (maxTok > 0) info.put("maxTokens", maxTok);
                                }
                                if (info.length() > 0) metaOut.put(name, info);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (!list.contains("auto")) list.add(0, "auto");
        if (!list.contains("raccoon-code")) list.add("raccoon-code");
        if (!list.contains("raccoon-chat-ml-5-5")) list.add("raccoon-chat-ml-5-5");
        return list;
    }

    private List<String> fetchStandardModels(String providerId, String baseUrl, String token, JSONObject metaOut) {
        List<String> list = new ArrayList<>();
        try {
            String base = baseUrl == null ? "" : baseUrl.trim();
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            if (base.endsWith("/v1")) base = base.substring(0, base.length() - 3);

            String urlStr = base + "/v1/models";
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "MagpiePocket/1.0");
            if ("anthropic".equals(providerId)) {
                conn.setRequestProperty("x-api-key", token);
                conn.setRequestProperty("anthropic-version", "2023-06-01");
            } else {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }

            if (conn.getResponseCode() >= 200 && conn.getResponseCode() < 400) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                JSONObject res = new JSONObject(sb.toString());
                JSONArray data = res.optJSONArray("data");
                if (data == null) data = res.optJSONArray("models");
                if (data != null) {
                    for (int i = 0; i < data.length(); i++) {
                        Object o = data.opt(i);
                        if (o instanceof JSONObject) {
                            JSONObject m = (JSONObject) o;
                            String id = m.optString("id", "");
                            if (id.isEmpty()) id = m.optString("name", "");
                            if (!id.isEmpty() && !list.contains(id)) list.add(id);
                        } else if (o != null) {
                            String id = String.valueOf(o);
                            if (!id.isEmpty() && !list.contains(id)) list.add(id);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    private JSONObject getWorkBuddyProvider() {
        JSONArray providers = configJson.optJSONArray("providers");
        if (providers == null) return null;
        for (int i = 0; i < providers.length(); i++) {
            JSONObject p = providers.optJSONObject(i);
            if (p != null && "workbuddy".equals(p.optString("id"))) {
                return p;
            }
        }
        return null;
    }

    private List<String> fetchOfficialModels(String token, JSONObject metaOut) {
        List<String> list = new ArrayList<>();
        try {
            String urlStr = "https://copilot.tencent.com/v3/config";
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("User-Agent", "CLI/2.63.2 CodeBuddy/2.63.2");
            conn.setRequestProperty("X-Agent-Type", "main");
            conn.setRequestProperty("X-Auth-Refresh-Source", "workbuddy");
            conn.setRequestProperty("Accept", "application/json");

            if (conn.getResponseCode() >= 200 && conn.getResponseCode() < 400) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                JSONObject res = new JSONObject(sb.toString());
                JSONObject data = res.optJSONObject("data");
                if (data != null) {
                    // 先建立 id -> credits 映射（腾讯官方倍率：x2.00 credits 等）
                    java.util.Map<String, Double> creditMap = new java.util.HashMap<>();
                    java.util.Map<String, String> rawCreditMap = new java.util.HashMap<>();
                    JSONArray allModels = data.optJSONArray("models");
                    if (allModels != null) {
                        for (int i = 0; i < allModels.length(); i++) {
                            JSONObject mo = allModels.optJSONObject(i);
                            if (mo == null) continue;
                            String mid = mo.optString("id", "");
                            if (mid.isEmpty()) continue;
                            String credits = mo.optString("credits", "");
                            if (!credits.isEmpty() && !"null".equals(credits)) {
                                rawCreditMap.put(mid, credits);
                                java.util.regex.Matcher mc = java.util.regex.Pattern
                                        .compile("([0-9]+(?:\\.[0-9]+)?)").matcher(credits);
                                if (mc.find()) {
                                    try {
                                        creditMap.put(mid, Double.parseDouble(mc.group(1)));
                                    } catch (Exception ignored) {}
                                }
                            }
                        }
                    }

                    JSONArray agents = data.optJSONArray("agents");
                    if (agents != null) {
                        for (int i = 0; i < agents.length(); i++) {
                            JSONObject a = agents.optJSONObject(i);
                            if (a != null && "cli".equals(a.optString("name"))) {
                                JSONArray models = a.optJSONArray("models");
                                if (models != null) {
                                    for (int j = 0; j < models.length(); j++) {
                                        String m = models.getString(j);
                                        if (!list.contains(m)) list.add(m);

                                        JSONObject info = new JSONObject();
                                        if (creditMap.containsKey(m)) {
                                            double mult = creditMap.get(m);
                                            info.put("multiplier", mult);
                                            info.put("free", mult == 0);
                                            if (rawCreditMap.containsKey(m)) {
                                                info.put("credits", rawCreditMap.get(m));
                                            }
                                        }
                                        if (info.length() > 0) metaOut.put(m, info);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 默认补充 auto 智能路由
        if (!list.contains("auto")) {
            list.add(0, "auto");
        }
        return list;
    }




    /**
     * 判断池内是否已经存在同一个账号。
     * 判定标准：凭据 token 完全相同，或账号名相同（忽略大小写且非空）。
     * 返回命中的已有账号对象，没有则返回 null。
     */
    private JSONObject findDuplicateAccount(JSONObject prov, String name, String token) {
        if (prov == null) return null;
        JSONArray accounts = prov.optJSONArray("accounts");
        if (accounts == null) return null;

        String t = token == null ? "" : token.trim();
        String n = name == null ? "" : name.trim();
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject a = accounts.optJSONObject(i);
            if (a == null) continue;
            String at = a.optString("token", "").trim();
            String an = a.optString("name", "").trim();
            boolean sameToken = !t.isEmpty() && t.equals(at);
            boolean sameName = !n.isEmpty() && n.equalsIgnoreCase(an);
            if (sameToken || sameName) return a;
        }
        return null;
    }

    /** 同名同账号时返回 409，并把已有账号信息带回前端，由前端弹窗提示 */
    private Response duplicateAccountResponse(String providerName, JSONObject existing) {
        JSONObject out = new JSONObject();
        try {
            String who = existing.optString("name", "未命名账号");
            String msg = providerName + " 池内已经有同名同账号「" + who + "」了，本次未重复加入。";
            out.put("ok", false);
            out.put("duplicate", true);
            out.put("code", "DUPLICATE_ACCOUNT");
            out.put("providerName", providerName);
            out.put("canForce", true);
            out.put("message", msg);

            JSONObject detail = new JSONObject();
            detail.put("message", msg);
            detail.put("type", "duplicate_account");
            out.put("error", detail);

            JSONObject ex = new JSONObject();
            ex.put("id", existing.optString("id", ""));
            ex.put("name", who);
            ex.put("addedAt", existing.optLong("addedAt", 0L));
            ex.put("site", existing.optString("site", ""));
            out.put("existing", ex);
        } catch (Exception ignored) {
        }
        Response resp = newFixedLengthResponse(Status.CONFLICT, "application/json; charset=UTF-8", out.toString());
        addCorsHeaders(resp);
        return resp;
    }

    private synchronized String addProviderAccount(String providerId, String name, String token) {
        try {
            JSONArray providers = configJson.optJSONArray("providers");
            if (providers == null) {
                providers = new JSONArray();
                configJson.put("providers", providers);
            }

            JSONObject targetProv = null;
            for (int i = 0; i < providers.length(); i++) {
                JSONObject p = providers.optJSONObject(i);
                if (p != null && providerId.equals(p.optString("id"))) {
                    targetProv = p;
                    break;
                }
            }

            if (targetProv == null) {
                targetProv = new JSONObject();
                targetProv.put("id", providerId);
                targetProv.put("name", providerId.toUpperCase() + " (免Key池)");
                targetProv.put("baseUrl", "https://api." + providerId + ".com/v1");
                targetProv.put("protocol", "openai");
                targetProv.put("enabled", true);
                JSONArray models = new JSONArray();
                models.put("default");
                targetProv.put("models", models);
                targetProv.put("keys", new JSONArray());
                targetProv.put("accounts", new JSONArray());
                providers.put(targetProv);
            }

            JSONArray accounts = targetProv.optJSONArray("accounts");
            if (accounts == null) {
                accounts = new JSONArray();
                targetProv.put("accounts", accounts);
            }

            String accId = "acc_" + System.currentTimeMillis();
            JSONObject acc = new JSONObject();
            acc.put("id", accId);
            acc.put("name", name);
            acc.put("token", token);
            acc.put("addedAt", System.currentTimeMillis());
            accounts.put(acc);

            JSONArray keys = targetProv.optJSONArray("keys");
            if (keys == null) {
                keys = new JSONArray();
                targetProv.put("keys", keys);
            }
            keys.put(token);

            saveConfig(configJson);
            return accId;
        } catch (Exception e) {
            e.printStackTrace();
            return "err";
        }
    }

    /**
     * 妙手 CatPaw 账号入池。token 就是 X-Passport-Token 的值，
     * 没有 refresh 接口：过期只能重新走一次网页登录。
     */
    private synchronized String addCatPawAccount(String name, String token, String uid) {
        try {
            JSONArray providers = configJson.optJSONArray("providers");
            if (providers == null) {
                providers = new JSONArray();
                configJson.put("providers", providers);
            }
            JSONObject targetProv = null;
            for (int i = 0; i < providers.length(); i++) {
                JSONObject p = providers.optJSONObject(i);
                if (p != null && "catpaw".equals(p.optString("id"))) {
                    targetProv = p;
                    break;
                }
            }
            if (targetProv == null) {
                targetProv = new JSONObject();
                targetProv.put("id", "catpaw");
                targetProv.put("name", "妙手 CatPaw (美团)");
                targetProv.put("baseUrl", CatPawAuth.ORIGIN);
                targetProv.put("protocol", "catpaw");
                targetProv.put("status", "ported");
                targetProv.put("enabled", true);
                providers.put(targetProv);
            }
            JSONArray accounts = targetProv.optJSONArray("accounts");
            if (accounts == null) {
                accounts = new JSONArray();
                targetProv.put("accounts", accounts);
            }
            String accId = "acc_" + System.currentTimeMillis();
            JSONObject acc = new JSONObject();
            acc.put("id", accId);
            acc.put("name", name);
            acc.put("token", token);
            if (uid != null && !uid.isEmpty()) acc.put("uid", uid);
            acc.put("addedAt", System.currentTimeMillis());
            accounts.put(acc);

            JSONArray keys = targetProv.optJSONArray("keys");
            if (keys == null) {
                keys = new JSONArray();
                targetProv.put("keys", keys);
            }
            boolean has = false;
            for (int i = 0; i < keys.length(); i++) {
                if (token.equals(keys.optString(i))) { has = true; break; }
            }
            if (!has) keys.put(token);

            JSONArray models = targetProv.optJSONArray("models");
            if (models == null || models.length() == 0) targetProv.put("models", CatPawAuth.modelList());
            targetProv.put("modelMeta", CatPawAuth.modelMeta());

            saveConfig(configJson);
            return accId;
        } catch (Exception e) {
            e.printStackTrace();
            return "err";
        }
    }

    /**
     * 供应商能力状态板：把「看着有 21 家，其实只有几家真能调」这件事显式化。
     * status 取值：ported（按 plugins 真移植）/ passthrough（OpenAI 兼容直连）/ placeholder（尚未实装）
     */
    private JSONObject buildProvidersBoard() {
        JSONObject out = new JSONObject();
        try {
            JSONArray list = new JSONArray();
            JSONArray providers = configJson.optJSONArray("providers");
            int ported = 0, native_ = 0, passthrough = 0, placeholder = 0;
            for (int i = 0; providers != null && i < providers.length(); i++) {
                JSONObject p = providers.optJSONObject(i);
                if (p == null) continue;
                String id = p.optString("id", "");
                String status = p.optString("status", "passthrough");
                if ("ported".equals(status)) ported++;
                else if ("native".equals(status)) native_++;
                else if ("placeholder".equals(status)) placeholder++;
                else passthrough++;

                JSONObject o = new JSONObject();
                o.put("id", id);
                o.put("name", p.optString("name", id));
                o.put("status", status);
                o.put("plugin", p.optString("plugin", ""));
                o.put("protocol", p.optString("protocol", "openai"));
                o.put("enabled", p.optBoolean("enabled", true));
                o.put("hasCredential", providerHasCredential(p));
                o.put("callable", providerHasCredential(p) && !"placeholder".equals(status));
                JSONArray accs = p.optJSONArray("accounts");
                o.put("accounts", accs == null ? 0 : accs.length());
                JSONArray keys = p.optJSONArray("keys");
                int kc = 0;
                for (int k = 0; keys != null && k < keys.length(); k++) {
                    if (!keys.optString(k, "").trim().isEmpty()) kc++;
                }
                o.put("keys", kc);
                JSONArray models = p.optJSONArray("models");
                o.put("models", models == null ? 0 : models.length());
                list.put(o);
            }
            out.put("ok", true);
            out.put("counts", new JSONObject()
                    .put("ported", ported)
                    .put("native", native_)
                    .put("passthrough", passthrough)
                    .put("placeholder", placeholder));
            out.put("providers", list);
        } catch (Exception e) {
            try { out.put("ok", false); out.put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) {}
        }
        return out;
    }


    // =========================================================================
    // Codex (ChatGPT 订阅) 原生协议桥接与账号维护实现
    // =========================================================================

    private Response handleCodexHttpCallback(IHTTPSession session) {
        Map<String, List<String>> params = session.getParameters();
        String code = (params.containsKey("code") && !params.get("code").isEmpty()) ? params.get("code").get(0) : "";
        String state = (params.containsKey("state") && !params.get("state").isEmpty()) ? params.get("state").get(0) : "";

        String accName = "Codex-User";
        boolean ok = false;
        String errMsg = "";

        if (!code.isEmpty()) {
            CodexAuth.Flow matchedFlow = null;
            for (CodexAuth.Flow f : codexFlows.values()) {
                if (f.state.equals(state)) {
                    matchedFlow = f;
                    break;
                }
            }
            String verifier = matchedFlow != null ? matchedFlow.verifier : "";
            if (!verifier.isEmpty()) {
                try {
                    CodexAuth.SignInInfo info = CodexAuth.exchangeCode(code, verifier);
                    addCodexAccount(info, info.email);
                    if (matchedFlow != null) {
                        matchedFlow.status = "done";
                        matchedFlow.result = info;
                    }
                    accName = info.email;
                    ok = true;
                } catch (Exception e) {
                    errMsg = e.getMessage();
                }
            } else {
                errMsg = "找不到匹配的授权流程 verifier";
            }
        } else {
            errMsg = "回调参数中缺少 code";
        }

        String html = "<!DOCTYPE html><html><head><meta charset='utf-8'><title>All In - Codex 授权完成</title>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1.0'>"
                + "<style>body{background:#0b0d14;color:#f3f4f6;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;}"
                + ".box{text-align:center;padding:36px 30px;background:#131622;border:1px solid #2e3440;border-radius:20px;box-shadow:0 10px 30px rgba(0,0,0,0.5);max-width:380px;width:90%;}"
                + "h1{color:" + (ok ? "#10a37f" : "#ef4444") + ";font-size:1.5rem;margin-bottom:12px;}"
                + "p{color:#9ca3af;font-size:0.95rem;line-height:1.6;margin-bottom:20px;}"
                + ".btn{display:inline-block;padding:10px 24px;border-radius:20px;background:#10a37f;color:#fff;text-decoration:none;font-weight:600;font-size:0.9rem;}"
                + "</style></head><body><div class='box'><h1>" + (ok ? "⚡ Codex 订阅连接成功！" : "❌ 授权遇到问题") + "</h1>"
                + "<p>" + (ok ? "已成功连接账号 <b>" + accName + "</b>。<br>您可以关闭此窗口，返回控制台开始使用。" : ("授权换取失败: " + errMsg)) + "</p>"
                + "<a class='btn' href='/'>返回 All In 控制台</a>"
                + "</div></body></html>";
        Response resp = newFixedLengthResponse(Status.OK, "text/html; charset=UTF-8", html);
        addCorsHeaders(resp);
        return resp;
    }

    /**
     * GitHub Copilot 的 /responses 直通（仅当请求里的模型属于 github-copilot）。
     *
     * 为什么要它：Copilot 有几款模型（实测 gpt-5.4-nano）在 /chat/completions 上会回
     * `unsupported_api_for_model`，只有 /responses 能用。这里不做格式转换——客户端本来就说
     * Responses 协议，原样转发请求体、原样回传 SSE/JSON，只把 model 换成上游名并补 Copilot 头。
     *
     * 返回 null 表示「这个请求不归 Copilot 管」，交回原来的管线。
     */
    private Response handleCopilotResponses(String uri, Map<String, String> headers, String rawBody) {
        JSONObject body;
        try {
            body = new JSONObject(rawBody);
        } catch (Exception e) {
            return null;
        }
        String modelRef = body.optString("model", "");
        if (!modelRef.startsWith("github-copilot/")) return null;
        String upstreamModel = modelRef.substring("github-copilot/".length());

        JSONObject prov = getProviderById("github-copilot");
        if (prov == null) return errorResponse(404, "供应商 github-copilot 不存在");
        JSONObject acc = null;
        JSONArray accs = prov.optJSONArray("accounts");
        for (int i = 0; accs != null && i < accs.length(); i++) {
            JSONObject a = accs.optJSONObject(i);
            if (a != null && !a.optString("refresh", "").trim().isEmpty()) { acc = a; break; }
        }
        if (acc == null) return errorResponse(401, "请先登录 GitHub Copilot");
        String before = acc.optString("access", "");
        String token = CopilotAuth.ensureCopilotToken(acc);
        if (token == null || token.trim().isEmpty()) {
            return errorResponse(401, "Copilot token 续期失败，请重新登录 GitHub 账号");
        }
        if (!token.equals(before)) saveConfig(configJson);

        HttpURLConnection conn = null;
        boolean handedOff = false;
        try {
            body.put("model", upstreamModel);
            boolean stream = body.optBoolean("stream", false);
            String base = prov.optString("baseUrl", CopilotAuth.BASE).replaceAll("/+$", "");
            String url = base + (uri.endsWith("/compact") ? "/responses/compact" : "/responses");

            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(15 * 60 * 1000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", stream ? "text/event-stream" : "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            for (java.util.Map.Entry<String, String> e : CopilotAuth.clientHeaders().entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
            conn.setRequestProperty("X-Initiator", CopilotAuth.isAgentCall(body) ? "agent" : "user");
            if (CopilotAuth.isVisionRequest(body)) {
                conn.setRequestProperty("Copilot-Vision-Request", "true");
            }

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.flush();
            os.close();

            int status = conn.getResponseCode();
            if (status < 200 || status >= 400) {
                String err = "";
                try {
                    err = drainText(conn.getErrorStream());
                } catch (Throwable ignored) {
                }
                Response resp = errorResponse(status <= 0 ? 502 : status,
                        "Copilot responses: " + (err.isEmpty() ? "上游没有返回响应体" : err));
                try { conn.disconnect(); } catch (Throwable ignored) {}
                return resp;
            }

            Response resp;
            if (stream) {
                resp = newFixedLengthResponse(Status.OK, "text/event-stream", conn.getInputStream(), -1);
                resp.addHeader("Cache-Control", "no-cache");
                handedOff = true;
            } else {
                String text = drainText(conn.getInputStream());
                resp = newFixedLengthResponse(Status.OK, "application/json; charset=UTF-8", text);
            }
            resp.addHeader("X-Magpie-Served-By", "github-copilot");
            addCorsHeaders(resp);
            return resp;
        } catch (Throwable e) {
            return errorResponse(502, "Copilot responses 转发失败: "
                    + (e.getMessage() == null ? String.valueOf(e) : e.getMessage()));
        } finally {
            if (conn != null && !handedOff) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    private Response handleCodexNative(IHTTPSession session, String uri, String method, Map<String, String> headers, String rawBody) {
        return handleCodexNative(session, uri, method, headers, rawBody, 0);
    }

    /** attempt 只用来把 401 重试限死一次：刷新成功但上游仍拒，就不要再递归下去 */
    private Response handleCodexNative(IHTTPSession session, String uri, String method, Map<String, String> headers, String rawBody, int attempt) {
        JSONObject prov = getProviderById("codex");
        if (prov == null) {
            return errorResponse(404, "Codex 供应商尚未初始化");
        }
        JSONArray accs = prov.optJSONArray("accounts");
        JSONObject activeAcc = null;
        if (accs != null && accs.length() > 0) {
            for (int i = 0; i < accs.length(); i++) {
                JSONObject a = accs.optJSONObject(i);
                if (a != null && a.optBoolean("enabled", true)) {
                    activeAcc = a;
                    break;
                }
            }
            if (activeAcc == null) activeAcc = accs.optJSONObject(0);
        }

        if (activeAcc == null) {
            return errorResponse(401, "请先在 All In 控制台登录或配置 Codex 订阅账号");
        }

        CodexAuth.SignInInfo info = new CodexAuth.SignInInfo();
        info.accessToken = activeAcc.optString("access", activeAcc.optString("token", ""));
        info.refreshToken = activeAcc.optString("refresh", "");
        info.idToken = activeAcc.optString("idToken", "");
        info.accountId = activeAcc.optString("accountId", "");
        info.email = activeAcc.optString("email", activeAcc.optString("name", ""));
        info.planType = activeAcc.optString("plan", "chatgpt");
        info.authMode = activeAcc.optString("authMode", "chatgpt");
        info.expiresAt = activeAcc.optLong("expires", 0);

        if (info.expiresAt > 0 && (info.expiresAt - System.currentTimeMillis() < 5 * 60 * 1000L) && !info.refreshToken.isEmpty()) {
            try {
                CodexAuth.refreshToken(info, "proactive_expiry");
                activeAcc.put("access", info.accessToken);
                activeAcc.put("token", info.accessToken);
                activeAcc.put("refresh", info.refreshToken);
                activeAcc.put("expires", info.expiresAt);
                activeAcc.put("lastRefresh", info.lastRefresh);
                saveConfig(configJson);
            } catch (Throwable ignored) {}
        }

        if (uri.endsWith("/models") && "GET".equalsIgnoreCase(method)) {
            try {
                List<String> liveModels = CodexAuth.syncModels(info);
                JSONObject res = new JSONObject();
                JSONArray mArr = new JSONArray();
                for (String m : liveModels) {
                    JSONObject mo = new JSONObject();
                    mo.put("slug", m);
                    mo.put("display_name", m);
                    mo.put("visibility", "show");
                    mArr.put(mo);
                }
                res.put("models", mArr);
                return jsonResponse(res);
            } catch (Exception e) {
                return errorResponse(500, "获取 Codex 模型失败: " + e.getMessage());
            }
        }

        if (uri.contains("/responses") && "POST".equalsIgnoreCase(method)) {
            try {
                String targetUrl = uri.endsWith("/compact") 
                        ? CodexAuth.CODEX_BASE + "/responses/compact" 
                        : CodexAuth.CODEX_RESPONSES;
                HttpURLConnection conn = CodexAuth.openHttp(targetUrl);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(60000);
                conn.setReadTimeout(180000);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "text/event-stream, application/json");
                conn.setRequestProperty("Authorization", "Bearer " + info.accessToken);
                if (!info.accountId.isEmpty()) {
                    conn.setRequestProperty("chatgpt-account-id", info.accountId);
                }
                conn.setRequestProperty("originator", CodexAuth.ORIGINATOR);
                conn.setRequestProperty("User-Agent", CodexAuth.USER_AGENT);
                conn.setRequestProperty("OpenAI-Beta", "responses=experimental");

                OutputStream os = conn.getOutputStream();
                os.write(rawBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                os.flush();
                os.close();

                int code = conn.getResponseCode();
                if (code == 401 && !info.refreshToken.isEmpty()) {
                    CodexAuth.refreshToken(info, "unauthorized_recovery");
                    activeAcc.put("access", info.accessToken);
                    activeAcc.put("token", info.accessToken);
                    activeAcc.put("refresh", info.refreshToken);
                    activeAcc.put("expires", info.expiresAt);
                    saveConfig(configJson);
                    if (attempt < 1) {
                        return handleCodexNative(session, uri, method, headers, rawBody, attempt + 1);
                    }
                    // 刷新成功仍然 401：不再递归，把上游的 401 原样交给下面处理
                }

                InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
                if (code >= 200 && code < 400) {
                    Response resp = newChunkedResponse(Status.OK, "text/event-stream; charset=UTF-8", is);
                    addCorsHeaders(resp);
                    return resp;
                } else {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[2048];
                    int n;
                    while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                    is.close();
                    Response resp = newFixedLengthResponse(Status.lookup(code) != null ? Status.lookup(code) : Status.INTERNAL_ERROR,
                            "application/json; charset=UTF-8", bos.toString("UTF-8"));
                    addCorsHeaders(resp);
                    return resp;
                }
            } catch (Exception e) {
                return errorResponse(502, "Codex 原生转发失败: " + e.getMessage());
            }
        }

        return errorResponse(404, "未知的 Codex 原生端点: " + uri);
    }

    private Attempt forwardCodex(RouteTarget target, JSONObject payload, boolean clientWantsStream,
                                       String requestedModel, PrivacyMasker.Session maskSession,
                                       ContextGuard.Result guardResult) {
        return forwardCodex(target, payload, clientWantsStream, requestedModel, maskSession, guardResult, 0);
    }

    private Attempt forwardCodex(RouteTarget target, JSONObject payload, boolean clientWantsStream,
                                       String requestedModel, PrivacyMasker.Session maskSession,
                                       ContextGuard.Result guardResult, int attempt) {
        Attempt out = new Attempt();
        out.providerId = target.providerId;
        out.modelRef = target.providerId + "/" + target.upstreamModel;
        HttpURLConnection conn = null;
        try {
            JSONObject acc = target.account;
            CodexAuth.SignInInfo info = new CodexAuth.SignInInfo();
            if (acc != null) {
                info.accessToken = acc.optString("access", acc.optString("token", target.apiKey));
                info.refreshToken = acc.optString("refresh", "");
                info.idToken = acc.optString("idToken", "");
                info.accountId = acc.optString("accountId", "");
                info.authMode = acc.optString("authMode", "chatgpt");
                info.expiresAt = acc.optLong("expires", 0);
                info.email = acc.optString("email", acc.optString("name", ""));
            } else {
                info.accessToken = target.apiKey;
                info.authMode = target.apiKey.startsWith("sk-") ? "apikey" : "chatgpt";
            }

            if (info.expiresAt > 0 && (info.expiresAt - System.currentTimeMillis() < 5 * 60 * 1000L) && !info.refreshToken.isEmpty()) {
                try {
                    CodexAuth.refreshToken(info, "proactive_expiry");
                    if (acc != null) {
                        acc.put("access", info.accessToken);
                        acc.put("token", info.accessToken);
                        acc.put("refresh", info.refreshToken);
                        acc.put("expires", info.expiresAt);
                        saveConfig(configJson);
                    }
                } catch (Throwable ignored) {}
            }

            String upstreamModel = target.upstreamModel != null ? target.upstreamModel : requestedModel;
            if (upstreamModel.contains("/")) {
                upstreamModel = upstreamModel.substring(upstreamModel.lastIndexOf('/') + 1);
            }

            JSONObject respReq = CodexAuth.chatToResponses(payload, upstreamModel);
            respReq.put("stream", true);

            conn = CodexAuth.openHttp(CodexAuth.CODEX_RESPONSES);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(180000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream, application/json");
            conn.setRequestProperty("Authorization", "Bearer " + info.accessToken);
            if (!info.accountId.isEmpty()) {
                conn.setRequestProperty("chatgpt-account-id", info.accountId);
            }
            conn.setRequestProperty("originator", CodexAuth.ORIGINATOR);
            conn.setRequestProperty("User-Agent", CodexAuth.USER_AGENT);
            conn.setRequestProperty("OpenAI-Beta", "responses=experimental");

            OutputStream os = conn.getOutputStream();
            os.write(respReq.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            os.flush();
            os.close();

            int statusCode = conn.getResponseCode();
            if (statusCode == 401 && !info.refreshToken.isEmpty()) {
                CodexAuth.refreshToken(info, "unauthorized_recovery");
                if (acc != null) {
                    acc.put("access", info.accessToken);
                    acc.put("token", info.accessToken);
                    acc.put("refresh", info.refreshToken);
                    acc.put("expires", info.expiresAt);
                    saveConfig(configJson);
                }
                conn.disconnect();
                if (attempt < 1) {
                    return forwardCodex(target, payload, clientWantsStream, requestedModel, maskSession, guardResult, attempt + 1);
                }
                out.ok = false;
                out.status = 401;
                out.body = "Codex 登录态被上游拒绝，请重新登录";
                out.response = errorResponse(401, out.body);
                return out;
            }

            boolean okStatus = statusCode >= 200 && statusCode < 400;
            InputStream is = okStatus ? conn.getInputStream() : conn.getErrorStream();
            if (!okStatus) {
                ByteArrayOutputStream eb = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) eb.write(buf, 0, n);
                is.close();
                String errBody = eb.toString("UTF-8");
                if (errBody.contains("<html") || errBody.contains("<HTML")) {
                    try {
                        JSONObject errJson = new JSONObject();
                        JSONObject inErr = new JSONObject();
                        inErr.put("message", "OpenAI Codex 拒绝了请求 (" + statusCode + ")：Token 无效或已失效，请在 All In 控制台重新登录或导入最新凭据");
                        inErr.put("type", "codex_error");
                        inErr.put("code", statusCode);
                        errJson.put("error", inErr);
                        errBody = errJson.toString();
                    } catch (Exception ignored) {}
                }
                out.status = statusCode;
                out.ok = false;
                out.body = errBody;
                Response resp = newFixedLengthResponse(Status.lookup(statusCode) != null ? Status.lookup(statusCode) : Status.INTERNAL_ERROR,
                        "application/json; charset=UTF-8", errBody);
                addCorsHeaders(resp);
                out.response = resp;
                return out;
            }

            out.ok = true;
            out.status = 200;

            if (clientWantsStream) {
                InputStream openAiStream = convertResponsesToOpenAiStream(is, requestedModel);
                out.response = wantsAnthropicOut()
                        ? streamForwardAnthropicResponse(openAiStream, requestedModel, maskSession, guardResult, conn)
                        : streamForwardResponse(openAiStream, maskSession, guardResult, conn, target.providerId);
                return out;
            } else {
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder fullText = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6).trim();
                        if ("[DONE]".equals(data)) break;
                        try {
                            JSONObject ev = new JSONObject(data);
                            String type = ev.optString("type", "");
                            if ("response.output_text.delta".equals(type) || "response.text.delta".equals(type)) {
                                fullText.append(ev.optString("delta", ""));
                            } else if ("response.output_item.delta".equals(type)) {
                                JSONObject dObj = ev.optJSONObject("delta");
                                if (dObj != null) fullText.append(dObj.optString("text", ""));
                            }
                        } catch (Exception ignored) {}
                    }
                }
                reader.close();

                String content = PrivacyMasker.unmaskText(fullText.toString(), maskSession);
                JSONObject sim = new JSONObject();
                sim.put("id", "chatcmpl-" + System.currentTimeMillis());
                sim.put("object", "chat.completion");
                sim.put("created", System.currentTimeMillis() / 1000);
                sim.put("model", requestedModel);

                JSONArray choices = new JSONArray();
                JSONObject c0 = new JSONObject();
                c0.put("index", 0);
                JSONObject msg = new JSONObject();
                msg.put("role", "assistant");
                msg.put("content", content);
                c0.put("message", msg);
                c0.put("finish_reason", "stop");
                choices.put(c0);
                sim.put("choices", choices);

                JSONObject usage = new JSONObject();
                usage.put("prompt_tokens", 10);
                usage.put("completion_tokens", content.length() / 2);
                usage.put("total_tokens", 10 + content.length() / 2);
                usage.put("estimated", true);
                sim.put("usage", usage);

                out.textOut = content;

                if (wantsAnthropicOut()) {
                    JSONObject anth = AnthropicBridge.responseToAnthropic(sim, requestedModel);
                    Response resp = newFixedLengthResponse(Status.OK, "application/json; charset=UTF-8", anth.toString());
                    addCorsHeaders(resp);
                    out.response = resp;
                    return out;
                }

                Response resp = newFixedLengthResponse(Status.OK, "application/json; charset=UTF-8", sim.toString());
                resp.addHeader("X-Magpie-Served-By", target.providerId);
                addCorsHeaders(resp);
                out.response = resp;
                return out;
            }

        } catch (Exception e) {
            e.printStackTrace();
            out.ok = false;
            out.status = 502;
            out.body = "Codex upstream failed: " + e.getMessage();
            out.response = errorResponse(502, out.body);
            return out;
        }
    }

    private InputStream convertResponsesToOpenAiStream(final InputStream responsesIs, final String model) {
        try {
            final java.io.PipedOutputStream pos = new java.io.PipedOutputStream();
            final java.io.PipedInputStream pis = new java.io.PipedInputStream(pos, 16384);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BufferedReader br = new BufferedReader(new InputStreamReader(responsesIs, java.nio.charset.StandardCharsets.UTF_8));
                        String line;
                        String chunkId = "chatcmpl-" + System.currentTimeMillis();
                        while ((line = br.readLine()) != null) {
                            if (line.startsWith("data: ")) {
                                String d = line.substring(6).trim();
                                if ("[DONE]".equals(d)) break;
                                try {
                                    JSONObject ev = new JSONObject(d);
                                    String type = ev.optString("type", "");
                                    String deltaText = null;
                                    if ("response.output_text.delta".equals(type) || "response.text.delta".equals(type)) {
                                        deltaText = ev.optString("delta", "");
                                    } else if ("response.output_item.delta".equals(type)) {
                                        JSONObject dObj = ev.optJSONObject("delta");
                                        if (dObj != null) deltaText = dObj.optString("text", "");
                                    }
                                    if (deltaText != null && !deltaText.isEmpty()) {
                                        String chunk = CodexAuth.wrapChatDelta(deltaText, model, chunkId);
                                        pos.write(chunk.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                        pos.flush();
                                    } else if ("response.completed".equals(type)) {
                                        String done = CodexAuth.wrapChatDone(model, chunkId);
                                        pos.write(done.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                        pos.flush();
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                        pos.write("data: [DONE]\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        pos.flush();
                    } catch (Throwable ignored) {
                    } finally {
                        try { pos.close(); } catch (Throwable ignored) {}
                        try { responsesIs.close(); } catch (Throwable ignored) {}
                    }
                }
            }, "Codex-SSE-Converter").start();
            return pis;
        } catch (Exception e) {
            return responsesIs;
        }
    }

    private synchronized String addCodexAccount(CodexAuth.SignInInfo info, String accName) {
        try {
            JSONArray providers = configJson.optJSONArray("providers");
            if (providers == null) {
                providers = new JSONArray();
                configJson.put("providers", providers);
            }

            JSONObject cp = null;
            for (int i = 0; i < providers.length(); i++) {
                JSONObject p = providers.optJSONObject(i);
                if (p != null && "codex".equals(p.optString("id"))) {
                    cp = p;
                    break;
                }
            }
            if (cp == null) {
                cp = new JSONObject();
                cp.put("id", "codex");
                cp.put("name", "Codex (ChatGPT 订阅)");
                cp.put("status", "ported");
                cp.put("plugin", "builtin/codex");
                cp.put("baseUrl", "https://chatgpt.com/backend-api/codex");
                cp.put("protocol", "codex");
                cp.put("note", "OpenAI Codex OAuth / auth.json / Refresh Token 导入；Responses API 与 OpenAI 互转");
                cp.put("enabled", true);
                cp.put("models", new JSONArray());
                cp.put("keys", new JSONArray());
                cp.put("accounts", new JSONArray());
                providers.put(cp);
            }

            JSONArray accounts = cp.optJSONArray("accounts");
            if (accounts == null) {
                accounts = new JSONArray();
                cp.put("accounts", accounts);
            }

            String accId = "codex_" + System.currentTimeMillis();
            JSONObject targetAcc = null;
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject a = accounts.optJSONObject(i);
                if (a != null) {
                    if ((!info.accountId.isEmpty() && info.accountId.equals(a.optString("accountId")))
                            || (!info.email.isEmpty() && info.email.equalsIgnoreCase(a.optString("email")))) {
                        targetAcc = a;
                        accId = a.optString("id");
                        break;
                    }
                }
            }

            if (targetAcc == null) {
                targetAcc = new JSONObject();
                targetAcc.put("id", accId);
                targetAcc.put("addedAt", System.currentTimeMillis());
                accounts.put(targetAcc);
            }

            targetAcc.put("type", "chatgpt".equals(info.authMode) ? "oauth" : "apikey");
            targetAcc.put("authMode", info.authMode);
            targetAcc.put("name", (accName != null && !accName.isEmpty()) ? accName : info.email);
            targetAcc.put("token", info.accessToken);
            targetAcc.put("access", info.accessToken);
            targetAcc.put("refresh", info.refreshToken);
            targetAcc.put("idToken", info.idToken);
            targetAcc.put("accountId", info.accountId);
            targetAcc.put("email", info.email);
            targetAcc.put("plan", info.planType);
            targetAcc.put("expires", info.expiresAt);
            targetAcc.put("lastRefresh", info.lastRefresh > 0 ? info.lastRefresh : System.currentTimeMillis());
            targetAcc.put("enabled", true);

            JSONArray keys = cp.optJSONArray("keys");
            if (keys == null) {
                keys = new JSONArray();
                cp.put("keys", keys);
            }
            boolean keyExists = false;
            for (int i = 0; i < keys.length(); i++) {
                if (info.accessToken.equals(keys.optString(i))) {
                    keyExists = true;
                    break;
                }
            }
            if (!keyExists) keys.put(info.accessToken);

            List<String> liveModels = CodexAuth.syncModels(info);
            JSONArray modelsArr = cp.optJSONArray("models");
            if (modelsArr == null || modelsArr.length() == 0) {
                modelsArr = new JSONArray();
                for (String m : liveModels) modelsArr.put(m);
                cp.put("models", modelsArr);
            }

            saveConfig(configJson);
            return accId;
        } catch (Exception e) {
            e.printStackTrace();
            return "err";
        }
    }

    // ==================================================================================
    // Antigravity（Google Code Assist）真机接入
    // 协议见 AntigravityAuth / AntigravityClient；这里只做 HTTP、错误映射与流接管。
    // ==================================================================================
    private Attempt forwardAntigravity(RouteTarget target, JSONObject payload, boolean clientWantsStream,
                                       String requestedModel, PrivacyMasker.Session maskSession,
                                       ContextGuard.Result guardResult) {
        return forwardAntigravity(target, payload, clientWantsStream, requestedModel, maskSession, guardResult, 0);
    }

    private Attempt forwardAntigravity(RouteTarget target, JSONObject payload, boolean clientWantsStream,
                                       String requestedModel, PrivacyMasker.Session maskSession,
                                       ContextGuard.Result guardResult, int attempt) {
        Attempt out = new Attempt();
        out.providerId = target.providerId;
        out.modelRef = target.providerId + "/" + target.upstreamModel;
        try {
            JSONObject acc = target.account;
            if (acc == null) {
                out.ok = false;
                out.status = 400;
                out.body = "Antigravity 尚未登录：请先在供应商卡片里用 Google 账号授权";
                out.response = errorResponse(400, out.body);
                return out;
            }

            String access;
            try {
                access = AntigravityAuth.ensureToken(acc);
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "Antigravity 账号掉线" : e.getMessage();
                out.status = 401;
                out.body = msg;
                out.response = errorResponse(401, msg);
                return out;
            }

            boolean hadProject = !acc.optString("project", "").isEmpty();
            String project;
            try {
                project = AntigravityAuth.ensureProject(acc, access);
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "Antigravity 取 Cloud 项目失败" : e.getMessage();
                out.status = 502;
                out.body = msg;
                out.response = errorResponse(502, msg);
                return out;
            }
            if (!hadProject) saveConfig(configJson);

            String upstreamModel = target.upstreamModel != null ? target.upstreamModel : requestedModel;
            if (upstreamModel.contains("/")) {
                upstreamModel = upstreamModel.substring(upstreamModel.lastIndexOf('/') + 1);
            }

            // 家族模型（gemini-3.8-flash）+ reasoning_effort → 具体变体 id（-high/-medium/-low）
            JSONObject agProv = getProviderById("antigravity");
            JSONObject agMeta = agProv == null ? null : agProv.optJSONObject("modelMeta");
            JSONObject famMeta = agMeta == null ? null : agMeta.optJSONObject(upstreamModel);
            String effort = payload.optString("reasoning_effort", "");
            String variant = AntigravityAuth.pickVariant(famMeta, effort);
            if (variant != null && !variant.isEmpty()) {
                upstreamModel = variant;
            }

            JSONObject env = AntigravityClient.buildEnvelope(payload, upstreamModel, project);
            byte[] bodyBytes = env.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);

            // 这台机到 Google 的连接会间歇性被 RST，两个域又是同一后端：
            // 连接层失败就地换另一个 base 重试一次，别把抖动当故障。
            AntigravityAuth.Resp resp = null;
            String usedBase = AntigravityAuth.chatBase;
            String firstErr = null;
            String[] bases = new String[]{AntigravityAuth.chatBase, AntigravityAuth.altBase()};
            for (int bi = 0; bi < bases.length; bi++) {
                try {
                    resp = AntigravityAuth.request(bases[bi] + AntigravityAuth.STREAM_PATH,
                            "POST", access, "text/event-stream", "application/json", bodyBytes, 20000, 300000);
                    usedBase = bases[bi];
                    break;
                } catch (Exception e) {
                    if (firstErr == null) firstErr = e.getMessage() == null ? "未知错误" : e.getMessage();
                }
            }
            if (resp == null) {
                String msg = "Antigravity 连接失败（两个端点都试过了）：" + firstErr
                        + "。这是本机网络到 Google 被拦，不是账号问题。";
                out.status = 502;
                out.body = msg;
                out.response = errorResponse(502, msg);
                return out;
            }
            // 换过 base 就记住，下次直接用通的那个
            if (!usedBase.equals(AntigravityAuth.chatBase)) {
                AntigravityAuth.switchChatBase(usedBase);
                try {
                    JSONObject ag = getProviderById("antigravity");
                    if (ag != null) {
                        ag.put("baseUrl", usedBase);
                        ag.put("probeChatBase", usedBase);
                        saveConfig(configJson);
                    }
                } catch (Throwable ignored) {
                }
                android.util.Log.d("Magpie", "antigravity base 自适应切换: " + usedBase);
            }

            int statusCode = resp.status;
            if (statusCode == 401) {
                resp.close();
                if (AntigravityAuth.refresh(acc)) {
                    acc.put("project", project);
                    saveConfig(configJson);
                    if (attempt < 1) {
                        return forwardAntigravity(target, payload, clientWantsStream, requestedModel, maskSession, guardResult, attempt + 1);
                    }
                    out.ok = false;
                    out.status = 401;
                    out.body = "Antigravity 登录态被上游拒绝，请重新登录 Google 账号";
                    out.response = errorResponse(401, out.body);
                    return out;
                }
            }

            if (statusCode < 200 || statusCode >= 400) {
                String errBody = resp.text();
                resp.close();
                out.ok = false;
                out.status = statusCode;
                String sysText = "";
                JSONArray msgs = payload.optJSONArray("messages");
                if (msgs != null) {
                    for (int i = 0; i < msgs.length(); i++) {
                        JSONObject m = msgs.optJSONObject(i);
                        if (m == null) continue;
                        String role = m.optString("role", "");
                        if ("system".equals(role) || "developer".equals(role)) {
                            Object c = m.opt("content");
                            if (c instanceof String) sysText = sysText + (String) c;
                        }
                    }
                }
                if (AntigravityAuth.looksTurnedAway(statusCode, errBody)) {
                    // Antigravity 的假 429：额度没动，转成 400 并说清原因（SDK 不会重试 400）
                    String hint = AntigravityClient.turnedAwayHint(sysText);
                    String msg = "Antigravity 拒绝了这段 system prompt（假 429，额度没动）：" + hint;
                    JSONObject wrap = new JSONObject();
                    JSONObject err = new JSONObject();
                    err.put("message", msg);
                    err.put("type", "prompt_turned_away");
                    err.put("provider", "antigravity");
                    wrap.put("error", err);
                    out.status = 400;
                    out.body = wrap.toString();
                    Response r = newFixedLengthResponse(Status.BAD_REQUEST, "application/json; charset=UTF-8", wrap.toString());
                    addCorsHeaders(r);
                    out.response = r;
                    return out;
                }
                if (errBody != null && (errBody.contains("<html") || errBody.contains("<HTML"))) {
                    try {
                        JSONObject wrap = new JSONObject();
                        JSONObject err = new JSONObject();
                        err.put("message", "Antigravity 返回了网页错误 (" + statusCode + ")，可能是网络/地区限制，请稍后重试");
                        err.put("type", "antigravity_error");
                        wrap.put("error", err);
                        errBody = wrap.toString();
                    } catch (Exception ignored) {
                    }
                }
                out.body = errBody;
                Status st = Status.lookup(statusCode) != null ? Status.lookup(statusCode) : Status.INTERNAL_ERROR;
                Response r = newFixedLengthResponse(st, "application/json; charset=UTF-8", errBody);
                addCorsHeaders(r);
                out.response = r;
                return out;
            }

            out.ok = true;
            out.status = 200;

            if (clientWantsStream) {
                InputStream openAiStream = AntigravityClient.toOpenAiStream(resp.body, requestedModel);
                out.response = wantsAnthropicOut()
                        ? streamForwardAnthropicResponse(openAiStream, requestedModel, maskSession, guardResult, null)
                        : streamForwardResponse(openAiStream, maskSession, guardResult, null, target.providerId);
                return out;
            }

            JSONObject sim;
            try {
                sim = AntigravityClient.toOpenAiCompletion(resp.body, requestedModel);
            } finally {
                resp.close();
            }
            JSONArray choices = sim.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject c0 = choices.optJSONObject(0);
                JSONObject msg = c0 == null ? null : c0.optJSONObject("message");
                if (msg != null) {
                    String content = PrivacyMasker.unmaskText(msg.optString("content", ""), maskSession);
                    msg.put("content", content);
                    out.textOut = content;
                    if (msg.has("reasoning_content")) {
                        out.reasoningOut = PrivacyMasker.unmaskText(msg.optString("reasoning_content", ""), maskSession);
                        msg.put("reasoning_content", out.reasoningOut);
                    }
                }
            }
            JSONObject usageObj = sim.optJSONObject("usage");
            if (usageObj != null) {
                totalTokens.addAndGet(usageObj.optInt("total_tokens", 0));
            }

            if (wantsAnthropicOut()) {
                JSONObject anth = AnthropicBridge.responseToAnthropic(sim, requestedModel);
                Response r = newFixedLengthResponse(Status.OK, "application/json; charset=UTF-8", anth.toString());
                addCorsHeaders(r);
                out.response = r;
                return out;
            }
            Response r = newFixedLengthResponse(Status.OK, "application/json; charset=UTF-8", sim.toString());
            r.addHeader("X-Magpie-Served-By", target.providerId);
            addCorsHeaders(r);
            out.response = r;
            return out;

        } catch (Exception e) {
            e.printStackTrace();
            out.ok = false;
            out.status = 502;
            out.body = "Antigravity upstream failed: " + e.getMessage();
            out.response = errorResponse(502, out.body);
            return out;
        }
    }

    /** 把 Antigravity 账号写进供应商池（同邮箱覆盖，不重复堆） */
    private synchronized String addAntigravityAccount(JSONObject info, String accName) {
        try {
            JSONArray providers = configJson.optJSONArray("providers");
            if (providers == null) {
                providers = new JSONArray();
                configJson.put("providers", providers);
            }
            JSONObject ap = null;
            for (int i = 0; i < providers.length(); i++) {
                JSONObject p = providers.optJSONObject(i);
                if (p != null && "antigravity".equals(p.optString("id"))) {
                    ap = p;
                    break;
                }
            }
            if (ap == null) {
                ap = new JSONObject();
                ap.put("id", "antigravity");
                ap.put("name", "Antigravity (Google 账号)");
                ap.put("status", "ported");
                ap.put("plugin", "builtin/antigravity");
                ap.put("baseUrl", AntigravityAuth.chatBase);
                ap.put("protocol", "antigravity");
                ap.put("note", "Google Code Assist 信封协议；OAuth 登录，模型与配额来自 fetchAvailableModels");
                ap.put("enabled", true);
                ap.put("models", new JSONArray());
                ap.put("keys", new JSONArray());
                ap.put("accounts", new JSONArray());
                providers.put(ap);
            }
            ap.put("status", "ported");
            ap.put("protocol", "antigravity");
            ap.put("plugin", "builtin/antigravity");

            JSONArray accounts = ap.optJSONArray("accounts");
            if (accounts == null) {
                accounts = new JSONArray();
                ap.put("accounts", accounts);
            }

            String email = info.optString("email", "");
            String refresh = info.optString("refresh", "");
            JSONObject targetAcc = null;
            String accId = "ag_" + System.currentTimeMillis();
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject a = accounts.optJSONObject(i);
                if (a == null) continue;
                // 优先按邮箱去重；邮箱取不到（userinfo 被网络挡）时按 refresh token 去重，
                // 否则每次登录都会往池子里堆一个重复账号
                boolean sameEmail = !email.isEmpty() && email.equalsIgnoreCase(a.optString("email", ""));
                boolean sameRefresh = !refresh.isEmpty() && refresh.equals(a.optString("refresh", ""));
                if (sameEmail || sameRefresh) {
                    targetAcc = a;
                    accId = a.optString("id", accId);
                    break;
                }
            }
            if (targetAcc == null) {
                targetAcc = new JSONObject();
                targetAcc.put("id", accId);
                targetAcc.put("addedAt", System.currentTimeMillis());
                accounts.put(targetAcc);
            }
            java.util.Iterator<String> it = info.keys();
            while (it.hasNext()) {
                String k = it.next();
                targetAcc.put(k, info.opt(k));
            }
            String shown = (accName != null && !accName.isEmpty()) ? accName : email;
            if (shown == null || shown.trim().isEmpty()) {
                shown = "Google 账号 " + (accId.length() > 10 ? accId.substring(accId.length() - 6) : accId);
            }
            targetAcc.put("name", shown);
            targetAcc.put("updatedAt", System.currentTimeMillis());
            targetAcc.put("enabled", true);

            JSONArray keys = ap.optJSONArray("keys");
            if (keys == null) {
                keys = new JSONArray();
                ap.put("keys", keys);
            }
            String tok = info.optString("access", "");
            if (!tok.isEmpty()) {
                boolean exists = false;
                for (int i = 0; i < keys.length(); i++) {
                    if (tok.equals(keys.optString(i))) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) keys.put(tok);
            }

            // 顺手拉一次模型清单（失败不影响入池）
            try {
                String access = AntigravityAuth.ensureToken(targetAcc);
                String project = AntigravityAuth.ensureProject(targetAcc, access);
                JSONArray models = AntigravityAuth.fetchModels(targetAcc, access, project);
                JSONArray ids = new JSONArray();
                JSONObject meta = ap.optJSONObject("modelMeta");
                if (meta == null) meta = new JSONObject();
                for (int i = 0; i < models.length(); i++) {
                    JSONObject mo = models.optJSONObject(i);
                    if (mo == null) continue;
                    String id = mo.optString("id", "");
                    if (id.isEmpty()) continue;
                    ids.put(id);
                    JSONObject m = new JSONObject();
                    m.put("name", mo.optString("name", id));
                    if (mo.has("context")) m.put("context", mo.optInt("context", 0));
                    if (mo.has("output")) m.put("output", mo.optInt("output", 0));
                    if (mo.has("remaining")) m.put("remaining", mo.optDouble("remaining", -1));
                    meta.put(id, m);
                }
                if (ids.length() > 0) {
                    ap.put("models", ids);
                    ap.put("modelMeta", meta);
                }
            } catch (Throwable ignored) {
            }

            saveConfig(configJson);
            return accId;
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("写入 Antigravity 账号失败: " + e.getMessage(), e);
        }
    }

    /** Google 只认 loopback 回调，这里在 1456 监听，浏览器授权后自动入池 */
    private void startAntigravityCallbackListener() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                java.net.ServerSocket ss = null;
                try {
                    ss = new java.net.ServerSocket(AntigravityAuth.CALLBACK_PORT);
                    ss.setSoTimeout(900000);
                    while (!ss.isClosed()) {
                        final Socket s = ss.accept();
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream()));
                                    String reqLine = br.readLine();
                                    String html;
                                    if (reqLine != null && reqLine.contains("code=")) {
                                        String[] reqParts = reqLine.split(" ");
                                        String code = null;
                                        String state = null;
                                        if (reqParts.length > 1) {
                                            String uriPart = reqParts[1];
                                            String query = uriPart.contains("?") ? uriPart.substring(uriPart.indexOf('?') + 1) : "";
                                            Map<String, String> qm = new HashMap<String, String>();
                                            for (String pair : query.split("&")) {
                                                String[] kv = pair.split("=");
                                                if (kv.length == 2) {
                                                    qm.put(java.net.URLDecoder.decode(kv[0], "UTF-8"),
                                                            java.net.URLDecoder.decode(kv[1], "UTF-8"));
                                                }
                                            }
                                            code = qm.get("code");
                                            state = qm.get("state");
                                        }
                                        AntigravityAuth.Flow matched = null;
                                        for (AntigravityAuth.Flow f : antigravityFlows.values()) {
                                            if (state != null && f.state.equals(state)) {
                                                matched = f;
                                                break;
                                            }
                                        }
                                        if (matched != null && code != null && !code.isEmpty()) {
                                            try {
                                                JSONObject acc = AntigravityAuth.exchangeCode(code, matched.verifier, matched.redirect);
                                                addAntigravityAccount(acc, acc.optString("email", ""));
                                                matched.account = acc;
                                                matched.status = "done";
                                                html = callbackPage(true, "Antigravity 账号已入池：" + acc.optString("email", "")
                                                        + "<br>项目：" + acc.optString("project", "（未分配）"));
                                            } catch (Exception e) {
                                                matched.status = "failed";
                                                matched.error = e.getMessage();
                                                html = callbackPage(false, "授权换 token 失败：" + e.getMessage());
                                            }
                                        } else {
                                            html = callbackPage(false, "没有匹配到进行中的授权流程，请回 All In 重新点一次登录");
                                        }
                                    } else {
                                        html = callbackPage(false, "回调里没有 code，请回 All In 重新发起登录");
                                    }
                                    OutputStream outStream = s.getOutputStream();
                                    byte[] hBytes = html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                                    String head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Length: "
                                            + hBytes.length + "\r\nConnection: close\r\n\r\n";
                                    outStream.write(head.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                    outStream.write(hBytes);
                                    outStream.flush();
                                    s.close();
                                } catch (Throwable ignored) {
                                }
                            }
                        }, "Antigravity-1456-Conn").start();
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (ss != null && !ss.isClosed()) {
                        try { ss.close(); } catch (Throwable ignored) {}
                    }
                }
            }
        }, "Antigravity-Callback-1456").start();
    }

    private static String callbackPage(boolean ok, String detail) {
        String color = ok ? "#34d399" : "#f87171";
        String title = ok ? "Antigravity 连接成功" : "Antigravity 授权未完成";
        return "<!DOCTYPE html><html><head><meta charset='utf-8'><title>All In - " + title + "</title>"
                + "<style>body{background:#0b0d14;color:#f3f4f6;font-family:-apple-system,sans-serif;display:flex;"
                + "align-items:center;justify-content:center;height:100vh;margin:0;}"
                + ".box{text-align:center;padding:40px 32px;background:#131622;border:1px solid #2e3440;border-radius:20px;"
                + "box-shadow:0 10px 30px rgba(0,0,0,.5);max-width:420px;}"
                + "h1{color:" + color + ";font-size:1.35rem;margin:0 0 14px;}"
                + "p{color:#9ca3af;font-size:.92rem;line-height:1.7;margin:0;}</style></head>"
                + "<body><div class='box'><h1>" + title + "</h1><p>" + detail
                + "<br><br>可以关闭此页，回到 All In 控制台。</p></div></body></html>";
    }

    private void startCodexCallbackListener() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                java.net.ServerSocket ss = null;
                try {
                    ss = new java.net.ServerSocket(1455);
                    ss.setSoTimeout(600000);
                    while (!ss.isClosed()) {
                        final Socket s = ss.accept();
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream()));
                                    String reqLine = br.readLine();
                                    if (reqLine != null && reqLine.contains("code=")) {
                                        String[] reqParts = reqLine.split(" ");
                                        if (reqParts.length > 1) {
                                            String uriPart = reqParts[1];
                                            String query = uriPart.contains("?") ? uriPart.substring(uriPart.indexOf('?') + 1) : "";
                                            Map<String, String> qm = new HashMap<String, String>();
                                            for (String pair : query.split("&")) {
                                                String[] kv = pair.split("=");
                                                if (kv.length == 2) qm.put(java.net.URLDecoder.decode(kv[0], "UTF-8"), java.net.URLDecoder.decode(kv[1], "UTF-8"));
                                            }
                                            String code = qm.get("code");
                                            String state = qm.get("state");
                                            if (code != null && !code.isEmpty()) {
                                                CodexAuth.Flow matchedFlow = null;
                                                for (CodexAuth.Flow f : codexFlows.values()) {
                                                    if (f.state.equals(state)) {
                                                        matchedFlow = f;
                                                        break;
                                                    }
                                                }
                                                String verifier = matchedFlow != null ? matchedFlow.verifier : "";
                                                if (!verifier.isEmpty()) {
                                                    CodexAuth.SignInInfo info = CodexAuth.exchangeCode(code, verifier);
                                                    addCodexAccount(info, info.email);
                                                    if (matchedFlow != null) {
                                                        matchedFlow.status = "done";
                                                        matchedFlow.result = info;
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    OutputStream out = s.getOutputStream();
                                    String html = "<!DOCTYPE html><html><head><meta charset='utf-8'><title>All In - Codex 授权完成</title>"
                                            + "<style>body{background:#0b0d14;color:#f3f4f6;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;}"
                                            + ".box{text-align:center;padding:40px;background:#131622;border:1px solid #2e3440;border-radius:20px;box-shadow:0 10px 30px rgba(0,0,0,0.5);}"
                                            + "h1{color:#10a37f;font-size:1.6rem;margin-bottom:12px;}"
                                            + "p{color:#9ca3af;font-size:0.95rem;line-height:1.6;}"
                                            + "</style></head><body><div class='box'><h1>⚡ Codex 订阅连接成功！</h1>"
                                            + "<p>All In 掌上网关已成功接入您的 ChatGPT Codex 订阅。<br>您可以关闭此窗口，返回 All In 控制台开始使用。</p>"
                                            + "</div></body></html>";
                                    byte[] hBytes = html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                                    String head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Length: " + hBytes.length + "\r\nConnection: close\r\n\r\n";
                                    out.write(head.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                    out.write(hBytes);
                                    out.flush();
                                    s.close();
                                } catch (Throwable ignored) {}
                            }
                        }, "Codex-1455-Conn").start();
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (ss != null && !ss.isClosed()) {
                        try { ss.close(); } catch (Throwable ignored) {}
                    }
                }
            }
        }, "Codex-Callback-1455").start();
    }
}
