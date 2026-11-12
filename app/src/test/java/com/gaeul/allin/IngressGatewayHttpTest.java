package com.gaeul.allin;
import fi.iki.elonen.NanoHTTPD;
import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import com.gaeul.allin.json.*;

/** Real Nano socket delegating to a real GatewayServer with fake configuration. */
public final class IngressGatewayHttpTest {
 static void check(boolean x,String m){if(!x)throw new AssertionError(m);}
 static final class Gateway extends NanoHTTPD implements AutoCloseable {
  final GatewayServer gateway;
  Gateway(boolean heartbeat,int max,String protocol,int upstream)throws Exception{super("127.0.0.1",0);gateway=ResponsesGatewayOfflineTest.gateway(upstream,protocol);JSONObject c=ResponsesGatewayOfflineTest.config(gateway);c.getJSONObject("server").put("maxBodyBytes",max);c.put("resilience",new JSONObject().put("heartbeat",heartbeat));start(400,false);}
  @Override protected ClientHandler createClientHandler(Socket socket,InputStream input){try{return super.createClientHandler(socket,GatewayServer.validateRawHeaders(socket,input));}catch(IOException e){throw new IllegalStateException(e);}}
  public Response serve(IHTTPSession s){
   if("1".equals(s.getHeaders().get("x-fixture-remote"))) {
    final IHTTPSession original=s;
    s=(IHTTPSession)java.lang.reflect.Proxy.newProxyInstance(IHTTPSession.class.getClassLoader(),new Class[]{IHTTPSession.class},(p,m,a)->m.getName().equals("getRemoteIpAddress")?"192.0.2.1":m.invoke(original,a));
   }
   return gateway.serve(s);
  }
  public void close(){stop();}
 }
 static String call(Gateway g,String headers,byte[] body,boolean half)throws Exception{
  try(Socket s=new Socket("127.0.0.1",g.getListeningPort())) {s.setSoTimeout(3000);OutputStream o=s.getOutputStream();o.write(("POST /v1/chat/completions HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer secret\r\nConnection: keep-alive\r\n"+headers+"\r\n").getBytes(StandardCharsets.US_ASCII));o.write(body);o.flush();if(half)s.shutdownOutput();ByteArrayOutputStream r=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=s.getInputStream().read(b))!=-1)r.write(b,0,n);return r.toString("UTF-8");}
 }
 static byte[] b(String x){return x.getBytes(StandardCharsets.UTF_8);}
 static void status(String response,int n){check(response.startsWith("HTTP/1.1 "+n+" "),"expected "+n+" "+response);check(response.toLowerCase().contains("connection: close"),"connection close "+response);}
 public static void main(String[] args)throws Exception {
  try(Gateway g=new Gateway(false,65536,"openai",1)){
   try(Socket auth=new Socket("127.0.0.1",g.getListeningPort())) {auth.setSoTimeout(1500);auth.getOutputStream().write(b("POST /v1/chat/completions HTTP/1.1\r\nHost: localhost\r\nX-Fixture-Remote: 1\r\nConnection: close\r\nContent-Length: 50000\r\n\r\n"));auth.getOutputStream().flush();String line=new BufferedReader(new InputStreamReader(auth.getInputStream(),StandardCharsets.US_ASCII)).readLine();check(line.contains(" 401 "),"auth before body "+line);}
   try(Socket empty=new Socket("127.0.0.1",g.getListeningPort())) {empty.setSoTimeout(1500);empty.getOutputStream().write(b("POST /v1/chat/completions HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer secret\r\nConnection: close\r\n\r\n"));empty.getOutputStream().flush();String line=new BufferedReader(new InputStreamReader(empty.getInputStream(),StandardCharsets.US_ASCII)).readLine();check(line!=null && line.contains(" 400 "),"no body immediate "+line);}
   status(call(g,"Content-Length: 1\r\n",new byte[]{(byte)0xc3},false),400);
   status(call(g,"Content-Length: 8\r\n",b("{}"),true),400);
   status(call(g,"Content-Length: 8\r\n",b("{}"),false),408);
   status(call(g,"Content-Length: 65537\r\n",b(""),false),413);
   status(call(g,"Transfer-Encoding: chunked\r\n",b(""),false),501);
   status(call(g,"Content-Encoding: gzip\r\nContent-Length: 0\r\n",b(""),false),415);
   status(call(g,"Content-Length: 9223372036854775808\r\n",b(""),false),400);
   String dup=call(g,"Content-Length: 0\r\nContent-Length: 0\r\n",b(""),false);status(dup,400);check(dup.indexOf("HTTP/1.1")==dup.lastIndexOf("HTTP/1.1"),"single raw response "+dup);
   status(call(g,"Transfer-Encoding: gzip\r\n",b(""),false),400);
  }
  try(Gateway g=new Gateway(true,65536,"openai",1)){
   status(call(g,"Content-Length: 1\r\n",new byte[]{(byte)0xc3},false),400);
   String json="{\"model\":\"missing\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"x\"}]}";
   String x=call(g,"Content-Length: "+b(json).length+"\r\n",b(json),false);check(x.startsWith("HTTP/1.1 4") && !x.contains("请求体不是合法 JSON"),"prepared body processed "+x);
  }
  try(Gateway g=new Gateway(false,Integer.MAX_VALUE,"openai",1)) {status(call(g,"Content-Length: 67108865\r\n",b(""),false),413);}
  try(ServerSocket upstream=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1")); Gateway g=new Gateway(false,65536,"anthropic",upstream.getLocalPort())) {
   upstream.setSoTimeout(700);
   String json="{\"model\":\"mock/m\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,%%%%\"}}]}]}";
   String x=call(g,"Content-Length: "+b(json).length+"\r\nConnection: close\r\n",b(json),true);
   check(x.startsWith("HTTP/1.1 400 "),"invalid image "+x);
   try {upstream.accept().close();throw new AssertionError("invalid image reached upstream");} catch(SocketTimeoutException expected) { }
  }
  GatewayServer actual=ResponsesGatewayOfflineTest.gateway(1,"openai");
  NanoHTTPD initialized=new NanoHTTPD(0){};actual.setTempFileManagerFactory(initialized.getTempFileManagerFactory());
  java.lang.reflect.Field async=NanoHTTPD.class.getDeclaredField("asyncRunner");async.setAccessible(true);async.set(actual,async.get(initialized));
  java.lang.reflect.Method hook=GatewayServer.class.getDeclaredMethod("createClientHandler",Socket.class,InputStream.class);hook.setAccessible(true);
  try(ServerSocket listener=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));Socket client=new Socket("127.0.0.1",listener.getLocalPort());Socket accepted=listener.accept()) {
   client.setSoTimeout(3000);accepted.setSoTimeout(1000);
   NanoHTTPD.ClientHandler handler=(NanoHTTPD.ClientHandler)hook.invoke(actual,accepted,accepted.getInputStream());
   Thread thread=new Thread(handler);thread.setDaemon(true);thread.start();
   client.getOutputStream().write(b("POST /v1/chat/completions HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\nContent-Length: 0\r\n\r\n"));client.getOutputStream().flush();ByteArrayOutputStream result=new ByteArrayOutputStream();int c;while((c=client.getInputStream().read())!=-1)result.write(c);String wire=result.toString("UTF-8");status(wire,400);check(wire.indexOf("HTTP/1.1")==wire.lastIndexOf("HTTP/1.1"),"actual hook single reply");thread.join(3000);check(!thread.isAlive(),"actual handler stopped");
  }
  System.out.println("IngressGatewayHttpTest passed");
 }
}
