package com.gaeul.allin;

import java.util.Map;
import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;

/** Exercises the public wiring adapter, including parsed streaming chunks. */
public final class WorkBuddyChatGatewayAdapterOfflineTest {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        JSONObject catalog=new JSONObject().put("m",new JSONObject().put("reasoning",new JSONObject()
                .put("defaultEffort","high").put("supportedEfforts",new JSONArray().put("low").put("high"))));
        JSONObject request=new JSONObject().put("model","workbuddy/m").put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hi")))
                .put("tools",new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","server:read").put("parameters",new JSONObject().put("type","object")))));
        WorkBuddyChatPolicy.Prepared prepared=WorkBuddyChatGatewayAdapter.prepare("workbuddy",request,catalog,"m");
        check("high".equals(prepared.body().optString("reasoning_effort")),"selected catalog effort wired");
        String safe=prepared.clientToUpstreamNames().get("server:read");
        check("server_read".equals(safe),"request exposes safe upstream name");
        Map<String,String> reverse=prepared.upstreamToClientNames();
        JSONObject chunk=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("delta",new JSONObject()
                .put("tool_calls",new JSONArray().put(new JSONObject().put("index",0).put("function",new JSONObject().put("name",safe)))))));
        WorkBuddyChatResponsePolicy.restoreChatObject(chunk,reverse);
        check("server:read".equals(chunk.getJSONArray("choices").getJSONObject(0).getJSONObject("delta")
                .getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").optString("name")),"converted response chunk restored");
        check("data: [DONE]\n\n".equals(prepared.newSseRestorer().feed("data: [DONE]\n\n",true)),"unmapped and terminal SSE passes through immediately");
        boolean unsupported=false;try{WorkBuddyChatGatewayAdapter.prepare("zcode",request,catalog,"m");}catch(IllegalArgumentException expected){unsupported=true;}
        check(unsupported,"adapter refuses non-WorkBuddy provider");
        byte[] utf8=("data: "+new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("delta",new JSONObject().put("tool_calls",new JSONArray().put(new JSONObject().put("function",new JSONObject().put("name",safe).put("arguments","文档")))))))+"\r\n\r\ndata: [DONE]\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.io.InputStream oneByte=new java.io.ByteArrayInputStream(utf8){@Override public synchronized int read(byte[] b,int off,int len){return super.read(b,off,Math.min(len,1));}};
        java.io.ByteArrayOutputStream restoredBytes=new java.io.ByteArrayOutputStream();
        try(java.io.InputStream restoredStream=WorkBuddyChatResponsePolicy.restoreStream(prepared,oneByte)){byte[] b=new byte[3];int n;while((n=restoredStream.read(b))!=-1)restoredBytes.write(b,0,n);}
        String restoredText=restoredBytes.toString("UTF-8");check(restoredText.contains("server:read")&&restoredText.contains("文档")&&restoredText.contains("[DONE]"),"one-byte UTF-8 and CRLF incremental stream restored");
        WorkBuddyChatGatewayHttpTest.main(args);
        System.out.println("WorkBuddyChatGatewayAdapterOfflineTest OK");
    }
}
