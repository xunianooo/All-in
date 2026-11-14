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
    /** Durable configuration the gateway restores, inside the app-private directory. */
    private static final String CONFIG_FILE = "config.json";
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
    boolean busy() { return busy; }
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
    /** False only for bytes that are not a readable configuration; interruption is never damage. */
    private static boolean readableConfig(byte[] config) throws InterruptedException {
        try { EncryptedConfigBackup.validateConfig(config); return true; }
        catch (InterruptedException interrupted) { throw interrupted; }
        catch (Exception damaged) { return false; }
    }
    private void perform(String action, Uri uri, char[] password) {
        byte[] config = null, encrypted = null;
        char[] snapshotPassword = null;
        BackupCoordinator.Preview preview = null;
        try {
            if ("export".equals(action)) {
                config = backend.readConfig();
                // The v1 envelope only wraps valid configurations, so a damaged file cannot be
                // exported; say why instead of failing with the generic message.
                if (!readableConfig(config)) {
                    post(() -> { finish(); message("当前配置文件已损坏，无法生成加密备份。可以从已有备份恢复，或先修复配置后再导出。"); });
                    return;
                }
                encrypted = EncryptedConfigBackup.encrypt(config, password);
                if (destroyed || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                try (OutputStream out = activity.getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new IOException(); out.write(encrypted); out.flush();
                }
                post(() -> { finish(); message("加密备份已导出。"); });
            } else {
                if ("rollback".equals(action)) {
                    File snapshot = new File(activity.getFilesDir(), "config-rollback.aibk");
                    if (!snapshot.isFile()) {
                        post(() -> { finish(); message("本机没有可用的回退快照。请改用备份文件恢复。"); });
                        return;
                    }
                    encrypted = BackupFiles.readFile(snapshot, EncryptedConfigBackup.MAX_BACKUP_BYTES);
                } else try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException(); encrypted = BackupFiles.read(in, EncryptedConfigBackup.MAX_BACKUP_BYTES);
                }
                snapshotPassword = password.clone();
                preview = BackupCoordinator.preview(backend, encrypted, password);
                if (destroyed || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                final BackupCoordinator.Preview ready = preview; preview = null;
                final char[] pass = snapshotPassword; snapshotPassword = null;
                final boolean fromSnapshot = "rollback".equals(action);
                postOrDispose(ready, pass, fromSnapshot);
            }
        } catch (Exception e) { post(() -> { finish(); message("操作未完成。请检查文件、密码或网关状态后重试。"); }); }
        finally { EncryptedConfigBackup.clear(password); EncryptedConfigBackup.clear(snapshotPassword); EncryptedConfigBackup.clear(config); EncryptedConfigBackup.clear(encrypted); if (preview != null) preview.close(); }
    }
    private void postOrDispose(final BackupCoordinator.Preview preview, final char[] password, final boolean fromSnapshot) {
        synchronized (ownership) {
            if (destroyed) { preview.close(); EncryptedConfigBackup.clear(password); return; }
            pendingPreview = preview; pendingPassword = password;
        }
        activity.runOnUiThread(() -> {
            synchronized (ownership) { if (destroyed || pendingPreview != preview) return; }
            com.gaeul.allin.json.JSONObject s = preview.summary;
            final boolean damaged = preview.currentDamaged;
            // The snapshot slot only knows its newest generation; after a damaged-configuration
            // restore it still holds an older one, so say so instead of offering it silently.
            final boolean stale = fromSnapshot
                    && new File(activity.getFilesDir(), BackupRestoreTransaction.STALE_MARKER).isFile();
            String text = "供应商：" + s.optInt("providers") + "\n账号：" + s.optInt("accounts") + "\n密钥：" + s.optInt("keys")
                    + "\n服务配置：" + (damaged ? "无法比较（当前配置已损坏）" : s.optBoolean("serverChanged") ? "与当前不同" : "相同")
                    + "\n\n恢复会替换全部配置并停止当前请求。包含账号令牌与密钥；不恢复用量账本、隐私脱敏密钥及会话映射。"
                    + (fromSnapshot ? "\n本次使用此前保存的回退快照；恢复成功后该快照会被本次恢复前的状态覆盖。" : "")
                    + (stale ? "\n\n注意：上一次恢复针对的是已损坏的配置，没有生成新的回退快照。下面这个快照来自更早的一次恢复，可能比现在更旧。" : "")
                    + (damaged
                        ? "\n\n当前配置文件已损坏、无法解析。恢复会先把原始内容另存为带内容指纹的救援副本（config.corrupt-*.json）并回读校验，再替换为本次备份；损坏内容完整保留在该副本中。本次不会生成可用同一密码解密的回退快照，救援副本就是恢复前状态的依据。"
                        : "\n恢复前会保存可用本次密码解密的本机密文回退快照。");
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
                                    final String rescued = preview.rescuedAs;
                                    post(() -> { finish(); message(rescued == null || rescued.isEmpty()
                                            ? "配置已恢复。"
                                            // The app has no screen that lists the private directory, so
                                            // do not promise a one-tap retrieval that does not exist.
                                            : "配置已恢复。损坏的原配置已完整保留为 " + rescued
                                              + "（应用私有目录）。应用内暂无取回入口，如需该文件请通过应用备份/文件工具从私有目录导出。"); });
                                } catch (Exception e) {
                                    // A failed restore can stop after the durable configuration was
                                    // already replaced (a failed rollback write is exactly that
                                    // case), so the outcome is measured on disk, never assumed.
                                    final boolean damaged = preview.currentDamaged;
                                    final String rescued = preview.rescuedAs;
                                    final DurableOutcome outcome = outcomeAfterFailure(damaged, rescued);
                                    post(() -> { finish(); message(restoreFailureMessage(damaged, outcome, rescued)); });
                                }
                            }
                            protected void clear() { preview.close(); EncryptedConfigBackup.clear(password); }
                        });
                    }).create(); dialog.show();
        });
    }
    /** What a failed restore provably did to the durable configuration; never an inference. */
    enum DurableOutcome { UNCHANGED, REPLACED, UNKNOWN }
    /**
     * Compares the preserved pre-restore bytes with the durable configuration as it is after the
     * failure. Identical bytes prove the file was left alone, different bytes prove it was not
     * (a restore that could not roll back leaves the incoming backup on disk), and anything
     * unreadable or absent stays UNKNOWN instead of being reported as either one.
     */
    static DurableOutcome durableOutcome(byte[] preserved, byte[] durable) {
        if (preserved == null || preserved.length == 0 || durable == null) return DurableOutcome.UNKNOWN;
        return Arrays.equals(preserved, durable) ? DurableOutcome.UNCHANGED : DurableOutcome.REPLACED;
    }
    /**
     * Measures the durable state after a failed restore. Only a damaged pre-restore configuration
     * leaves a rescue copy holding its exact bytes, so a readable one is reported as UNKNOWN: the
     * rollback snapshot is encrypted and comparing it would need a second decryption path.
     */
    static DurableOutcome outcomeAfterFailure(boolean damaged, String rescued, File privateDirectory) {
        if (!damaged || rescued == null || rescued.isEmpty() || privateDirectory == null) return DurableOutcome.UNKNOWN;
        byte[] preserved = null, durable = null;
        try {
            preserved = BackupFiles.readFile(new File(privateDirectory, rescued), EncryptedConfigBackup.MAX_CONFIG_BYTES);
            durable = BackupFiles.readFile(new File(privateDirectory, CONFIG_FILE), EncryptedConfigBackup.MAX_CONFIG_BYTES);
            return durableOutcome(preserved, durable);
        } catch (Exception unreadable) { return DurableOutcome.UNKNOWN; }
        finally { EncryptedConfigBackup.clear(preserved); EncryptedConfigBackup.clear(durable); }
    }
    private DurableOutcome outcomeAfterFailure(boolean damaged, String rescued) {
        return outcomeAfterFailure(damaged, rescued, activity.getFilesDir());
    }
    /**
     * Failure text that states only what was verified. "未被替换" is claimed solely for
     * {@link DurableOutcome#UNCHANGED}; a failed restore-and-rollback never reports it.
     */
    static String restoreFailureMessage(boolean damaged, DurableOutcome outcome, String rescued) {
        boolean named = rescued != null && !rescued.isEmpty();
        if (damaged && outcome == DurableOutcome.UNKNOWN)
            // The rescue copy could not be read back here, so its existence is only suggested.
            return "恢复失败；无法确认当前配置是否已被替换（恢复可能在替换之后才失败）。"
                    + (named ? "若恢复前已生成救援副本，它位于应用私有目录的 " + rescued + "。"
                             : "本次恢复没有留下损坏配置的救援副本。")
                    + "请重新导入备份，或检查应用私有目录中的配置状态。";
        String copy = named ? "损坏的原配置已完整保留为 " + rescued + "（应用私有目录）。" : "";
        if (damaged) {
            if (outcome == DurableOutcome.UNCHANGED)
                return "恢复失败；当前配置仍是恢复前的内容，未被替换。" + copy + "请检查网关状态后重新导入备份。";
            // REPLACED: the incoming backup is on disk and the rollback is what failed.
            return "恢复失败且未能回退：当前配置已不是恢复前的内容，网关可能无法启动。" + copy
                    + "请重新导入备份，或从该副本核对损坏前的配置。";
        }
        return "恢复失败；请检查配置是否已变化或网关是否可用。若提示无法回退，恢复前的加密快照会保留在应用私有目录"
                + "（config-rollback*.aibk），可用本次备份密码解密。";
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
