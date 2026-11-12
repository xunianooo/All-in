package com.gaeul.allin;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.webkit.*;

import android.widget.FrameLayout;
import android.widget.Toast;

import java.lang.reflect.Method;

public class MainActivity extends Activity {
    private static final String PREFS_NAME = "magpie_prefs";
    private static final String PREF_GATEWAY_ENABLED = "gateway_enabled";
    private static final int GATEWAY_PORT = 3425;

    private WebView webView;
    private BackupActivityController backupController;
    /** 当前 WebView 正在显示的页面地址；原生桥只认面板自己的页面 */
    private volatile String currentPageUrl = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        applySystemBarsInternal(isSystemDark());

        // 按用户上次的开关选择决定是否自动启动网关（默认开启）
        if (isGatewayEnabledPref()) {
            startGatewayService();
            // 打开 App 是唯一保证「进程此刻没被冻结」的时刻，顺手把周期自检续上
            try {
                KeepAliveScheduler.schedule(this);
            } catch (Throwable ignored) {
            }
        }

        // 让 LocalBridge 能读到网关口令（config.json 在 filesDir 下）
        try {
            GatewayAuthKeyHolder.init(getFilesDir());
        } catch (Throwable ignored) {
        }

        // 明文 HTTP 与本地回环自检（结果落盘，便于无 logcat 时排查）
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("sdk=").append(Build.VERSION.SDK_INT).append('\n');
            Class<?> nspCls = Class.forName("android.security.NetworkSecurityPolicy");
            Object nsp = nspCls.getMethod("getInstance").invoke(null);
            sb.append("cleartext global=").append(nspCls.getMethod("isCleartextTrafficPermitted").invoke(nsp));
            sb.append(" 127.0.0.1=").append(nspCls.getMethod("isCleartextTrafficPermitted", String.class)
                    .invoke(nsp, "127.0.0.1")).append('\n');
            try {
                java.net.HttpURLConnection hc = (java.net.HttpURLConnection)
                        new java.net.URL("http://127.0.0.1:3425/api/status").openConnection(java.net.Proxy.NO_PROXY);
                hc.setRequestProperty("X-All-In-Management", GatewayManagementCredential.get());
                hc.setConnectTimeout(1500);
                hc.setReadTimeout(1500);
                sb.append("http loopback code=").append(hc.getResponseCode()).append('\n');
                hc.disconnect();
            } catch (Throwable t) {
                sb.append("http loopback err=").append(t).append('\n');
            }
            java.io.FileOutputStream fo = new java.io.FileOutputStream(
                    new java.io.File(getFilesDir(), "diag.txt"));
            fo.write(sb.toString().getBytes("UTF-8"));
            fo.close();
            Log.e("Magpie", sb.toString().replace('\n', ' '));
        } catch (Throwable t) {
            Log.e("Magpie", "cleartext probe failed: " + t);
        }

        // 构建全屏且具备完整触控焦点的 WebView
        webView = new WebView(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        );
        webView.setLayoutParams(params);

        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.requestFocus(View.FOCUS_DOWN);
        webView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_UP:
                        if (!v.hasFocus()) {
                            v.requestFocus();
                        }
                        break;
                }
                return false;
            }
        });

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        try {
            // Release 包不再永久打开远程调试：能被 chrome://inspect 读到面板 DOM 与全部 /api/* 响应
            boolean debuggable = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            Method debugMethod = WebView.class.getMethod("setWebContentsDebuggingEnabled", boolean.class);
            debugMethod.invoke(null, debuggable);
        } catch (Throwable ignored) {}
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                Method m = WebSettings.class.getMethod("setMixedContentMode", int.class);
                m.invoke(settings, 0); // 0 = MIXED_CONTENT_ALWAYS_ALLOW
            } catch (Exception ignored) {}
        }

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Log.e("MagpieWeb", "[" + consoleMessage.messageLevel() + "] " + consoleMessage.message()
                    + " (" + consoleMessage.sourceId() + ":" + consoleMessage.lineNumber() + ")");
                return true;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
                new android.app.AlertDialog.Builder(MainActivity.this)
                    .setTitle("All In 提示")
                    .setMessage(message)
                    .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            result.confirm();
                        }
                    })
                    .setCancelable(false)
                    .create()
                    .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
                new android.app.AlertDialog.Builder(MainActivity.this)
                    .setTitle("All In 确认")
                    .setMessage(message)
                    .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            result.confirm();
                        }
                    })
                    .setNegativeButton("取消", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            result.cancel();
                        }
                    })
                    .setCancelable(false)
                    .create()
                    .show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, final JsPromptResult result) {
                final android.widget.EditText input = new android.widget.EditText(MainActivity.this);
                if (defaultValue != null) input.setText(defaultValue);
                input.setSingleLine(false);
                input.setMaxLines(4);

                new android.app.AlertDialog.Builder(MainActivity.this)
                    .setTitle("All In 录入")
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            result.confirm(input.getText().toString());
                        }
                    })
                    .setNegativeButton("取消", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            result.cancel();
                        }
                    })
                    .setCancelable(false)
                    .create()
                    .show();
                return true;
            }
        });

        // 原生 Socket 桥：把面板的 /api/* 请求直接送到本机网关，绕开明文 HTTP 限制
        try {
            webView.addJavascriptInterface(new NativeBridge(), "AllInNative");
        } catch (Throwable t) {
            Log.e("Magpie", "addJavascriptInterface failed: " + t);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                currentPageUrl = url == null ? "" : url;
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                if (isTrustedPanelResource(url)) return null;
                return new android.webkit.WebResourceResponse("text/plain", "UTF-8", new java.io.ByteArrayInputStream(new byte[0]));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                currentPageUrl = url == null ? "" : url;
                pushGatewayStateToWeb();
                view.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        pushGatewayStateToWeb();
                        view.loadUrl("javascript:(function(){"
                                + "if(typeof applyGatewayUI==='function')applyGatewayUI(true,3425);"
                                + "if(typeof loadStats==='function')loadStats();"
                                + "if(typeof loadConfig==='function')loadConfig();"
                                + "})();");
                    }
                }, 400);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // 一键连 ETA：拉起 ETA 主界面（不写对方数据，纯 startActivity，不需要 root）
                if (url != null && url.startsWith("magpie://eta")) {
                    try {
                        android.content.Intent i = getPackageManager()
                                .getLaunchIntentForPackage("io.github.mangi.eta");
                        if (i != null) {
                            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i);
                        } else {
                            Toast.makeText(MainActivity.this, "没有找到 ETA（io.github.mangi.eta）",
                                    Toast.LENGTH_LONG).show();
                        }
                    } catch (Throwable t) {
                        Toast.makeText(MainActivity.this, "打开 ETA 失败: " + t.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                    return true;
                }
                // 网关开关的回退通道：magpie://gateway?on=1 / 0
                if (url != null && url.startsWith("magpie://gateway")) {
                    boolean enable = (url.contains("on=1"));
                    if (enable) {
                        setGatewayEnabledPref(true);
                        startGatewayService();
                    } else {
                        setGatewayEnabledPref(false);
                        stopGatewayService();
                    }
                    return true;
                }
                // 权限管理：magpie://settings/notifications|battery|autostart|app
                if (url != null && url.startsWith("magpie://settings")) {
                    String target = "app";
                    int idx = url.indexOf("target=");
                    if (idx >= 0) {
                        target = url.substring(idx + 7);
                        int amp = target.indexOf('&');
                        if (amp >= 0) target = target.substring(0, amp);
                    } else {
                        // 兼容 magpie://settings/notifications 形式
                        int slash = url.indexOf('/', "magpie://settings".length());
                        if (slash >= 0) target = url.substring(slash + 1);
                    }
                    final String t = target.trim();
                    // 通知权限优先走系统运行时授权弹窗，比跳设置页更直接
                    if ("notifications".equals(t) && requestNotificationPermission()) {
                        return true;
                    }
                    final boolean opened = PermissionCenter.openSettings(MainActivity.this, t);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            android.widget.Toast.makeText(MainActivity.this,
                                    opened ? "已打开系统设置，回来后点「刷新」查看状态" : "无法打开该设置页，请手动到系统设置中授权",
                                    android.widget.Toast.LENGTH_SHORT).show();
                        }
                    });
                    return true;
                }
                if (url != null && url.startsWith("magpie://login")) {
                    Intent intent = new Intent(MainActivity.this, LoginActivity.class);
                    try {
                        android.net.Uri u = android.net.Uri.parse(url);
                        String p = u.getQueryParameter("provider");
                        if (p != null) intent.putExtra("provider", p);
                        String st = u.getQueryParameter("site");
                        if (st != null) intent.putExtra("site", st);
                    } catch (Exception ignored) {}
                    startActivity(intent);
                    return true;
                }
                if(isTrustedPanelResource(url))return false;
                // Login pages never share the management bridge's WebView.
                if(url!=null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url))); }
                    catch(Exception ignored) { Toast.makeText(MainActivity.this,"无法打开外部页面",Toast.LENGTH_SHORT).show(); }
                }
                return true;
            }
        });



        // 面板始终从本地 assets 加载。
        // 这台设备（Android 16）上系统判定 cleartext 全部禁止（NetworkSecurityPolicy
        // 恒为 false，manifest 的 usesCleartextTraffic 也被忽略），所以 WebView/fetch
        // 访问 http://127.0.0.1:3425 必然 ERR_CLEARTEXT_NOT_PERMITTED。
        // 因此面板的 /api/* 请求改为走下面的原生 Socket 桥（raw socket 不受明文策略约束）。
        String fileUrl = "file:///android_asset/dashboard.html";
        webView.loadUrl(fileUrl);

        setContentView(webView);
    }

    /**
     * 面板是浅色主题，状态栏 / 导航栏也要跟着变浅，否则顶上会压一条黑边，
     * 和整体风格割裂。精简 android.jar 没有这些方法，统一走反射。
     */
    /** 系统当前是否深色模式 */
    private boolean isSystemDark() {
        try {
            int mode = getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable e) {
            return false;
        }
    }

    private void applySystemBarsInternal(final boolean dark) {
        try {
            Window w = getWindow();
            final int bg = dark ? 0xFF000000 : 0xFFF4F4F7;
            final int navBg = dark ? 0xFF000000 : 0xFFFFFFFF;
            try {
                java.lang.reflect.Method m = Window.class.getMethod("setStatusBarColor", int.class);
                m.invoke(w, bg);
            } catch (Throwable ignored) {
            }
            try {
                java.lang.reflect.Method m = Window.class.getMethod("setNavigationBarColor", int.class);
                m.invoke(w, navBg);
            } catch (Throwable ignored) {
            }
            // Android 16 强制 edge-to-edge，状态栏变透明后透出来的是「窗口底色」。
            // 不把它也刷成浅色，顶上就会压一条黑带，跟页面割裂。
            try {
                w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(bg));
            } catch (Throwable ignored) {
            }
            try {
                w.getDecorView().setBackgroundColor(bg);
            } catch (Throwable ignored) {
            }
            android.view.View dv = w.getDecorView();
            // 深色底要用浅色图标（不设 LIGHT_*），浅色底才设 LIGHT_STATUS_BAR / LIGHT_NAVIGATION_BAR
            try {
                java.lang.reflect.Method m = android.view.View.class
                        .getMethod("setSystemUiVisibility", int.class);
                m.invoke(dv, dark ? 0 : (0x00002000 | 0x00000010));
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    /** ETA 的主 Activity（显式启动用） */
    private static final String ETA_PKG = "io.github.mangi.eta";
    private static final String ETA_ACTIVITY = "io.github.mangi.eta.ui.MainActivity";

    /**
     * 拉起 ETA。
     * 注意 Android 11+ 的包可见性：没在 <queries> 里声明时
     * getLaunchIntentForPackage() 会返回 null（表现就像“没安装”）。
     * 所以这里先走隐式启动，拿不到再用显式 ComponentName 兜底 —— 显式启动不受可见性限制。
     */
    private boolean launchEta() {
        android.content.Intent i = null;
        try {
            i = getPackageManager().getLaunchIntentForPackage(ETA_PKG);
        } catch (Throwable ignored) {
        }
        if (i == null) {
            try {
                i = new android.content.Intent(android.content.Intent.ACTION_MAIN);
                i.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
                i.setComponent(new android.content.ComponentName(ETA_PKG, ETA_ACTIVITY));
            } catch (Throwable ignored) {
            }
        }
        if (i == null) return false;
        try {
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Throwable t) {
            Log.w("Magpie", "launchEta failed: " + t);
            return false;
        }
    }

    /** 是否装了 ETA：隐式/显式都探一次；都探不到就当作已装（让按钮可点，点失败会 toast） */
    private boolean etaInstalled() {
        try {
            if (getPackageManager().getLaunchIntentForPackage(ETA_PKG) != null) return true;
        } catch (Throwable ignored) {
        }
        try {
            android.content.Intent probe = new android.content.Intent(android.content.Intent.ACTION_MAIN);
            probe.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
            probe.setComponent(new android.content.ComponentName(ETA_PKG, ETA_ACTIVITY));
            if (getPackageManager().resolveActivity(probe, 0) != null) return true;
        } catch (Throwable ignored) {
        }
        return true;   // 包可见性可能挡住探测，别误报“没安装”
    }

    /**
     * 原生桥的来源校验：只有面板自己的页面允许调用。
     * 原来 addJavascriptInterface 之后没有任何判断，面板里任何一处 innerHTML 拼接（账号昵称、
     * 模型名、用户自己填的 baseUrl）被注入就能调 requestAsync 改配置、导凭据。
     */
    private static boolean isTrustedPanelResource(String url) {
        if(url==null)return false;
        try {
            Uri u=Uri.parse(url);
            if(u.getUserInfo()!=null || u.getQuery()!=null)return false;
            if("file".equals(u.getScheme())) {
                String path=u.getPath();
                return (u.getAuthority()==null || u.getAuthority().isEmpty()) && path!=null
                        && ("/android_asset/dashboard.html".equals(path) || "/android_asset/licenses.html".equals(path) || "/android_asset/icon.png".equals(path)
                            || path.matches("/android_asset/provider-icons/[a-z0-9_-]+\\.(svg|png)"));
            }
            return false;
        }catch(Exception invalid){return false;}
    }

    private boolean bridgeAllowed() {
        try {
            Uri uri = Uri.parse(currentPageUrl);
            if (uri.getUserInfo() != null || uri.getQuery() != null) return false;
            if ("file".equals(uri.getScheme()))
                return (uri.getAuthority() == null || uri.getAuthority().isEmpty()) && "/android_asset/dashboard.html".equals(uri.getPath());
            return false;
        } catch (Exception ignored) { return false; }
    }

    /**
     * JS 桥：用 raw socket 直接和本机网关对话。
     * raw socket 不受 Android cleartext HTTP 策略限制，也不需要 CORS/代理，最稳。
     * 返回 {"status":200,"body":"..."} 的 JSON 字符串。
     */
    private class NativeBridge {
        @android.webkit.JavascriptInterface
        public boolean configureBackgroundKeepAlive(String option, boolean enabled) {
            if (!bridgeAllowed() || !("overlay".equals(option) || "audio".equals(option))) return false;
            if (!BackgroundKeepAlive.setOption(MainActivity.this, option, enabled)) return false;
            GatewayService.refreshKeepAlive(MainActivity.this);
            return true;
        }

        @android.webkit.JavascriptInterface
        public boolean openPermissionSettings(final String target) {
            if (!bridgeAllowed() || !("overlay".equals(target) || "autostart".equals(target)
                    || "powersave".equals(target) || "battery".equals(target)
                    || "notifications".equals(target) || "app".equals(target))) return false;
            runOnUiThread(() -> {
                if (!bridgeAllowed() || isFinishing()) return;
                if ("notifications".equals(target) && requestNotificationPermission()) return;
                if (!PermissionCenter.openSettings(MainActivity.this, target))
                    Toast.makeText(MainActivity.this, "无法打开设置，请手动前往系统设置", Toast.LENGTH_SHORT).show();
            });
            return true;
        }

        @android.webkit.JavascriptInterface
        public boolean openEncryptedBackup(final String action) {
            if (!bridgeAllowed() || !("export".equals(action) || "import".equals(action) || "rollback".equals(action))) return false;
            runOnUiThread(() -> {
                if (!bridgeAllowed() || isFinishing()) return;
                if (backupController == null) backupController = new BackupActivityController(MainActivity.this, GatewayService.backupBackend(MainActivity.this));
                backupController.open(action);
            });
            return true;
        }

        /** 同步调用（保留兼容，短请求用；长请求请用 requestAsync 避免卡住 JS 线程） */
        @android.webkit.JavascriptInterface
        public String request(String method, String pathAndQuery, String body) {
            if (!bridgeAllowed()) {
                return "{\"status\":403,\"body\":\"bridge blocked: 非面板页面\"}";
            }
            return doRequest(method, pathAndQuery, body);
        }

        /**
         * 打开 ETA 主界面。
         * 为什么不用 magpie://eta 跳转：WebView 里对 file:// 页面赋 location 到一个未知 scheme，
         * Chromium 直接丢弃、不会回调 shouldOverrideUrlLoading（实测点了没反应）。
         * 走 JS 桥则一定能到原生，且 startActivity 不需要任何 root 权限。
         */
        @android.webkit.JavascriptInterface
        public boolean openEta() {
            if (!bridgeAllowed()) return false;
            return launchEta();
        }

        /** ETA 是否已安装（面板据此决定按钮文案） */
        @android.webkit.JavascriptInterface
        public boolean hasEta() {
            return etaInstalled();
        }

        /** Optional existing browser management, explicit copy only; never part of inference import. */
        @android.webkit.JavascriptInterface
        public boolean copyManagementPanelLink() {
            if (!bridgeAllowed()) return false;
            String key=GatewayManagementCredential.get();
            String ip=GatewayServer.getLocalIpAddress();
            if (key.isEmpty() || ip==null || ip.isEmpty() || "127.0.0.1".equals(ip)) return false;
            return copyText("http://"+ip+":3425/#manage="+key);
        }

        /** 把文本放进剪贴板（面板「一键导入 ETA」用；WebView 里 navigator.clipboard 不稳） */
        @android.webkit.JavascriptInterface
        public boolean copyText(String text) {
            if (!bridgeAllowed() || text == null) return false;
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                if (cm == null) return false;
                cm.setPrimaryClip(android.content.ClipData.newPlainText("All In", text));
                return true;
            } catch (Throwable t) {
                Log.w("Magpie", "copyText failed: " + t.getMessage());
                return false;
            }
        }

        @android.webkit.JavascriptInterface
        public boolean isSystemDark() {
            if (!bridgeAllowed()) return false;
            return MainActivity.this.isSystemDark();
        }

        /** 面板切主题时同步系统栏（状态栏/导航栏/窗口底色） */
        @android.webkit.JavascriptInterface
        public void applySystemBars(final boolean dark) {
            if (!bridgeAllowed()) return;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    applySystemBarsInternal(dark);
                }
            });
        }

        /**
         * 导出用量 CSV：在原生侧把 /api/usage/csv 取回来写到应用外部目录。
         * WebView 里 window.open / a[download] 在 http://127.0.0.1 上不可靠，
         * 所以落盘 + 回传路径，由前端提示用户。
         */
        @android.webkit.JavascriptInterface
        public String exportUsageCsv(String days) {
            if (!bridgeAllowed()) {
                return "{\"ok\":false,\"error\":\"bridge blocked: 非面板页面\"}";
            }
            java.io.FileOutputStream fos = null;
            try {
                int d = 7;
                try {
                    d = Integer.parseInt(days);
                } catch (Throwable ignored) {
                }
                String csv = LocalBridge.get("/api/usage/csv?days=" + d);
                if (csv == null) {
                    return "{\"ok\":false,\"error\":\"网关没有返回数据\"}";
                }
                java.io.File dir = getExternalFilesDir(null);
                if (dir == null) dir = getFilesDir();
                java.io.File out = new java.io.File(dir, "usage-" + d + "d.csv");
                fos = new java.io.FileOutputStream(out, false);
                fos.write(csv.getBytes("UTF-8"));
                fos.flush();
                return "{\"ok\":true,\"path\":" + com.gaeul.allin.json.JSONObject.quote(out.getAbsolutePath())
                        + ",\"size\":" + out.length() + "}";
            } catch (Throwable e) {
                return "{\"ok\":false,\"error\":" + com.gaeul.allin.json.JSONObject.quote(
                        e.getClass().getSimpleName() + ": " + e.getMessage()) + "}";
            } finally {
                try {
                    if (fos != null) fos.close();
                } catch (Throwable ignored) {
                }
            }
        }

        /**
         * 流式调用：边收边把每一行回调给 JS 的 __allInStreamChunk(id, line)，
         * 结束时回调 __allInStreamEnd(id, status, error)。
         *
         * 桥原来只返回完整响应，面板里所有 SSE 都会变成「生成完才出现」，
         * 所以面板的流式测试必须走这个入口。
         */
        @android.webkit.JavascriptInterface
        public void startStream(final String reqId, final String method,
                                final String pathAndQuery, final String body) {
            if (reqId == null || !bridgeAllowed()) return;
            final BridgeRequestValidator.Request request;
            try {
                // 流式入口同样会拼请求行，必须先过与 doRequest 相同的校验。
                request = BridgeRequestValidator.validate(method, pathAndQuery);
            } catch (IllegalArgumentException rejected) {
                evalJs(webView, "window.__allInStreamEnd && window.__allInStreamEnd("
                        + com.gaeul.allin.json.JSONObject.quote(reqId) + ",400,"
                        + com.gaeul.allin.json.JSONObject.quote("bridge request rejected: "
                                + rejected.getMessage()) + ")");
                return;
            }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final WebView view = webView;
                    LocalBridge.stream(request.method, request.pathAndQuery, body, 180000,
                            new LocalBridge.StreamCallback() {
                                @Override
                                public boolean onLine(String line) {
                                    evalJs(view, "window.__allInStreamChunk && window.__allInStreamChunk("
                                            + com.gaeul.allin.json.JSONObject.quote(reqId) + ","
                                            + com.gaeul.allin.json.JSONObject.quote(line) + ")");
                                    return true;
                                }

                                @Override
                                public void onDone(int status, String error) {
                                    evalJs(view, "window.__allInStreamEnd && window.__allInStreamEnd("
                                            + com.gaeul.allin.json.JSONObject.quote(reqId) + ","
                                            + status + ","
                                            + com.gaeul.allin.json.JSONObject.quote(error == null ? "" : error) + ")");
                                }
                            });
                }
            }, "AllIn-Stream").start();
        }

        private void evalJs(final WebView view, final String script) {
            if (view == null) return;
            view.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        java.lang.reflect.Method m = WebView.class
                                .getMethod("evaluateJavascript", String.class,
                                        Class.forName("android.webkit.ValueCallback"));
                        if(bridgeAllowed()) m.invoke(view, script, null);
                    } catch (Throwable ignored) {
                    }
                }
            });
        }

        /**
         * 异步调用：Java 侧开线程跑，完成后回调用 JS 的 __allInBridgeDone(id, result)。
         * 同步版会把 WebView 的 JS 线程整个堵住，遇到「同步官方模型」这类要打外网、
         * 十几秒才返回的接口，页面就会完全点不动。异步后页面始终保持响应。
         */
        @android.webkit.JavascriptInterface
        public void requestAsync(final String reqId, final String method, final String pathAndQuery, final String body) {
            if (reqId == null || !bridgeAllowed()) return;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String result = doRequest(method, pathAndQuery, body);
                    final String script = "__allInBridgeDone("
                            + com.gaeul.allin.json.JSONObject.quote(reqId) + ","
                            + com.gaeul.allin.json.JSONObject.quote(result) + ")";
                    final WebView view = webView;
                    if (view != null) {
                        view.post(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    // evaluateJavascript 是 API19+；本工程编译用的 android.jar 很老，
                                    // 走反射调用，运行时在真机上正常链接。
                                    java.lang.reflect.Method m = WebView.class
                                            .getMethod("evaluateJavascript", String.class, Class.forName("android.webkit.ValueCallback"));
                                    if(bridgeAllowed()) m.invoke(view, script, null);
                                } catch (Throwable ignored) {
                                }
                            }
                        });
                    }
                }
            }).start();
        }

        /** 请求行被校验器拒绝时回给面板的固定结构；此时不会建立 socket，也不会碰网关。 */
        private String bridgeRejected(String reason) {
            try {
                com.gaeul.allin.json.JSONObject ret = new com.gaeul.allin.json.JSONObject();
                ret.put("status", 400);
                ret.put("body", "");
                ret.put("error", "bridge request rejected: " + reason);
                return ret.toString();
            } catch (Exception ignored) {
                return "{\"status\":400,\"body\":\"\",\"error\":\"bridge request rejected\"}";
            }
        }

        private String doRequest(String method, String pathAndQuery, String body) {
            final BridgeRequestValidator.Request request;
            try {
                // 面板可控的 method/path 在拼请求行之前必须过校验：拦住 CRLF、控制字符、
                // 绝对 URI 与异常长度，避免把注入内容写进发往本机网关的请求行。
                request = BridgeRequestValidator.validate(method, pathAndQuery);
            } catch (IllegalArgumentException rejected) {
                return bridgeRejected(rejected.getMessage());
            }
            java.net.Socket socket = null;
            try {
                String m = request.method;
                String path = request.pathAndQuery;
                byte[] payload = (body == null) ? new byte[0] : body.getBytes("UTF-8");

                socket = new java.net.Socket();
                socket.connect(new java.net.InetSocketAddress("127.0.0.1", 3425), 3000);
                socket.setSoTimeout(90000);

                StringBuilder head = new StringBuilder();
                head.append(m).append(' ').append(path).append(" HTTP/1.1\r\n");
                head.append("Host: 127.0.0.1:3425\r\n");
                head.append("Origin: null\r\n");
                head.append("Connection: close\r\n");
                head.append("X-All-In-Bridge: 1\r\n");
                // 关键：网关对 /api/* 的本机回路放行，但 /v1/* 必须带口令。
                // 这里原来没带，面板里任何打 /v1/* 的请求都会 401。
                String management = GatewayManagementCredential.get();
                if (!management.isEmpty()) head.append("X-All-In-Management: ").append(management).append("\r\n");
                String gwKey = GatewayAuthKeyHolder.get();
                if (gwKey != null && !gwKey.isEmpty()) {
                    head.append("Authorization: Bearer ").append(gwKey).append("\r\n");
                }
                if (payload.length > 0) {
                    head.append("Content-Type: application/json; charset=utf-8\r\n");
                }
                head.append("Content-Length: ").append(payload.length).append("\r\n\r\n");

                java.io.OutputStream os = socket.getOutputStream();
                os.write(head.toString().getBytes("UTF-8"));
                if (payload.length > 0) os.write(payload);
                os.flush();

                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                java.io.InputStream is = socket.getInputStream();
                byte[] tmp = new byte[8192];
                int n;
                while ((n = is.read(tmp)) > 0) {
                    buf.write(tmp, 0, n);
                }
                byte[] all = buf.toByteArray();

                String raw = new String(all, "ISO-8859-1");
                int split = raw.indexOf("\r\n\r\n");
                if (split < 0) {
                    throw new java.io.IOException("bad response");
                }
                String headerPart = raw.substring(0, split);
                int status = 200;
                try {
                    int sp = headerPart.indexOf(' ');
                    if (sp > 0) status = Integer.parseInt(headerPart.substring(sp + 1, sp + 4).trim());
                } catch (Exception ignored) {
                }
                boolean chunked = headerPart.toLowerCase().contains("transfer-encoding: chunked");
                byte[] bodyBytes = java.util.Arrays.copyOfRange(all, split + 4, all.length);

                String text;
                if (chunked) {
                    java.io.ByteArrayOutputStream dec = new java.io.ByteArrayOutputStream();
                    int pos = 0;
                    while (pos < bodyBytes.length) {
                        int nl = indexOfCrLf(bodyBytes, pos);
                        if (nl < 0) break;
                        String sizeStr = new String(bodyBytes, pos, nl - pos, "ISO-8859-1").trim();
                        int semi = sizeStr.indexOf(';');
                        if (semi > 0) sizeStr = sizeStr.substring(0, semi);
                        int size;
                        try {
                            size = Integer.parseInt(sizeStr, 16);
                        } catch (Exception e) {
                            break;
                        }
                        if (size <= 0) break;
                        pos = nl + 2;
                        int end = Math.min(pos + size, bodyBytes.length);
                        dec.write(bodyBytes, pos, end - pos);
                        pos = end + 2;
                    }
                    text = new String(dec.toByteArray(), "UTF-8");
                } else {
                    text = new String(bodyBytes, "UTF-8");
                }

                com.gaeul.allin.json.JSONObject ret = new com.gaeul.allin.json.JSONObject();
                ret.put("status", status);
                ret.put("body", text);
                return ret.toString();
            } catch (Throwable t) {
                try {
                    com.gaeul.allin.json.JSONObject ret = new com.gaeul.allin.json.JSONObject();
                    ret.put("status", 0);
                    ret.put("body", "");
                    ret.put("error", String.valueOf(t));
                    return ret.toString();
                } catch (Exception ignored) {
                    return "{\"status\":0,\"body\":\"\"}";
                }
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        private int indexOfCrLf(byte[] data, int from) {
            for (int i = from; i + 1 < data.length; i++) {
                if (data[i] == '\r' && data[i + 1] == '\n') return i;
            }
            return -1;
        }
    }

    /** 等网关端口起来再加载面板；最多重试约 12 秒，超时退回本地 assets 兜底页 */
    private void loadPanelWithRetry(final String httpUrl, final String fileUrl, final int attempt) {
        if (webView == null) return;
        if (attempt == 0) {
            webView.loadUrl(fileUrl); // 先给出本地兜底页，避免长时间白屏
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean up = false;
                try {
                    java.net.Socket s = new java.net.Socket();
                    s.connect(new java.net.InetSocketAddress("127.0.0.1", 3425), 400);
                    s.close();
                    up = true;
                } catch (Throwable ignored) {
                }
                final boolean ready = up;
                if (!ready && attempt < 40) {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ignored) {
                    }
                    loadPanelWithRetry(httpUrl, fileUrl, attempt + 1);
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (webView == null) return;
                        if (ready) {
                            Log.i("Magpie", "loading dashboard from " + httpUrl);
                            webView.loadUrl(httpUrl);
                        } else {
                            Log.w("Magpie", "gateway not ready, keep local dashboard");
                        }
                    }
                });
            }
        }).start();
    }

    /** 读 config.json 里的 server.authKey；读不到返回空串（面板会提示需要口令） */
    private String readGatewayAuthKey() {
        java.io.FileInputStream in = null;
        try {
            java.io.File f = new java.io.File(getFilesDir(), "config.json");
            if (!f.exists()) return "";
            in = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
            com.gaeul.allin.json.JSONObject c = new com.gaeul.allin.json.JSONObject(new String(bo.toByteArray(), "UTF-8"));
            com.gaeul.allin.json.JSONObject server = c.optJSONObject("server");
            return server == null ? "" : server.optString("authKey", "").trim();
        } catch (Exception e) {
            return "";
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 申请 POST_NOTIFICATIONS 运行时权限（Android 13+）。
     * 精简 android.jar 里没有 Activity.requestPermissions，只能反射调用。
     * @return true 表示已发起申请，false 表示无需申请或申请失败（调用方应退回设置页）
     */
    private boolean requestNotificationPermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT < 33) return false;
            String perm = "android.permission.POST_NOTIFICATIONS";
            if (checkCallingOrSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) return false; // 已授予
            Method m = android.app.Activity.class.getMethod(
                    "requestPermissions", String[].class, int.class);
            m.invoke(this, new Object[]{new String[]{perm}, Integer.valueOf(REQ_NOTIFICATION)});
            return true;
        } catch (Throwable t) {
            Log.w("Magpie", "requestNotificationPermission failed: " + t.getMessage());
            return false;
        }
    }

    private static final int REQ_NOTIFICATION = 1001;

    // 注意：精简 android.jar 的 Activity 没有声明 onRequestPermissionsResult，
    // 这里不能写 @Override 也不能调 super（编译期不存在），但运行期框架会按
    // 方法签名正常回调到它，效果与覆写一致。
    @SuppressWarnings("unused")
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_NOTIFICATION) {
            boolean granted = grantResults != null && grantResults.length > 0 && grantResults[0] == 0;
            Toast.makeText(this,
                    granted ? "通知权限已授予，网关可常驻" : "通知权限被拒绝，请在系统设置中手动开启",
                    Toast.LENGTH_SHORT).show();
            if (webView != null) {
                webView.loadUrl("javascript:if(typeof loadPermissions==='function'){loadPermissions();}");
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isGatewayEnabledPref()) {
            GatewayService.requestHealthCheck(this, "resume");
            GatewayService.refreshKeepAlive(this);
        }
        if (webView != null) {
            webView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    pushGatewayStateToWeb();
                    if (GatewayService.isRunning()) {
                        webView.loadUrl("javascript:if(typeof loadConfig==='function'){loadConfig();}");
                    }
                }
            }, 400);
        }
    }

    // ===================== 网关开关核心逻辑 =====================

    private boolean isGatewayEnabledPref() {
        try {
            SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            return sp.getBoolean(PREF_GATEWAY_ENABLED, true);
        } catch (Throwable t) {
            return true;
        }
    }

    private void setGatewayEnabledPref(boolean enabled) {
        try {
            SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            sp.edit().putBoolean(PREF_GATEWAY_ENABLED, enabled).apply();
        } catch (Throwable ignored) {}
    }

    public boolean startGatewayService() {
        try {
            Intent serviceIntent = new Intent(this, GatewayService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    Method m = Context.class.getMethod("startForegroundService", Intent.class);
                    m.invoke(this, serviceIntent);
                } catch (Exception e) {
                    startService(serviceIntent);
                }
            } else {
                startService(serviceIntent);
            }
            Log.i("Magpie", "Gateway service start requested");
            return true;
        } catch (Exception e) {
            Log.e("Magpie", "Start service error: " + e.getMessage());
            return false;
        }
    }

    public boolean stopGatewayService() {
        try {
            Intent serviceIntent = new Intent(this, GatewayService.class);
            stopService(serviceIntent);
            Log.i("Magpie", "Gateway service stop requested");
            return true;
        } catch (Exception e) {
            Log.e("Magpie", "Stop service error: " + e.getMessage());
            return false;
        }
    }

    // 把真实运行状态推送给前端
    private void pushGatewayStateToWeb() {
        if (webView == null) return;
        final boolean running = GatewayService.isRunning();
        final String js = "javascript:(function(){"
                + "if(typeof window.__setGatewayState==='function'){"
                + "window.__setGatewayState(" + running + "," + GATEWAY_PORT + ");"
                + "}})();";
        webView.post(new Runnable() {
            @Override
            public void run() {
                try {
                    webView.loadUrl(js);
                } catch (Throwable ignored) {}
            }
        });
    }

    private boolean panelBackPending;

    @Override
    public void onBackPressed() {
        if (panelBackPending) return;
        final WebView panel = webView;
        if (panel == null || !bridgeAllowed()) {
            finishBackNavigation();
            return;
        }
        final String page = currentPageUrl;
        panelBackPending = true;
        try {
            panel.evaluateJavascript("(function(){try{return typeof window.allInHandleBack==='function'"
                    + " && window.allInHandleBack()===true;}catch(e){return false;}})()",
                    new ValueCallback<String>() {
                        @Override public void onReceiveValue(String handled) {
                            panelBackPending = false;
                            // A navigation or destroyed activity makes this callback obsolete.
                            if (isFinishing() || isDestroyed() || panel != webView
                                    || !page.equals(currentPageUrl) || !bridgeAllowed()) return;
                            if (!"true".equals(handled)) finishBackNavigation();
                        }
                    });
        } catch (RuntimeException failure) {
            panelBackPending = false;
            finishBackNavigation();
        }
    }

    private void finishBackNavigation() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (backupController != null && backupController.onActivityResult(requestCode, resultCode, data)) return;
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onDestroy() {
        if (backupController != null) backupController.destroy();
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
