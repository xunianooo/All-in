package com.gaeul.allin;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.*;

import android.widget.FrameLayout;
import android.widget.Toast;

import java.lang.reflect.Method;

public class MainActivity extends Activity {
    private static final String PREFS_NAME = "magpie_prefs";
    private static final String PREF_GATEWAY_ENABLED = "gateway_enabled";
    private static final int GATEWAY_PORT = 3425;

    private WebView webView;
    private final Handler panelRefreshHandler = new Handler(Looper.getMainLooper());
    private int statusBarInsetPx;
    private int navBarInsetPx;
    private final BridgeStreamOwner bridgeStreams = new BridgeStreamOwner();
    private final java.util.concurrent.ThreadPoolExecutor bridgeExecutor = new java.util.concurrent.ThreadPoolExecutor(
            0, 16, 30L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.SynchronousQueue<Runnable>(),
            r -> {
                Thread t = new Thread(r, "AllIn-BridgeRequest");
                t.setDaemon(true);
                return t;
            },
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private BridgeRequestRegistry bridgeRegistry;
    private volatile long bridgePageGeneration;
    private volatile boolean bridgeDestroyed;
    private synchronized BridgeRequestRegistry bridgeRegistry() {
        if (bridgeRegistry == null) bridgeRegistry = new BridgeRequestRegistry();
        return bridgeRegistry;
    }
    private long bridgePageGeneration() { return bridgePageGeneration; }
    private void cancelBridgeCalls() {
        synchronized (this) {
            bridgePageGeneration++;
            bridgeRegistry().cancelAll(bridgeDestroyed);
        }
    }
    private boolean registerBridgeCall(String reqId, LocalBridge.Call call, long expectedGeneration) {
        synchronized (this) {
            if (bridgeDestroyed || bridgePageGeneration != expectedGeneration) {
                call.cancel();
                return false;
            }
            BridgeRequestRegistry registry = bridgeRegistry();
            return registry.register(reqId, call, registry.generation());
        }
    }

    private BackupActivityController backupController;
    private AccountTransferController accountTransferController;
    /** 当前 WebView 正在显示的页面地址；原生桥只认面板自己的页面 */
    private volatile String currentPageUrl = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Object retained=getLastNonConfigurationInstance();
        String exportName=savedInstanceState==null?null:savedInstanceState.getString(AccountTransferController.PENDING_EXPORT_NAME);
        if(retained instanceof AccountTransferController.PendingExport || exportName!=null)
            accountTransfer().restoreExport(retained instanceof AccountTransferController.PendingExport
                    ? (AccountTransferController.PendingExport)retained:null,exportName);
        applySystemBarsInternal(isSystemDark());
        AppUpdateManager.schedule(this);

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

        // 构建全屏且具备完整触控焦点的 WebView
        webView = new WebView(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        );
        webView.setLayoutParams(params);
        webView.setOnApplyWindowInsetsListener((view, insets) -> {
            WindowInsets rootInsets = view.getRootWindowInsets();
            WindowInsets systemInsets = rootInsets != null ? rootInsets : insets;
            int top;
            int bottom;
            int imeBottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets topInsets = systemInsets.getInsets(
                        WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets navInsets = systemInsets.getInsets(WindowInsets.Type.navigationBars());
                android.graphics.Insets imeInsets = systemInsets.getInsets(WindowInsets.Type.ime());
                top = topInsets.top;
                bottom = navInsets.bottom;
                imeBottom = imeInsets.bottom;
            } else {
                top = Math.max(systemInsets.getStableInsetTop(), systemInsets.getSystemWindowInsetTop());
                if (Build.VERSION.SDK_INT >= 28 && systemInsets.getDisplayCutout() != null) {
                    top = Math.max(top, systemInsets.getDisplayCutout().getSafeInsetTop());
                }
                bottom = systemInsets.getStableInsetBottom();
                imeBottom = Math.max(0, systemInsets.getSystemWindowInsetBottom() - bottom);
            }
            statusBarInsetPx = top;
            navBarInsetPx = imeBottom > 0 ? 0 : bottom;
            if (view.getPaddingBottom() != imeBottom) {
                view.setPadding(0, 0, 0, imeBottom);
            }
            updateWebStatusBarInset();
            return Build.VERSION.SDK_INT >= 30 ? WindowInsets.CONSUMED : insets.consumeSystemWindowInsets();
        });

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
        // Packaged assets remain available; gateway API requests use the native bridge.
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
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
                m.invoke(settings, 1); // 1 = MIXED_CONTENT_NEVER_ALLOW
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
                synchronized (MainActivity.this) {
                    currentPageUrl = url == null ? "" : url;
                    bridgeStreams.cancelAll();
                    cancelBridgeCalls();
                }
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                if (view == webView && !bridgeDestroyed && "file:///android_asset/dashboard.html".equals(url)) {
                    try {
                        return new android.webkit.WebResourceResponse("text/html", "UTF-8",
                                PanelThemePage.readAndSeed(getAssets().open("dashboard.html"), isSystemDark()));
                    } catch (java.io.IOException | IllegalArgumentException failure) {
                        Log.w("Magpie", "Local panel theme bootstrap unavailable", failure);
                    }
                }
                if (isTrustedPanelResource(url)) return null;
                return new android.webkit.WebResourceResponse("text/plain", "UTF-8", new java.io.ByteArrayInputStream(new byte[0]));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (view != webView || bridgeDestroyed || isFinishing() || isDestroyed()) return;
                currentPageUrl = url == null ? "" : url;
                notifySystemTheme(view);
                updateWebStatusBarInset();
                pushGatewayStateToWeb();
                postPanelRefresh(view, () -> {
                    pushGatewayStateToWeb();
                    if (GatewayService.isRunning()) {
                        view.loadUrl("javascript:if(typeof refreshGatewayPanel==='function'){refreshGatewayPanel();}");
                    }
                });
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                return !request.isForMainFrame() || shouldOverrideUrlLoading(view, request.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri destination = url == null ? null : Uri.parse(url);
                String route = null;
                if (destination != null && "magpie".equals(destination.getScheme())) {
                    if (view != webView || !bridgeAllowed()) return true;
                    route = destination.getAuthority();
                }
                // 一键连 ETA：拉起 ETA 主界面（不写对方数据，纯 startActivity，不需要 root）
                if ("eta".equals(route)) {
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
                if ("gateway".equals(route)) {
                    String flag = destination.getQueryParameter("on");
                    if (!"0".equals(flag) && !"1".equals(flag)) return true;
                    boolean enable = "1".equals(flag);
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
                if ("settings".equals(route)) {
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
                if ("login".equals(route)) {
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
        webView.requestApplyInsets();
    }

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

    private void updateWebStatusBarInset() {
        if (webView == null) return;
        if (!bridgeAllowed() && !"file:///android_asset/licenses.html".equals(currentPageUrl)) return;
        float density = webView.getResources().getDisplayMetrics().density;
        int topDp = (int) Math.ceil(statusBarInsetPx / density);
        int bottomDp = (int) Math.ceil(navBarInsetPx / density);
        webView.evaluateJavascript(
                "document.documentElement.style.setProperty('--native-status-bar-inset','" + topDp + "px');"
                + "document.documentElement.style.setProperty('--native-nav-bar-inset','" + bottomDp + "px');",
                null);
    }

    private void applySystemBarsInternal(boolean dark) {
        Window window = getWindow();
        int background = dark ? android.graphics.Color.BLACK : 0xFFF4F4F7;
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
        window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }
        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(background));

        // Full edge-to-edge: ensure DecorView is installed before touching WindowInsetsController.
        View decor = window.getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController controller = decor != null ? decor.getWindowInsetsController() : null;
            if (controller != null) {
                int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(dark ? 0 : mask, mask);
            }
        }
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        if (!dark) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        if (decor != null) decor.setSystemUiVisibility(flags);
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

    /** 检查已在清单声明可见的 ETA 启动界面。 */
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
        return false;
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
        if (bridgeDestroyed) return false;
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
        private void updateResult(final android.webkit.WebView view, final long generation, final com.gaeul.allin.json.JSONObject result) {
            if(view==null)return;
            view.post(()->{if(bridgeAllowed()&&view==webView&&generation==bridgePageGeneration())try{view.evaluateJavascript("window.onAppUpdateState&&window.onAppUpdateState("+result.toString()+")",null);}catch(RuntimeException ignored){}});
        }
        @android.webkit.JavascriptInterface
        public void checkAppUpdate(boolean manual) {
            if(!bridgeAllowed())return;final long generation=bridgePageGeneration();final android.webkit.WebView view=webView;
            AppUpdateManager.check(MainActivity.this,manual,result->updateResult(view,generation,result));
        }
        @android.webkit.JavascriptInterface
        public void dismissAppUpdate(String key) {
            if(!bridgeAllowed()||key==null||key.length()>256)return;
            updateResult(webView,bridgePageGeneration(),AppUpdateManager.core(MainActivity.this).dismiss(key));
        }
        @android.webkit.JavascriptInterface
        public boolean openAppUpdatePage() {
            if(!bridgeAllowed())return false;
            runOnUiThread(()->{if(!bridgeAllowed())return;if(!AppUpdateManager.openBrowser(MainActivity.this))evalJs(webView,"window.onAppUpdateOpenError&&window.onAppUpdateOpenError()");});
            return true;
        }

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
        public boolean openAccountTransfer(final String action,final String providerId) {
            if(!bridgeAllowed() || !("import".equals(action)||"export".equals(action))
                    || AccountTransferCodec.canonicalProvider(providerId).isEmpty())return false;
            runOnUiThread(()-> {
                if(!bridgeAllowed()||isFinishing()||isDestroyed())return;
                if(backupController!=null&&backupController.busy()){Toast.makeText(MainActivity.this,"已有备份操作进行中，请先完成或取消",Toast.LENGTH_SHORT).show();return;}
                accountTransfer().open(action,providerId);
            });
            return true;
        }

        @android.webkit.JavascriptInterface
        public boolean openEncryptedBackup(final String action) {
            if (!bridgeAllowed() || !("export".equals(action) || "import".equals(action) || "rollback".equals(action))) return false;
            runOnUiThread(() -> {
                if (!bridgeAllowed() || isFinishing()) return;
                if(accountTransferController!=null&&accountTransferController.busy()){Toast.makeText(MainActivity.this,"已有账号文件操作进行中，请先完成或取消",Toast.LENGTH_SHORT).show();return;}
                if (backupController == null) backupController = new BackupActivityController(MainActivity.this, GatewayService.backupBackend(MainActivity.this));
                backupController.open(action);
            });
            return true;
        }

        /** 同步调用（保留兼容，短请求用；长请求请用 requestAsync 避免卡住 JS 线程） */
        @android.webkit.JavascriptInterface
        public String request(String method, String pathAndQuery, String body) {
            final long generation = bridgePageGeneration();
            if (!bridgeAllowed()) {
                return "{\"status\":403,\"body\":\"bridge blocked: 非面板页面\"}";
            }
            return doRequest(method, pathAndQuery, body, generation);
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
            return copyTextInternal("http://"+ip+":3425/#manage="+key, true);
        }

        /** 把文本放进剪贴板（面板「一键导入 ETA」用；WebView 里 navigator.clipboard 不稳） */
        @android.webkit.JavascriptInterface
        public boolean copyText(String text) {
            return copyTextInternal(text, false);
        }

        private boolean copyTextInternal(String text, boolean sensitive) {
            if (!bridgeAllowed() || text == null) return false;
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                if (cm == null) return false;
                android.content.ClipData clip = android.content.ClipData.newPlainText("All In", text);
                if (sensitive && Build.VERSION.SDK_INT >= 33) {
                    android.os.PersistableBundle extras = new android.os.PersistableBundle();
                    extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
                    clip.getDescription().setExtras(extras);
                }
                cm.setPrimaryClip(clip);
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
            final BridgeStreamOwner.Ticket ticket = bridgeStreams.begin(reqId, 180000);
            if (ticket == null) {
                if (!bridgeStreams.activeId(reqId)) evalJs(webView, "window.__allInStreamEnd && window.__allInStreamEnd("
                        + com.gaeul.allin.json.JSONObject.quote(reqId) + ",409,\"已有流式测试正在运行\")");
                return;
            }
            final WebView view = webView;
            Thread worker = new Thread(() -> {
                try {
                    LocalBridge.stream(request.method, request.pathAndQuery, body, ticket.call,
                            new LocalBridge.StreamCallback() {
                                public boolean onLine(String line) {
                                    if (!bridgeStreams.accepts(ticket)) return false;
                                    evalJs(view, "window.__allInStreamChunk && window.__allInStreamChunk("
                                            + com.gaeul.allin.json.JSONObject.quote(reqId) + ","
                                            + com.gaeul.allin.json.JSONObject.quote(line) + ")", ticket);
                                    return bridgeStreams.accepts(ticket);
                                }
                                public void onDone(int status, String error) {
                                    evalJs(view, "window.__allInStreamEnd && window.__allInStreamEnd("
                                            + com.gaeul.allin.json.JSONObject.quote(reqId) + "," + status + ","
                                            + com.gaeul.allin.json.JSONObject.quote(error == null ? "" : error) + ")", ticket);
                                }
                            });
                } finally { ticket.call.close(); bridgeStreams.finished(ticket); }
            }, "AllIn-Stream");
            try { worker.start(); }
            catch (RuntimeException cannotStart) {
                ticket.call.close(); bridgeStreams.finished(ticket);
                evalJs(view, "window.__allInStreamEnd && window.__allInStreamEnd("
                        + com.gaeul.allin.json.JSONObject.quote(reqId) + ",0,\"无法启动流式测试\")", ticket);
            }
        }

        @android.webkit.JavascriptInterface
        public void cancelStream(String reqId) {
            if (bridgeAllowed() && reqId != null) bridgeStreams.cancel(reqId);
        }

        private void evalJs(final WebView view, final String script) { evalJs(view, script, null); }
        private void evalJs(final WebView view, final String script, final BridgeStreamOwner.Ticket ticket) {
            if (view == null || (ticket != null && !bridgeStreams.accepts(ticket))) return;
            view.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (bridgeAllowed() && view == webView && (ticket == null || bridgeStreams.accepts(ticket)))
                            view.evaluateJavascript(script, null);
                    } catch (Throwable ignored) {
                    }
                }
            });
        }

        /**
         * 异步调用：通过有界线程池执行，完成后回调用 JS 的 __allInBridgeDone(id, result)。
         * 同步版会把 WebView 的 JS 线程整个堵住，遇到「同步官方模型」这类要打外网、
         * 十几秒才返回的接口，页面就会完全点不动。异步后页面始终保持响应。
         */
        @android.webkit.JavascriptInterface
        public void cancelRequest(String reqId) {
            if (!bridgeAllowed() || reqId == null || reqId.isEmpty()) return;
            bridgeRegistry().cancelById(reqId);
        }

        private void postBridgeDone(final WebView target, final String reqId, final long pageGeneration, final String result) {
            if (target == null) return;
            final String script = "__allInBridgeDone("
                    + com.gaeul.allin.json.JSONObject.quote(reqId) + ","
                    + com.gaeul.allin.json.JSONObject.quote(result) + ")";
            target.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (bridgeAllowed() && pageGeneration == bridgePageGeneration() && target == webView)
                            target.evaluateJavascript(script, null);
                    } catch (Throwable ignored) {
                    }
                }
            });
        }

        @android.webkit.JavascriptInterface
        public void requestAsync(final String reqId, final String method, final String pathAndQuery, final String body) {
            if (reqId == null || reqId.isEmpty() || !bridgeAllowed()) return;
            final long pageGeneration = bridgePageGeneration();
            final WebView view = webView;
            final LocalBridge.Call call = new LocalBridge.Call(90000);
            // 提交线程池前先登记可取消的 Call，消除「前端立即 cancelRequest 早于工作线程启动」的竞态窗口
            if (!registerBridgeCall(reqId, call, pageGeneration)) return;
            try {
                bridgeExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        String result;
                        try {
                            result = doRequestWithCall(method, pathAndQuery, body, call);
                        } finally {
                            bridgeRegistry().release(reqId, call);
                        }
                        postBridgeDone(webView, reqId, pageGeneration, result);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                bridgeRegistry().release(reqId, call);
                postBridgeDone(view, reqId, pageGeneration, "{\"status\":503,\"body\":\"\",\"error\":\"bridge busy\"}");
            } catch (RuntimeException | Error unexpected) {
                bridgeRegistry().release(reqId, call);
                throw unexpected;
            }
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
            return doRequest(method, pathAndQuery, body, bridgePageGeneration());
        }

        private String doRequest(String method, String pathAndQuery, String body, long expectedGeneration) {
            LocalBridge.Call call = new LocalBridge.Call(90000);
            if (!registerBridgeCall(null, call, expectedGeneration)) {
                return "{\"status\":0,\"body\":\"\",\"error\":\"cancelled\"}";
            }
            try {
                return doRequestWithCall(method, pathAndQuery, body, call);
            } finally {
                bridgeRegistry().release(null, call);
            }
        }

        private String doRequestWithCall(String method, String pathAndQuery, String body, LocalBridge.Call call) {
            if (call.cancelled()) {
                return "{\"status\":0,\"body\":\"\",\"error\":\"cancelled\"}";
            }
            final BridgeRequestValidator.Request request;
            try {
                // 面板可控的 method/path 在拼请求行之前必须过校验：拦住 CRLF、控制字符、
                // 绝对 URI 与异常长度，避免把注入内容写进发往本机网关的请求行。
                request = BridgeRequestValidator.validate(method, pathAndQuery);
            } catch (IllegalArgumentException rejected) {
                return bridgeRejected(rejected.getMessage());
            }
            if (call.cancelled()) {
                return "{\"status\":0,\"body\":\"\",\"error\":\"cancelled\"}";
            }
            String m = request.method;
            String path = request.pathAndQuery;
            LocalBridge.Result result = LocalBridge.requestResult(m, path, body, call);
            com.gaeul.allin.json.JSONObject ret = new com.gaeul.allin.json.JSONObject();
            ret.put("status", result.status);
            ret.put("body", result.body);
            if (!result.transportError.isEmpty()) ret.put("error", result.transportError);
            return ret.toString();
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
        final WebView view = webView;
        if (view != null) {
            postPanelRefresh(view, () -> {
                pushGatewayStateToWeb();
                notifySystemTheme(view);
                view.loadUrl("javascript:window.appUpdateResume&&window.appUpdateResume()");
                if (GatewayService.isRunning()) {
                    view.loadUrl("javascript:if(typeof loadConfig==='function'){loadConfig();}");
                }
            });
        }
    }

    private void notifySystemTheme(WebView view) {
        if (view != webView || bridgeDestroyed || !bridgeAllowed() || isFinishing() || isDestroyed()) return;
        // The snapshot is only for startup; live bridge/media updates take over once the page is ready.
        view.evaluateJavascript("window.__allinInitialSystemDark=null;"
                + "if(typeof window.__onSystemThemeChanged==='function'){window.__onSystemThemeChanged();}", null);
    }

    private void postPanelRefresh(WebView view, Runnable refresh) {
        panelRefreshHandler.postDelayed(() -> {
            if (view == webView && !isFinishing() && !isDestroyed() && bridgeAllowed()) refresh.run();
        }, 400);
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
        final WebView view = webView;
        if (view == null || bridgeDestroyed) return;
        final boolean running = GatewayService.isRunning();
        final String js = "(function(){"
                + "if(typeof window.__setGatewayState==='function'){"
                + "window.__setGatewayState(" + running + "," + GATEWAY_PORT + ");"
                + "}})();";
        view.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (view == webView && !bridgeDestroyed && bridgeAllowed()) {
                        view.evaluateJavascript(js, null);
                    }
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

    private AccountTransferController accountTransfer() {
        if(accountTransferController==null)accountTransferController=new AccountTransferController(this,
                GatewayService.accountTransferBackend(this),summary->{
            if(webView!=null&&bridgeAllowed()&&!isFinishing()&&!isDestroyed())
                webView.evaluateJavascript("window.onAccountTransferFinished&&window.onAccountTransferFinished("+summary.toString()+");",null);
        });
        return accountTransferController;
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        return accountTransferController==null?null:accountTransferController.retainExport();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        AccountTransferController.PendingExport pending=accountTransferController==null?null:accountTransferController.retainExport();
        // After process death only the randomized cleanup name can be restored, not the selection.
        if(pending!=null)state.putString(AccountTransferController.PENDING_EXPORT_NAME,pending.name);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode==AccountTransferController.EXPORT_DOCUMENT) {
            accountTransfer().onActivityResult(requestCode,resultCode,data);
            return;
        }
        if (accountTransferController != null && accountTransferController.onActivityResult(requestCode, resultCode, data)) return;
        if (backupController != null && backupController.onActivityResult(requestCode, resultCode, data)) return;
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onDestroy() {
        bridgeDestroyed = true;
        panelRefreshHandler.removeCallbacksAndMessages(null);
        bridgeStreams.close();
        cancelBridgeCalls();
        bridgeExecutor.shutdownNow();
        if (accountTransferController != null) accountTransferController.destroy();
        if (backupController != null) backupController.destroy();
        WebView view = webView;
        webView = null;
        if (view != null) {
            view.removeJavascriptInterface("AllInNative");
            if (view.getParent() instanceof android.view.ViewGroup)
                ((android.view.ViewGroup) view.getParent()).removeView(view);
            view.stopLoading();
            view.destroy();
        }
        super.onDestroy();
    }
}
