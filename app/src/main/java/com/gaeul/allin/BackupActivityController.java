package com.gaeul.allin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import java.io.*;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native-only passwords and confirmation; never returns secrets to JavaScript. */
final class BackupActivityController {
    private static final int EXPORT = 29101, IMPORT = 29102;
    private final Activity activity;
    private final BackupCoordinator.Backend backend;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean destroyed;
    private boolean busy, hadSecure, secured;
    private final Object ownership = new Object();
    private char[] pendingPassword;
    private BackupCoordinator.Preview pendingPreview;
    private AlertDialog dialog;
    private EditText passwordField, confirmationField;

    BackupActivityController(Activity activity, BackupCoordinator.Backend backend) { this.activity = activity; this.backend = backend; }
    void open(String action) {
        if (destroyed || busy) return;
        if (Build.VERSION.SDK_INT < 26) { message("加密备份需要 Android 8.0 或更高版本。"); return; }
        if (!"export".equals(action) && !"import".equals(action) && !"rollback".equals(action)) return;
        busy = true;
        hadSecure = (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); secured = true;
        if ("rollback".equals(action)) passwordDialog(action, null); else {
            try {
                Intent intent = new Intent("export".equals(action) ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                // Providers may label an unfamiliar .aibk extension with a different
                // MIME type. Import validates bounded authenticated content itself.
                intent.setType("export".equals(action) ? "application/octet-stream" : "*/*");
                if ("export".equals(action)) intent.putExtra(Intent.EXTRA_TITLE, "all-in-backup.aibk");
                activity.startActivityForResult(intent, "export".equals(action) ? EXPORT : IMPORT);
            } catch (Exception e) { finish(); message("无法打开系统文件选择器。"); }
        }
    }
    boolean onActivityResult(int request, int result, Intent data) {
        if (request != EXPORT && request != IMPORT) return false;
        if (!busy || destroyed) return true;
        Uri uri = data == null ? null : data.getData();
        if (result != Activity.RESULT_OK || uri == null) { finish(); return true; }
        passwordDialog(request == EXPORT ? "export" : "import", uri); return true;
    }
    private void passwordDialog(final String action, final Uri uri) {
        final EditText first = new EditText(activity), second = new EditText(activity);
        passwordField = first; confirmationField = second;
        first.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        second.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        first.setHint("备份密码"); second.setHint("再次输入密码");
        LinearLayout fields = new LinearLayout(activity); fields.setOrientation(LinearLayout.VERTICAL); fields.addView(first);
        if ("export".equals(action)) fields.addView(second);
        dialog = new AlertDialog.Builder(activity).setTitle("export".equals(action) ? "设置备份密码" : "输入备份密码")
                .setMessage("备份包含账号、令牌和密钥；不含用量账本、隐私脱敏密钥或会话映射。请妥善保存密码。")
                .setView(fields).setNegativeButton("取消", (d,w) -> { first.setText(""); second.setText(""); finish(); })
                .setPositiveButton("继续", (d,w) -> {
                    char[] p = chars(first), p2 = chars(second); first.setText(""); second.setText("");
                    if (p.length == 0 || p.length > 1024 || ("export".equals(action) && !Arrays.equals(p,p2))) {
                        EncryptedConfigBackup.clear(p); EncryptedConfigBackup.clear(p2); finish(); message("密码为空、过长或两次输入不一致。"); return;
                    }
                    EncryptedConfigBackup.clear(p2);
                    submit(new BackupOwnedTask() {
                        protected void execute() { perform(action, uri, p); }
                        protected void clear() { EncryptedConfigBackup.clear(p); }
                    });
                }).setOnCancelListener(d -> { first.setText(""); second.setText(""); finish(); }).create();
        dialog.show();
    }
    private static char[] chars(EditText field) { android.text.Editable e = field.getText(); char[] out = new char[e.length()]; e.getChars(0, e.length(), out, 0); return out; }
    private void perform(String action, Uri uri, char[] password) {
        byte[] config = null, encrypted = null;
        char[] snapshotPassword = null;
        BackupCoordinator.Preview preview = null;
        try {
            if ("export".equals(action)) {
                config = backend.readConfig(); encrypted = EncryptedConfigBackup.encrypt(config, password);
                if (destroyed || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                try (OutputStream out = activity.getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new IOException(); out.write(encrypted); out.flush();
                }
                post(() -> { finish(); message("加密备份已导出。"); });
            } else {
                if ("rollback".equals(action)) encrypted = BackupFiles.readFile(new File(activity.getFilesDir(), "config-rollback.aibk"), EncryptedConfigBackup.MAX_BACKUP_BYTES);
                else try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException(); encrypted = BackupFiles.read(in, EncryptedConfigBackup.MAX_BACKUP_BYTES);
                }
                snapshotPassword = password.clone();
                preview = BackupCoordinator.preview(backend, encrypted, password);
                if (destroyed || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                final BackupCoordinator.Preview ready = preview; preview = null;
                final char[] pass = snapshotPassword; snapshotPassword = null;
                postOrDispose(ready, pass);
            }
        } catch (Exception e) { post(() -> { finish(); message("操作未完成。请检查文件、密码或网关状态后重试。"); }); }
        finally { EncryptedConfigBackup.clear(password); EncryptedConfigBackup.clear(snapshotPassword); EncryptedConfigBackup.clear(config); EncryptedConfigBackup.clear(encrypted); if (preview != null) preview.close(); }
    }
    private void postOrDispose(final BackupCoordinator.Preview preview, final char[] password) {
        synchronized (ownership) {
            if (destroyed) { preview.close(); EncryptedConfigBackup.clear(password); return; }
            pendingPreview = preview; pendingPassword = password;
        }
        activity.runOnUiThread(() -> {
            synchronized (ownership) { if (destroyed || pendingPreview != preview) return; }
            org.json.JSONObject s = preview.summary;
            String text = "供应商：" + s.optInt("providers") + "\n账号：" + s.optInt("accounts") + "\n密钥：" + s.optInt("keys")
                    + "\n服务配置：" + (s.optBoolean("serverChanged") ? "与当前不同" : "相同")
                    + "\n\n恢复会替换全部配置并停止当前请求。包含账号令牌与密钥；不恢复用量账本、隐私脱敏密钥及会话映射。恢复前会保存可用本次密码解密的本机密文回退快照。";
            dialog = new AlertDialog.Builder(activity).setTitle("确认恢复配置？").setMessage(text)
                    .setNegativeButton("取消", (d,w) -> finish()).setOnCancelListener(d -> finish())
                    .setPositiveButton("确认恢复", (d,w) -> {
                        synchronized (ownership) {
                            if (destroyed || pendingPreview != preview) return;
                            pendingPreview = null; pendingPassword = null;
                        }
                        submit(new BackupOwnedTask() {
                            protected void execute() {
                                try { if (destroyed) return; BackupCoordinator.apply(backend, preview, password);
                                    post(() -> { finish(); message("配置已恢复。"); });
                                } catch (Exception e) { post(() -> { finish(); message("恢复失败；请检查配置是否已变化或网关是否可用。若回退失败，请使用本机密文快照恢复。"); }); }
                            }
                            protected void clear() { preview.close(); EncryptedConfigBackup.clear(password); }
                        });
                    }).create(); dialog.show();
        });
    }
    private void submit(BackupOwnedTask task) {
        synchronized (ownership) {
            if (destroyed) { task.close(); return; }
            try { worker.execute(task); }
            catch (java.util.concurrent.RejectedExecutionException rejected) { task.close(); finish(); message("操作已取消。"); }
        }
    }
    private void post(Runnable action) { if (!destroyed) activity.runOnUiThread(() -> { if (!destroyed) action.run(); }); }
    private void message(String text) { if (!destroyed) Toast.makeText(activity, text, Toast.LENGTH_LONG).show(); }
    private void finish() {
        synchronized (ownership) {
            EncryptedConfigBackup.clear(pendingPassword); pendingPassword = null;
            if (pendingPreview != null) pendingPreview.close(); pendingPreview = null;
        }
        if (passwordField != null) passwordField.setText("");
        if (confirmationField != null) confirmationField.setText("");
        passwordField = confirmationField = null;
        busy = false; dialog = null;
        if (secured && !hadSecure) activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE); secured = false;
    }
    void destroy() {
        synchronized (ownership) { destroyed = true; }
        if (dialog != null) dialog.dismiss();
        for (Runnable task : worker.shutdownNow()) if (task instanceof BackupOwnedTask) ((BackupOwnedTask)task).close();
        finish();
    }
}
