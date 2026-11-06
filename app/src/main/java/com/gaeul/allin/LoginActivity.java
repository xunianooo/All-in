package com.gaeul.allin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.*;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LoginActivity extends Activity {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // Native login work keeps the policy captured when this Activity starts.
    // Browser and WebView networking are controlled by the system, not this scope.
    private NetworkPolicy.Snapshot loginNetworkSnapshot;
    private Runnable scopedLoginTask(final Runnable task) {
        return () -> {
            try (NetworkPolicy.Scope scope = NetworkPolicy.use(loginNetworkSnapshot)) { task.run(); }
        };
    }
    private WebView webView;
    private TextView subtitleView;
    private boolean isPolling = false;
    private String currentState = "";
    private String currentProvider = "workbuddy";
    private String currentZCodeSite = "zai";
    private String zcodeFlowId = "";
    private String clineFlowId = "";
    /** GitHub Copilot / MiniMax 这类纯设备流的当前 flowId */
    private String deviceFlowId = "";
    /** 设备流的用户码（GitHub 的设备页不认 ?user_code=，只能等页面出来后用 JS 填） */
    private String deviceFlowCode = "";
    private String antigravityFlowId = "";
    private String traeFlowId = "";
    private String traeUrl = "";
    private long traeDeadline;
    private volatile int traeGeneration;
    private volatile boolean traeActive = true;
    private String codexFlowId = "";
    private String qoderFlowId = "";
    private String catpawSid = "";

    private static final String DEEPSEEK_ORIGIN = "https://chat.deepseek.com";
    // Evaluated only in the top-level page, with a second origin check inside JS.
    private static final String DEEPSEEK_EXTRACT_JS = "(function(){try{if(location.origin!=='https://chat.deepseek.com')return '';var raw=localStorage.getItem('userToken');if(!raw)return '';var obj=JSON.parse(raw);if(!obj||typeof obj!=='object'||Array.isArray(obj)||typeof obj.value!=='string'||!obj.value.trim())return '';return JSON.stringify({access_token:obj.value.trim()});}catch(e){return '';}})()";
    private boolean deepSeekActive = true, deepSeekExtracting, deepSeekSaving, deepSeekComplete;
    private int deepSeekGeneration, deepSeekAttempts, deepSeekFailures;
    private String deepSeekLastFingerprint = "";
    private long deepSeekRetryAfter;
    private static final int DEEPSEEK_MAX_ATTEMPTS = 450;
    private final Runnable deepSeekPoll = new Runnable() {
        @Override public void run() {
            if (!deepSeekActive || deepSeekComplete || deepSeekAttempts >= DEEPSEEK_MAX_ATTEMPTS) return;
            deepSeekAttempts++;
            extractDeepSeek(false);
            if (deepSeekActive && !deepSeekComplete && deepSeekAttempts < DEEPSEEK_MAX_ATTEMPTS)
                mainHandler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loginNetworkSnapshot = NetworkPolicy.capture();
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        String prov = getIntent().getStringExtra("provider");
        if (prov != null && !prov.isEmpty()) {
            currentProvider = prov;
        }
        String siteArg = getIntent().getStringExtra("site");
        if (siteArg != null && !siteArg.isEmpty()) {
            currentZCodeSite = "bigmodel".equals(siteArg) ? "bigmodel" : "zai";
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#090a0f"));

        // 顶部原生导航头
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(30, 36, 30, 20);
        header.setBackgroundColor(Color.parseColor("#131622"));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        Button backBtn = new Button(this);
        backBtn.setText("✕");
        backBtn.setTextColor(Color.WHITE);
        backBtn.setBackgroundColor(Color.TRANSPARENT);
        backBtn.setTextSize(18);
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        titleRow.addView(backBtn);

        TextView titleView = new TextView(this);
        titleView.setText(" 登录 " + getProviderDisplayName(currentProvider) + " 入池");
        titleView.setTextSize(16);
        titleView.setTextColor(Color.WHITE);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParam = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        titleView.setLayoutParams(titleParam);
        titleRow.addView(titleView);

        // 右上角“完成/提取”按钮
        Button doneBtn = new Button(this);
        doneBtn.setText("完成入池");
        doneBtn.setTextColor(Color.WHITE);
        doneBtn.setBackgroundColor(Color.parseColor("#10b981"));
        doneBtn.setTextSize(13);
        doneBtn.setPadding(24, 10, 24, 10);
        doneBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if ("trae-cn".equals(currentProvider)) checkTraeStatus();
                else extractTokenFromPage(true);
            }
        });
        titleRow.addView(doneBtn);

        header.addView(titleRow);

        subtitleView = new TextView(this);
        subtitleView.setText("📱 在下方页面完成手机验证码或账号登录后，点击右上角【完成入池】即可");
        subtitleView.setTextSize(11);
        subtitleView.setTextColor(Color.parseColor("#a5b4fc"));
        subtitleView.setPadding(10, 8, 0, 0);
        header.addView(subtitleView);

        TextView policyHint = new TextView(this);
        policyHint.setText("网络策略修改对新请求生效；当前原生登录需重新打开登录页才会使用新策略。浏览器和网页使用系统网络设置。");
        policyHint.setTextSize(11);
        policyHint.setTextColor(Color.parseColor("#a5b4fc"));
        policyHint.setPadding(10, 8, 0, 0);
        header.addView(policyHint);

        root.addView(header);

        try {
            GatewayAuthKeyHolder.init(getFilesDir());
        } catch (Throwable ignored) {
        }

        if ("trae-cn".equals(currentProvider)) {
            doneBtn.setText("检查状态");
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(30, 32, 30, 20);
            TextView instructions = new TextView(this);
            instructions.setText("请在系统浏览器完成 Trae 授权。遇到浏览器网络错误时，可以重新打开授权页；若授权已失效，请重新发起授权。");
            instructions.setTextColor(Color.WHITE);
            instructions.setTextSize(15);
            content.addView(instructions);
            Button reopen = new Button(this);
            reopen.setText("重新打开浏览器");
            reopen.setOnClickListener(v -> openTraeBrowser(traeUrl));
            content.addView(reopen);
            Button fresh = new Button(this);
            fresh.setText("重新发起授权");
            fresh.setOnClickListener(v -> initTraeAuth(true));
            content.addView(fresh);
            root.addView(content);
            setContentView(root);
            initTraeAuth(false);
            return;
        }

        // 原生 WebView
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        
        // 伪装现代移动端真实 User-Agent
        settings.setUserAgentString("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36");
        
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            try {
                CookieManager.class.getMethod("setAcceptThirdPartyCookies", WebView.class, boolean.class)
                    .invoke(CookieManager.getInstance(), webView, true);
            } catch (Exception ignored) {}
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if ("deepseek_web".equals(currentProvider)) {
                    deepSeekGeneration++;
                    if (!isDeepSeekOrigin(url)) view.stopLoading();
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if ("deepseek_web".equals(currentProvider) && isDeepSeekOrigin(url)
                        && isDeepSeekOrigin(view.getUrl()) && deepSeekActive && !deepSeekComplete) {
                    mainHandler.removeCallbacks(deepSeekPoll);
                    if (deepSeekAttempts < DEEPSEEK_MAX_ATTEMPTS) mainHandler.post(deepSeekPoll);
                }
                // 页面加载完成时不主动关闭页面，确保用户可以自如在网页内输入与登录
                if ("codex".equals(currentProvider)) {
                    detectCodexRegionBlock(url);
                }
                // GitHub 设备授权页忽略 ?user_code=（实测 2026-11），代码只能等页面出来再注入
                if (!deviceFlowCode.isEmpty() && url != null && url.contains("github.com/login/device")) {
                    autofillGitHubDeviceCode();
                }
            }

            /**
             * 拦截自定义协议。zcode 登录成功后会跳 zcode://oauth/callback?code=...
             * 若不拦截，WebView 会直接显示 ERR_UNKNOWN_URL_SCHEME 错误页，
             * 用户看到的就是"登上去就断了"。
             */
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if ("deepseek_web".equals(currentProvider)) return !isDeepSeekOrigin(url);
                if (url == null) return false;
                if (url.startsWith("zcode://")) {
                    onOAuthCallback(url);
                    return true;
                }
                // WebView 里识别到 OpenAI 的地区限制响应，换成中文说明页
                if (url.startsWith("magpie://codex-region-block")) {
                    showCodexRegionBlockPage();
                    return true;
                }
                return false;
            }
        });

        LinearLayout.LayoutParams webParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1.0f
        );
        root.addView(webView, webParams);

        setContentView(root);

        // 根据供应商启动不同通道
        routeLogin();
    }

    private String getProviderDisplayName(String id) {
        if ("workbuddy".equals(id)) return "WorkBuddy";
        if ("trae".equals(id)) return "字节 Trae";
        if ("catpaw".equals(id)) return "妙手 CatPaw (美团)";
        if ("workbuddy-ai".equals(id)) return "WorkBuddy AI (国际版)";
        if ("trae-cn".equals(id)) return "Trae CN";
        if ("deepseek_web".equals(id)) return "DeepSeek";
        if ("zhipu_web".equals(id)) return "智谱清言";
        if ("agentmore".equals(id)) return "AgentMore";
        if ("wenxin_web".equals(id)) return "百度文心";
        if ("meoo".equals(id)) return "秒悟 Meoo";
        if ("raccoon".equals(id)) return "商汤小浣熊";
        if ("runable".equals(id)) return "Runable";
        if ("dangbei".equals(id)) return "当贝 AI";
        if ("loomy".equals(id)) return "Loomy";
        if ("tabbit".equals(id)) return "Tabbit";
        if ("zcode".equals(id)) return "ZCode (智谱 GLM Coding)";
        if ("qoder_cn".equals(id)) return "Qoder CN (通义千问编程)";
        if ("codex".equals(id)) return "Codex (ChatGPT 订阅)";
        if ("cline".equals(id)) return "Cline (cline.bot)";
        if ("antigravity".equals(id)) return "Antigravity (Google 账号)";
        if ("github-copilot".equals(id)) return "GitHub Copilot";
        if ("minimax-code".equals(id)) return "MiniMax Code";
        return id;
    }

    private void routeLogin() {
        if ("codex".equals(currentProvider)) {
            initCodexAuth();
        } else if ("zcode".equals(currentProvider)) {
            initZCodeAuth();
        } else if ("cline".equals(currentProvider)) {
            initClineAuth();
        } else if ("antigravity".equals(currentProvider)) {
            initAntigravityAuth();
        } else if ("qoder_cn".equals(currentProvider)) {
            initQoderCnAuth();
        } else if ("github-copilot".equals(currentProvider)) {
            initDeviceFlowAuth("github-copilot", "GitHub Copilot", 15 * 60 * 1000L);
        } else if ("minimax-code".equals(currentProvider)) {
            initDeviceFlowAuth("minimax-code", "MiniMax Code", 10 * 60 * 1000L);
        } else if ("workbuddy".equals(currentProvider)) {
            initWorkBuddyAuth();
        } else if ("deepseek_web".equals(currentProvider)) {
            subtitleView.setText("请在官方页面登录，登录后自动入池，也可点击完成入池");
            webView.loadUrl("https://chat.deepseek.com/sign_in");
        } else if ("trae".equals(currentProvider)) {
            webView.loadUrl("https://www.trae.ai");
        } else if ("catpaw".equals(currentProvider)) {
            initCatPawAuth();
        } else if ("workbuddy-ai".equals(currentProvider)) {
            // 国际版只走官方 API Key 通道：去 keys 页建一把，粘进供应商卡片的 Key 输入框即可
            subtitleView.setText("在 WorkBuddy AI 个人中心创建 API Key，然后填到供应商卡片的 Key 输入框");
            webView.loadUrl("https://www.workbuddy.ai/profile/keys");
        } else if ("trae-cn".equals(currentProvider)) {
            initTraeAuth();
        } else if ("zhipu_web".equals(currentProvider)) {
            subtitleView.setText("智谱清言登录暂不支持");
            Toast.makeText(this, "智谱清言登录暂不支持", Toast.LENGTH_LONG).show();
            finish();
        } else if ("agentmore".equals(currentProvider)) {
            webView.loadUrl("https://agentmore.chatglm.cn/");
        } else if ("wenxin_web".equals(currentProvider)) {
            webView.loadUrl("https://wappass.baidu.com/passport/?login&u=https%3A%2F%2Fwenxin.baidu.com%2F");
        } else if ("meoo".equals(currentProvider)) {
            webView.loadUrl("https://meoo.com");
        } else if ("raccoon".equals(currentProvider)) {
            webView.loadUrl("https://xiaohuanxiong.com");
        } else if ("runable".equals(currentProvider)) {
            webView.loadUrl("https://runable.com");
        } else if ("dangbei".equals(currentProvider)) {
            webView.loadUrl("https://ai.dangbei.com/chat");
        } else if ("tabbit".equals(currentProvider)) {
            webView.loadUrl("https://tabbit.ai");
        } else {
            webView.loadUrl("https://copilot.tencent.com/login?platform=CLI");
        }
    }

    /** 妙手 CatPaw 登录：走官方 login-config 入口，sid 由网关侧轮询（不需要回跳端口） */
    private void initCatPawAuth() {
        subtitleView.setText("正在向妙手申请官方登录通道…");
        new Thread(scopedLoginTask(new Runnable() {
            @Override
            public void run() {
                try {
                    String api = "http://127.0.0.1:3425/api/auth/catpaw/init";
                    // 走 raw socket：明文 HTTP 在本机被系统禁止，HttpURLConnection 直接会被拦
                    String gwBody = gatewayGet(api);
                    if (gwBody == null) throw new java.io.IOException("连接网关失败（网关未启动？）");
                    final JSONObject res = new JSONObject(gwBody);
                    if (!res.optBoolean("ok", false)) {
                        final String err = res.optString("error", "初始化失败");
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                subtitleView.setText("初始化失败：" + err);
                                Toast.makeText(LoginActivity.this, err, Toast.LENGTH_LONG).show();
                            }
                        });
                        return;
                    }
                    catpawSid = res.optString("sid", "");
                    final String authUrl = res.optString("url", "");
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            subtitleView.setText("登录妙手账号，完成后会自动入池");
                            webView.loadUrl(authUrl);
                            startCatPawPolling();
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            String m = e.getMessage() == null ? "未知错误" : e.getMessage();
                            subtitleView.setText("初始化失败：" + m);
                            Toast.makeText(LoginActivity.this, m, Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        })).start();
    }

    private void startCatPawPolling() {
        isPolling = true;
        new Thread(scopedLoginTask(new Runnable() {
            @Override
            public void run() {
                long deadline = System.currentTimeMillis() + 3 * 60 * 1000L;
                while (isPolling && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(1200);
                        if (!isPolling) break;
                        String api = "http://127.0.0.1:3425/api/auth/catpaw/poll?sid="
                                + URLEncoder.encode(catpawSid, "UTF-8");
                        // 走 raw socket：明文 HTTP 在本机被系统禁止，HttpURLConnection 直接会被拦
                    String gwBody = gatewayGet(api);
                    if (gwBody == null) throw new java.io.IOException("连接网关失败（网关未启动？）");
                    final JSONObject res = new JSONObject(gwBody);
                        String status = res.optString("status", "");
                        if (res.optBoolean("ok", false) && "ready".equals(status)) {
                            isPolling = false;
                            final String who = res.optString("account", "");
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(LoginActivity.this,
                                            "🎉 妙手账号" + (who.isEmpty() ? "" : " [" + who + "]") + " 已入池",
                                            Toast.LENGTH_LONG).show();
                                    finish();
                                }
                            });
                            return;
                        }
                        if ("duplicate".equals(status) || "failed".equals(status)) {
                            isPolling = false;
                            final String err = res.optString("error", "登录未完成");
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    subtitleView.setText(err);
                                    Toast.makeText(LoginActivity.this, err, Toast.LENGTH_LONG).show();
                                }
                            });
                            return;
                        }
                    } catch (Exception ignored) {
                        // 单次轮询失败不算失败，妙手自己的客户端也是直接跳过
                    }
                }
                isPolling = false;
            }
        })).start();
    }

    private void initWorkBuddyAuth() {
        subtitleView.setText("正在连接腾讯官方申请授权通道...");
        new Thread(scopedLoginTask(new Runnable() {
            @Override
            public void run() {
                try {
                    String stateUrl = "https://copilot.tencent.com/v2/plugin/auth/state?platform=CLI";
                    HttpURLConnection conn = NetworkPolicy.openHttp(stateUrl);
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

                        JSONObject res = new JSONObject(sb.toString());
                        JSONObject data = res.optJSONObject("data");
                        if (data != null) {
                            final String authUrl = data.optString("authUrl");
                            currentState = data.optString("state");

                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    subtitleView.setText("输入手机号短信验证码或微信登录，完成后自动入池");
                                    webView.loadUrl(authUrl);
                                    startWorkBuddyPolling();
                                }
                            });
                            return;
                        }
                    }
                    showError("获取腾讯官方授权链接失败");
                } catch (final Exception e) {
                    showError("网络连接失败: " + e.getMessage());
                }
            }
        })).start();
    }

    /**
     * 收到 zcode://oauth/callback 回跳：授权已完成，立刻加速轮询一次。
     * 真正换 Key 仍由网关完成（poll 只需 flowId，不依赖这里的 code）。
     */
    private void onOAuthCallback(String url) {
        String code = "";
        try {
            android.net.Uri u = android.net.Uri.parse(url);
            code = u.getQueryParameter("code");
        } catch (Exception ignored) {
        }
        final boolean hasCode = code != null && !code.isEmpty();
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                subtitleView.setText(hasCode ? "授权成功，正在换取 API Key…" : "授权回调已收到，正在确认登录状态…");
                Toast.makeText(LoginActivity.this, "✅ 授权成功，正在入池…", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * ZCode 登录：原生侧开一个 cli 流程拿到授权页，WebView 只负责让用户登录，
     * 之后原生侧轮询，成功时由网关把 <id>.<secret> 写进供应商池。
     */
    private void initZCodeAuth() {
        final String siteName = "bigmodel".equals(currentZCodeSite) ? "BigModel（智谱）" : "Z.ai";
        subtitleView.setText("正在向 ZCode 申请 " + siteName + " 登录通道…");
        new Thread(scopedLoginTask(new Runnable() {
            @Override
            public void run() {
                try {
                    String api = "http://127.0.0.1:3425/api/auth/zcode/init?site="
                            + URLEncoder.encode(currentZCodeSite, "UTF-8");
                    // 走 raw socket：明文 HTTP 在本机被系统禁止，HttpURLConnection 直接会被拦
                    String gwBody = gatewayGet(api);
                    if (gwBody == null) throw new java.io.IOException("连接网关失败（网关未启动？）");
                    final JSONObject res = new JSONObject(gwBody);
                    if (!res.optBoolean("ok", false)) {
                        final String err = res.optString("error", "初始化失败");
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                subtitleView.setText("初始化失败：" + err);
                                Toast.makeText(LoginActivity.this, err, Toast.LENGTH_LONG).show();
                            }
                        });
                        return;
                    }
                    zcodeFlowId = res.optString("flowId", "");
                    final String authUrl = res.optString("url", "");
                    final long interval = Math.max(res.optLong("intervalMs", 2000), 1500);

                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            subtitleView.setText("请在下方登录 " + siteName + "，成功后自动入池");
                            webView.loadUrl(authUrl);
                            startZCodePolling(interval);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            subtitleView.setText("连接网关失败：" + e.getMessage());
                        }
                    });
                }
            }
        })).start();
    }

    // The generation belongs to this Activity; stale workers may never change a new flow.
    private boolean traeCurrent(int generation) {
        return traeActive && generation == traeGeneration && !isFinishing();
    }

    private void initTraeAuth() { initTraeAuth(false); }

    private void initTraeAuth(boolean fresh) {
        final int generation = ++traeGeneration;
        android.content.SharedPreferences prefs = getSharedPreferences("magpie", MODE_PRIVATE);
        boolean restoring = !prefs.getString("trae_flow_id", "").isEmpty()
                && System.currentTimeMillis() - prefs.getLong("trae_flow_at", 0) < 5 * 60 * 1000L;
        traeUrl = "";
        subtitleView.setText("正在申请 Trae 授权…");
        new Thread(scopedLoginTask(() -> {
            try {
                String body = gatewayGet("http://127.0.0.1:3425/api/auth/trae-cn/init" + (fresh ? "?fresh=1" : ""));
                if (body == null) throw new java.io.IOException("无法连接本机网关");
                JSONObject result = new JSONObject(body);
                if (!result.optBoolean("ok", false)) throw new java.io.IOException(result.optString("error", "初始化失败"));
                final String id = result.optString("flowId", "");
                final String url = result.optString("url", "");
                if (id.isEmpty() || !validTraeUrl(url)) throw new java.io.IOException("授权链接无效");
                long now = System.currentTimeMillis();
                long returnedDeadline = result.optLong("deadline", 0);
                if (returnedDeadline > 0 && returnedDeadline < 100000000000L) returnedDeadline *= 1000L;
                long localDeadline = restoring && !fresh ? prefs.getLong("trae_flow_at", now) + 5 * 60 * 1000L : now + 5 * 60 * 1000L;
                final long deadline = returnedDeadline > 0 ? Math.min(returnedDeadline, localDeadline) : localDeadline;
                final boolean resumed = result.optBoolean("resumed", false);
                mainHandler.post(() -> {
                    if (!traeCurrent(generation)) return;
                    traeFlowId = id;
                    traeUrl = url;
                    traeDeadline = deadline;
                    prefs.edit().putString("trae_flow_id", id).putLong("trae_flow_at", deadline - 5 * 60 * 1000L).apply();
                    subtitleView.setText("请在系统浏览器完成 Trae 登录，点此重新打开授权页");
                    subtitleView.setOnClickListener(v -> openTraeBrowser(traeUrl));
                    if (fresh || (!restoring && !resumed)) openTraeBrowser(url);
                    startTraePolling(generation, id, deadline);
                });
            } catch (Exception e) {
                mainHandler.post(() -> { if (traeCurrent(generation)) subtitleView.setText("Trae 登录初始化失败，请重新发起授权"); });
            }
        })).start();
    }

    private boolean validTraeUrl(String url) {
        try {
            android.net.Uri uri = android.net.Uri.parse(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && "www.trae.cn".equalsIgnoreCase(uri.getHost())
                    && "/authorization".equals(uri.getEncodedPath()) && uri.getPort() == -1
                    && uri.getUserInfo() == null;
        } catch (Exception e) { return false; }
    }

    private void openTraeBrowser(String url) {
        if (!validTraeUrl(url) || System.currentTimeMillis() >= traeDeadline) {
            subtitleView.setText("授权链接不可用或已过期，请重新发起授权");
            return;
        }
        try { startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))); }
        catch (Throwable ignored) { subtitleView.setText("无法打开系统浏览器，请检查浏览器后重试"); }
    }

    private void clearTraeFlow() {
        traeFlowId = "";
        traeUrl = "";
        traeDeadline = 0;
        getSharedPreferences("magpie", MODE_PRIVATE).edit().remove("trae_flow_id").remove("trae_flow_at").apply();
    }

    private void checkTraeStatus() {
        if (traeFlowId.isEmpty()) {
            subtitleView.setText("没有可检查的授权，请重新发起授权");
            return;
        }
        int generation = ++traeGeneration;
        subtitleView.setText("正在检查授权状态…");
        startTraePolling(generation, traeFlowId, traeDeadline);
    }

    private void startTraePolling(final int generation, final String id, final long deadline) {
        new Thread(scopedLoginTask(() -> {
            int failures = 0;
            while (traeCurrent(generation) && System.currentTimeMillis() < deadline) {
                try {
                    String body = gatewayGet("http://127.0.0.1:3425/api/auth/trae-cn/poll?flowId=" + URLEncoder.encode(id, "UTF-8"));
                    if (body == null) throw new java.io.IOException("无法连接网关");
                    JSONObject r = new JSONObject(body);
                    String status = r.optString("status", "");
                    if (!r.optBoolean("ok", false) || "failed".equals(status) || "binding_failed".equals(status)
                            || "expired".equals(status) || "closed".equals(status) || "missing".equals(status)
                            || "error".equals(status) || status.isEmpty()) {
                        final String detail = r.optString("error", r.optString("hint", "授权已失效"));
                        mainHandler.post(() -> { if (traeCurrent(generation)) { clearTraeFlow(); subtitleView.setText("授权未完成：" + detail + "，请重新发起授权"); } });
                        return;
                    }
                    failures = 0;
                    if ("ready".equals(status)) {
                        final String hint = r.optString("hint", "");
                        mainHandler.post(() -> { if (traeCurrent(generation)) { clearTraeFlow(); Toast.makeText(this, "Trae 账号已入池" + (hint.isEmpty() ? "" : "：" + hint), Toast.LENGTH_LONG).show(); finish(); } });
                        return;
                    }
                    if ("binding".equals(status)) mainHandler.post(() -> { if (traeCurrent(generation)) subtitleView.setText("登录成功，正在绑定设备…"); });
                    else mainHandler.post(() -> { if (traeCurrent(generation)) subtitleView.setText("等待 Trae 授权，点此重新打开授权页"); });
                } catch (Exception e) {
                    if (++failures >= 3) {
                        mainHandler.post(() -> { if (traeCurrent(generation)) subtitleView.setText("连续连接网关失败，请检查网络后点检查状态或重新发起授权"); });
                        return;
                    }
                }
                try { Thread.sleep(2500); } catch (InterruptedException e) { return; }
            }
            mainHandler.post(() -> { if (traeCurrent(generation)) { clearTraeFlow(); subtitleView.setText("授权等待超时，请重新发起授权"); } });
        })).start();
    }

    /**
     * Antigravity (Google Code Assist) 登录。
     * Google 不允许 App 内 WebView 做 OAuth（disallowed_useragent），
     * 所以走系统浏览器 + 本机 loopback 回调（网关在 1456 端口接）。
     */
    private void initAntigravityAuth() {
        subtitleView.setText("正在向网关申请 Google 授权链接…");
        new Thread(scopedLoginTask(new Runnable() {
            @Override
            public void run() {
                try {
                    String api = "http://127.0.0.1:3425/api/auth/antigravity/init";
                    String gwBody = gatewayGet(api);
                    if (gwBody == null) throw new java.io.IOException("连接网关失败（网关未启动？）");
                    final JSONObject res = new JSONObject(gwBody);
                    if (!res.optBoolean("ok", false)) {
                        final String err = res.optString("error", "初始化失败");
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                subtitleView.setText("初始化失败：" + err);
                                Toast.makeText(LoginActivity.this, err, Toast.LENGTH_LONG).show();
                            }
                        });
                        return;
                    }
                    antigravityFlowId = res.optString("flowId", "");
                    try {
                        getSharedPreferences("magpie", MODE_PRIVATE).edit()
                                .putString("antigravity_flow_id", antigravityFlowId).apply();
                    } catch (Throwable ignored) {
                    }
                    final String authUrl = res.optString("authUrl", "");
                    final boolean resumed = res.optBoolean("resumed", false);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (resumed) {
                                subtitleView.setText("检测到进行中的授权，正在等待结果…（点这里重新打开授权页）");
                            } else {
                                subtitleView.setText("已在系统浏览器打开 Google 授权页；授权完成后会自动回到本页提示入池结果");
                            }
                            subtitleView.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    try {
                                        android.content.Intent i = new android.content.Intent(
                                                android.content.Intent.ACTION_VIEW, android.net.Uri.parse(authUrl));
                                        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                                        startActivity(i);
                                    } catch (Throwable t) {
                                        webView.loadUrl(authUrl);
                                    }
                                }
                            });
                            if (!resumed) {
                                try {
                                    android.content.Intent i = new android.content.Intent(
                                            android.content.Intent.ACTION_VIEW, android.net.Uri.parse(authUrl));
                                    i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                                    startActivity(i);
                                } catch (Throwable t) {
                                    webView.loadUrl(authUrl);
                                }
                            }
                            startAntigravityPolling();
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            String m = e.getMessage() == null ? "未知错误" : e.getMessage();
                            subtitleView.setText("初始化失败：" + m);
                            Toast.makeText(LoginActivity.this, m, Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        })).start();
    }

    private void startAntigravityPolling() {
        if (isPolling) return;   // Activity 重建会重复调，避免两个线程抢同一个 flow
        isPolling = true;
        new Thread(scopedLoginTask(new Runnable() {
            @Override
            public void run() {
                long deadline = System.currentTimeMillis() + 15 * 60 * 1000L;
                while (isPolling && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(2500);
                        if (!isPolling) break;
                        String api = "http://127.0.0.1:3425/api/auth/antigravity/poll?flowId="
                                + URLEncoder.encode(antigravityFlowId, "UTF-8");
                        String gwBody = gatewayGet(api);
                        if (gwBody == null) continue;
                        final JSONObject res = new JSONObject(gwBody);
                        String status = res.optString("status", "");
                        if (res.optBoolean("waiting", false)) {
                            // 服务端还没有任何流程，继续等（最多 15 分钟）
                            continue;
                        }
                        if (res.optBoolean("ok", false) && "done".equals(status)) {
                            isPolling = false;
                            try {
                                getSharedPreferences("magpie", MODE_PRIVATE).edit()
                                        .remove("antigravity_flow_id").apply();
                            } catch (Throwable ignored) {
                            }
                            final String who = res.optString("account", "");
                            final String proj = res.optString("project", "");
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(LoginActivity.this,
                                            "🎉 Antigravity 账号" + (who.isEmpty() ? "" : " [" + who + "]")
                                                    + " 已入池" + (proj.isEmpty() ? "" : "（项目 " + proj + "）"),
                                            Toast.LENGTH_LONG).show();
                                    finish();
                                }
                            });
                            break;
                        } else if (!res.optBoolean("ok", false) && "failed".equals(status)) {
                            isPolling = false;
                            final String err = res.optString("error", "授权失败");
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    subtitleView.setText("授权失败：" + err);
                                    Toast.makeText(LoginActivity.this, err, Toast.LENGTH_LONG).show();
                                }
                            });
                            break;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        })).start();
    }

    /**
     * 把 user_code 填进 GitHub 的设备授权页（8 个单字符输入框，第 5 个位置是分隔横线，
     * 所以 id 是 user-code-0..3 与 user-code-5..8）。
     * 这页是 React 渲染的，直接改 value 不生效，必须用原型上的 setter + input 事件。
     */
    private void autofillGitHubDeviceCode() {
        final String code = deviceFlowCode.replace("-", "");
        if (code.isEmpty()) return;
        String js = "(function(){var c='" + code + "';var idx=[0,1,2,3,5,6,7,8];"
                + "for(var i=0;i<idx.length&&i<c.length;i++){"
                + "var el=document.getElementById('user-code-'+idx[i]);if(!el)continue;"
                + "var s=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;"
                + "s.call(el,c.charAt(i));el.dispatchEvent(new Event('input',{bubbles:true}));}"
                + "})();";
        try {
            // evaluateJavascript 是 API19+；本项目 android.jar 是精简版，只能反射
            java.lang.reflect.Method m = WebView.class.getMethod("evaluateJavascript",
                    String.class, Class.forName("android.webkit.ValueCallback"));
            m.invoke(webView, js, null);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 通用设备流登录：GitHub Copilot / MiniMax Code 共用。
     *
     * 思路和 Cline 一致（原生发起、WebView 只负责让用户确认），但这两家都是
     * 「显示一个 user_code，用户在自己的页面里输」的形态，所以这里：
     *   1. 向网关 /api/auth/{provider}/init 申请设备码
     *   2. 副标题直接写用户码，并提供点击用外部浏览器打开（更稳）
     *   3. 同时把验证页载入 WebView（GitHub 支持 ?user_code= 预填）
     *   4. 后台轮询 /api/auth/{provider}/poll，ready 即入池并关页
     */
    private void initDeviceFlowAuth(final String providerId, final String display, final long hardDeadlineMs) {
        subtitleView.setText("正在向 " + display + " 申请设备授权…");
        new Thread(scopedLoginTask(new Runnable() {
            @Override
            public void run() {
                try {
                    String api = "http://127.0.0.1:3425/api/auth/" + providerId + "/init";
                    String gwBody = gatewayGet(api);
                    if (gwBody == null) throw new java.io.IOException("连接网关失败（网关未启动？）");
                    final JSONObject res = new JSONObject(gwBody);
                    if (!res.optBoolean("ok", false)) {
                        final String err = res.optString("error", "初始化失败");
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                subtitleView.setText("初始化失败：" + err);
                                Toast.makeText(LoginActivity.this, err, Toast.LENGTH_LONG).show();
                            }
                        });
                        return;
                    }
                    deviceFlowId = res.optString("flowId", "");
                    deviceFlowCode = res.optString("userCode", "");
                    final String authUrl = res.optString("url", "");
                    final String userCode = deviceFlowCode;
                    final long interval = Math.max(res.optLong("intervalMs", 5000), 2000);

                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            String hint = providerId.startsWith("github")
                                    ? "（GitHub 页会自动填好）" : "（在该页里确认这个码）";
                            subtitleView.setText("用户码 " + userCode + hint + " · 点这里改用外部浏览器");
                            subtitleView.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    try {
                                        android.content.Intent i = new android.content.Intent(
                                                android.content.Intent.ACTION_VIEW, android.net.Uri.parse(authUrl));
                                        startActivity(i);
                                    } catch (Throwable t) {
                                        Toast.makeText(LoginActivity.this,
                                                "无法打开外部浏览器: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                                    }
                                }
                            });
                            if (!authUrl.isEmpty()) webView.loadUrl(authUrl);
                            startDeviceFlowPolling(providerId, display, interval, hardDeadlineMs);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            String m = e.getMessage() == null ? "未知错误" : e.getMessage();
                            subtitleView.setText("初始化失败：" + m);
                            Toast.makeText(LoginActivity.this, m, Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        })).start();
    }

    private void startDeviceFlowPolling(final String providerId, final String display,
                                        final long intervalMs, final long hardDeadlineMs) {
        isPolling = true;
        new Thread(scopedLoginTask(new Runnable() {
            @Override
            public void run() {
                long deadline = System.currentTimeMillis() + hardDeadlineMs;
                while (isPolling && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(intervalMs);
                        if (!isPolling) break;
                        String api = "http://127.0.0.1:3425/api/auth/" + providerId + "/poll?flowId="
                                + URLEncoder.encode(deviceFlowId, "UTF-8");
                        String gwBody = gatewayGet(api);
                        if (gwBody == null) continue;
                        JSONObject res = new JSONObject(gwBody);
                        String status = res.optString("status", "");

                        if (res.optBoolean("ok", false) && "ready".equals(status)) {
                            isPolling = false;
                            final String who = res.optString("account", "");
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(LoginActivity.this,
                                            "🎉 " + display + " 账号" + (who.isEmpty() ? "" : " [" + who + "]") + " 已入池",
                                            Toast.LENGTH_LONG).show();
                                    finish();
                                }
                            });
                            break;
                        }
                        if ("duplicate".equals(status)) {
                            isPolling = false;
                            final String dupErr = res.optString("error", "池内已存在同名账号");
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(LoginActivity.this, dupErr, Toast.LENGTH_LONG).show();
                                    finish();
                                }
                            });
                            break;
                        }
                        if (!res.optBoolean("ok", false) && "failed".equals(status)) {
                            isPolling = false;
                            final String err = res.optString("error", "授权失败");
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    subtitleView.setText("授权失败：" + err);
                                    Toast.makeText(LoginActivity.this, err, Toast.LENGTH_LONG).show();
                                }
                            });
                            break;
                        }
                    } catch (Exception ignored) {
                        // 单次轮询失败不算失败
                    }
                }
                isPolling = false;
            }
        })).start();
    }

    /**
     * Cline (cline.bot) WorkOS 设备流登录：
     * 原生向网关 /api/auth/cline/init 发起授权，WebView 打开 WorkOS 验证页，
     * 后台轮询 /api/auth/cline/poll，用户授权后自动换取长期凭据并入池。
     */
    private void initClineAuth() {
        subtitleView.setText("正在向 Cline 申请设备授权…");
        new Thread(scopedLoginTask(new Runnable() {
            @Override
            public void run() {
                try {
                    String api = "http://127.0.0.1:3425/api/auth/cline/init";
                    // 走 raw socket：明文 HTTP 在本机被系统禁止，HttpURLConnection 直接会被拦
                    String gwBody = gatewayGet(api);
                    if (gwBody == null) throw new java.io.IOException("连接网关失败（网关未启动？）");
                    final JSONObject res = new JSONObject(gwBody);
                    if (!res.optBoolean("ok", false)) {
                        final String err = res.optString("error", "初始化失败");
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                subtitleView.setText("初始化失败：" + err);
                                Toast.makeText(LoginActivity.this, err, Toast.LENGTH_LONG).show();
                            }
                        });
                        return;
                    }
                    clineFlowId = res.optString("flowId", "");
                    final String authUrl = res.optString("url", "");
                    final String userCode = res.optString("userCode", "");
                    final long interval = Math.max(res.optLong("intervalMs", 5000), 2000);

                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            subtitleView.setText("请在浏览器中确认授权（用户码: " + userCode + "）[点击在此打开外部浏览器]");
                            subtitleView.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    try {
                                        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(authUrl));
                                        startActivity(i);
                                    } catch (Throwable t) {
                                        Toast.makeText(LoginActivity.this, "无法打开外部浏览器: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                                    }
                                }
                            });
                            webView.loadUrl(authUrl);
                            startClinePolling(interval);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            String m = e.getMessage() == null ? "未知错误" : e.getMessage();
                            subtitleView.setText("初始化失败：" + m);
                            Toast.makeText(LoginActivity.this, m, Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        })).start();
    }

    private void startClinePolling(final long intervalMs) {
        isPolling = true;
        new Thread(scopedLoginTask(new Runnable() {
            @Override
            public void run() {
                long deadline = System.currentTimeMillis() + 15 * 60 * 1000L;
                while (isPolling && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(intervalMs);
                        if (!isPolling) break;

                        String api = "http://127.0.0.1:3425/api/auth/cline/poll?flowId="
                                + URLEncoder.encode(clineFlowId, "UTF-8");
                        // 走 raw socket：明文 HTTP 在本机被系统禁止，HttpURLConnection 直接会被拦
                    String gwBody = gatewayGet(api);
                    if (gwBody == null) throw new java.io.IOException("连接网关失败（网关未启动？）");
                    final JSONObject res = new JSONObject(gwBody);
                        String status = res.optString("status", "");

                        if (res.optBoolean("ok", false) && "ready".equals(status)) {
                            isPolling = false;
                            final String who = res.optString("account", "");
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(LoginActivity.this,
                                            "🎉 Cline 账号" + (who.isEmpty() ? "" : " [" + who + "]") + " 已入池",
                                            Toast.LENGTH_LONG).show();
                                    finish();
                                }
                            });
                            break;
                        } else if ("duplicate".equals(status)) {
                            isPolling = false;
                            final String dupErr = res.optString("error", "池内已存在同名账号");
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(LoginActivity.this, dupErr, Toast.LENGTH_LONG).show();
                                    finish();
                                }
                            });
                            break;
                        } else if (!res.optBoolean("ok", false) && "failed".equals(status)) {
                            isPolling = false;
                            final String err = res.optString("error", "授权失败");
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    subtitleView.setText("授权失败：" + err);
                                    Toast.makeText(LoginActivity.this, err, Toast.LENGTH_LONG).show();
                                }
                            });
                            break;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        })).start();
    }

    /**
     * Qoder CN 设备流登录：和 ZCode 一样，原生侧开流程，WebView 只负责让用户登录授权。
     * Qoder 的登录页不跳自定义协议（没有 redirect_uri），所以全靠后台轮询感知结果。
     */
    private void initQoderCnAuth() {
        subtitleView.setText("正在向 Qoder CN 申请登录通道…");
        new Thread(scopedLoginTask(new Runnable() {
            @Override
            public void run() {
                try {
                    String api = "http://127.0.0.1:3425/api/auth/qoder_cn/init";
                    // 走 raw socket：明文 HTTP 在本机被系统禁止，HttpURLConnection 直接会被拦
                    String gwBody = gatewayGet(api);
                    if (gwBody == null) throw new java.io.IOException("连接网关失败（网关未启动？）");
                    final JSONObject res = new JSONObject(gwBody);
                    if (!res.optBoolean("ok", false)) {
                        final String err = res.optString("error", "初始化失败");
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                subtitleView.setText("初始化失败：" + err);
                                Toast.makeText(LoginActivity.this, err, Toast.LENGTH_LONG).show();
                            }
                        });
                        return;
                    }
                    qoderFlowId = res.optString("flowId", "");
                    final String authUrl = res.optString("url", "");
                    final long interval = Math.max(res.optLong("intervalMs", 2000), 1500);

                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            subtitleView.setText("请登录 Qoder CN 并授权本设备，成功后自动入池");
                            webView.loadUrl(authUrl);
                            startQoderCnPolling(interval);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            String m = e.getMessage() == null ? "未知错误" : e.getMessage();
                            subtitleView.setText("初始化失败：" + m);
                            Toast.makeText(LoginActivity.this, m, Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        })).start();
    }

    private void startQoderCnPolling(final long intervalMs) {
        isPolling = true;
        new Thread(scopedLoginTask(new Runnable() {
            @Override
            public void run() {
                long deadline = System.currentTimeMillis() + 15 * 60 * 1000L;
                while (isPolling && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(intervalMs);
                        if (!isPolling) break;

                        String api = "http://127.0.0.1:3425/api/auth/qoder_cn/poll?flowId="
                                + URLEncoder.encode(qoderFlowId, "UTF-8");
                        // 走 raw socket：明文 HTTP 在本机被系统禁止，HttpURLConnection 直接会被拦
                    String gwBody = gatewayGet(api);
                    if (gwBody == null) throw new java.io.IOException("连接网关失败（网关未启动？）");
                    final JSONObject res = new JSONObject(gwBody);
                        String status = res.optString("status", "");

                        if (res.optBoolean("ok", false) && "ready".equals(status)) {
                            isPolling = false;
                            final String who = res.optString("account", "");
                            final boolean deviceChat = res.optBoolean("deviceChat", false);
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(LoginActivity.this,
                                            "🎉 Qoder CN 账号" + (who.isEmpty() ? "" : " [" + who + "]") + " 已入池"
                                                    + (deviceChat ? "（设备令牌直连）" : ""),
                                            Toast.LENGTH_LONG).show();
                                    finish();
                                }
                            });
                            return;
                        }

                        if ("duplicate".equals(status)) {
                            isPolling = false;
                            final String err = res.optString("error", "该账号已在池内");
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    subtitleView.setText(err);
                                    Toast.makeText(LoginActivity.this, err, Toast.LENGTH_LONG).show();
                                }
                            });
                            return;
                        }

                        if ("failed".equals(status)) {
                            isPolling = false;
                            final String err = res.optString("error", "登录失败");
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    subtitleView.setText(err);
                                    Toast.makeText(LoginActivity.this, err, Toast.LENGTH_LONG).show();
                                }
                            });
                            return;
                        }
                    } catch (Exception ignored) {
                    }
                }
                if (isPolling) {
                    isPolling = false;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            subtitleView.setText("登录超时，请返回后重新发起");
                        }
                    });
                }
            }
        })).start();
    }

    private void startZCodePolling(final long intervalMs) {
        isPolling = true;
        new Thread(scopedLoginTask(new Runnable() {
            @Override
            public void run() {
                long deadline = System.currentTimeMillis() + 6 * 60 * 1000L;
                while (isPolling && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(intervalMs);
                        if (!isPolling) break;

                        String api = "http://127.0.0.1:3425/api/auth/zcode/poll?flowId="
                                + URLEncoder.encode(zcodeFlowId, "UTF-8");
                        // 走 raw socket：明文 HTTP 在本机被系统禁止，HttpURLConnection 直接会被拦
                    String gwBody = gatewayGet(api);
                    if (gwBody == null) throw new java.io.IOException("连接网关失败（网关未启动？）");
                    final JSONObject res = new JSONObject(gwBody);
                        String status = res.optString("status", "");

                        if (res.optBoolean("ok", false) && "ready".equals(status)) {
                            isPolling = false;
                            final String plan = res.optString("plan", "");
                            final String who = res.optString("account", "");
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    String tail = plan.isEmpty() ? "" : "（" + plan + "）";
                                    Toast.makeText(LoginActivity.this,
                                            "🎉 ZCode 账号" + (who.isEmpty() ? "" : " [" + who + "]")
                                                    + " 已入池" + tail,
                                            Toast.LENGTH_LONG).show();
                                    finish();
                                }
                            });
                            return;
                        }

                        if ("failed".equals(status) || "no-plan".equals(status) || "duplicate".equals(status)) {
                            isPolling = false;
                            final String err = res.optString("error", "登录失败");
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    subtitleView.setText(err);
                                    Toast.makeText(LoginActivity.this, err, Toast.LENGTH_LONG).show();
                                }
                            });
                            return;
                        }
                    } catch (Exception ignored) {
                    }
                }
                if (isPolling) {
                    isPolling = false;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            subtitleView.setText("登录超时，请返回后重新发起");
                        }
                    });
                }
            }
        })).start();
    }

    private void startWorkBuddyPolling() {
        isPolling = true;
        new Thread(scopedLoginTask(new Runnable() {
            @Override
            public void run() {
                int count = 0;
                while (isPolling && count < 100) {
                    count++;
                    try {
                        Thread.sleep(2500);
                        if (!isPolling) break;

                        String tokenUrl = "https://copilot.tencent.com/v2/plugin/auth/token?state=" + URLEncoder.encode(currentState, "UTF-8");
                        HttpURLConnection conn = NetworkPolicy.openHttp(tokenUrl);
                        conn.setRequestMethod("GET");
                        conn.setConnectTimeout(10000);
                        conn.setReadTimeout(10000);

                        int code = conn.getResponseCode();
                        InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
                        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                        br.close();

                        JSONObject resJson = new JSONObject(sb.toString());
                        int respCode = resJson.optInt("code", -1);

                        if (respCode == 0) {
                            JSONObject data = resJson.optJSONObject("data");
                            String token = "";
                            String username = "WorkBuddy用户";

                            if (data != null) {
                                token = data.optString("accessToken", "");
                                if (token.isEmpty()) token = data.optString("access_token", "");
                                if (token.isEmpty()) token = data.optString("token", "");

                                username = data.optString("name", "");
                                if (username.isEmpty()) username = data.optString("nickname", "");
                                if (username.isEmpty()) username = data.optString("userId", "WorkBuddy用户");
                            }

                            if (!token.isEmpty()) {
                                isPolling = false;
                                saveAccountToGateway(currentProvider, username, token);

                                final String finalUser = username;
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        Toast.makeText(LoginActivity.this, "🎉 " + getProviderDisplayName(currentProvider) + " 账号 [" + finalUser + "] 已成功加入网关池！", Toast.LENGTH_LONG).show();
                                        finish();
                                    }
                                });
                                return;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        })).start();
    }

    private void extractTokenFromPage(final boolean isManualClick) {
        if ("deepseek_web".equals(currentProvider)) { extractDeepSeek(isManualClick); return; }
        if ("zhipu_web".equals(currentProvider)) return;
        // 1. 原生 CookieManager 扫描（直接从底层的 Cookie 数据库提取，包含 HttpOnly 与跨子域名 Cookie）
        String tokenFound = "";
        String userFound = getProviderDisplayName(currentProvider) + "用户";

        try {
            CookieManager cm = CookieManager.getInstance();
            if (cm != null) {
                try {
                    CookieManager.class.getMethod("flush").invoke(cm);
                } catch (Exception ignored) {}
                StringBuilder sb = new StringBuilder();
                String cur = (webView != null && webView.getUrl() != null) ? webView.getUrl() : "";
                if (!cur.isEmpty()) {
                    String c = cm.getCookie(cur);
                    if (c != null) sb.append(c).append("; ");
                }
                if ("raccoon".equals(currentProvider)) {
                    String c1 = cm.getCookie("https://xiaohuanxiong.com");
                    if (c1 != null) sb.append(c1).append("; ");
                    String c2 = cm.getCookie("https://www.xiaohuanxiong.com");
                    if (c2 != null) sb.append(c2).append("; ");
                    String c3 = cm.getCookie("https://api.xiaohuanxiong.com");
                    if (c3 != null) sb.append(c3).append("; ");

                } else if ("catpaw".equals(currentProvider)) {
                    String c = cm.getCookie("https://ai.catpaw.meituan.com");
                    if (c != null) sb.append(c).append("; ");

                }
                String allCookies = sb.toString();

                if (!allCookies.isEmpty()) {
                    // A. 专门针对小浣熊 xhx_token 和 user_name
                    if ("raccoon".equals(currentProvider)) {
                        Matcher mToken = Pattern.compile("(?:^|;\\s*)xhx_token=([^;]+)").matcher(allCookies);
                        if (mToken.find()) {
                            tokenFound = mToken.group(1).trim();
                        }
                        Matcher mUser = Pattern.compile("(?:^|;\\s*)user_name=([^;]+)").matcher(allCookies);
                        if (mUser.find()) {
                            try {
                                userFound = URLDecoder.decode(mUser.group(1).trim(), "UTF-8");
                            } catch (Exception e) {
                                userFound = mUser.group(1).trim();
                            }
                        }
                    }

                    // B. 通用 JWT 扫描（匹配任何 Cookie 中的标准 JWT 格式）
                    if (tokenFound.isEmpty()) {
                        Matcher mJwt = Pattern.compile("eyJ[a-zA-Z0-9_\\-]{20,}\\.[a-zA-Z0-9_\\-]{20,}\\.[a-zA-Z0-9_\\-]{20,}").matcher(allCookies);
                        if (mJwt.find()) {
                            tokenFound = mJwt.group(0).trim();
                        }
                    }

                    // C. 常见 token 命名扫描
                    if (tokenFound.isEmpty()) {
                        Matcher mKeys = Pattern.compile("(?:^|;\\s*)(?:xhx_token|access_token|accessToken|user_token|auth_token|token|session_token)=([^;]+)", Pattern.CASE_INSENSITIVE).matcher(allCookies);
                        if (mKeys.find()) {
                            String val = mKeys.group(1).trim();
                            if (val.length() >= 25 && !val.contains("distinct_id") && !val.startsWith("GA1.")) {
                                tokenFound = val;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // 如果在原生 CookieManager 中已经成功识别，直接保存并退出
        if (tokenFound.length() >= 20) {
            saveAccountToGateway(currentProvider, userFound, tokenFound);
            return;
        }

        // 2. 第二重保障：通过 evaluateJavascript 扫描 LocalStorage 和 SessionStorage
        String js = "(function(){"
            + "function findJwt(str){"
            + "  if(!str) return '';"
            + "  let s = typeof str === 'string' ? str : JSON.stringify(str);"
            + "  let m = s.match(/\\beyJ[a-zA-Z0-9_\\-]{20,}\\.[a-zA-Z0-9_\\-]{20,}\\.[a-zA-Z0-9_\\-]{20,}\\b/);"
            + "  return m ? m[0] : '';"
            + "}"
            + "let t = '';"
            + "try {"
            + "  for(let i=0; i<localStorage.length; i++){"
            + "    let k = localStorage.key(i);"
            + "    let v = localStorage.getItem(k);"
            + "    t = findJwt(v) || findJwt(k);"
            + "    if(t) return t;"
            + "  }"
            + "} catch(e){}"
            + "try {"
            + "  for(let i=0; i<sessionStorage.length; i++){"
            + "    let k = sessionStorage.key(i);"
            + "    let v = sessionStorage.getItem(k);"
            + "    t = findJwt(v) || findJwt(k);"
            + "    if(t) return t;"
            + "  }"
            + "} catch(e){}"
            + "return '';"
            + "})()";

        if (android.os.Build.VERSION.SDK_INT >= 19) {
            try {
                java.lang.reflect.Method m = WebView.class.getMethod("evaluateJavascript", String.class, ValueCallback.class);
                m.invoke(webView, js, new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        String clean = "";
                        if (value != null && !value.equals("\"\"") && !value.equals("null")) {
                            clean = value.replace("\"", "").trim();
                        }
                        if (clean.length() >= 20) {
                            saveAccountToGateway(currentProvider, getProviderDisplayName(currentProvider) + "用户", clean);
                        } else {
                            if (isManualClick) {
                                new AlertDialog.Builder(LoginActivity.this)
                                    .setTitle("未检测到有效登录状态")
                                    .setMessage("请先在下方页面输入手机号或微信登录，完成登录后再点击右上角【完成入池】。")
                                    .setPositiveButton("我知道了", null)
                                    .show();
                            }
                        }
                    }
                });
            } catch (Exception e) {
                if (isManualClick) {
                    Toast.makeText(LoginActivity.this, "提取失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private boolean isDeepSeekOrigin(String url) {
        try {
            java.net.URI uri = new java.net.URI(url);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "chat.deepseek.com".equalsIgnoreCase(uri.getHost())
                    && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getUserInfo() == null;
        } catch (Exception ignored) { return false; }
    }

    private void extractDeepSeek(final boolean manual) {
        if (!deepSeekActive || deepSeekComplete || isFinishing()) return;
        if (deepSeekExtracting || deepSeekSaving) {
            if (manual) Toast.makeText(this, "正在检查登录状态，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        if (webView == null || !isDeepSeekOrigin(webView.getUrl())) {
            if (manual) showError("请在 DeepSeek 官方网页完成登录后重试。");
            return;
        }
        final int generation = deepSeekGeneration;
        deepSeekExtracting = true;
        try {
            java.lang.reflect.Method evaluate = WebView.class.getMethod("evaluateJavascript", String.class, ValueCallback.class);
            evaluate.invoke(webView, DEEPSEEK_EXTRACT_JS, new ValueCallback<String>() {
                @Override public void onReceiveValue(String result) {
                    deepSeekExtracting = false;
                    if (!deepSeekActive || deepSeekComplete || isFinishing() || generation != deepSeekGeneration
                            || webView == null || !isDeepSeekOrigin(webView.getUrl())) return;
                    try {
                        Object decoded = new org.json.JSONTokener(result == null ? "null" : result).nextValue();
                        if (!(decoded instanceof String) || ((String) decoded).isEmpty()) {
                            if (manual) showError("未检测到登录状态，请先在官方页面完成登录和验证。");
                            return;
                        }
                        JSONObject credential = new JSONObject((String) decoded);
                        Object value = credential.opt("access_token");
                        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
                            if (manual) showError("未检测到登录状态，请先完成登录。");
                            return;
                        }
                        String text = credential.toString();
                        byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8"));
                        StringBuilder fp = new StringBuilder();
                        for (byte b : hash) fp.append(String.format(java.util.Locale.US, "%02x", b & 255));
                        String fingerprint = fp.toString();
                        if (!manual && fingerprint.equals(deepSeekLastFingerprint)
                                && android.os.SystemClock.elapsedRealtime() < deepSeekRetryAfter) return;
                        saveDeepSeek(text, fingerprint, manual);
                    } catch (Exception ignored) {
                        if (manual) showError("读取登录状态失败，请完成网页验证后重试。");
                    }
                }
            });
        } catch (Exception ignored) {
            deepSeekExtracting = false;
            if (manual) showError("读取登录状态失败，请重试。");
        }
    }

    private void saveDeepSeek(final String credential, final String fingerprint, final boolean manual) {
        if (!deepSeekActive || deepSeekSaving || deepSeekComplete || isFinishing()
                || webView == null || !isDeepSeekOrigin(webView.getUrl())) return;
        deepSeekSaving = true;
        new Thread(scopedLoginTask(new Runnable() { @Override public void run() {
            boolean success = false;
            try {
                JSONObject body = new JSONObject();
                body.put("provider", "deepseek_web");
                body.put("name", "DeepSeek用户");
                body.put("token", credential);
                String response = LocalBridge.post("/api/auth/deepseek_web/add", body.toString());
                JSONObject result = response == null ? null : new JSONObject(response);
                success = result != null && result.optBoolean("ok", false) && result.optBoolean("validated", false);
            } catch (Exception ignored) { }
            final boolean ok = success;
            mainHandler.post(new Runnable() { @Override public void run() {
                deepSeekSaving = false;
                if (!deepSeekActive || isFinishing()) return;
                if (ok) {
                    deepSeekComplete = true;
                    mainHandler.removeCallbacks(deepSeekPoll);
                    Toast.makeText(LoginActivity.this, "账号验证成功，已加入网关池", Toast.LENGTH_LONG).show();
                    finish();
                } else {
                    if (fingerprint.equals(deepSeekLastFingerprint)) deepSeekFailures = Math.min(deepSeekFailures + 1, 4);
                    else { deepSeekLastFingerprint = fingerprint; deepSeekFailures = 1; }
                    deepSeekRetryAfter = android.os.SystemClock.elapsedRealtime() + (15000L << (deepSeekFailures - 1));
                    if (manual) showError("入池验证失败，请检查网关并完成网页登录验证后重试。");
                }
            }});
        }})).start();
    }

    // ===== 网关口令 =====
    // 网关的 /api/* 现在要鉴权，原生侧（登录页）自己发起的 /api/auth/* 调用也必须带上，
    // 否则登录流程会被自己刚加的口令拦成 401 —— 表现为"点登录没反应"。
    private String cachedAuthKey = null;

    private String gatewayAuthKey() {
        if (cachedAuthKey != null && !cachedAuthKey.isEmpty()) return cachedAuthKey;
        java.io.FileInputStream in = null;
        try {
            java.io.File f = new java.io.File(getFilesDir(), "config.json");
            if (!f.exists()) return "";
            in = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) != -1) bo.write(b, 0, n);
            JSONObject c = new JSONObject(new String(bo.toByteArray(), "UTF-8"));
            JSONObject server = c.optJSONObject("server");
            String k = server == null ? "" : server.optString("authKey", "").trim();
            cachedAuthKey = k;
            return k;
        } catch (Exception e) {
            return "";
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 把网关 URL 归一成 LocalBridge 需要的 path+query。
     * 这里原来是 openAuthed()，用 HttpURLConnection 直连 127.0.0.1 —— 在本机
     * （Android 16 禁明文）上必然抛 "Cleartext HTTP traffic to 127.0.0.1 not permitted"，
     * 于是所有"点登录 → 连网关"的通道全废。现在统一走 raw socket。
     */
    private static String gatewayPath(String urlString) {
        if (urlString == null) return "/";
        int scheme = urlString.indexOf("://");
        if (scheme < 0) return urlString.startsWith("/") ? urlString : "/" + urlString;
        int slash = urlString.indexOf('/', scheme + 3);
        return slash < 0 ? "/" : urlString.substring(slash);
    }

    /** 读网关 GET 接口，返回正文；失败返回 null（与原 try/catch 语义一致） */
    private String gatewayGet(String urlString) {
        return LocalBridge.get(gatewayPath(urlString));
    }

    private void saveAccountToGateway(final String providerId, final String name, final String token) {
        new Thread(scopedLoginTask(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject body = new JSONObject();
                    body.put("name", name);
                    body.put("token", token);
                    body.put("provider", providerId);

                    // raw socket：明文 HTTP 在本机被系统禁止
                    String resp = LocalBridge.post("/api/auth/" + providerId + "/add", body.toString());
                    final int code = (resp != null) ? 200 : 0;

                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (code >= 200 && code < 300) {
                                Toast.makeText(LoginActivity.this, "🎉 " + getProviderDisplayName(providerId) + " 账号 [" + name + "] 已成功加入网关池！", Toast.LENGTH_LONG).show();
                                finish();
                            } else {
                                Toast.makeText(LoginActivity.this, "入池失败，状态码: " + code, Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(LoginActivity.this, "保存账号异常: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        })).start();
    }

    private void showError(final String msg) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                subtitleView.setText("❌ " + msg);
                Toast.makeText(LoginActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        traeActive = false;
        traeGeneration++;
        isPolling = false;
        deepSeekActive = false;
        mainHandler.removeCallbacks(deepSeekPoll);
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    /**
     * Codex (ChatGPT 订阅) OAuth 授权与凭据导入：
     * 向网关 /api/auth/codex/init 获取授权链接，WebView/浏览器中完成授权，
     * 同时支持在手机端直接截获 localhost:1455 回调完成自动入池。
     */
    /** 在 Codex 授权页加载后读一次页面文本，命中地区限制就换成中文说明 */
    private void detectCodexRegionBlock(final String url) {
        if (url == null) return;
        // 回调页 / 正常登录页不用管
        if (url.indexOf("localhost:1455") >= 0 || url.indexOf("/auth/callback") >= 0) return;
        try {
            java.lang.reflect.Method m = WebView.class.getMethod("evaluateJavascript",
                    String.class, Class.forName("android.webkit.ValueCallback"));
            // 返回页面可见文本，由原生侧判断关键词
            m.invoke(webView,
                    "(function(){try{return (document.body?document.body.innerText:'').slice(0,4000);}catch(e){return '';}})()",
                    null);
        } catch (Throwable ignored) {
        }

        // 反射拿不到返回值，改用 DOM 文本兜底：把判断放进页面自身的 JS，
        // 命中后回调一个自定义协议给原生层。
        try {
            String js = "(function(){try{"
                    + "var t=(document.body?document.body.innerText:'').toLowerCase();"
                    + "if(t.indexOf('unsupported_country_region_territory')>=0"
                    + "||t.indexOf('not available in your country')>=0"
                    + "||t.indexOf('unsupported_country')>=0"
                    + "||t.indexOf('unsupported region')>=0){"
                    + "document.location='magpie://codex-region-block';}"
                    + "}catch(e){}})();";
            java.lang.reflect.Method m2 = WebView.class.getMethod("evaluateJavascript",
                    String.class, Class.forName("android.webkit.ValueCallback"));
            m2.invoke(webView, js, null);
        } catch (Throwable ignored) {
        }
    }

    /** 浏览器授权时的 App 内引导页：等回调 + 手动粘贴 code 的兜底入口 */
    private void showCodexBrowserGuide(final String authUrl) {
        String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>body{background:#0b0e14;color:#e5e7eb;font-family:-apple-system,sans-serif;"
                + "padding:24px;line-height:1.75;font-size:15px}"
                + "h3{color:#6ee7b7;font-size:18px;margin:0 0 12px}"
                + "p{color:#9ca3af;margin:10px 0}"
                + ".box{background:rgba(56,189,248,.08);border:1px solid rgba(56,189,248,.25);"
                + "border-radius:10px;padding:14px 16px;margin:16px 0}"
                + ".warn{background:rgba(251,191,36,.10);border-color:rgba(251,191,36,.35);color:#fbbf24}"
                + "b{color:#38bdf8}code{background:#1e2333;padding:2px 6px;border-radius:4px;"
                + "color:#a5b4fc;font-size:13px;word-break:break-all}</style></head><body>"
                + "<h3>✅ 已切换到系统浏览器授权</h3>"
                + "<p>请在刚才打开的浏览器里完成 ChatGPT 登录。这一幕必须在浏览器里做，"
                + "因为 OpenAI 的登录页有人机验证，App 内嵌网页过不了。</p>"
                + "<div class=\"box\">"
                + "<b>授权完成后</b><br>"
                + "浏览器会尝试跳回 <code>localhost:1455</code>。App 已经在监听这个端口，"
                + "跳回后会自动完成入池，这个页面会自动关闭。"
                + "</div>"
                + "<div class=\"box warn\">"
                + "<b>如果浏览器里看到一段英文 JSON</b><br>"
                + "例如 <code>unsupported_country_region_territory</code>，"
                + "说明当前出口 IP 在 OpenAI 不支持的地区。把代理切到"
                + " <b>美国 / 日本 / 新加坡</b> 后再点一次授权。"
                + "</div>"
                + "<div class=\"box\">"
                + "<b>如果浏览器没有自动跳回</b><br>"
                + "复制浏览器地址栏里最后那条 <code>localhost:1455/...</code> 的完整链接，"
                + "回到控制台的 Codex 卡片，用「📋 导入 auth.json / Token」或手动提交 code 入池。"
                + "</div>"
                + "</body></html>";
        try {
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        } catch (Throwable ignored) {
        }
    }

    /** 把地区限制换成用户看得懂的中文说明，并给两条可行路径 */
    private void showCodexRegionBlockPage() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    subtitleView.setText("OpenAI 拒绝了当前网络出口");
                    String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                            + "<style>body{background:#0b0e14;color:#e5e7eb;font-family:-apple-system,sans-serif;"
                            + "padding:24px;line-height:1.75;font-size:15px}"
                            + "h3{color:#fbbf24;font-size:18px;margin:0 0 12px}"
                            + "p{color:#9ca3af;margin:10px 0}"
                            + ".box{background:rgba(56,189,248,.08);border:1px solid rgba(56,189,248,.25);"
                            + "border-radius:10px;padding:14px 16px;margin:16px 0}"
                            + "b{color:#38bdf8}code{background:#1e2333;padding:2px 6px;border-radius:4px;"
                            + "color:#a5b4fc;font-size:13px}</style></head><body>"
                            + "<h3>⚠️ 当前网络出口不被 OpenAI 支持</h3>"
                            + "<p>OpenAI 返回了 <code>unsupported_country_region_territory</code>，"
                            + "意思是这个出口 IP 所在地区（中国大陆等）不允许使用 ChatGPT / Codex 授权。"
                            + "这不是 All In 的问题，换成任何客户端都会被同样拒绝。</p>"
                            + "<div class=\"box\">"
                            + "<b>路径一：换支持地区的节点</b><br>"
                            + "把代理切到 <b>美国 / 日本 / 新加坡</b> 等地区，确认浏览器能打开 "
                            + "<code>chatgpt.com</code> 之后，回来重新点「OAuth 授权登录」。"
                            + "</div>"
                            + "<div class=\"box\">"
                            + "<b>路径二：直接导入凭据（最快）</b><br>"
                            + "在已经能登录的地区或电脑上，取出 <code>~/.codex/auth.json</code>，"
                            + "回到控制台的 Codex 卡片点「📋 导入 auth.json / Token」粘进去，"
                            + "完全不需要在这里走浏览器授权。"
                            + "</div>"
                            + "<p style=\"font-size:13px;color:#6b7280\">"
                            + "提示：关闭代理直连时，国内网络连 OpenAI 本身就会失败；"
                            + "开了代理但节点在不支持地区，就会看到上面这个错误。</p>"
                            + "</body></html>";
                    webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void initCodexAuth() {
        subtitleView.setText("正在向 OpenAI 发起 Codex 授权申请…");
        new Thread(scopedLoginTask(new Runnable() {
            @Override
            public void run() {
                try {
                    String api = "http://127.0.0.1:3425/api/auth/codex/init";
                    // 走 raw socket：明文 HTTP 在本机被系统禁止，HttpURLConnection 直接会被拦
                    String gwBody = gatewayGet(api);
                    if (gwBody == null) throw new java.io.IOException("连接网关失败（网关未启动？）");
                    final JSONObject res = new JSONObject(gwBody);
                    if (!res.optBoolean("ok", false)) {
                        final String err = res.optString("error", "初始化失败");
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                subtitleView.setText("Codex 授权初始化失败：" + err);
                                Toast.makeText(LoginActivity.this, err, Toast.LENGTH_LONG).show();
                            }
                        });
                        return;
                    }
                    codexFlowId = res.optString("flowId", "");
                    final String authUrl = res.optString("authUrl", "");
                    final boolean regionBlocked = res.optBoolean("regionBlocked", false);
                    final String regionWarning = res.optString("regionWarning", "");

                    // 预检已经判定出口不被 OpenAI 支持，就别白加载一次授权页了
                    if (regionBlocked) {
                        showCodexRegionBlockPage();
                        return;
                    }

                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            // 关键：Codex 授权页有 Cloudflare 人机验证，App 内 WebView 的
                            // 指纹会被判成自动化环境，永远卡在"请稍候"进不去登录表单。
                            // 必须交给系统浏览器（Chrome）打开，它带完整浏览器特性才能过验证。
                            subtitleView.setText("已切换到浏览器授权｜若浏览器显示一段英文 JSON，说明该地区被 OpenAI 限制");
                            try {
                                android.content.Intent i = new android.content.Intent(
                                        android.content.Intent.ACTION_VIEW, android.net.Uri.parse(authUrl));
                                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(i);
                            } catch (Throwable t) {
                                Toast.makeText(LoginActivity.this,
                                        "无法打开浏览器: " + t.getMessage(), Toast.LENGTH_LONG).show();
                            }
                            showCodexBrowserGuide(authUrl);
                            startPollingCodexFlow();
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            subtitleView.setText("连接网关失败：" + e.getMessage());
                        }
                    });
                }
            }
        })).start();
    }

    private void startPollingCodexFlow() {
        new Thread(scopedLoginTask(new Runnable() {
            @Override
            public void run() {
                long deadline = System.currentTimeMillis() + 600000L;
                while (System.currentTimeMillis() < deadline && !isFinishing()) {
                    try {
                        Thread.sleep(3000);
                        String api = "http://127.0.0.1:3425/api/auth/codex/poll?flowId="
                                + URLEncoder.encode(codexFlowId, "UTF-8");
                        String gwBody = gatewayGet(api);
                        if (gwBody != null) {
                            JSONObject res = new JSONObject(gwBody);
                            String status = res.optString("status", "");
                            if ("done".equals(status)) {
                                final String acc = res.optString("account", "Codex 账号");
                                final String plan = res.optString("plan", "chatgpt");
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        Toast.makeText(LoginActivity.this, "Codex 授权成功：" + acc + " (" + plan + ")", Toast.LENGTH_LONG).show();
                                        setResult(RESULT_OK);
                                        finish();
                                    }
                                });
                                return;
                            } else if ("failed".equals(status)) {
                                final String err = res.optString("error", "授权失败");
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        subtitleView.setText("授权失败：" + err);
                                        Toast.makeText(LoginActivity.this, err, Toast.LENGTH_SHORT).show();
                                    }
                                });
                                return;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
        })).start();
    }

}
