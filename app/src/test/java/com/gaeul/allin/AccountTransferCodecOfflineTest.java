package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.*;

/** Independent fixtures: canonical bundle, legacy arrays, OpenCode/Magpie auth maps and Codex auth.json. */
public final class AccountTransferCodecOfflineTest {
    static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    static AccountTransferCodec.Document parse(String value,String hint){return AccountTransferCodec.parse(bytes(value),hint);}
    static void reject(String value){try{parse(value,"workbuddy");throw new AssertionError("accepted malformed file");}catch(AccountTransferCodec.TransferException expected){checks++;check(!expected.getMessage().contains("PRIVATE_FIXTURE"),"error never contains document content");}}
    static JSONObject account(String id){return new JSONObject().put("id",id).put("name","测试账号中文").put("token","fixture-access-"+id).put("access","fixture-access-"+id).put("refresh","fixture-refresh-"+id).put("uid","owner-"+id).put("type","oauth").put("enabled",false).put("expires",1900000000000L).put("addedAt",1700000000000L);}
    static JSONObject config(){JSONArray ps=new JSONArray();for(String p:AccountTransferCodec.PROVIDERS)ps.put(new JSONObject().put("id",p).put("enabled",true).put("accounts",new JSONArray()).put("keys",new JSONArray()).put("models",new JSONArray()));return new JSONObject().put("providers",ps).put("server",new JSONObject().put("authKey","PRIVATE_FIXTURE_GATEWAY_KEY")).put("groups",new JSONArray().put("keep-route")).put("guard",new JSONObject().put("maxOutput",99999));}
    static JSONObject provider(JSONObject cfg,String id){JSONArray ps=cfg.getJSONArray("providers");for(int i=0;i<ps.length();i++)if(id.equals(ps.getJSONObject(i).getString("id")))return ps.getJSONObject(i);throw new AssertionError("missing provider");}
    public static void main(String[] args)throws Exception {
        AccountTransferCodec.Document mixed=parse("{\"workbuddy#slot-a\":{\"type\":\"oauth\",\"access\":\"fixture-workbuddy\",\"refresh\":\"fixture-refresh\",\"expires\":\"1900000000000\",\"metadata\":{\"uid\":\"user-a\",\"domain\":\"cn\"}},\"raccoon#slot-b\":{\"type\":\"oauth\",\"access\":\"fixture-raccoon\",\"refresh\":\"fixture-raccoon-refresh\"},\"openai\":{\"type\":\"api\",\"key\":\"fixture-openai-key\"}}","catpaw");
        check(mixed.entries.size()==3,"mixed provider dictionary keeps every row");
        Set<String> ids=new HashSet<>();for(AccountTransferCodec.Entry e:mixed.entries){check(e.valid(),"known dictionary entry normalized");ids.add(e.provider);}
        check(ids.equals(new HashSet<>(Arrays.asList("workbuddy","raccoon","openai"))),"explicit provider wins over opened card");
        AccountTransferCodec.Entry wb=mixed.entries.stream().filter(e->e.provider.equals("workbuddy")).findFirst().get();
        check(wb.value.getString("refresh").equals("fixture-refresh"),"full refresh preserved");check(wb.value.getString("uid").equals("user-a"),"scoped identity from metadata");check(wb.value.getLong("expires")==1900000000000L,"upstream milliseconds not multiplied");
        AccountTransferCodec.Document codex=parse("{\"auth_mode\":\"chatgpt\",\"tokens\":{\"access_token\":\"fixture-codex-access\",\"refresh_token\":\"fixture-codex-refresh\",\"id_token\":\"fixture-id-token\",\"account_id\":\"tenant-a\"}}","workbuddy");
        AccountTransferCodec.Entry ce=codex.entries.get(0);check(ce.valid()&&ce.provider.equals("codex"),"Codex auth.json recognized independently of hint");
        for(String field:new String[]{"access","token","refresh","idToken","accountId","authMode"})check(!ce.value.optString(field).isEmpty(),"Codex required field retained: "+field);
        check(parse("[{\"token\":\"fixture-token\",\"name\":\"legacy\"}]","raccoon").entries.get(0).provider.equals("raccoon"),"legacy array uses explicitly selected supplier");
        AccountTransferCodec.Entry unknown=parse("{\"unknown-provider\":{\"token\":\"fixture-token\"}}","workbuddy").entries.get(0);
        check(!unknown.valid()&&unknown.provider.isEmpty(),"unknown explicit provider never forced into current card");AccountTransferCodec.assign(unknown,"raccoon");check(unknown.valid()&&unknown.provider.equals("raccoon"),"manual provider mapping explicit");
        AccountTransferCodec.Document nested=parse("{\"plugins\":{\"catpaw#one\":{\"type\":\"oauth\",\"access\":\"fixture-catpaw\"}}}","workbuddy");check(nested.entries.get(0).provider.equals("catpaw"),"Magpie carried plugin map");
        JSONObject cfg=config(),a=account("one"),p=provider(cfg,"workbuddy");p.getJSONArray("accounts").put(a);p.getJSONArray("keys").put(a.getString("token")).put(new JSONObject().put("key","fixture-independent-key").put("off",true).put("label","独立Key"));
        provider(cfg,"raccoon").getJSONArray("accounts").put(account("two"));
        List<AccountTransferCodec.Entry> selection=AccountTransferCodec.exportEntries(cfg);check(selection.size()==3,"mirrored account token not exported twice");
        byte[] exported=AccountTransferCodec.export(cfg,new LinkedHashSet<>(Arrays.asList(0,1,2)),1900000000000L);
        String text=new String(exported,StandardCharsets.UTF_8);check(!text.contains("PRIVATE_FIXTURE_GATEWAY_KEY")&&!text.contains("keep-route")&&!text.contains("maxOutput"),"no global configuration leakage");
        JSONObject bundle=new JSONObject(text);check(bundle.getString("format").equals("all-in-accounts")&&bundle.getInt("version")==1,"explicit versioned format");
        AccountTransferCodec.Document round=AccountTransferCodec.parse(exported,"catpaw");check(round.entries.size()==3,"file roundtrip record count");
        AccountTransferCodec.Entry restored=round.entries.stream().filter(e->e.kind.equals("accounts")&&e.provider.equals("workbuddy")).findFirst().get();
        check(restored.value.similar(a),"complete native account roundtrip, not just access token");check(!restored.value.getBoolean("enabled"),"disabled state retained");
        check(round.entries.stream().anyMatch(e->e.kind.equals("keys")&&e.value.optBoolean("off")),"independent disabled key retained");
        check(new JSONObject(new String(AccountTransferCodec.export(cfg,Collections.singleton(2),1900000000000L),StandardCharsets.UTF_8)).getJSONArray("providers").length()==1,"selective export only requested provider");
        try{AccountTransferCodec.export(cfg,Collections.singleton(99),0);throw new AssertionError("stale selection accepted");}catch(AccountTransferCodec.TransferException expected){checks++;}
        byte[] utf8Bom=new byte[exported.length+3];utf8Bom[0]=(byte)239;utf8Bom[1]=(byte)187;utf8Bom[2]=(byte)191;System.arraycopy(exported,0,utf8Bom,3,exported.length);check(AccountTransferCodec.parse(utf8Bom,"").entries.size()==3,"Windows UTF-8 BOM");
        for(java.nio.charset.Charset cs:new java.nio.charset.Charset[]{StandardCharsets.UTF_16LE,StandardCharsets.UTF_16BE}){
            byte[] body=text.getBytes(cs),bom=new byte[body.length+2];bom[0]=(byte)(cs==StandardCharsets.UTF_16LE?255:254);bom[1]=(byte)(cs==StandardCharsets.UTF_16LE?254:255);System.arraycopy(body,0,bom,2,body.length);check(AccountTransferCodec.parse(bom,"").entries.size()==3,"Windows UTF-16 file");
        }
        for(String bad:new String[]{"", "null", "true", "[", "{}", "{\"token\":\"a\",\"token\":\"PRIVATE_FIXTURE\"}","{\"token\":\"allin-secret-ref:abc\"}","{\"format\":\"all-in-accounts\",\"version\":2,\"providers\":[]}","{\"format\":\"all-in-accounts\",\"version\":1.5,\"providers\":[]}","{\"token\":\"PRIVATE_FIXTURE\"} trailing"})reject(bad);
        StringBuilder deep=new StringBuilder();for(int i=0;i<100;i++)deep.append('[');deep.append("{}");for(int i=0;i<100;i++)deep.append(']');reject(deep.toString());
        AccountTransferCodec.Entry contradiction=parse("{\"providerId\":\"workbuddy\",\"access\":\"fixture-one\",\"access_token\":\"fixture-two\"}","").entries.get(0);check(!contradiction.valid(),"conflicting aliases rejected");
        check(!parse("{\"token\":\"fixture\\nunsafe\"}","workbuddy").entries.get(0).valid(),"header control characters rejected");
        check(!parse("{\"token\":\"fixture-token\",\"enabled\":\"false\"}","workbuddy").entries.get(0).valid(),"enabled boolean not coerced");
        check(!parse("{\"token\":\"fixture-token\",\"expires\":-1}","workbuddy").entries.get(0).valid(),"negative expiry rejected");
        check(!parse("{\"token\":\"fixture-token\",\"authMode\":\"browser\",\"authHost\":\"attacker.invalid\"}","trae-cn").entries.get(0).valid(),"untrusted Trae auth host rejected");
        check(!parse("{\"token\":\"fixture-token\"}","trae-cn").entries.get(0).valid(),"incomplete device binding never pretends success");
        JSONObject foreign=account("foreign").put("headers",new JSONObject().put("Authorization","must-not-import")).put("proxy","must-not-import");
        AccountTransferCodec.Entry safe=parse(new JSONArray().put(foreign).toString(),"raccoon").entries.get(0);check(!safe.value.has("headers")&&!safe.value.has("proxy"),"account file cannot alter request configuration");
        try{AccountTransferCodec.parse(new byte[AccountTransferCodec.MAX_BYTES+1],"workbuddy");throw new AssertionError("oversize accepted");}catch(AccountTransferCodec.TransferException expected){checks++;}
        try{AccountTransferCodec.readBounded(new ByteArrayInputStream(new byte[AccountTransferCodec.MAX_BYTES+1]));throw new AssertionError("oversize stream accepted");}catch(IOException expected){checks++;}
        check(Arrays.equals(AccountTransferCodec.readBounded(new ByteArrayInputStream(exported)),exported),"bounded stream preserves exact bytes");
        check(AccountTransferCodec.PROVIDERS.size()==20,"only implemented providers");
        for(String id:AccountTransferCodec.PROVIDERS)check(AccountTransferCodec.canonicalProvider(id).equals(id),"canonical supplier recognized");
        check(AccountTransferCodec.canonicalProvider("qoder#slot-2").equals("qoder_cn"),"upstream slot ID");
        check(AccountTransferCodec.canonicalProvider("trae-global").isEmpty(),"international identity not silently mapped to CN");
        // Source-defined ZCode OAuth.refresh state and Antigravity refresh|project contracts.
        JSONObject zstate=new JSONObject().put("site","zai").put("jwt","fixture-zcode-session").put("key","fixture-zcode-paid-key").put("device","fixture-device-id").put("plan","fixture-plan");
        JSONObject zauth=new JSONObject().put("type","oauth").put("access","fixture-zcode-paid-key").put("refresh",zstate.toString()).put("expires",0);
        AccountTransferCodec.Document zd=parse(new JSONObject().put("zcode#one",zauth).toString(),"workbuddy");
        check(zd.entries.size()==2&&zd.entries.stream().allMatch(AccountTransferCodec.Entry::valid),"dual ZCode state creates explicit session and paid-key choices");
        AccountTransferCodec.Entry zs=zd.entries.stream().filter(e->e.kind.equals("accounts")).findFirst().get(),zk=zd.entries.stream().filter(e->e.kind.equals("keys")).findFirst().get();
        check(zs.value.getString("jwt").equals("fixture-zcode-session")&&zs.value.getString("token").equals("fixture-zcode-session"),"gift JWT never replaced with paid key");
        check(zs.value.getString("device").equals("fixture-device-id")&&!zs.value.toString().contains("fixture-zcode-paid-key"),"session retains device without hidden unselected paid key");
        check(zk.value.getString("key").equals("fixture-zcode-paid-key")&&!zk.value.toString().contains("fixture-zcode-session"),"paid key export does not drag in a session");
        check(!parse("{\"token\":\"ambiguous-value\"}","zcode").entries.get(0).valid(),"never guess whether ambiguous ZCode material is paid or gift");
        JSONObject ga=new JSONObject().put("type","oauth").put("access","fixture-google-access").put("refresh","fixture-google-refresh|project-original").put("accountId","fixture@example.invalid");
        AccountTransferCodec.Entry ge=parse(new JSONObject().put("antigravity",ga).toString(),"workbuddy").entries.get(0);
        check(ge.valid()&&ge.value.getString("refresh").equals("fixture-google-refresh"),"Google refresh not sent with project suffix");
        check(ge.value.getString("project").equals("project-original")&&ge.value.getString("email").equals("fixture@example.invalid"),"Google project and identity retained");
        check(!parse(new JSONObject().put("antigravity",new JSONObject(ga.toString()).put("project","other")).toString(),"").entries.get(0).valid(),"contradictory project rejected");
        AccountTransferCodec.Entry qe=parse("{\"qoder#one\":{\"type\":\"oauth\",\"access\":\"fixture-qoder\",\"refresh\":\"fixture-job-refresh\",\"expires\":1900000000000,\"deviceToken\":\"fixture-device\",\"deviceRefresh\":\"fixture-device-refresh\",\"machineId\":\"fixture-machine\"}}","").entries.get(0);
        check(qe.valid()&&qe.value.getLong("expiresAt")==1900000000000L,"Qoder runtime expiry alias preserves milliseconds");
        check(qe.value.getString("deviceRefresh").equals("fixture-device-refresh")&&qe.value.getString("refresh").equals("fixture-job-refresh"),"Qoder device and job refresh are not conflated");
        System.out.println("AccountTransferCodecOfflineTest PASS: "+checks+" checks");
    }
}
