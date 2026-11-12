package com.gaeul.allin;
import com.gaeul.allin.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class WorkBuddyAllowanceRegressionTest {
    static int checks;
    static void check(boolean v,String label){checks++;if(!v)throw new AssertionError(label);}
    static JSONObject pkg(Object total,Object used,Object left){return new JSONObject().put("CycleTotalCapacity",total).put("CycleUsedCapacity",used).put("CycleRemainCapacity",left);}
    static JSONObject sum(JSONObject... rows){JSONArray a=new JSONArray();for(JSONObject r:rows)a.put(r);return new JSONObject().put("Packages",a);}
    static JSONObject account(String id){return new JSONObject().put("id",id).put("uid","uid-"+id).put("name",id).put("type","oauth").put("access","fixture-"+id).put("token","fixture-"+id).put("refresh","refresh-"+id).put("expires",System.currentTimeMillis()+3600000).put("domain","copilot.tencent.com");}
    static class Conn extends HttpURLConnection {
        int status=200;String challenge;JSONObject data;boolean closed;
        ByteArrayOutputStream sent=new ByteArrayOutputStream();
        Conn(String u,JSONObject d)throws Exception{super(new URL(u));data=d;}
        public void connect(){throw new AssertionError("No real network");}
        public void disconnect(){closed=true;}
        public boolean usingProxy(){return false;}
        public OutputStream getOutputStream(){return sent;}
        public int getResponseCode(){return status;}
        public String getHeaderField(String k){return "WWW-Authenticate".equals(k)?challenge:null;}
        public InputStream getInputStream(){return new ByteArrayInputStream(new JSONObject().put("code",0).put("data",data).toString().getBytes(StandardCharsets.UTF_8));}
    }
    static JSONObject ask(GatewayServer s,String id)throws Exception{return new JSONObject(ResponsesGatewayOfflineTest.read(s.serve(ProviderBillingGatewayHttpTest.request("127.0.0.1","GET","fixture-auth",id)).getData()));}
    public static void main(String[] args)throws Exception {
        check(WorkBuddyAllowance.summary(sum(pkg("100","25",null))).getDouble("remaining")==75,"decimal strings and used");
        check(WorkBuddyAllowance.summary(sum(pkg("1.5","1.5","0"))).getDouble("remaining")==0,"zero not unknown");
        check(WorkBuddyAllowance.summary(sum(pkg("100",null,null)))==null,"missing not zero");
        check(WorkBuddyAllowance.summary(sum(pkg("100",null,"101")))==null,"invalid remainder");
        check(WorkBuddyAllowance.summary(sum(pkg("NaN",null,null)))==null,"nonfinite unknown");
        check(WorkBuddyAllowance.summary(sum())==null,"empty not exhausted");
        check(WorkBuddyAllowance.summary(sum(pkg(null,null,"0"))).getDouble("remaining")==0,"empty prepaid zero");
        JSONObject grouped=sum(pkg("100","1","99"),pkg(null,null,"3.5")).put("CreditGroups",new JSONArray().put(pkg("100","1","99").put("Kind","plan_base")).put(pkg("20","2","18").put("Kind","addon")));
        JSONObject allow=WorkBuddyAllowance.summary(grouped);
        check(allow.getDouble("remaining")==120.5,"groups and prepaid no double count");
        check(allow.getJSONArray("windows").length()==3,"separate group windows");
        check(WorkBuddyAllowance.enterprise(new JSONObject().put("limitNum","20").put("credit","2")).getDouble("remaining")==18,"team account meter");
        List<Conn> calls=new ArrayList<>();
        for(String provider:new String[]{"workbuddy","workbuddy-ai"}) {
            JSONObject a=account("a");
            JSONObject got=WorkBuddyAllowance.fetch(provider,a,u->{Conn c=new Conn(u,sum(pkg(100,20,80)));calls.add(c);return c;});
            Conn c=calls.get(calls.size()-1);
            check(c.getURL().toString().equals(WorkBuddyAuth.origin(provider)+WorkBuddyAllowance.SUMMARY),"fixed official summary origin");
            check(c.getRequestMethod().equals("POST") && c.sent.toString("UTF-8").equals("{}"),"read-only meter request");
            check(!c.getInstanceFollowRedirects() && c.closed,"no redirects and connection closed");
            check(c.getRequestProperty("X-User-Id").equals("uid-a") && got.getDouble("remaining")==80,"selected account uid");
        }
        JSONObject team=account("team").put("enterpriseId","e-1");
        WorkBuddyAllowance.fetch("workbuddy",team,u->{Conn c=new Conn(u,new JSONObject().put("limitNum",20).put("credit",1));check(u.endsWith(WorkBuddyAllowance.ENTERPRISE),"enterprise endpoint");calls.add(c);return c;});
        Conn tc=calls.get(calls.size()-1);check("e-1".equals(tc.getRequestProperty("X-Enterprise-Id")) && "e-1".equals(tc.getRequestProperty("X-Tenant-Id")),"dual team headers");
        for(String challenge:new String[]{null,"Bearer error=invalid_token"}) {
            try{WorkBuddyAllowance.fetch("workbuddy",account("a"),u->{Conn c=new Conn(u,null);c.status=401;c.challenge=challenge;return c;});throw new AssertionError("expected refused");}
            catch(WorkBuddyAllowance.Failure e){check(e.invalidToken==(challenge!=null),"renew only explicit invalid-token");}
        }
        JSONObject p=new JSONObject().put("id","workbuddy").put("enabled",true).put("models",new JSONArray()).put("accounts",new JSONArray().put(account("a")).put(account("b")).put(account("key").put("type","api")));
        JSONObject cfg=new JSONObject().put("setup",new JSONObject().put("done",true)).put("providers",new JSONArray().put(p));
        GatewayServer s=RoutingSetupOfflineTest.server(cfg);int[] count={0};
        s.setVendorConnectionsForTests(u->{try{count[0]++;return new Conn(u,sum(pkg(100,0,100))){public int getResponseCode(){return "uid-b".equals(getRequestProperty("X-User-Id"))?503:200;}};}catch(Exception e){throw new IOException(e);}});
        JSONObject result=ask(s,"workbuddy");JSONArray rows=result.getJSONArray("accounts");
        check(count[0]==2,"actual meter for each oauth, never static key");
        check(rows.length()==3 && rows.getJSONObject(0).getJSONObject("allowance").getDouble("remaining")==100,"actual gateway success instead of not_provided");
        check(rows.getJSONObject(0).getJSONObject("allowance").getJSONArray("windows").getJSONObject(0).getDouble("remaining")==100,"safe allowance retains credit windows");
        check(rows.getJSONObject(1).getString("errorKind").equals("unavailable"),"partial error independent");
        check(rows.getJSONObject(2).getString("errorKind").equals("key_not_supported"),"static key explicit unsupported");
        check(!result.toString().contains("fixture-") && !result.toString().contains("refresh-"),"no credentials returned");
        JSONObject rp=new JSONObject().put("id","workbuddy").put("models",new JSONArray()).put("accounts",new JSONArray().put(account("renew")));
        GatewayServer renew=RoutingSetupOfflineTest.server(new JSONObject().put("setup",new JSONObject().put("done",true)).put("providers",new JSONArray().put(rp)));
        java.nio.file.Path testDir=java.nio.file.Files.createTempDirectory("workbuddy-meter-renew-");
        ResponsesGatewayOfflineTest.set(renew,"configFile",testDir.resolve("config.json").toFile());
        int[] exchanges={0},reads={0};
        renew.setTokenRefreshForTests((before,force)->{exchanges[0]++;return new JSONObject(before.toString()).put("access","renewed").put("token","renewed").put("refresh","new-refresh").put("expires",System.currentTimeMillis()+7200000);});
        renew.setVendorConnectionsForTests(u->{try{return new Conn(u,sum(pkg(10,2,8))){
            public int getResponseCode(){reads[0]++;return "Bearer renewed".equals(getRequestProperty("Authorization"))?200:401;}
            public String getHeaderField(String k){return "Bearer error=invalid_token";}
        };}catch(Exception e){throw new IOException(e);}});
        JSONObject renewed=ask(renew,"workbuddy");
        check(reads[0]==2 && exchanges[0]==1,"one coordinated renewal and one read retry: reads="+reads[0]+", exchanges="+exchanges[0]+", result="+renewed);
        check(renewed.getJSONArray("accounts").length()==1 && renewed.getJSONArray("accounts").getJSONObject(0).getJSONObject("allowance").getDouble("remaining")==8,"renewed account snapshot not discarded");
        renewed=ask(renew,"workbuddy");
        check(exchanges[0]==1 && renewed.getJSONArray("accounts").length()==1,"renewal persisted, no repeated exchange");
        System.out.println("WorkBuddyAllowanceRegressionTest PASS: "+checks+" checks");
    }
}
