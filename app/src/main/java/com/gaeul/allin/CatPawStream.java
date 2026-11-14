package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** CatPaw's cumulative SSE as a live OpenAI stream. Never splits a completed reply to fake streaming. */
final class CatPawStream extends InputStream {
    interface Transport { Reply post(String path, JSONObject body, Map<String,String> headers, RequestContext request) throws Exception; }
    static final class Reply implements AutoCloseable {
        final int status; final InputStream input; final HttpURLConnection connection;
        private final RequestContext context; private final AtomicBoolean closed=new AtomicBoolean();
        Reply(int status,InputStream input,HttpURLConnection connection,RequestContext context){this.status=status;this.input=input;this.connection=connection;this.context=context;}
        public void close(){if(!closed.compareAndSet(false,true))return;if(connection!=null){if(context!=null)context.detach(connection);connection.disconnect();}if(input!=null)try{input.close();}catch(IOException ignored){}}
    }
    static final Transport HTTP=(path,body,headers,request)->{
        HttpURLConnection c=(request.network==null?NetworkPolicy.capture():request.network).openHttp(CatPawAuth.ORIGIN+path);
        try {
            request.check();request.attach(c);c.setInstanceFollowRedirects(false);c.setRequestMethod("POST");c.setDoOutput(true);
            c.setConnectTimeout((int)Math.max(1,Math.min(15000,request.remainingMillis())));
            c.setReadTimeout((int)Math.max(1,Math.min(900000,request.remainingMillis())));
            for(Map.Entry<String,String> h:headers.entrySet())c.setRequestProperty(h.getKey(),h.getValue());
            c.setRequestProperty("Accept-Encoding","identity");byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(bytes.length);
            request.check();try(OutputStream out=c.getOutputStream()){out.write(bytes);}
            int status=c.getResponseCode();InputStream input=status>=200&&status<300?c.getInputStream():c.getErrorStream();
            if(status>=200&&status<300&&path.endsWith("/turn")){
                String type=c.getContentType();if(type==null||!type.toLowerCase(Locale.ROOT).contains("text/event-stream"))throw new IOException("CatPaw expected SSE");
                String encoding=c.getContentEncoding();if("gzip".equalsIgnoreCase(encoding))input=new java.util.zip.GZIPInputStream(input);else if(encoding!=null&&!encoding.isEmpty()&&!"identity".equalsIgnoreCase(encoding))throw new IOException("CatPaw unsupported encoding");
            }
            return new Reply(status,input,c,request);
        }catch(Exception failure){request.detach(c);c.disconnect();throw failure;}
    };
    static Transport legacy(CatPawAuth.ChatTransport transport){return (path,body,headers,request)->{
        CatPawAuth.ChatResponse r=transport.post(CatPawAuth.ORIGIN+path,body.toString(),headers,(int)Math.max(1,Math.min(900000,request.remainingMillis())),path.endsWith("/turn")?"text/event-stream":"application/json");
        if(r==null)throw new IOException("CatPaw no response");return new Reply(r.status,new ByteArrayInputStream(r.body),null,request);
    };}
    private static Reply send(Transport transport,String path,JSONObject body,Map<String,String> headers,RequestContext request)throws Exception {
        request.check();Reply r=transport.post(path,body,headers,request);if(r==null)throw new IOException("CatPaw no reply");
        if(r.status<200||r.status>=300){int status=r.status;r.close();throw new CatPawAuth.Failure(status,null,true);}if(r.input==null){r.close();throw new IOException("CatPaw empty stream");}return r;
    }
    private static JSONObject document(Reply reply)throws Exception {
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
            while((n=reply.input.read(buffer))!=-1){if(bytes.size()+n>2*1024*1024)throw new IOException("CatPaw metadata too large");bytes.write(buffer,0,n);}
            String text=bytes.toString("UTF-8");new BoundedStrictJson(text).run();return new JSONObject(text);
        }finally{reply.close();}
    }
    private static Integer code(Object raw){if(raw instanceof Number){double n=((Number)raw).doubleValue();if(Double.isFinite(n)&&n==Math.rint(n)&&n>=0&&n<=Integer.MAX_VALUE)return (int)n;}if(raw instanceof String&&((String)raw).matches("[0-9]{1,9}"))return Integer.valueOf((String)raw);return null;}
    private static int business(Integer code){if(code!=null&&(code==4010||code==4011))return 401;return CatPawAuth.businessStatus(code);}
    static CatPawStream open(String token,String model,JSONArray messages,Integer type,String requested,RequestContext request,Transport transport)throws Exception {
        if(CatPawAuth.unsupportedReason(messages,null)!=null)throw new CatPawAuth.Failure(400,null,false);
        if(type!=null&&type<=0)throw new CatPawAuth.Failure(400,null,false);
        String conv=UUID.randomUUID().toString(),msg=UUID.randomUUID().toString(),turnId=UUID.randomUUID().toString();String prompt=CatPawAuth.buildPrompt(messages);
        int modelType=type==null?CatPawAuth.modelTypeOf(model):type;
        JSONObject round=body(conv,msg,prompt,modelType).put("repositories",new JSONArray());Map<String,String> headers=CatPawAuth.gatewayHeaders(token);headers.put("Accept","application/json");headers.put("m-spanid","0.2");headers.put("m-spanname","conversation.round");
        JSONObject r=document(send(transport,"/api/agent/conversation/round",round,headers,request));Integer c=code(r.opt("code"));if(c==null||c!=0)throw new CatPawAuth.Failure(business(c),c,true);
        JSONObject event=new JSONObject().put("conversationId",conv).put("eventType","conversation").put("data",new JSONObject().put("status","running"));headers=CatPawAuth.gatewayHeaders(token);headers.put("Accept","application/json");headers.put("m-spanid","0.3");headers.put("m-spanname","conversation.event");
        r=document(send(transport,"/api/agent/conversation/event",event,headers,request));c=code(r.opt("code"));if(r.has("code")&&(c==null||c!=0))throw new CatPawAuth.Failure(business(c),c,true);
        JSONObject turn=body(conv,msg,prompt,modelType).put("turnRequestId",turnId).put("toolConfigs",new JSONArray());headers=CatPawAuth.gatewayHeaders(token);headers.put("Accept","text/event-stream");headers.put("enableheartbeat","true");headers.put("m-spanid","0.4.1");headers.put("m-spanname","conversation.turn");
        Reply live=send(transport,"/api/agent/conversation/turn",turn,headers,request);CatPawStream stream=new CatPawStream(live,requested,request);
        try{stream.fill();stream.primed=true;return stream;}catch(Exception failure){stream.close();throw failure;}
    }
    private static JSONObject body(String conv,String msg,String prompt,int model)throws Exception{return new JSONObject().put("conversationId",conv).put("message",CatPawAuth.userMessage(msg,prompt)).put("modelType",model).put("source","CatX").put("mode","CATX_APP").put("permissionMode","unsafeBypassPermissions").put("toolVersion","2.0.2");}
    private static final int MAX_LINE=16*1024*1024;
    private final Reply reply;private final RequestContext request;private final Reader reader;private final String model,id="chatcmpl-"+UUID.randomUUID().toString();private final long created=System.currentTimeMillis()/1000;
    private String text="",reasoning="";private boolean terminal,closed,primed,role,finished;private ByteArrayInputStream pending=new ByteArrayInputStream(new byte[0]);
    private CatPawStream(Reply reply,String model,RequestContext request){this.reply=reply;this.model=model;this.request=request;this.reader=new InputStreamReader(reply.input,StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT));}
    private String line()throws IOException {StringBuilder s=new StringBuilder();while(true){request.check();int c=reader.read();if(c<0){if(s.length()!=0)throw new IOException("CatPaw truncated SSE line");return null;}if(c=='\n')return s.toString();if(c!='\r')s.append((char)c);if(s.length()>MAX_LINE)throw new IOException("CatPaw frame too large");}}
    private String chunk(JSONObject delta,String finish){return "data: "+new JSONObject().put("id",id).put("object","chat.completion.chunk").put("created",created).put("model",model).put("choices",new JSONArray().put(new JSONObject().put("index",0).put("delta",delta).put("finish_reason",finish==null?JSONObject.NULL:finish)))+"\n\n";}
    private void emit(JSONObject delta){String s=role?"":chunk(new JSONObject().put("role","assistant"),null);role=true;pending=new ByteArrayInputStream((s+chunk(delta,null)).getBytes(StandardCharsets.UTF_8));}
    private void done()throws IOException {if(text.isEmpty()&&reasoning.isEmpty())throw new IOException("CatPaw empty turn");if(!text.isEmpty()&&Character.isHighSurrogate(text.charAt(text.length()-1)))throw new IOException("CatPaw incomplete Unicode");terminal=true;pending=new ByteArrayInputStream((chunk(new JSONObject(),"stop")+"data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));reply.close();}
    private static String delta(String old,String current)throws IOException {if(old.startsWith(current))return "";if(!current.startsWith(old))throw new IOException("CatPaw cumulative text changed");int end=current.length();if(end>0&&Character.isHighSurrogate(current.charAt(end-1)))end--;return end<old.length()?"":current.substring(old.length(),end);}
    private void fill()throws Exception {
        while(pending.available()==0&&!terminal){String line=line();if(line==null){if(!finished)throw new IOException("CatPaw stream ended without terminal evidence");done();return;}if(!line.startsWith("data:"))continue;String data=line.substring(5).trim();if(data.isEmpty())continue;if("[DONE]".equals(data)){done();return;}
            new BoundedStrictJson(data).run();JSONObject frame=new JSONObject(data);if(!frame.isNull("error")){JSONObject error=frame.optJSONObject("error");Integer code=error==null?null:code(error.opt("code"));int status=error!=null&&error.optString("message").contains("余额不足")?402:business(code);throw new CatPawAuth.Failure(status,code,true);}
            JSONObject message=frame.optJSONObject("message");if(message==null){if(!frame.isNull("message"))throw new IOException("CatPaw invalid message");continue;}JSONArray parts=message.optJSONArray("content");if(parts==null){if(message.has("content"))throw new IOException("CatPaw invalid content");continue;}
            StringBuilder t=new StringBuilder(),r=new StringBuilder();boolean hasText=false,hasReason=false;
            for(int i=0;i<parts.length();i++){JSONObject p=parts.optJSONObject(i);if(p==null)throw new IOException("CatPaw invalid part");if(!"text".equals(p.optString("type")))continue;if(p.has("text")&&!p.isNull("text")){if(!(p.opt("text")instanceof String))throw new IOException("CatPaw invalid text");t.append(p.getString("text"));hasText=true;}if(p.has("reasoningContent")&&!p.isNull("reasoningContent")){if(!(p.opt("reasoningContent")instanceof String))throw new IOException("CatPaw invalid reasoning");r.append(p.getString("reasoningContent"));hasReason=true;}}
            JSONObject d=new JSONObject();if(hasText){String current=t.toString(),head=current.replaceFirst("^\\s+","");boolean modelless=message.isNull("model")&&message.isNull("modelDisplayName");if(text.isEmpty()&&modelless&&head.startsWith("当前余额不足以完成本次操作"))throw new CatPawAuth.Failure(402,null,true);boolean held=text.isEmpty()&&modelless&&!head.isEmpty()&&"当前余额不足以完成本次操作".startsWith(head);if(!held){String next=delta(text,current);if(!next.isEmpty()){d.put("content",next);text+=next;}}}
            if(hasReason){String next=delta(reasoning,r.toString());if(!next.isEmpty()){d.put("reasoning_content",next);reasoning+=next;}}
            finished=Boolean.TRUE.equals(message.opt("finished"));if(d.length()>0)emit(d);
        }
    }
    private void fail(Exception error){int status=CatPawAuth.failureStatus(error);String message=status==402?"妙手余额不足，流式响应已停止":status==401?"妙手登录已失效，流式响应已停止":status==429?"妙手限流，请等待约45秒后重试":"妙手流式响应不完整，未重复发送请求";JSONObject detail=new JSONObject().put("type",status==402?"insufficient_quota":status==429?"rate_limit_error":"upstream_error").put("status",status).put("message",message);if(error instanceof CatPawAuth.Failure&&((CatPawAuth.Failure)error).businessCode!=null)detail.put("business_code",((CatPawAuth.Failure)error).businessCode);if(status==429)detail.put("retry_after_seconds",45);terminal=true;pending=new ByteArrayInputStream(("data: "+new JSONObject().put("error",detail)+"\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));reply.close();}
    @Override public int read()throws IOException {byte[] b=new byte[1];int n=read(b,0,1);return n<0?-1:b[0]&255;}
    @Override public int read(byte[] b,int off,int len)throws IOException {if(b==null)throw new NullPointerException();if(off<0||len<0||off>b.length-len)throw new IndexOutOfBoundsException();if(len==0)return 0;if(closed)throw new IOException("CatPaw stream closed");try{fill();}catch(Exception e){if(!primed)throw new IOException("CatPaw stream not primed",e);fail(e);}return pending.available()>0?pending.read(b,off,len):-1;}
    @Override public void close(){if(closed)return;closed=true;reply.close();try{reader.close();}catch(IOException ignored){}}
}
