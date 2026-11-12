package com.gaeul.allin;
import com.gaeul.allin.json.*;
import java.lang.reflect.*;

public final class TraeControlOfflineTest {
 static void ok(boolean b){if(!b)throw new AssertionError();}
 static class H implements TraeControl.Host {
  final Object lock=new Object(); JSONObject data; boolean fail,retired;
  H() throws Exception {data=new JSONObject("{\"providers\":[{\"id\":\"trae-cn\",\"enabled\":true,\"accounts\":[{\"id\":\"a\",\"enabled\":true,\"token\":\"t\",\"generation\":\"1\"}]}]}");}
  public Object lock(){return lock;} public JSONObject config(){ok(Thread.holdsLock(lock));return data;}
  public boolean save(JSONObject x){ok(Thread.holdsLock(lock));if(fail)return false;data=x;return true;}
  public boolean retired(){return retired;}
  JSONObject account(){return data.optJSONArray("providers").optJSONObject(0).optJSONArray("accounts").optJSONObject(0);}
 }
 static void rejects(Throwing f)throws Exception {try{f.run();throw new AssertionError();}catch(java.io.IOException expected){}}
 interface Throwing {void run()throws Exception;}
 public static void main(String[] args)throws Exception {
  H h=new H(); TraeControl c=new TraeControl(h); Field field=TraeControl.class.getDeclaredField("store");field.setAccessible(true); TraeAccounts.Store store=(TraeAccounts.Store)field.get(c);
  JSONObject original=store.current("a"); original.put("token","mutated");ok("t".equals(h.account().optString("token")));
  JSONObject expected=store.current("a"), next=new JSONObject(expected.toString()).put("token","new").put("models",new JSONArray().put("m")).put("modelMeta",new JSONObject().put("m",new JSONObject().put("x",1)));
  h.fail=true;rejects(()->store.replace("a",expected,next));ok("t".equals(h.account().optString("token")));h.fail=false;
  store.replace("a",expected,next);JSONObject p=h.data.optJSONArray("providers").optJSONObject(0);ok("new".equals(p.optJSONArray("keys").optString(0)));ok("m".equals(p.optJSONArray("models").optString(0)));ok(p.optJSONObject("modelMeta").optJSONObject("m").optInt("x")==1);
  rejects(()->store.replace("a",expected,next));h.account().put("enabled",false);ok(store.current("a")==null);rejects(()->store.replace("a",next,next));
  Method add=TraeControl.class.getDeclaredMethod("addAccount",JSONObject.class);add.setAccessible(true);
  for(JSONObject bad:new JSONObject[]{new JSONObject().put("bound",false),new JSONObject().put("bound",true).put("refreshState","outcome_unknown")}) {
   try {add.invoke(c,bad);throw new AssertionError();}catch(InvocationTargetException e){ok(e.getCause() instanceof java.io.IOException);}
  }
  ok(h.data.optJSONArray("providers").optJSONObject(0).optJSONArray("accounts").length()==1);
  c.close();rejects(()->store.current("a"));System.out.println("TraeControlOfflineTest OK");
 }
}
