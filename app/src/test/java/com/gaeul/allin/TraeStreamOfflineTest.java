package com.gaeul.allin;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

public class TraeStreamOfflineTest {
    static InputStream input(String s){return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));}
    static void ok(boolean v){if(!v)throw new AssertionError();}
    static String readAll(InputStream in) throws Exception { try(InputStream x=in; ByteArrayOutputStream b=new ByteArrayOutputStream()){ byte[] buf=new byte[4096]; int n; while((n=x.read(buf))>=0)b.write(buf,0,n); return new String(b.toByteArray(),StandardCharsets.UTF_8); } }
    static void bad(String s) throws Exception { try(TraeStream t=new TraeStream(input(s),null,"m",null,null)){while(t.read()!=-1){} throw new AssertionError("expected failure");}catch(IOException expected){ok(!expected.getMessage().contains("SECRET"));} }
    public static void main(String[] args) throws Exception {
        String end="event: extra_info\ndata: {\"model\":\"fixture\",\"content\":\"opaque upstream metadata\",\"input_token\":4}\n\nevent: done\ndata: {}\n\n";
        String source="event: progress_notice\ndata:\"Processing_1793976302\"\n\nevent: timing_cost\ndata: {\"name\":\"chat\",\"queue_timing\":0,\"provider_model_name\":\"fixture\"}\n\n: comment\r\nevent: output\r\ndata: {\"content\":\r\ndata: \"hello\"}\r\n\r\nevent: output\ndata: {\"content\":\" world\"}\n\n"+end;
        TraeStream t=new TraeStream(input(source),null,"m",null,null); byte[] buf=new byte[4096]; int n=t.read(buf); String a=new String(buf,0,n,StandardCharsets.UTF_8); ok(a.contains("hello")&&!a.contains("[DONE]")); n=t.read(buf); ok(new String(buf,0,n,StandardCharsets.UTF_8).contains(" world")); t.close();
        final JSONObject[] usage={null};
        JSONObject r=TraeStream.aggregate(input(source.replace(end,"event: token_usage\ndata: {\"input_tokens\":4,\"output_tokens\":2}\n\n"+end)),null,"m",null,u->usage[0]=u);
        ok(r.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").equals("hello world")); ok(usage[0].optBoolean("magpie_cache_unknown")&&r.getJSONObject("usage").getInt("prompt_tokens")==4);
        String streamed=readAll(new TraeStream(input(source.replace(end,"event: token_usage\ndata: {\"input_tokens\":4,\"output_tokens\":2}\n\nevent: done\ndata: {}\n\n")),null,"m",null,null)); ok(streamed.contains("\"choices\":[]") && streamed.indexOf("\"choices\":[]") < streamed.indexOf("[DONE]"));
        JSONArray tools=new JSONArray().put(new JSONObject().put("function",new JSONObject().put("name","foo")));
        String tool="event: output\ndata: {\"tool_calls\":[{\"id\":\"c\",\"function\":{\"name\":\"foo\",\"arguments\":\"{}\"}}]}\n\n"+end;
        JSONObject choice=TraeStream.aggregate(input(tool),tools,"m",null,null).getJSONArray("choices").getJSONObject(0); ok(choice.getString("finish_reason").equals("tool_calls")); ok(choice.getJSONObject("message").getJSONArray("tool_calls").length()==1);
        String cutTools="event: output\ndata: {\"tool_calls\":[{\"id\":\"c\",\"function\":{\"name\":\"foo\",\"arguments\":\"{}\"}}],\"finish_reason\":\"length\"}\n\n"+end;
        ok(TraeStream.aggregate(input(cutTools),tools,"m",null,null).getJSONArray("choices").getJSONObject(0).getString("finish_reason").equals("tool_calls"));
        String cutText="event: output\ndata: {\"content\":\"partial\",\"finish_reason\":\"length\"}\n\n"+end;
        ok(TraeStream.aggregate(input(cutText),tools,"m",null,null).getJSONArray("choices").getJSONObject(0).getString("finish_reason").equals("length"));
        String filtered="event: output\ndata: {\"content\":\"blocked\",\"finish_reason\":\"sensitive\"}\n\n"+end;
        ok(TraeStream.aggregate(input(filtered),tools,"m",null,null).getJSONArray("choices").getJSONObject(0).getString("finish_reason").equals("content_filter"));
        bad("event: error\ndata: {\"error\":\"SECRET\"}\n\n"); bad("event: output\ndata: {\"content\":\"SECRET\"}\n\n"); bad("data: "+new String(new char[1024*1024+1]).replace('\0','x')+"\n\n");
        ok(TraeStream.aggregate(input("data: {\"type\":\"output\",\"content\":\"x\"}\n\n"+end),null,"m",null,null).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").equals("x"));
        String error="event: error\ndata: {\"code\":4011,\"message\":\"We're sorry, your requests have exceeded the rate limit. Please wait and try again later.\",\"extra\":null}\n\n";
        try(TraeStream first=new TraeStream(input(error+end),null,"m",null,null)) { try {first.prime();throw new AssertionError();} catch(TraeProtocol.UpstreamError e) {ok(e.status==429&&e.code.equals("4011"));} }
        try(TraeStream primed=new TraeStream(input(source),null,"m",null,null); TraeStream plain=new TraeStream(input(source),null,"m",null,null)) {primed.prime();primed.prime();byte[] a1=new byte[4096],a2=new byte[4096];int n1=primed.read(a1),n2=plain.read(a2);JSONObject j1=new JSONObject(new String(a1,0,n1,StandardCharsets.UTF_8).trim().substring(6));JSONObject j2=new JSONObject(new String(a2,0,n2,StandardCharsets.UTF_8).trim().substring(6));ok(j1.getJSONArray("choices").toString().equals(j2.getJSONArray("choices").toString()));}
        try(TraeStream late=new TraeStream(input("event: output\ndata: {\"content\":\"hello\"}\n\n"+error+end),null,"m",null,null)) {late.prime();int k=late.read(buf);ok(!new String(buf,0,k,StandardCharsets.UTF_8).contains("[DONE]"));try{late.read(buf);throw new AssertionError();}catch(TraeProtocol.UpstreamError e){ok(e.status==429&&e.code.equals("4011"));}}
        StringBuilder queues=new StringBuilder();for(int i=0;i<9000;i++)queues.append("event: queue\ndata: {\"padding\":\"").append(new String(new char[1000]).replace('\0','x')).append("\"}\n\n");bad(queues.toString()+error);
        System.out.println("TraeStreamOfflineTest OK");
    }
}
