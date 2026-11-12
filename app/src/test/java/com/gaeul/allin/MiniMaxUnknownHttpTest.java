package com.gaeul.allin;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import com.gaeul.allin.json.*;
import fi.iki.elonen.NanoHTTPD;

/** Synthetic upstream drops response after consuming request. No external network. */
public final class MiniMaxUnknownHttpTest {
 static void check(boolean ok,String msg) { if(!ok) throw new AssertionError(msg); }
 public static void main(String[] args) throws Exception {
  Path dir=Files.createTempDirectory("minimax-unknown"); UsageLedger.init(dir.toFile());
  List<UsageLedger.Row> rows=Collections.synchronizedList(new ArrayList<>()); UsageLedger.setHooks(rows::add);
  try(ServerSocket primary=new ServerSocket(0); ServerSocket alternative=new ServerSocket(0)) {
   primary.setSoTimeout(3500);alternative.setSoTimeout(1500);AtomicInteger primaryCount=new AtomicInteger(),fallbackCount=new AtomicInteger();AtomicReference<Throwable> failure=new AtomicReference<>();
   Thread first=new Thread(()->{try(Socket s=primary.accept()) {
    primaryCount.incrementAndGet(); InputStream in=s.getInputStream(); ByteArrayOutputStream headers=new ByteArrayOutputStream(); int state=0,c;
    while((c=in.read())!=-1) {headers.write(c);state=(state==0&&c==13)?1:(state==1&&c==10)?2:(state==2&&c==13)?3:(state==3&&c==10)?4:0;if(state==4)break;}
    int length=0; for(String line:headers.toString("UTF-8").split("\\r\\n")) if(line.toLowerCase(Locale.ROOT).startsWith("content-length:")) length=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());
    while(length-->0) if(in.read()<0) throw new EOFException(); s.setSoLinger(true,0);
   } catch(Throwable e) {failure.set(e);} });
   Thread second=new Thread(()->{try(Socket s=alternative.accept()){fallbackCount.incrementAndGet();}catch(SocketTimeoutException expected){}catch(Throwable e){failure.set(e);}});
   first.start();second.start();
   GatewayServer g=MiniMaxIntegrationHttpTest.setup(primary.getLocalPort(),dir.resolve("config.json"),System.currentTimeMillis()+3600000);
   JSONObject config=ResponsesGatewayOfflineTest.config(g);
   config.getJSONArray("providers").put(new JSONObject().put("id","fallback").put("baseUrl","http://127.0.0.1:"+alternative.getLocalPort()+"/v1").put("protocol","openai").put("models",new JSONArray().put("m")).put("keys",new JSONArray().put("fixture")));
   config.put("routing",new JSONObject().put("groupsEnabled",true)).put("failover",new JSONObject().put("enabled",true).put("maxTargets",2).put("maxAttempts",3)).put("groups",new JSONArray().put(new JSONObject().put("id","g").put("strategy","order").put("targets",new JSONArray().put("minimax-code/m").put("fallback/m"))));
   String request="{\"model\":\"group/g\",\"messages\":[{\"role\":\"user\",\"content\":\"fixture\"}]}";
   NanoHTTPD.Response response=g.serve(ResponsesGatewayOfflineTest.session(request,"secret","/v1/chat/completions")); ResponsesGatewayOfflineTest.read(response.getData()); first.join();second.join();
   check(failure.get()==null,"fixture error: "+failure.get()); check(primaryCount.get()==1 && fallbackCount.get()==0,"unknown execution replayed");
   int terminal=0; boolean unknown=false; for(UsageLedger.Row row:rows) { if(row.terminal) terminal++; if(row.diagnostics!=null && "unknown_execution".equals(row.diagnostics.executionState)) unknown=true; }
   check(terminal==1,"one logical terminal, got "+terminal);
   check(unknown,"missing unknown_execution diagnostics");
   System.out.println("MiniMaxUnknownHttpTest passed");
  } finally {UsageLedger.setHooks(null);}
 }
}
