package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class AccountExportSelectionOfflineTest {
    private static final Set<String> API=Set.of("workbuddy-ai","deepseek","siliconflow","openai","anthropic","openrouter","tencent");
    private static int count;
    private static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);count++;}
    private static final class Backend implements AccountTransferPlan.Backend {
        JSONObject config;
        Runnable intervening;
        int exports;
        Backend(JSONObject c){config=c;}
        public byte[] readConfig(){return config.toString().getBytes(StandardCharsets.UTF_8);}
        public byte[] export(byte[] hash,Set<Integer> selected,long now)throws Exception{
            exports++;
            if(intervening!=null){Runnable once=intervening;intervening=null;once.run();}
            return AccountTransferPlan.Backend.super.export(hash,selected,now);
        }
        public JSONObject commit(byte[] hash,AccountTransferCodec.Document doc,Set<Integer> indexes,boolean updates,long now){throw new AssertionError("export attempted a write");}
    }
    private static JSONObject account(String id){
        JSONObject a=new JSONObject().put("id",id).put("name","fixture").put("token","fixture-"+id).put("refresh","fixture-refresh-"+id).put("enabled",false);
        switch(id){
            case "zcode": a.put("jwt","fixture-"+id).put("device","local-fixture"); break;
            case "trae-cn": a.put("deviceId","local-fixture").put("devicePrivateKey","fixture-placeholder"); break;
            case "codex": a.put("accountId","local-fixture").put("authMode","chatgpt"); break;
            default: break;
        }
        return a;
    }
    private static JSONObject block(String id){return new JSONObject().put("id",id).put("accounts",new JSONArray()).put("keys",new JSONArray());}
    private static AccountExportSelection snapshot(Backend b,Set<Integer> indexes){return new AccountExportSelection(AccountTransferCodec.digest(b.readConfig()),AccountTransferCodec.exportEntries(b.config),indexes);}
    private static void expectRefusal(AccountExportSelection selected,Backend b) throws Exception {
        int before=b.exports;
        try{selected.prepare(b);throw new AssertionError("changed or ambiguous account accepted");}catch(AccountTransferCodec.TransferException expected){check(b.exports==before,"no serializer invoked on changed selection");}
    }
    public static void main(String[] args)throws Exception{
        List<String> ids=AccountTransferCodec.PROVIDERS;
        List<JSONObject> entries=new ArrayList<JSONObject>();
        JSONArray providers=new JSONArray();
        for(String id:ids){JSONObject p=block(id);p.getJSONArray(API.contains(id)?"keys":"accounts").put(API.contains(id)?new JSONObject().put("key","fixture-"+id).put("off",true):account(id));entries.add(p);providers.put(p);}
        Backend b=new Backend(new JSONObject().put("providers",providers));
        Set<Integer> all=new LinkedHashSet<>();
        for(int i=0;i<ids.size();i++)all.add(i);
        AccountExportSelection combined=snapshot(b,all);
        List<AccountExportSelection> singles=new ArrayList<AccountExportSelection>();for(int n:all)singles.add(snapshot(b,Collections.singleton(n)));
        // Reverse provider/index order and change unrelated settings between selection and save.
        JSONArray permuted=new JSONArray();for(int i=entries.size()-1;i>=0;i--)permuted.put(entries.get(i));
        b.config.put("providers",permuted).put("unrelated",true);
        for(int i:all){
            String id=ids.get(i);byte[] bytes=singles.get(i).prepare(b);check(bytes.length>0,id+" bytes");
            AccountTransferCodec.Document output=AccountTransferCodec.parse(bytes,"");check(output.entries.size()==1,id+" exact count");
            AccountTransferCodec.Entry row=output.entries.get(0);check(row.valid()&&id.equals(row.provider),id+" valid roundtrip");
        }
        check(AccountTransferCodec.parse(combined.prepare(b),"").entries.size()==20,"all 20 across provider reorder");
        int w=ids.indexOf("workbuddy");JSONArray wb=entries.get(w).getJSONArray("accounts");
        b.intervening=()->b.config.put("unrelated","updated during serialization");int attempts=b.exports;
        check(singles.get(w).prepare(b).length>0&&b.exports-attempts==2,"CAS conflict retries only with fresh selected match");
        wb.getJSONObject(0).put("token","fixture-different");expectRefusal(singles.get(w),b);
        wb.put(0,account("workbuddy"));wb.put(account("workbuddy"));expectRefusal(singles.get(w),b);
        System.out.println("AccountExportSelectionOfflineTest PASS: "+count+" checks");
    }
}
