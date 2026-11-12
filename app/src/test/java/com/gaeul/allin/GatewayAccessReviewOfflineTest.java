package com.gaeul.allin;
import com.gaeul.allin.json.*;
import java.util.concurrent.atomic.AtomicInteger;
public final class GatewayAccessReviewOfflineTest {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static int count(JSONObject c)throws Exception{return GatewayAccessCatalog.build(c,1,null).getJSONArray("models").length();}
 public static void main(String[] args)throws Exception{
  JSONObject p=new JSONObject("{\"id\":\"workbuddy\",\"keys\":[\"a\",\"b\"],\"models\":[\"old\"],\"accounts\":[{\"token\":\"a\",\"models\":[\"old\"]},{\"token\":\"b\"}]}");
  JSONObject cfg=new JSONObject().put("providers",new JSONArray().put(p));
  check(count(cfg)==1,"owned model present");
  GatewayAccessCatalog.preserveAccountScope(null,cfg);
  JSONObject next=cfg; GatewayAccessCatalog.preserveAccountScope(cfg,next);
  JSONObject np=next.getJSONArray("providers").getJSONObject(0); np.getJSONArray("accounts").remove(0);
  check(count(next)==0,"deleted owner cannot transfer model");
  JSONObject shared=new JSONObject("{\"id\":\"workbuddy\",\"keys\":[\"a\"],\"models\":[\"m\"]}");
  shared.put("accessCatalogPool",GatewayAccessCatalog.poolFingerprint(shared));
  JSONObject sc=new JSONObject().put("providers",new JSONArray().put(shared));
  check(count(sc)==1,"matching pool"); shared.getJSONArray("keys").put("b"); check(count(sc)==0,"changed pool");
  JSONObject stable=new JSONObject("{\"id\":\"workbuddy\",\"accounts\":[{\"id\":\"owner\",\"token\":\"one\"}],\"keys\":[\"one\"],\"models\":[\"m\"]}");
  String fp=GatewayAccessCatalog.poolFingerprint(stable); stable.put("accessCatalogPool",fp);
  stable.getJSONArray("accounts").getJSONObject(0).put("token","two").put("name","changed").put("modelMeta",new JSONObject().put("x",1)); stable.put("keys",new JSONArray().put("two"));
  check(fp.equals(GatewayAccessCatalog.poolFingerprint(stable)),"renewal and metadata stable");
  check(count(new JSONObject().put("providers",new JSONArray().put(stable)))==1,"renewed directory stays visible");
  stable.getJSONArray("accounts").getJSONObject(0).put("id","other"); check(!fp.equals(GatewayAccessCatalog.poolFingerprint(stable)),"identity change");
  AtomicInteger calls=new AtomicInteger();
  ZCodeGiftControl.setTestHooks(()->1000,new ZCodeGiftControl.Transport(){public JSONObject summary(String j,String d)throws Exception{calls.incrementAndGet();return new JSONObject().put("ok",true).put("claimable",new JSONArray()).put("buckets",new JSONArray());}public JSONObject claim(String j,String d,String id){throw new AssertionError("claim");}});
  ZCodeGiftControl.summary("j","d"); ZCodeGiftControl.summary("j","d"); check(calls.get()==1,"cache"); ZCodeGiftControl.summary("j","d",true); check(calls.get()==2,"refresh");
  ZCodeGiftControl.setTestHooks(null,null); System.out.println("GatewayAccessReviewOfflineTest PASS");
 }
}
