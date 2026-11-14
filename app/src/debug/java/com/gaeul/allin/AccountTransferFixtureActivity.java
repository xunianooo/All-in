package com.gaeul.allin;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.widget.*;
import com.gaeul.allin.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Debug-only native file integration host; never shipped in the signed release. */
public final class AccountTransferFixtureActivity extends Activity {
    private AccountTransferController controller;
    private TextView status;
    private CheckBox fail;
    private volatile boolean failWrites;
    private final Object mutation=new Object();
    private File config;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(!getPackageName().endsWith(".repairtest"))throw new SecurityException("isolatedTestInstall required");
        config=new File(getFilesDir(),"account-transfer-fixture.json");
        if(!config.exists())writeFixture(true);
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(24,28,24,24);
        status=new TextView(this);status.setTextSize(17);layout.addView(status);
        fail=new CheckBox(this);fail.setText("模拟提交失败（仅测试账号）");layout.addView(fail);fail.setOnCheckedChangeListener((button,checked)->failWrites=checked);
        AccountTransferPlan.Backend backend=new AccountTransferPlan.Backend(){
            public byte[] readConfig()throws Exception{synchronized(mutation){try(InputStream in=new FileInputStream(config)){return AccountTransferCodec.readBounded(in);}}}
            public JSONObject commit(byte[] expected,AccountTransferCodec.Document doc,Set<Integer> selected,boolean updates,long now)throws Exception{
                synchronized(mutation){byte[] bytes=readConfig();try{
                    if(!java.security.MessageDigest.isEqual(expected,AccountTransferCodec.digest(bytes)))throw AccountTransferCodec.invalid("测试快照已变化");
                    AccountTransferPlan.Result result=AccountTransferPlan.merge(new JSONObject(AccountTransferCodec.decode(bytes)),doc,selected,updates,now);
                    if(failWrites)throw new IOException("injected storage refusal");
                    AtomicConfigFile.write(config,result.config.toString(2));return result.summary;
                }finally{Arrays.fill(bytes,(byte)0);}}
            }
        };
        controller=new AccountTransferController(this,backend,result->{
            try{AtomicConfigFile.write(new File(getFilesDir(),"account-transfer-fixture-result.json"),result.toString());}catch(Exception ignored){}
            updateStatus(result.optString("action")+" 成功 saved="+result.optInt("saved"));
        });
        button(layout,"导出测试账号JSON",()->controller.open("export","workbuddy"));
        button(layout,"导入测试账号JSON",()->controller.open("import","workbuddy"));
        button(layout,"清空测试目标账号",()->{if(!controller.busy()){writeFixture(false);updateStatus("测试目标已清空");}});
        button(layout,"恢复初始测试账号",()->{if(!controller.busy()){writeFixture(true);updateStatus("初始测试账号已恢复");}});
        button(layout,"读取测试账号数量",()->updateStatus("当前状态"));
        setContentView(layout);updateStatus("隔离文件测试");
    }
    private void button(LinearLayout layout,String text,Runnable action){Button b=new Button(this);b.setText(text);b.setOnClickListener(v->action.run());layout.addView(b);}
    private void writeFixture(boolean populated){
        synchronized(mutation){try{
            JSONArray providers=new JSONArray();for(String id:AccountTransferCodec.PROVIDERS)providers.put(new JSONObject().put("id",id).put("enabled",true).put("accounts",new JSONArray()).put("keys",new JSONArray()).put("models",new JSONArray()));
            JSONObject root=new JSONObject().put("providers",providers).put("server",new JSONObject().put("authKey","fixture-local-gateway-only")).put("checkin",new JSONObject().put("autoEnabled",false)).put("guard",new JSONObject().put("maxOutput",8888));
            if(populated){
                for(int i=0;i<providers.length();i++){
                    JSONObject p=providers.getJSONObject(i);String id=p.getString("id");
                    if(id.equals("workbuddy"))for(int n=1;n<=2;n++)p.getJSONArray("accounts").put(account("wb-"+n,n==1));
                    if(id.equals("raccoon"))p.getJSONArray("accounts").put(account("raccoon-1",false));
                    if(id.equals("openai"))p.getJSONArray("keys").put(new JSONObject().put("key","fixture-only-openai-key").put("label","测试独立Key").put("off",true));
                }
            }
            AtomicConfigFile.write(config,root.toString(2));
        }catch(Exception failure){throw new IllegalStateException("fixture initialization failed");}}
    }
    private JSONObject account(String id,boolean enabled){return new JSONObject().put("id",id).put("name","本地测试 "+id).put("uid","fixture-owner-"+id).put("token","fixture-only-access-"+id).put("access","fixture-only-access-"+id).put("refresh","fixture-only-refresh-"+id).put("type","oauth").put("enabled",enabled).put("expires",1900000000000L).put("addedAt",1700000000000L);}
    private void updateStatus(String prefix){try{
        JSONObject root;try(InputStream in=new FileInputStream(config)){root=new JSONObject(AccountTransferCodec.decode(AccountTransferCodec.readBounded(in)));}
        int accounts=0,keys=0;JSONArray ps=root.getJSONArray("providers");for(int i=0;i<ps.length();i++){JSONObject p=ps.getJSONObject(i);accounts+=p.getJSONArray("accounts").length();keys+=p.getJSONArray("keys").length();}
        status.setText(prefix+"\n账号="+accounts+" Key="+keys+"\n只操作 repairtest 内的合成数据，不调用供应商。");
    }catch(Exception failure){status.setText("读取测试状态失败");}}
    @Override protected void onActivityResult(int request,int result,Intent data){if(controller!=null&&controller.onActivityResult(request,result,data))return;super.onActivityResult(request,result,data);}
    @Override protected void onDestroy(){if(controller!=null)controller.destroy();super.onDestroy();}
}
