package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class AccountTransferMergeOfflineTest {
    static int checks;
    static final long NOW=1794648600000L;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static AccountTransferCodec.Document doc(JSONObject... accounts){JSONArray a=new JSONArray();for(JSONObject o:accounts)a.put(o);return AccountTransferCodec.parse(a.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),"raccoon");}
    static Set<Integer> select(Integer... indexes){return new LinkedHashSet<>(Arrays.asList(indexes));}
    static JSONObject p(JSONObject c,String id){return AccountTransferCodecOfflineTest.provider(c,id);}
    static JSONObject a(String id){return AccountTransferCodecOfflineTest.account(id).put("enabled",true);}
    static void reject(Runnable action){try{action.run();throw new AssertionError("unsafe merge accepted");}catch(IllegalArgumentException expected){checks++;}}
    public static void main(String[] args)throws Exception {
        JSONObject current=AccountTransferCodecOfflineTest.config();JSONObject original=new JSONObject(current.toString());
        AccountTransferCodec.Document document=doc(a("r1").put("enabled",false),a("w1").put("providerId","workbuddy"));
        List<AccountTransferPlan.Row> rows=AccountTransferPlan.preview(current,document);check(rows.size()==2&&rows.stream().allMatch(r->r.action.equals("new")),"mixed accounts identified as new");
        check(current.similar(original),"preview has zero mutation");
        AccountTransferPlan.Result merged=AccountTransferPlan.merge(current,document,select(0,1),false,NOW);
        check(current.similar(original),"merge works on detached copy");check(merged.summary.getInt("added")==2,"accurate added count");check(!merged.summary.getBoolean("loginVerified"),"not authentication success");
        check(p(merged.config,"raccoon").getJSONArray("accounts").length()==1&&p(merged.config,"workbuddy").getJSONArray("accounts").length()==1,"mixed file actually enters separate pools");
        JSONObject raccoon=p(merged.config,"raccoon").getJSONArray("accounts").getJSONObject(0);check(!raccoon.getBoolean("enabled"),"disabled import stays disabled");
        check(p(merged.config,"raccoon").getJSONArray("keys").length()==0,"disabled token not activated via mirror");
        check(p(merged.config,"workbuddy").getJSONArray("keys").length()==1,"active token mirror synchronized");
        check(merged.config.getJSONObject("server").similar(current.getJSONObject("server"))&&merged.config.getJSONArray("groups").similar(current.getJSONArray("groups")),"global settings unchanged");
        for(String id:AccountTransferCodec.PROVIDERS)if(!Arrays.asList("raccoon","workbuddy").contains(id))check(p(merged.config,id).similar(p(current,id)),"unselected provider unchanged");
        rows=AccountTransferPlan.preview(merged.config,document);check(rows.stream().allMatch(r->r.action.equals("duplicate")),"repeated import idempotent");
        reject(()->AccountTransferPlan.merge(merged.config,document,select(0),false,NOW));
        JSONObject updatedAccount=a("r1").put("token","fixture-new-access").put("access","fixture-new-access").put("refresh","fixture-new-refresh").put("enabled",false);
        AccountTransferCodec.Document update=doc(updatedAccount);check(AccountTransferPlan.preview(merged.config,update).get(0).action.equals("update"),"different credentials require explicit update");
        reject(()->AccountTransferPlan.merge(merged.config,update,select(0),false,NOW));
        AccountTransferPlan.Result revised=AccountTransferPlan.merge(merged.config,update,select(0),true,NOW+1);
        JSONObject after=p(revised.config,"raccoon").getJSONArray("accounts").getJSONObject(0);
        check(after.getString("id").equals(raccoon.getString("id"))&&after.getLong("addedAt")==raccoon.getLong("addedAt"),"same identity retains local stable id/order");
        check(after.getString("refresh").equals("fixture-new-refresh"),"full credential bundle updated");check(!after.getBoolean("enabled"),"update cannot silently enable disabled account");
        JSONObject incomplete=new JSONObject(updatedAccount.toString());incomplete.remove("refresh");AccountTransferCodec.Document missing=doc(incomplete);
        reject(()->AccountTransferPlan.merge(merged.config,missing,select(0),true,NOW));
        JSONObject sameName=a("other").put("name",raccoon.getString("name"));
        check(AccountTransferPlan.preview(merged.config,doc(sameName)).get(0).action.equals("new"),"same nickname is not same identity");
        JSONObject otherDomain=a("r1").put("domain","enterprise-other");check(AccountTransferPlan.preview(merged.config,doc(otherDomain)).get(0).action.equals("new"),"same uid in a different domain is separate");
        JSONObject duplicateId=a("new-owner").put("id",raccoon.getString("id"));AccountTransferPlan.Result collision=AccountTransferPlan.merge(merged.config,doc(duplicateId),select(0),false,NOW);
        check(!p(collision.config,"raccoon").getJSONArray("accounts").getJSONObject(1).getString("id").equals(raccoon.getString("id")),"foreign local id collision remapped safely");
        JSONObject protectedConfig=new JSONObject(merged.config.toString());p(protectedConfig,"raccoon").getJSONArray("accounts").getJSONObject(0).put("refreshPending",true);
        check(!AccountTransferPlan.preview(protectedConfig,update).get(0).selectable(),"unknown refresh cannot be cleared by import");
        reject(()->AccountTransferPlan.merge(protectedConfig,update,select(0),true,NOW));
        AccountTransferCodec.Document repeated=doc(a("repeat"),a("repeat"));List<AccountTransferPlan.Row> repeatedRows=AccountTransferPlan.preview(current,repeated);
        check(repeatedRows.get(0).action.equals("new")&&repeatedRows.get(1).action.equals("duplicate"),"file duplicate only one selected");
        AccountTransferCodec.Document conflicting=doc(a("repeat"),a("repeat").put("token","different").put("access","different"));
        check(AccountTransferPlan.preview(current,conflicting).stream().noneMatch(AccountTransferPlan.Row::selectable),"conflicting records cannot win by file order");
        AccountTransferCodec.Document unknown=AccountTransferCodec.parse("{\"not-supported\":{\"token\":\"fixture-token\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8),"workbuddy");
        reject(()->AccountTransferPlan.merge(current,unknown,select(0),false,NOW));
        JSONObject withKey=new JSONObject(current.toString());p(withKey,"raccoon").getJSONArray("keys").put("fixture-independent-old");
        AccountTransferPlan.Result preserveKey=AccountTransferPlan.merge(withKey,doc(a("a")),select(0),false,NOW);
        check(p(preserveKey.config,"raccoon").getJSONArray("keys").toList().contains("fixture-independent-old"),"independent legacy keys not overwritten");
        check(p(preserveKey.config,"raccoon").getJSONArray("keys").length()==2,"new mirror plus original independent key");
        // Migration creates a conservative daily fence, never a fabricated successful reward.
        JSONObject cfg=preserveKey.config;JSONObject imported=p(cfg,"raccoon").getJSONArray("accounts").getJSONObject(0);
        JSONObject fence=cfg.getJSONObject("checkin").getJSONObject("records").getJSONObject("raccoon:"+imported.getString("id"));
        check(fence.getString("outcome").equals("unknown")&&!fence.getBoolean("verified")&&fence.getBoolean("retryBlocked"),"new import fenced without fake check-in success");
        AtomicInteger calls=new AtomicInteger();final JSONObject[] live={cfg};
        CheckinCenter center=new CheckinCenter(new CheckinCenter.Host(){public Object lock(){return live;}public JSONObject config(){return live[0];}public boolean save(JSONObject x){live[0]=x;return true;}public boolean retired(){return false;}},(provider,account)->{calls.incrementAndGet();return new JSONObject().put("outcome","claimed").put("verified",true);},()->NOW);
        try {JSONObject result=center.runOne("raccoon",imported.getString("id"),true);check(result.getBoolean("reused")&&calls.get()==0,"actual automatic import-day run sends zero claim calls");check(center.snapshot().getInt("completed")==0,"migrated record not counted as official success");}finally{center.close();}
        JSONObject localProof=new JSONObject(fence.toString()).put("outcome","done").put("verified",true).put("credit",10);
        cfg.getJSONObject("checkin").getJSONObject("records").put("raccoon:"+imported.getString("id"),localProof);
        CheckinCenter.protectAccountImport(cfg,"raccoon",imported,new JSONObject().put("outcome","done").put("verified",true),NOW);
        check(cfg.getJSONObject("checkin").getJSONObject("records").getJSONObject("raccoon:"+imported.getString("id")).similar(localProof),"existing local proof and fence not erased");
        reject(()->AccountTransferPlan.merge(current,document,Collections.emptySet(),false,NOW));reject(()->AccountTransferPlan.merge(current,document,select(999),false,NOW));
        System.out.println("AccountTransferMergeOfflineTest PASS: "+checks+" checks");
    }
}
