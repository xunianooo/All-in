package com.gaeul.allin;
import fi.iki.elonen.NanoHTTPD;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
public final class QuirksGatewayHttpTest {
 static void check(boolean v){if(!v)throw new AssertionError();}
 static NanoHTTPD.IHTTPSession session(String method,String uri,String key) {
  Map<String,String> headers=new HashMap<>(); if(key!=null)headers.put("authorization","Bearer "+key);
  return (NanoHTTPD.IHTTPSession)Proxy.newProxyInstance(QuirksGatewayHttpTest.class.getClassLoader(),new Class[]{NanoHTTPD.IHTTPSession.class},(p,m,a)->{
   switch(m.getName()) {case "getUri":return uri;case "getMethod":return NanoHTTPD.Method.valueOf(method);case "getHeaders":return headers;case "getInputStream":return new java.io.ByteArrayInputStream(new byte[0]);case "getRemoteIpAddress":return "192.0.2.1";case "getParameters":return Collections.emptyMap();case "getParms":return Collections.emptyMap();default:return null;}
  });
 }
 public static void main(String[] args)throws Exception {
  GatewayServer g=ResponsesGatewayOfflineTest.gateway(1);byte[] bytes=Files.readAllBytes(Paths.get(Files.exists(Paths.get("src/main/assets/default_quirks.json"))?"src/main/assets/default_quirks.json":"app/src/main/assets/default_quirks.json"));
  ProviderQuirks.Snapshot builtin=ProviderQuirks.parse(bytes);ResponsesGatewayOfflineTest.set(g,"quirksSnapshot",builtin);ResponsesGatewayOfflineTest.set(g,"quirksUpdateUnavailable","disabled");
  check(g.serve(session("GET","/api/quirks",null)).getStatus().getRequestStatus()==401);
  check(g.serve(session("POST","/api/quirks/check",null)).getStatus().getRequestStatus()==401);
  NanoHTTPD.Response response=g.serve(session("GET","/api/quirks","secret"));check(response.getStatus().getRequestStatus()==200);
  JSONObject json=new JSONObject(ResponsesGatewayOfflineTest.read(response.getData()));check(json.getString("version").equals("1.0.2"));check(json.getString("source").equals("builtin"));check(json.getInt("schema")==1);check(!json.getJSONObject("update").getBoolean("enabled"));
  response=g.serve(session("POST","/api/quirks/check","secret"));check(response.getStatus().getRequestStatus()==200);json=new JSONObject(ResponsesGatewayOfflineTest.read(response.getData()));check(json.getString("result").equals("disabled"));
  check(g.serve(session("GET","/api/quirks/check","secret")).getStatus().getRequestStatus()==405);g.stop();System.out.println("QuirksGatewayHttpTest PASS");
 }
}
