package com.gaeul.allin;
import com.gaeul.allin.json.*;
public final class GatewayAccessCatalogOfflineTest {
 static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 static JSONObject p(String id,String protocol){return new JSONObject().put("id",id).put("protocol",protocol).put("models",new JSONArray().put("m"));}
 static boolean has(JSONObject r,String id){JSONArray a=r.getJSONArray("models"); for(int i=0;i<a.length();i++)if(id.equals(a.getJSONObject(i).getString("id")))return true;return false;}
 public static void main(String[] args)throws Exception {
  JSONObject disabled=new JSONObject().put("id","1").put("token","secret").put("enabled",false).put("models",new JSONArray().put("other"));
  JSONObject active=new JSONObject().put("id","2").put("token","secret2").put("models",new JSONArray().put("only")).put("modelMeta",new JSONObject().put("only",new JSONObject()));
  JSONObject trae=p("trae-cn","trae").put("accounts",new JSONArray().put(disabled).put(active));
  JSONObject commercial=p("test","openai").put("keys",new JSONArray().put("key"));
  JSONObject mini=p("minimax-code","minimax").put("accounts",new JSONArray().put(new JSONObject().put("refresh","refresh")));
  JSONObject z=p("zcode","openai").put("accounts",new JSONArray().put(new JSONObject().put("jwt","jwt")));
  JSONObject g=new JSONObject().put("id","g").put("targets",new JSONArray().put("trae-cn/only"));
  JSONObject cfg=new JSONObject().put("providers",new JSONArray().put(trae).put(commercial).put(mini).put(z).put(p("empty","openai"))).put("groups",new JSONArray().put(g)).put("routing",new JSONObject().put("groupsEnabled",true));
  JSONObject gift=new JSONObject().put("buckets",new JSONArray().put(new JSONObject().put("remaining",1).put("capabilities",new JSONArray().put("model:gift"))));
  JSONObject r=GatewayAccessCatalog.build(cfg,123,(a,b)->gift);
  check(has(r,"trae-cn/only")&&!has(r,"trae-cn/other")&&!has(r,"trae-cn/m"),"account catalog");
  check(has(r,"test/m")&&has(r,"minimax-code/m")&&!has(r,"empty/m"),"credentials");
  check(has(r,"zcode/gift")&&!has(r,"zcode/m")&&has(r,"group/g"),"gift and group");
  check(!r.toString().contains("secret")&&r.getLong("observedAt")==123,"safe contract");
  cfg.put("exposed",new JSONObject().put("enabled",true).put("items",new JSONArray().put("group/g")));
  r=GatewayAccessCatalog.build(cfg,124,(a,b)->null); check(r.getJSONArray("models").length()==1 && has(r,"group/g") && r.getInt("unconfirmedCatalogs")==1,"whitelist and unknown");
  active.put("enabled",false); r=GatewayAccessCatalog.build(cfg,125,(a,b)->null); check(!has(r,"group/g"),"disabled group target");
  JSONObject qa=new JSONObject().put("id","q").put("token","qtoken").put("models",new JSONArray().put("m")).put("modelMeta",new JSONObject().put("m",new JSONObject().put("key","m")));
  JSONObject qp=p("qoder_cn","qoder").put("accounts",new JSONArray().put(qa));
  JSONObject qc=new JSONObject().put("providers",new JSONArray().put(qp));
  check(has(GatewayAccessCatalog.build(qc,200,null),"qoder_cn/m"),"qoder metadata");
  qa.put("expiresAt",199); check(!has(GatewayAccessCatalog.build(qc,200,null),"qoder_cn/m"),"qoder expiry");
  qa.put("expiresAt",0); qa.getJSONObject("modelMeta").getJSONObject("m").put("key","wrong");
  check(!has(GatewayAccessCatalog.build(qc,200,null),"qoder_cn/m"),"qoder metadata key");
  JSONObject placeholder=p("dummy","openai").put("status","PlaceHolder").put("keys",new JSONArray().put("key"));
  check(!has(GatewayAccessCatalog.build(new JSONObject().put("providers",new JSONArray().put(placeholder)),200,null),"dummy/m"),"placeholder excluded");
  JSONObject withoutSession=p("zcode","openai").put("keys",new JSONArray().put("key"));
  final int[] calls={0};
  JSONObject zr=GatewayAccessCatalog.build(new JSONObject().put("providers",new JSONArray().put(withoutSession)),200,(a,b)->{calls[0]++;return gift;});
  check(calls[0]==0 && !has(zr,"zcode/gift") && has(zr,"zcode/m"),"manual plan key without session");
  withoutSession.put("accounts",new JSONArray().put(new JSONObject().put("jwt","j").put("token","key").put("enabled",false)));
  zr=GatewayAccessCatalog.build(new JSONObject().put("providers",new JSONArray().put(withoutSession)),200,(a,b)->{calls[0]++;return gift;});
  check(calls[0]==0 && zr.getJSONArray("models").length()==0,"disabled session");
  JSONObject giftSession=p("zcode","openai").put("keys",new JSONArray().put("giftkey")).put("accounts",new JSONArray().put(new JSONObject().put("jwt","session").put("token","giftkey")));
  JSONObject gr=GatewayAccessCatalog.build(new JSONObject().put("providers",new JSONArray().put(giftSession)),200,(a,b)->gift);
  check(has(gr,"zcode/gift") && !has(gr,"zcode/m"),"gift-only session excludes plan catalog");
  JSONObject first=new JSONObject().put("token","a").put("models",new JSONArray().put("a-model"));
  JSONObject second=new JSONObject().put("token","b").put("models",new JSONArray().put("b-model"));
  JSONObject shared=p("workbuddy","openai").put("models",new JSONArray().put("a-model").put("b-model")).put("keys",new JSONArray().put("a").put("b")).put("accounts",new JSONArray().put(first).put(second));
  JSONObject sc=new JSONObject().put("providers",new JSONArray().put(shared));
  check(has(GatewayAccessCatalog.build(sc,200,null),"workbuddy/b-model"),"enabled account model");
  second.put("enabled",false);
  JSONObject sr=GatewayAccessCatalog.build(sc,200,null);
  check(has(sr,"workbuddy/a-model") && !has(sr,"workbuddy/b-model"),"disabled sole owning account");
  JSONObject accountOnly=p("minimax-code","minimax").put("accounts",new JSONArray().put(new JSONObject().put("refresh","r").put("models",new JSONArray().put("account-model"))));
  JSONObject ar=GatewayAccessCatalog.build(new JSONObject().put("providers",new JSONArray().put(accountOnly)),200,null);
  check(has(ar,"minimax-code/account-model")&&!has(ar,"minimax-code/m"),"account subscription catalog");
  JSONObject codex=p("codex","codex").put("accounts",new JSONArray().put(new JSONObject().put("access","x").put("refresh","r")));
  check(!has(GatewayAccessCatalog.build(new JSONObject().put("providers",new JSONArray().put(codex)),200,null),"codex/m"),"account without routable key");
  codex.put("keys",new JSONArray().put("x")); check(has(GatewayAccessCatalog.build(new JSONObject().put("providers",new JSONArray().put(codex)),200,null),"codex/m"),"codex access key routed");
  JSONObject manual=new JSONObject().put("id","manual").put("strategy","manual").put("pick","test/missing").put("targets",new JSONArray().put("test/missing").put("test/m"));
  JSONObject mc=new JSONObject().put("providers",new JSONArray().put(commercial)).put("routing",new JSONObject().put("groupsEnabled",true)).put("groups",new JSONArray().put(manual));
  check(!has(GatewayAccessCatalog.build(mc,200,null),"group/manual"),"unavailable selected model");
  manual.put("pick","test/m"); check(has(GatewayAccessCatalog.build(mc,200,null),"group/manual"),"available selected model");
  manual.remove("pick"); check(!has(GatewayAccessCatalog.build(mc,200,null),"group/manual"),"missing selection");
  JSONObject disabledKey=p("shared","openai").put("keys",new JSONArray().put("linked")).put("accounts",new JSONArray().put(new JSONObject().put("token","linked").put("enabled",false)));
  check(!has(GatewayAccessCatalog.build(new JSONObject().put("providers",new JSONArray().put(disabledKey)),200,null),"shared/m"),"disabled linked key");
  disabledKey.getJSONArray("keys").put("independent"); check(has(GatewayAccessCatalog.build(new JSONObject().put("providers",new JSONArray().put(disabledKey)),200,null),"shared/m"),"independent manual key");
  System.out.println("GatewayAccessCatalogOfflineTest PASS");
 }
}
