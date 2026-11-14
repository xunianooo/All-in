package com.gaeul.allin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.view.WindowManager;
import android.widget.Toast;
import com.gaeul.allin.json.JSONObject;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Native-only SAF flow. Plain credentials never enter JavaScript, clipboard, Bundle or logs. */
final class AccountTransferController {
    static final int EXPORT_DOCUMENT=29201, IMPORT_DOCUMENT=29202;
    interface Listener { void completed(JSONObject safeSummary); }
    private final Activity activity;
    private final AccountTransferPlan.Backend backend;
    private final Listener listener;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Object ownership=new Object();
    private volatile boolean destroyed;
    private volatile boolean busy;
    private boolean hadSecure;
    private volatile long generation;
    private int awaitingRequest;
    private Future<?> pending;
    private AlertDialog dialog;
    private String provider="", action="", fileName="";
    private byte[] expectedHash;
    private JSONObject snapshot;
    private AccountTransferCodec.Document document;
    private Set<Integer> selected=new LinkedHashSet<>();
    AccountTransferController(Activity activity,AccountTransferPlan.Backend backend,Listener listener) {
        this.activity=activity;this.backend=backend;this.listener=listener;
    }
    boolean busy() { return busy; }
    void open(String mode,String providerId) {
        if(destroyed||busy)return;
        String p=AccountTransferCodec.canonicalProvider(providerId);
        if(!Arrays.asList("import","export").contains(mode)||p.isEmpty()){message("此供应商尚不支持账号文件迁移");return;}
        busy=true;generation++;provider=p;action=mode;
        hadSecure=(activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0;
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if("import".equals(mode)){chooseDocument(false);return;}
        final long ticket=generation;
        execute(ticket,()-> {
            byte[] bytes=backend.readConfig();
            try {
                JSONObject current=new JSONObject(AccountTransferCodec.decode(bytes));
                byte[] hash=AccountTransferCodec.digest(bytes);
                ui(ticket,()->{snapshot=current;expectedHash=hash;chooseExportAccounts(ticket);});
            } finally { Arrays.fill(bytes,(byte)0); }
        });
    }
    private void chooseExportAccounts(long ticket) {
        List<AccountTransferCodec.Entry> entries=AccountTransferCodec.exportEntries(snapshot);
        if(entries.isEmpty()){finish();message("当前没有可导出的账号或API Key");return;}
        String[] labels=new String[entries.size()];boolean[] checked=new boolean[entries.size()];
        for(int i=0;i<entries.size();i++) {
            AccountTransferCodec.Entry e=entries.get(i);
            labels[i]=e.provider+" · "+e.label()+(e.value.optBoolean("enabled",true)&&!e.value.optBoolean("off",false)?"":" [停用]");
            checked[i]=provider.equals(e.provider);
        }
        dialog=new AlertDialog.Builder(activity).setTitle("导出账号 · 可跨供应商多选")
                .setMultiChoiceItems(labels,checked,(d,which,on)->checked[which]=on)
                .setNegativeButton("取消",(d,w)->finish()).setPositiveButton("继续",null)
                .setOnCancelListener(d->finish()).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            Set<Integer> choice=new LinkedHashSet<>();for(int i=0;i<checked.length;i++)if(checked[i])choice.add(entries.get(i).index);
            if(choice.isEmpty()){message("请至少选择一个账号");return;}
            selected=choice;dialog.dismiss();confirmExport(ticket);
        }));dialog.show();
    }
    private void confirmExport(long ticket) {
        if(!active(ticket))return;
        dialog=new AlertDialog.Builder(activity).setTitle("保存账号JSON文件")
                .setMessage("将导出所选 "+selected.size()+" 条账号/密钥。\n\nJSON包含完整登录凭据，相当于账号钥匙；请只保存到自己的可信位置，不要发到群聊或公开仓库。\n\n迁移后建议停用旧设备对应账号，避免两台设备同时续期。")
                .setNegativeButton("取消",(d,w)->finish()).setPositiveButton("选择保存位置",(d,w)->chooseDocument(true))
                .setOnCancelListener(d->finish()).create();dialog.show();
    }
    private void chooseDocument(boolean export) {
        try {
            Intent intent=new Intent(export?Intent.ACTION_CREATE_DOCUMENT:Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            // Some document providers label JSON as text/plain or application/octet-stream.
            // Content is strictly decoded and validated; extensions are never trusted.
            intent.setType(export?"application/json":"*/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if(export) {
                fileName="AllIn-"+provider+"-"+new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date())+".json";
                intent.putExtra(Intent.EXTRA_TITLE,fileName);intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
            awaitingRequest=export?EXPORT_DOCUMENT:IMPORT_DOCUMENT;
            activity.startActivityForResult(intent,awaitingRequest);
        } catch(Exception failed){finish();message("无法打开系统文件选择器，请检查系统文件应用");}
    }
    boolean onActivityResult(int request,int result,Intent data) {
        if(request!=EXPORT_DOCUMENT&&request!=IMPORT_DOCUMENT)return false;
        if(!busy||destroyed||request!=awaitingRequest)return true;
        awaitingRequest=0;
        Uri uri=data==null?null:data.getData();
        if(result!=Activity.RESULT_OK||uri==null){finish();message("已取消，账号未改动");return true;}
        if(!"content".equals(uri.getScheme())){finish();message("请选择系统文件管理器提供的文档");return true;}
        final long ticket=generation;
        if(request==EXPORT_DOCUMENT)writeDocument(ticket,uri);else readDocument(ticket,uri);
        return true;
    }
    private void writeDocument(long ticket,Uri uri) {
        final Set<Integer> choice=new LinkedHashSet<>(selected);
        final byte[] expected=expectedHash==null?null:expectedHash.clone();
        execute(ticket,()-> {
            byte[] current=null,bytes=null,readback=null;
            try {
                current=backend.readConfig();
                if(expected==null||!java.security.MessageDigest.isEqual(expected,AccountTransferCodec.digest(current)))
                    throw AccountTransferCodec.invalid("导出选择后账号已变化，请重新选择，未写入凭据");
                bytes=backend.export(expected,choice,System.currentTimeMillis());
                if(!active(ticket)||Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                try(OutputStream stream=activity.getContentResolver().openOutputStream(uri,"wt")) {
                    if(stream==null)throw new IOException();stream.write(bytes);stream.flush();
                }
                // A successful chooser or opened stream is not a successful export. Read the
                // actual persisted document back before reporting that it was saved.
                try(InputStream stream=activity.getContentResolver().openInputStream(uri)) {
                    if(stream==null)throw new IOException();readback=AccountTransferCodec.readBounded(stream);
                }
                if(!Arrays.equals(bytes,readback))throw new IOException();
                JSONObject summary=new JSONObject().put("action","export").put("ok",true).put("saved",choice.size());
                ui(ticket,()->{finish();message("已保存并校验 "+choice.size()+" 条账号/密钥到JSON文件");listener.completed(summary);});
            } finally { clear(current);clear(bytes);clear(readback);clear(expected); }
        });
    }
    private void readDocument(long ticket,Uri uri) {
        execute(ticket,()-> {
            byte[] bytes=null,current=null;
            try {
                try(InputStream stream=activity.getContentResolver().openInputStream(uri)) {
                    if(stream==null)throw new IOException();bytes=AccountTransferCodec.readBounded(stream);
                }
                AccountTransferCodec.Document parsed=AccountTransferCodec.parse(bytes,provider);
                current=backend.readConfig();JSONObject config=new JSONObject(AccountTransferCodec.decode(current));
                byte[] hash=AccountTransferCodec.digest(current);
                ui(ticket,()-> { document=parsed;snapshot=config;expectedHash=hash;offerUnknown(ticket); });
            } finally { clear(bytes);clear(current); }
        });
    }
    private void offerUnknown(long ticket) {
        int count=0;for(AccountTransferCodec.Entry e:document.entries)if(e.provider.isEmpty())count++;
        if(count==0){previewImport(ticket);return;}
        dialog=new AlertDialog.Builder(activity).setTitle("有 "+count+" 条记录无法确定供应商")
                .setMessage("已识别的记录可正常导入。未知记录不会被自动塞入当前供应商。可逐条指定，或跳过后继续预览。")
                .setPositiveButton("逐条指定",(d,w)->assignNext(ticket,0))
                .setNeutralButton("跳过未知项",(d,w)->previewImport(ticket))
                .setNegativeButton("取消",(d,w)->finish()).setOnCancelListener(d->finish()).create();dialog.show();
    }
    private void assignNext(long ticket,int from) {
        if(!active(ticket))return;
        int index=from;while(index<document.entries.size()&&!document.entries.get(index).provider.isEmpty())index++;
        if(index>=document.entries.size()){previewImport(ticket);return;}
        final int at=index;AccountTransferCodec.Entry entry=document.entries.get(at);
        List<String> ids=new ArrayList<>();
        com.gaeul.allin.json.JSONArray ps=snapshot.optJSONArray("providers");
        for(int i=0;ps!=null&&i<ps.length();i++){JSONObject p=ps.optJSONObject(i);if(p!=null&&AccountTransferCodec.PROVIDERS.contains(p.optString("id")))ids.add(p.getString("id"));}
        dialog=new AlertDialog.Builder(activity).setTitle("指定供应商 · "+entry.label())
                .setItems(ids.toArray(new String[0]),(d,which)->{AccountTransferCodec.assign(entry,ids.get(which));assignNext(ticket,at+1);})
                .setNegativeButton("跳过此条",(d,w)->assignNext(ticket,at+1))
                .setNeutralButton("返回预览",(d,w)->previewImport(ticket)).setOnCancelListener(d->previewImport(ticket)).create();dialog.show();
    }
    private void previewImport(long ticket) {
        if(!active(ticket))return;
        execute(ticket,()-> {
            List<AccountTransferPlan.Row> rows=AccountTransferPlan.preview(snapshot,document);
            ui(ticket,()->showImportPreview(ticket,rows));
        });
    }
    private void showImportPreview(long ticket,List<AccountTransferPlan.Row> rows) {
        if(!active(ticket))return;
        String[] labels=new String[rows.size()];boolean[] checked=new boolean[rows.size()];int added=0,updates=0,skipped=0;
        for(int i=0;i<rows.size();i++) {
            AccountTransferPlan.Row r=rows.get(i);labels[i]=r.text();checked[i]="new".equals(r.action);
            if("new".equals(r.action))added++;else if("update".equals(r.action))updates++;else skipped++;
        }
        dialog=new AlertDialog.Builder(activity).setTitle("导入预览：新增"+added+" / 待更新"+updates+" / 跳过"+skipped)
                .setMultiChoiceItems(labels,checked,(d,which,on)->{
                    if(!rows.get(which).selectable()){checked[which]=false;((AlertDialog)d).getListView().setItemChecked(which,false);message("此条不可导入："+rows.get(which).reason);}
                    else checked[which]=on;
                }).setNegativeButton("取消",(d,w)->finish()).setPositiveButton("导入所选",null)
                .setOnCancelListener(d->finish()).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            Set<Integer> choice=new LinkedHashSet<>();int replacing=0;
            for(int i=0;i<rows.size();i++)if(checked[i]&&rows.get(i).selectable()){choice.add(rows.get(i).entry.index);if("update".equals(rows.get(i).action))replacing++;}
            if(choice.isEmpty()){message("请勾选可导入记录；重复项无需再次导入");return;}
            selected=choice;dialog.dismiss();confirmImport(ticket,replacing);
        }));dialog.show();
    }
    private void confirmImport(long ticket,int updates) {
        dialog=new AlertDialog.Builder(activity).setTitle(updates>0?"确认更新现有账号":"确认导入账号")
                .setMessage("将保存 "+selected.size()+" 条记录"+(updates>0?"，其中 "+updates+" 条会更新同一身份的现有账号资料":"")+"。\n\n不删除其他账号，不改网关口令和路由。导入不会验证登录有效性、发起推理或领取；迁移当日保留签到防重复保护。\n\n过期、缺少资料或设备绑定不兼容时仍可能需要重新授权。")
                .setNegativeButton("返回预览",(d,w)->previewImport(ticket))
                .setPositiveButton(updates>0?"确认更新并导入":"确认导入",(d,w)->commitImport(ticket,updates>0))
                .setOnCancelListener(d->previewImport(ticket)).create();dialog.show();
    }
    private void commitImport(long ticket,boolean updates) {
        final Set<Integer> choice=new LinkedHashSet<>(selected);final byte[] hash=expectedHash.clone();
        final AccountTransferCodec.Document parsed=document;
        execute(ticket,()-> {
            try {
                final JSONObject summary;
                synchronized(ownership) {
                    if(!active(ticket)||Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                    summary=backend.commit(hash,parsed,choice,updates,System.currentTimeMillis());
                }
                ui(ticket,()->{
                    finish();message("导入已保存：新增 "+summary.optInt("added")+"，更新 "+summary.optInt("updated")+"，跳过 "+summary.optInt("skipped")+"。登录有效性尚未验证。");
                    listener.completed(summary);
                    if(summary.optBoolean("runtimeWarning"))message("账号已保存，但网关运行状态需检查，请查看状态页");
                });
            } finally { clear(hash); }
        });
    }
    private interface Job { void run() throws Exception; }
    private boolean active(long ticket) { return !destroyed&&busy&&generation==ticket&&!activity.isFinishing()&&!activity.isDestroyed(); }
    private void ui(long ticket,Runnable action) { activity.runOnUiThread(()->{if(active(ticket))try{action.run();}catch(Exception failed){finish();message("无法显示账号预览，请检查文件后重试");}}); }
    private void execute(long ticket,Job task) {
        if(!active(ticket))return;
        pending=worker.submit(()->{
            try { task.run(); }
            catch(Exception failure) {
                String text=failure instanceof IllegalArgumentException?failure.getMessage():"操作未完成：请检查文件内容、存储权限；账号刚有变化时请重新预览。";
                // Parser/storage exception bodies can contain private document content. Only
                // our codec's explicitly generated safe failures may enter a user-facing label.
                if(!(failure instanceof AccountTransferCodec.TransferException))text="操作未完成：请检查文件与存储权限，稍后重新选择或预览。原有账号不会被清空。";
                final String safe=text;ui(ticket,()->{finish();message(safe);});
            }
        });
    }
    private void message(String text) { if(!destroyed)Toast.makeText(activity,text,Toast.LENGTH_LONG).show(); }
    private static void clear(byte[] bytes) { if(bytes!=null)Arrays.fill(bytes,(byte)0); }
    private void finish() {
        synchronized(ownership) {
            busy=false;awaitingRequest=0;generation++;clear(expectedHash);expectedHash=null;snapshot=null;
            if(document!=null)document.entries.clear();document=null;selected.clear();
            if(dialog!=null){dialog.dismiss();dialog=null;}
            if(!hadSecure)activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }
    void destroy() {
        synchronized(ownership){destroyed=true;if(pending!=null)pending.cancel(true);finish();}
        worker.shutdownNow();
    }
}
