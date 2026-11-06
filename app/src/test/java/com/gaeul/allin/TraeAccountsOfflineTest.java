package com.gaeul.allin;
import org.json.JSONObject;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
public final class TraeAccountsOfflineTest {
 static void ok(boolean b){if(!b)throw new AssertionError();}
 interface Checked {void run()throws Exception;}
 static void fails(Checked f)throws Exception{try{f.run();throw new AssertionError("expected failure");}catch(Exception expected){}}
 static class S implements TraeAccounts.Store {
  JSONObject a; int writes,failAt; S(JSONObject x){a=x;}
  public synchronized JSONObject current(String id)throws Exception{return a==null?null:new JSONObject(a.toString());}
  public synchronized void replace(String id,JSONObject expected,JSONObject next)throws Exception{writes++;if(writes==failAt)throw new Exception("disk");if(a==null||!a.toString().equals(expected.toString()))throw new IllegalStateException("cas");a=new JSONObject(next.toString());}
 }
 static JSONObject account()throws Exception{return new JSONObject().put("id","x").put("generation","1").put("enabled",true).put("token","a").put("refresh","r").put("expires",1);}
 static JSONObject rotated(JSONObject a)throws Exception{return a.put("token","new").put("refresh","newrefresh").put("expires",System.currentTimeMillis()+3600000);}
 public static void main(String[] args)throws Exception {
  AtomicInteger calls=new AtomicInteger();S pendingFail=new S(account());pendingFail.failAt=1;
  TraeAccounts m=new TraeAccounts((a,n,r)->{calls.incrementAndGet();return rotated(a);});fails(()->m.lease("x",pendingFail,null,null));ok(calls.get()==0&&pendingFail.a.optString("refreshState").isEmpty());
  S uncertain=new S(account());TraeAccounts unknown=new TraeAccounts((a,n,r)->{calls.incrementAndGet();ok("pending".equals(uncertain.a.optString("refreshState")));throw new TraeAuth.Failure("outcome_unknown");});
  fails(()->unknown.lease("x",uncertain,null,null));ok("pending".equals(uncertain.a.optString("refreshState")));int before=calls.get();fails(()->new TraeAccounts((a,n,r)->{calls.incrementAndGet();return a;}).lease("x",uncertain,null,null));ok(calls.get()==before);
  S publishFail=new S(account());publishFail.failAt=2;TraeAccounts publish=new TraeAccounts((a,n,r)->rotated(a));fails(()->publish.lease("x",publishFail,null,null));ok("pending".equals(publishFail.a.optString("refreshState"))&&"a".equals(publishFail.a.optString("token")));fails(()->publish.lease("x",publishFail,null,null));fails(()->new TraeAccounts((a,n,r)->{throw new AssertionError();}).lease("x",publishFail,null,null));
  for(String code:new String[]{"network_error","outbound_timeout"}) {S unsent=new S(account());TraeAccounts u=new TraeAccounts((a,n,r)->{throw new TraeAuth.Failure(code);});fails(()->u.lease("x",unsent,null,null));ok(unsent.a.optString("refreshState").isEmpty());ok("new".equals(new TraeAccounts((a,n,r)->rotated(a)).lease("x",unsent,null,null).optString("token")));}
  S rollbackFail=new S(account());rollbackFail.failAt=2;TraeAccounts r=new TraeAccounts((a,n,q)->{throw new TraeAuth.Failure("network_error");});fails(()->r.lease("x",rollbackFail,null,null));ok("pending".equals(rollbackFail.a.optString("refreshState")));fails(()->r.lease("x",rollbackFail,null,null));
  S relogin=new S(account());TraeAccounts re=new TraeAccounts((a,n,q)->{throw new TraeAuth.Failure("requires_relogin");});fails(()->re.lease("x",relogin,null,null));ok("requires_relogin".equals(relogin.a.optString("refreshState")));fails(()->re.lease("x",relogin,null,null));
  S replaced=new S(account());TraeAccounts changed=new TraeAccounts((a,n,q)->{if("1".equals(a.optString("generation"))) replaced.a=account().put("generation","2");return rotated(a);});fails(()->changed.lease("x",replaced,null,null));ok("2".equals(replaced.a.optString("generation")));ok("new".equals(changed.lease("x",replaced,null,null).optString("token")));
  S zero=new S(account().put("expires",0));AtomicInteger count=new AtomicInteger();new TraeAccounts((a,n,q)->{count.incrementAndGet();return rotated(a);}).lease("x",zero,null,null);ok(count.get()==1);
  S fresh=new S(account());AtomicInteger n=new AtomicInteger();TraeAccounts single=new TraeAccounts((a,nn,q)->{n.incrementAndGet();return rotated(a);});Thread[] threads=new Thread[8];for(int i=0;i<8;i++){threads[i]=new Thread(()->{try{single.lease("x",fresh,null,null);}catch(Exception e){throw new RuntimeException(e);}});threads[i].start();}for(Thread t:threads)t.join(2000);ok(n.get()==1&&"new".equals(fresh.a.optString("token")));
  Object host=new Object();CountDownLatch entered=new CountDownLatch(1);S blocking=new S(account()){public JSONObject current(String id)throws Exception{entered.countDown();synchronized(host){return super.current(id);}}};TraeAccounts noDeadlock=new TraeAccounts((a,nn,q)->a);Thread blocked=new Thread(()->{try{noDeadlock.lease("x",blocking,null,null);}catch(Exception expected){}});synchronized(host){blocked.start();ok(entered.await(2,TimeUnit.SECONDS));Thread closer=new Thread(noDeadlock::retire);closer.start();closer.join(1000);ok(!closer.isAlive());}blocked.join(2000);ok(!blocked.isAlive());
  System.out.println("TraeAccountsOfflineTest OK");
 }
}
