package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Login-free API integration, blocked network means no upstream calls. */
public final class ProviderEndpointProbeGatewayHttpTest {
    static void check(boolean ok, String why) { if(!ok)throw new AssertionError(why); }
    static final class Gateway extends NanoHTTPD {
        final GatewayServer inner;
        Gateway() throws Exception {
            super("127.0.0.1",0);
            inner=RoutingSetupOfflineTest.server(new JSONObject()
                    .put("setup",new JSONObject().put("done",true))
                    .put("server",new JSONObject().put("authKey","fixture-key"))
                    .put("providers",new JSONArray()));
            start(SOCKET_READ_TIMEOUT,true);
        }
        public Response serve(IHTTPSession session) { return inner.serve(session); }
    }
    static JSONObject call(Gateway g,String method,String body,int expected) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+g.getListeningPort()+"/api/providers/endpoint-probe").openConnection();
        c.setConnectTimeout(2000);c.setReadTimeout(3000);c.setRequestMethod(method);
        if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream out=c.getOutputStream()){out.write(body.getBytes(StandardCharsets.UTF_8));}}
        try {
            check(c.getResponseCode()==expected,"HTTP "+c.getResponseCode());
            if(expected==200)check("no-store".equals(c.getHeaderField("Cache-Control")),"no cache");
            try(InputStream in=expected>=400?c.getErrorStream():c.getInputStream()){
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] b=new byte[1024];int n;
                while((n=in.read(b))!=-1)bytes.write(b,0,n);
                return new JSONObject(bytes.toString("UTF-8"));
            }
        } finally {c.disconnect();}
    }
    public static void main(String[] args) throws Exception {
        Gateway g=new Gateway();NetworkPolicy.block();
        try {
            call(g,"GET",null,405);
            call(g,"POST","not-json",400);
            call(g,"POST","{\"provider\":\"other\"}",400);
            call(g,"POST","{\"provider\":\"workbuddy\",\"url\":\"http://127.0.0.1\"}",400);
            String originalConfig=ResponsesGatewayOfflineTest.config(g.inner).toString();
            for(String provider:ProviderEndpointProbe.supportedProviders()){
                JSONObject result=call(g,"POST",new JSONObject().put("provider",provider).toString(),200);
                check(result.optBoolean("ok") && provider.equals(result.optString("provider")),"no account required");
                check("unreachable".equals(result.optString("verdict")),"policy verdict");
                check(result.getJSONArray("results").length()>=2,"fixed endpoints");
                for(int i=0;i<result.getJSONArray("results").length();i++)check("policy".equals(result.getJSONArray("results").getJSONObject(i).optString("kind")),"captured blocked policy");
            }
            check(originalConfig.equals(ResponsesGatewayOfflineTest.config(g.inner).toString()),"all probes leave gateway configuration untouched");
            NanoHTTPD.Response rejected=g.inner.serve(RoutingSetupOfflineTest.request("203.0.113.9","/api/providers/endpoint-probe","POST","{\"provider\":\"workbuddy\"}",null));
            check(rejected.getStatus().getRequestStatus()==401,"remote management auth required");
        } finally {g.stop();g.inner.stop();NetworkPolicy.configure(null);}
        System.out.println("ProviderEndpointProbeGatewayHttpTest PASS");
    }
}
