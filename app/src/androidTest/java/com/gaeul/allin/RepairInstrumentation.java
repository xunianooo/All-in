package com.gaeul.allin;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.*;
import java.lang.reflect.Method;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Runs on Android's actual class loader and socket stack, without accounts or vendor traffic. */
public final class RepairInstrumentation extends Instrumentation {
    private int checks;
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    private void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private Object call(String type,String method,Class<?>[] types,Object... args)throws Exception {
        Method m=Class.forName("com.gaeul.allin."+type).getDeclaredMethod(method,types);m.setAccessible(true);return m.invoke(null,args);
    }
    private void jsonRuntime()throws Exception {
        check(!JSONObject.class.equals(Class.forName("org.json.JSONObject")),"App JSON must not be Android framework JSON");
        JSONObject a=new JSONObject("{\"a\":[{\"b\":null},2],\"c\":true}"),b=new JSONObject("{\"c\":true,\"a\":[{\"b\":null},2]}");
        check((Boolean)call("ConfigurationView","equivalent",new Class[]{Object.class,Object.class},a,b),"Nested JSON equivalence on Android");
        check(!(Boolean)call("ConfigurationView","equivalent",new Class[]{Object.class,Object.class},a,new JSONObject("{\"a\":[2,{\"b\":null}],\"c\":true}")),"Array order remains significant");
        JSONObject branch=new JSONObject().put("type","object").put("properties",new JSONObject().put("字段",new JSONObject().put("type","string")));
        JSONObject schema=new JSONObject().put("anyOf",new JSONArray().put(branch).put(new JSONObject(branch.toString())));
        JSONObject body=new JSONObject().put("messages",new JSONArray()).put("tools",new JSONArray().put(new JSONObject().put("type","function")
                .put("function",new JSONObject().put("name","read").put("parameters",schema))));
        JSONObject fitted=WorkBuddyChatPolicy.prepare(body,null).body().getJSONArray("tools").getJSONObject(0).getJSONObject("function").getJSONObject("parameters");
        check("string".equals(fitted.getJSONObject("properties").getJSONObject("字段").getString("type")),"Duplicate schema properties merge using private JSON similar");
        String sse="event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"fixture\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"fixture\",\"content\":[],\"usage\":{\"input_tokens\":1}}}\n\n"
                +"event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"vendor_extra\":{\"ok\":true}},\"usage\":{\"output_tokens\":3}}\n\n"
                +"event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
        JSONObject aggregate=(JSONObject)call("WorkBuddyNativeAggregator","aggregate",new Class[]{InputStream.class},new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)));
        check(aggregate.getJSONObject("usage").getInt("input_tokens")==1 && aggregate.getJSONObject("usage").getInt("output_tokens")==3,"Native SSE usage aggregates without framework linkage errors");
        check(aggregate.getJSONObject("vendor_extra").getBoolean("ok"),"Unknown message delta preserved");
        for(double remaining:new double[]{0.4,12.5}) {
            Number value=(Number)call("ZCodeGift","bucketNumber",new Class[]{JSONObject.class,String.class},new JSONObject().put("remaining_units",remaining),"remaining_units");
            check(value!=null && Math.abs(value.doubleValue()-remaining)<0.0000001,"Fractional gift balance retained on Android");
        }
        JSONObject args=new JSONObject().put("中文","值");
        JSONObject fn=new JSONObject().put("name",new JSONObject().put("n","read")).put("arguments",args);
        String objectId=QoderProtocol.callID(0,0,new JSONObject().put("function",fn));
        fn.put("arguments",args.toString());
        check(objectId.equals(QoderProtocol.callID(0,0,new JSONObject().put("function",fn))),"Qoder object args and serialized args have the same call id");
        check(new LocalBridge.Result(200,"{\"ok\":true,\"id\":\"saved\"}","").accountSaved(),"Android recognizes confirmed save");
        for(int status:new int[]{200,400,401,409,500})check(!new LocalBridge.Result(status,"{\"ok\":false}","").accountSaved(),"Android never reports a failed save as success");
    }
    private static final class Reply {
        int status;String headers,body;
    }
    private Reply http(int port,String method,String path,String body,boolean authorized,long declared)throws Exception {
        byte[] payload=body.getBytes(StandardCharsets.UTF_8);
        try(Socket socket=new Socket("127.0.0.1",port)) {
            socket.setSoTimeout(10000);
            String head=method+" "+path+" HTTP/1.1\r\nHost: 127.0.0.1:"+port+"\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: "+(declared>=0?declared:payload.length)+"\r\n";
            if(authorized)head+="X-All-In-Management: "+GatewayManagementCredential.get()+"\r\n";
            OutputStream out=socket.getOutputStream();out.write((head+"\r\n").getBytes(StandardCharsets.US_ASCII));out.write(payload);out.flush();
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();InputStream in=socket.getInputStream();byte[] buffer=new byte[4096];int n;
            while((n=in.read(buffer))!=-1){if(bytes.size()+n>4*1024*1024)throw new IOException("Oversized local fixture response");bytes.write(buffer,0,n);}
            byte[] raw=bytes.toByteArray();String latin=new String(raw,StandardCharsets.ISO_8859_1);int split=latin.indexOf("\r\n\r\n");
            if(split<0)throw new IOException("Incomplete local response");Reply result=new Reply();result.headers=latin.substring(0,split);
            result.status=Integer.parseInt(result.headers.split(" ",3)[1]);result.body=new String(raw,split+4,raw.length-split-4,StandardCharsets.UTF_8);return result;
        }
    }
    private void gateway(Context target)throws Exception {
        File dir=new File(target.getFilesDir(),"repair-runtime-"+UUID.randomUUID());if(!dir.mkdirs())throw new IOException("Fixture directory");
        Context isolated=new ContextWrapper(target){@Override public File getFilesDir(){return dir;}@Override public Context getApplicationContext(){return this;}};
        GatewayServer server=null;
        try {
            server=new GatewayServer(isolated,0);server.start(5000,true);int port=server.getListeningPort();
            check(port>0,"Real Android gateway starts on an isolated ephemeral port");
            check(!GatewayManagementCredential.get().isEmpty(),"Android management credential created privately");
            check(http(port,"GET","/","",false,-1).status==200,"Dashboard GET loads actual APK assets");
            Reply head=http(port,"HEAD","/","",false,-1);check(head.status==200 && head.body.isEmpty(),"Dashboard HEAD sends no body; status="+head.status+" bytes="+head.body.length()+" headers="+head.headers);
            check(http(port,"GET","/provider-icons/openai.svg","",false,-1).status==200,"Icon GET works");
            head=http(port,"HEAD","/provider-icons/openai.svg","",false,-1);check(head.status==200 && head.body.isEmpty(),"Icon HEAD works");
            Reply notice=http(port,"GET","/licenses.html","",false,-1);
            check(notice.status==200 && notice.body.contains("SST") && notice.body.contains("magpie-community contributors"),"Bundled license originals are readable over the real gateway");
            Reply noticeHead=http(port,"HEAD","/licenses.html","",false,-1);
            check(noticeHead.status==200 && noticeHead.body.isEmpty(),"License HEAD is also body-free");
            check((Boolean)call("MainActivity","isTrustedPanelResource",new Class[]{String.class},"file:///android_asset/licenses.html"),"Offline license asset is explicitly whitelisted");
            Reply rejected=http(port,"POST","/","",false,1024*1024);check(rejected.status==405 && rejected.headers.toLowerCase(java.util.Locale.ROOT).contains("allow: get, head"),"Static POST rejects before waiting for a declared 1 MiB body");
            check(http(port,"GET","/api/config","",false,-1).status==401,"Unauthenticated management blocked on Android");
            for(String token:new String[]{"ck_android_fixture_A","ck_android_fixture_B"}) {
                Reply added=http(port,"POST","/api/auth/workbuddy/add",new JSONObject().put("name","同名账号").put("token",token).toString(),true,-1);
                check(added.status==200 && new JSONObject(added.body).getBoolean("ok") && new JSONObject(added.body).has("id"),"Same-name account saved through Android HTTP");
            }
            JSONObject stored=new JSONObject(read(new File(dir,"config.json")));JSONObject workbuddy=null;
            JSONArray providers=stored.getJSONArray("providers");for(int i=0;i<providers.length();i++)if("workbuddy".equals(providers.getJSONObject(i).optString("id")))workbuddy=providers.getJSONObject(i);
            check(workbuddy!=null && workbuddy.getJSONArray("accounts").length()==2,"Both Android accounts persisted");
            Method build=GatewayServer.class.getDeclaredMethod("buildTarget",String.class);build.setAccessible(true);java.util.Set<String> selected=new java.util.HashSet<>();
            for(int i=0;i<4;i++){Object route=build.invoke(server,"workbuddy/android-model");java.lang.reflect.Field field=route.getClass().getDeclaredField("apiKey");field.setAccessible(true);selected.add((String)field.get(route));}
            check(selected.size()==2,"Actual Android selector rotates both stored accounts");
            String view=http(port,"GET","/api/config","",true,-1).body;check(!view.contains("ck_android_fixture_"),"Android configuration response masks tokens");
            server.stop();server=null;server=new GatewayServer(isolated,0);
            selected.clear();for(int i=0;i<2;i++){Object route=build.invoke(server,"workbuddy/android-model");java.lang.reflect.Field field=route.getClass().getDeclaredField("apiKey");field.setAccessible(true);selected.add((String)field.get(route));}
            check(selected.size()==2,"Restart reload preserves and rotates both accounts");
            check(IngressMemoryBudget.usedBytes()==0,"Android ingress allocations released");
        } finally {if(server!=null)server.stop();delete(dir);}
    }
    private void alarms(Context context) {
        SharedPreferences prefs=context.getSharedPreferences("magpie_prefs",Context.MODE_PRIVATE);
        prefs.edit().putBoolean("gateway_enabled",true).commit();KeepAliveScheduler.cancel(context);
        check(KeepAliveScheduler.schedule(context),"Android AlarmManager accepts the request");
        SharedPreferences alarm=context.getSharedPreferences("magpie_keepalive",Context.MODE_PRIVATE);long requested=alarm.getLong("nextElapsed",0);
        check(requested>0 && KeepAliveScheduler.schedule(context) && requested==alarm.getLong("nextElapsed",0),"Repeated scheduling does not postpone an in-flight alarm");
        prefs.edit().putBoolean("gateway_enabled",false).commit();
        check(!KeepAliveScheduler.schedule(context) && !alarm.contains("nextElapsed"),"Manual stop cancels alarm and does not revive the gateway");
    }
    private static String read(File file)throws Exception{try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");}}
    private static void delete(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)delete(child);file.delete();}
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            Context target=getTargetContext();
            if(!target.getPackageName().endsWith(".repairtest"))throw new SecurityException("Runtime tests require the isolatedTestInstall package");
            jsonRuntime();gateway(target);alarms(target);
            result.putString("stream","ANDROID_REPAIR_PASS checks="+checks+" api="+android.os.Build.VERSION.SDK_INT+"\n");finish(Activity.RESULT_OK,result);
        } catch(Throwable failure) {
            StringWriter trace=new StringWriter();failure.printStackTrace(new PrintWriter(trace));result.putString("stream","ANDROID_REPAIR_FAIL checks="+checks+"\n"+trace);finish(Activity.RESULT_CANCELED,result);
        }
    }
}
