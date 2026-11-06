package com.gaeul.allin;
import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import org.json.*;

/** Image cross-protocol integration with isolated localhost upstream. */
public final class ImageGatewayHttpTest {
 static Object getField(Object x,String name)throws Exception {java.lang.reflect.Field f=GatewayServer.class.getDeclaredField(name);f.setAccessible(true);return f.get(x);}
 static void check(boolean v,String s){if(!v)throw new AssertionError(s);}
 static final class Upstream implements AutoCloseable {
  final ServerSocket socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));final Thread worker;volatile JSONObject request;volatile Throwable failure;
  Upstream(String response)throws Exception {socket.setSoTimeout(4000);worker=new Thread(()->{try(Socket s=socket.accept()){
   s.setSoTimeout(3000);InputStream in=s.getInputStream();ByteArrayOutputStream h=new ByteArrayOutputStream();int state=0,c;while((c=in.read())!=-1){h.write(c);state=state==0&&c==13?1:state==1&&c==10?2:state==2&&c==13?3:state==3&&c==10?4:0;if(state==4)break;}
   int len=0;for(String line:h.toString("US-ASCII").split("\\r\\n"))if(line.toLowerCase().startsWith("content-length:"))len=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());byte[] body=new byte[len];int off=0;while(off<len){int n=in.read(body,off,len-off);if(n<0)throw new EOFException();off+=n;}request=new JSONObject(new String(body,StandardCharsets.UTF_8));byte[] b=response.getBytes(StandardCharsets.UTF_8);s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: "+b.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));s.getOutputStream().write(b);s.getOutputStream().flush();
  }catch(Throwable t){failure=t;}});worker.setDaemon(true);worker.start();}
  public void close()throws Exception{worker.join(5000);socket.close();check(!worker.isAlive(),"upstream hanging");if(failure!=null)throw new AssertionError(failure);}
 }
 static String post(IngressGatewayHttpTest.Gateway g,String uri,String body)throws Exception {
  try(Socket s=new Socket("127.0.0.1",g.getListeningPort())){s.setSoTimeout(5000);byte[] b=body.getBytes(StandardCharsets.UTF_8);s.getOutputStream().write(("POST "+uri+" HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: "+b.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));s.getOutputStream().write(b);s.getOutputStream().flush();ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;while((n=s.getInputStream().read(buf))!=-1)o.write(buf,0,n);return o.toString("UTF-8");}
 }
 static String anthropicResponse="{\"id\":\"x\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
 static String toolChat="{\"model\":\"mock/m\",\"messages\":[{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"t\",\"type\":\"function\",\"function\":{\"name\":\"f\",\"arguments\":\"{}\"}}]},{\"role\":\"tool\",\"tool_call_id\":\"t\",\"content\":[{\"type\":\"text\",\"text\":\"before\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,eA==\"}},{\"type\":\"text\",\"text\":\"after\"}]}]}";
 public static void main(String[]args)throws Exception{
  try(Upstream u=new Upstream(anthropicResponse);IngressGatewayHttpTest.Gateway g=new IngressGatewayHttpTest.Gateway(false,65536,"anthropic",u.socket.getLocalPort())){
   String x=post(g,"/v1/chat/completions",toolChat);check(x.startsWith("HTTP/1.1 200 "),"chat->anthropic "+x);check(x.toLowerCase().contains("x-allin-image-token-estimate-known: false"),"image token header "+x);u.worker.join(3000);JSONArray msgs=u.request.getJSONArray("messages");JSONArray content=msgs.getJSONObject(msgs.length()-1).getJSONArray("content").getJSONObject(0).getJSONArray("content");check(content.length()==3&&content.getJSONObject(0).getString("text").equals("before")&&content.getJSONObject(2).getString("text").equals("after")&&content.getJSONObject(1).getJSONObject("source").getString("data").equals("eA=="),"tool order and bytes "+content);
  }
  StringBuilder dataBuilder=new StringBuilder();for(int i=0;i<262145;i++)dataBuilder.append("AAAA");String longData=dataBuilder.toString();
  try(Upstream u=new Upstream(anthropicResponse);IngressGatewayHttpTest.Gateway g=new IngressGatewayHttpTest.Gateway(false,2*1024*1024,"anthropic",u.socket.getLocalPort())) {
   String response=post(g,"/v1/chat/completions",toolChat.replace("eA==",longData));check(response.startsWith("HTTP/1.1 200 "),"long image "+response);u.worker.join(3000);JSONArray msgs=u.request.getJSONArray("messages");String received=msgs.getJSONObject(msgs.length()-1).getJSONArray("content").getJSONObject(0).getJSONArray("content").getJSONObject(1).getJSONObject("source").getString("data");java.security.MessageDigest md=java.security.MessageDigest.getInstance("SHA-256");check(received.length()==longData.length() && java.security.MessageDigest.isEqual(md.digest(received.getBytes(StandardCharsets.US_ASCII)),md.digest(longData.getBytes(StandardCharsets.US_ASCII))),"1MiB image digest mismatch");
  }
  String source="{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"eA==\"}";
  String nativeRequest="{\"model\":\"mock/m\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image\",\"source\":"+source+",\"cache_control\":{\"type\":\"ephemeral\"}}]},{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"x\",\"signature\":\"signed\"},{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"f\",\"input\":{}}]},{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t\",\"content\":[{\"type\":\"text\",\"text\":\"result\"},{\"type\":\"image\",\"source\":"+source+",\"cache_control\":{\"type\":\"ephemeral\"}}]}]}]}";
  try(Upstream u=new Upstream(anthropicResponse);IngressGatewayHttpTest.Gateway g=new IngressGatewayHttpTest.Gateway(false,65536,"anthropic",u.socket.getLocalPort())) {
   String x=post(g,"/v1/messages",nativeRequest);check(x.startsWith("HTTP/1.1 200 "),"native "+x);u.worker.join(3000);check(GatewayServer.jsonEquivalent(new JSONObject(nativeRequest).get("messages"),u.request.get("messages")),"native image/signature preservation "+u.request);
  }
  for(String protocol:new String[]{"responses","anthropic"}) try(ServerSocket upstream=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));IngressGatewayHttpTest.Gateway g=new IngressGatewayHttpTest.Gateway(false,65536,protocol,upstream.getLocalPort())) {
   upstream.setSoTimeout(400);String body="responses".equals(protocol)?toolChat:toolChat.replace("eA==","%%%%");String x=post(g,"/v1/chat/completions",body);check(x.startsWith("HTTP/1.1 400 "),"reject "+protocol+" "+x);try{upstream.accept().close();throw new AssertionError("unexpected upstream");}catch(SocketTimeoutException expected){}
  }
  try(ServerSocket upstream=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));IngressGatewayHttpTest.Gateway g=new IngressGatewayHttpTest.Gateway(false,65536,"openai",upstream.getLocalPort())) {
   upstream.setSoTimeout(400);String bad="{\"model\":\"mock/m\",\"input\":[{\"role\":\"user\",\"content\":[{\"type\":\"input_image\",\"image_url\":\"data:image/png;base64,%%%%\"}]}]}";String x=post(g,"/v1/responses",bad);check(x.startsWith("HTTP/1.1 400 "),"Responses invalid image "+x);try{upstream.accept().close();throw new AssertionError("invalid Responses image reached upstream");}catch(SocketTimeoutException expected){}
  }
  try(ServerSocket upstream=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));IngressGatewayHttpTest.Gateway g=new IngressGatewayHttpTest.Gateway(true,65536,"anthropic",upstream.getLocalPort())) {
   upstream.setSoTimeout(400);ResponsesGatewayOfflineTest.config(g.gateway).getJSONObject("guard").put("maxBodyBytes",150);String body=toolChat.replace("\"model\":", "\"stream\":true,\"model\":");String x=post(g,"/v1/chat/completions",body);check(x.startsWith("HTTP/1.1 413 "),"image budget "+x);check("budget_exceeded".equals(g.gateway.lastGuardStats.optString("error")),"rejected guard stats "+g.gateway.lastGuardStats);check(((java.util.concurrent.atomic.AtomicInteger)getField(g.gateway,"totalContextDropped")).get()==0,"rejected trim counted");try{upstream.accept().close();throw new AssertionError("budget reached upstream");}catch(SocketTimeoutException expected){}
  }
  System.out.println("ImageGatewayHttpTest passed");
 }
}
