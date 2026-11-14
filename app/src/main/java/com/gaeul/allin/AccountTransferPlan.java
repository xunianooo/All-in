package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.util.*;

/** Preview and deterministic account-only merge. No file, network, login or check-in side effects. */
final class AccountTransferPlan {
    interface Backend {
        byte[] readConfig() throws Exception;
        default byte[] export(byte[] expectedHash,Set<Integer> selected,long now) throws Exception {
            byte[] current=readConfig();
            try {
                if(!java.security.MessageDigest.isEqual(expectedHash,AccountTransferCodec.digest(current)))throw AccountTransferCodec.invalid("账号已变化，请重新选择导出");
                return AccountTransferCodec.export(new JSONObject(AccountTransferCodec.decode(current)),selected,now);
            } finally {Arrays.fill(current,(byte)0);}
        }
        JSONObject commit(byte[] expectedHash, AccountTransferCodec.Document document, Set<Integer> selected,
                          boolean confirmedUpdates, long now) throws Exception;
    }
    static final class Row {
        final AccountTransferCodec.Entry entry;
        final String action, reason;
        final JSONObject existing;
        Row(AccountTransferCodec.Entry e,String a,String r,JSONObject x) { entry=e;action=a;reason=r;existing=x; }
        boolean selectable() { return "new".equals(action)||"update".equals(action); }
        String text() {
            String state="new".equals(action)?"新增":"update".equals(action)?"待更新":"duplicate".equals(action)?"重复，跳过":"不可导入";
            return state+" · "+(entry.provider.isEmpty()?"未识别":entry.provider)+" · "+entry.label()
                    +(entry.value.optBoolean("enabled",true)&&!entry.value.optBoolean("off",false)?"":" [停用]")
                    +(reason.isEmpty()?"":"\n"+reason);
        }
    }
    static final class Result {
        final JSONObject config, summary;
        final boolean changed;
        Result(JSONObject c,int added,int updated,int skipped) {
            config=c;changed=added+updated>0;
            summary=new JSONObject().put("ok",true).put("added",added).put("updated",updated).put("skipped",skipped)
                    .put("saved",added+updated).put("loginVerified",false).put("action","import");
        }
    }
    private static JSONObject provider(JSONObject config,String id) {
        JSONArray ps=config.optJSONArray("providers");JSONObject found=null;
        for(int i=0;ps!=null&&i<ps.length();i++) {
            JSONObject p=ps.optJSONObject(i);if(p==null||!id.equals(p.optString("id")))continue;
            if(found!=null)throw AccountTransferCodec.invalid("本机供应商ID重复，需先修复配置");found=p;
        }
        return found;
    }
    private static boolean same(AccountTransferCodec.Entry e,JSONObject a) {
        if("keys".equals(e.kind))return e.value.optString("key").equals(a.optString("key"));
        return AccountPoolPolicy.same(a,e.value,e.provider);
    }
    private static JSONObject sameAccount(JSONObject p,AccountTransferCodec.Entry e) {
        JSONArray rows=p.optJSONArray(e.kind);JSONObject found=null;
        for(int i=0;rows!=null&&i<rows.length();i++) {
            Object raw=rows.opt(i);JSONObject a=raw instanceof JSONObject?(JSONObject)raw:
                    "keys".equals(e.kind)&&raw instanceof String?new JSONObject().put("key",raw):null;
            if(a==null||!same(e,a))continue;
            if(found!=null)throw AccountTransferCodec.invalid("本机存在多个同身份账号，不能自动合并");found=a;
        }
        return found;
    }
    private static boolean accountOwnsKey(JSONObject p,String key) {
        JSONArray aa=p.optJSONArray("accounts");
        for(int i=0;aa!=null&&i<aa.length();i++) {
            JSONObject a=aa.optJSONObject(i);if(a!=null&&(key.equals(a.optString("access"))||key.equals(a.optString("token"))))return true;
        }
        return false;
    }
    private static JSONObject comparable(JSONObject a) {
        JSONObject out=AccountTransferCodec.copy(a);
        for(String k:new String[]{"id","addedAt","updatedAt","transferImportedAt","transferLoginVerified","transferSource"})out.remove(k);
        return out;
    }
    static boolean protectedAccount(JSONObject a) {
        if(a==null)return false;
        for(Iterator<String> it=a.keys();it.hasNext();) {
            String key=it.next(),k=key.toLowerCase(Locale.ROOT);
            if(!(k.contains("pending")||k.contains("quarantine")||k.contains("uncertain")||k.contains("unknown")||k.contains("consumed")))continue;
            Object v=a.opt(key);
            if(Boolean.TRUE.equals(v) || (v instanceof Number&&((Number)v).longValue()>0))return true;
            if(v instanceof JSONObject && ((JSONObject)v).length()>0)return true;
            if(v instanceof String && !((String)v).isEmpty() && !Arrays.asList("false","none","idle","ok","0").contains(v))return true;
        }
        return false;
    }
    static List<Row> preview(JSONObject current,AccountTransferCodec.Document document) {
        List<Row> rows=new ArrayList<>();
        for(AccountTransferCodec.Entry e:document.entries) {
            if(!e.valid()) { rows.add(new Row(e,"invalid",e.problem.isEmpty()?"供应商不受支持":e.problem,null));continue; }
            JSONObject p=provider(current,e.provider);
            if(p==null || "placeholder".equals(p.optString("status"))) { rows.add(new Row(e,"invalid","本机没有已实现的对应供应商",null));continue; }
            try {
                if("keys".equals(e.kind)&&accountOwnsKey(p,e.value.optString("key"))) { rows.add(new Row(e,"duplicate","已由本机账号持有，不重复创建Key",null));continue; }
                JSONObject existing=sameAccount(p,e);
                if(existing==null)rows.add(new Row(e,"new",e.warning,null));
                else if(comparable(existing).similar(comparable(e.value)))rows.add(new Row(e,"duplicate","账号及凭据相同",existing));
                else if(protectedAccount(existing))rows.add(new Row(e,"invalid","本机账号处于结果未知/续期保护中，不能用文件覆盖",existing));
                else rows.add(new Row(e,"update","同一身份的资料不同；需额外确认更新",existing));
            } catch(IllegalArgumentException ex) { rows.add(new Row(e,"invalid",ex.getMessage(),null)); }
        }
        // More than one source row for one identity must not win by array order.
        for(int i=0;i<rows.size();i++) {
            Row a=rows.get(i);if(!a.entry.valid())continue;
            for(int j=0;j<i;j++) {
                Row b=rows.get(j);if(!b.entry.valid()||!a.entry.provider.equals(b.entry.provider))continue;
                boolean mirror="keys".equals(a.entry.kind)&&"accounts".equals(b.entry.kind)
                        &&(a.entry.value.optString("key").equals(b.entry.value.optString("token"))||a.entry.value.optString("key").equals(b.entry.value.optString("access")));
                if(mirror){rows.set(i,new Row(a.entry,"duplicate","该Key已包含在本文件账号中",null));break;}
                if(!a.entry.kind.equals(b.entry.kind)||!same(a.entry,b.entry.value))continue;
                if(comparable(a.entry.value).similar(comparable(b.entry.value)))rows.set(i,new Row(a.entry,"duplicate","文件内重复记录",null));
                else {
                    rows.set(i,new Row(a.entry,"invalid","文件中同一账号有矛盾资料，请仅保留正确的一份",null));
                    rows.set(j,new Row(b.entry,"invalid","文件中同一账号有矛盾资料，请仅保留正确的一份",null));
                }
                break;
            }
        }
        return rows;
    }
    private static String id(JSONObject a) { return a.optString("id",""); }
    private static boolean hasId(JSONArray aa,String value) {
        for(int i=0;i<aa.length();i++){JSONObject a=aa.optJSONObject(i);if(a!=null&&value.equals(id(a)))return true;}return false;
    }
    private static JSONObject mergedAccount(JSONObject old,JSONObject incoming,long now,String provider) {
        JSONObject next=old==null?AccountTransferCodec.copy(incoming):AccountTransferCodec.copy(old);
        if(old!=null) {
            // Credential updates are an explicit unit. Never retain a stale refresh or binding
            // beside a new access token merely because the foreign file omitted that field.
            String[] coupled={"access","token","refresh","jwt","idToken","deviceToken","deviceRefresh",
                    "deviceId","machineId","devicePrivateKey","devicePublicKey","expires","expiresAt","refreshExpiresAt"};
            for(String field:coupled) {
                if(old.has(field)&&!incoming.has(field)&&!old.optString(field,"").isEmpty())
                    throw AccountTransferCodec.invalid("更新文件缺少该账号原有的续期或绑定字段，请使用完整导出");
            }
            for(Iterator<String> it=incoming.keys();it.hasNext();) {
                String k=it.next();if("id".equals(k)||"addedAt".equals(k))continue;
                String low=k.toLowerCase(Locale.ROOT);
                if(!AccountTransferProtection.FIELD.equals(k)&&old.has(k)&&(low.contains("fence")||low.contains("pending")||low.contains("quarantine")||low.contains("uncertain")||low.contains("generation")))continue;
                next.put(k,incoming.opt(k));
            }
        }
        AccountTransferProtection.preserve(old,next);
        if(id(next).isEmpty())next.put("id",AccountPoolPolicy.newId(provider+"_"));
        if(!next.has("addedAt"))next.put("addedAt",now);
        if(!next.has("enabled"))next.put("enabled",true);
        next.put("transferImportedAt",now).put("transferLoginVerified",false);
        return next;
    }
    static Result merge(JSONObject current,AccountTransferCodec.Document document,Set<Integer> selected,
                        boolean confirmedUpdates,long now) {
        if(selected.isEmpty())throw AccountTransferCodec.invalid("请至少勾选一条可导入记录");
        List<Row> rows=preview(current,document);Map<Integer,Row> byIndex=new HashMap<>();for(Row r:rows)byIndex.put(r.entry.index,r);
        for(int index:selected) {
            Row r=byIndex.get(index);if(r==null||!r.selectable())throw AccountTransferCodec.invalid("所选记录不可导入，请重新预览");
            if("update".equals(r.action)&&!confirmedUpdates)throw AccountTransferCodec.invalid("更新现有账号需要单独确认");
        }
        JSONObject next=AccountTransferCodec.copy(current);Map<String,JSONArray> previous=new HashMap<>();int added=0,updated=0;
        for(Row row:rows) {
            AccountTransferCodec.Entry e=row.entry;if(!selected.contains(e.index))continue;
            JSONObject p=provider(next,e.provider);if(!previous.containsKey(e.provider)) {
                JSONArray before=p.optJSONArray("accounts");previous.put(e.provider,before==null?new JSONArray():new JSONArray(before.toString()));
            }
            JSONArray aa=p.optJSONArray(e.kind);if(aa==null){aa=new JSONArray();p.put(e.kind,aa);}
            JSONObject existing=sameAccount(p,e);
            if("keys".equals(e.kind)) {
                JSONObject key=AccountTransferCodec.copy(e.value);
                if(existing==null){aa.put(key);added++;}
                else {
                    for(int i=0;i<aa.length();i++) {
                        Object raw=aa.opt(i);String value=raw instanceof String?(String)raw:raw instanceof JSONObject?((JSONObject)raw).optString("key"):"";
                        if(value.equals(existing.optString("key"))){aa.put(i,key);break;}
                    }
                    updated++;
                }
                continue;
            }
            JSONObject account=mergedAccount(existing,e.value,now,e.provider);
            if(existing==null) {
                if(hasId(aa,id(account)))account.put("id",AccountPoolPolicy.newId(e.provider+"_"));
                aa.put(account);added++;
            } else {
                boolean replaced=false;for(int i=0;i<aa.length();i++)if(aa.optJSONObject(i)==existing){aa.put(i,account);replaced=true;break;}
                if(!replaced)throw AccountTransferCodec.invalid("账号状态已变化");updated++;
            }
            CheckinCenter.protectAccountImport(next,e.provider,account,e.protection,now);
        }
        for(Map.Entry<String,JSONArray> p:previous.entrySet())AccountPoolPolicy.synchronizeKeys(provider(next,p.getKey()),p.getValue());
        byte[] encoded=next.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if(encoded.length>AccountTransferCodec.MAX_BYTES)throw AccountTransferCodec.invalid("合并后配置超过安全大小限制，请减少导入记录");
        return new Result(next,added,updated,document.entries.size()-selected.size());
    }
}
