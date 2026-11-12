package com.gaeul.allin;

import java.io.*;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import com.gaeul.allin.json.*;

/** Converts a Trae event stream to OpenAI chat completion SSE. */
public final class TraeStream extends InputStream {
    public interface UsageSink { void accept(JSONObject usage); }
    private static final int FRAME_LIMIT = 1024 * 1024, TOTAL_LIMIT = 8 * 1024 * 1024;
    private final Reader reader;
    private final TraeProtocol.Decoder decoder;
    private final RequestContext request;
    private final UsageSink sink;
    private byte[] pending = new byte[0];
    private int offset, skipped, frameSize;
    private boolean done, closed;
    private JSONObject lastUsage;
    private int held = -1;

    public TraeStream(InputStream upstream, JSONArray tools, String model, RequestContext request, UsageSink sink) {
        if (upstream == null) throw new IllegalArgumentException("upstream");
        reader = new InputStreamReader(upstream, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT));
        decoder = new TraeProtocol.Decoder(tools, model);
        this.request = request; this.sink = sink;
    }
    private void check() throws IOException { if (request != null) request.check(); }
    private int next() throws IOException { int c; if (held >= 0) { c=held; held=-1; } else { try { c=reader.read(); } catch (IOException ex) { throw new IOException("Trae 上游读取失败"); } } return c; }
    private String line() throws IOException {
        StringBuilder s=new StringBuilder();
        while (true) {
            if ((frameSize & 4095)==0) check();
            int c=next();
            if (c<0) return s.length()==0 ? null : s.toString();
            if (++frameSize>FRAME_LIMIT) throw new IOException("Trae 事件超过大小限制");
            if (c=='\n') return s.toString();
            if (c=='\r') { int n=next(); if(n>=0 && n!='\n') held=n; else if(n=='\n' && ++frameSize>FRAME_LIMIT) throw new IOException("Trae 事件超过大小限制"); return s.toString(); }
            s.append((char)c);
        }
    }
    private void fill() throws IOException {
        while (!done && offset==pending.length) {
            check(); frameSize=0;
            String event=""; StringBuilder data=new StringBuilder(); boolean hasData=false, complete=false;
            while (true) {
                String line=line();
                if (line==null) break;
                if (line.isEmpty()) { complete=true; break; }
                if (line.charAt(0)==':') continue;
                int colon=line.indexOf(':'); String key=colon<0?line:line.substring(0,colon);
                String value=colon<0?"":line.substring(colon+1); if(value.startsWith(" "))value=value.substring(1);
                if(key.equals("event")) event=value;
                else if(key.equals("data")) { if(hasData)data.append('\n'); data.append(value); hasData=true; }
            }
            if (!complete) throw new IOException("Trae 流在结束事件前中断");
            if (!hasData) { skipped+=frameSize; if(skipped>TOTAL_LIMIT)throw new IOException("Trae 流缺少有效数据"); continue; }
            String body=data.toString();
            try {
                if((event.isEmpty() || event.equals("message")) && !body.equals("[DONE]")) {
                    JSONObject obj=new JSONObject(body);
                    Object e=obj.opt("event"); if(!(e instanceof String)||((String)e).isEmpty()) e=obj.opt("type");
                    if(e instanceof String)event=(String)e;
                }
                List<JSONObject> chunks=decoder.accept(event,body);
                if(decoder.usage!=null && decoder.usage!=lastUsage) { lastUsage=decoder.usage; if(sink!=null)sink.accept(lastUsage); }
                ByteArrayOutputStream out=new ByteArrayOutputStream();
                for(JSONObject chunk:chunks) { byte[] b=("data: "+chunk.toString()+"\n\n").getBytes(StandardCharsets.UTF_8); if(out.size()+b.length>TOTAL_LIMIT)throw new IOException("Trae 输出超过大小限制"); out.write(b); }
                if(decoder.terminal) { if(decoder.usage!=null) { JSONObject usageChunk=new JSONObject().put("id",decoder.chunkId()).put("object","chat.completion.chunk").put("created",decoder.createdAt()).put("model",decoder.modelName()).put("choices",new JSONArray()).put("usage",decoder.usage); out.write(("data: "+usageChunk.toString()+"\n\n").getBytes(StandardCharsets.UTF_8)); } out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8)); done=true; }
                pending=out.toByteArray(); offset=0;
                if(pending.length==0) { skipped+=frameSize; if(skipped>TOTAL_LIMIT)throw new IOException("Trae 流缺少有效数据"); } else skipped=0;
            } catch (TraeProtocol.UpstreamError ex) { throw ex; } catch (Exception ex) { throw new IOException("Trae 流数据无效或上游报错"); }
        }
    }
    public void prime() throws IOException { if(closed)throw new IOException("流已关闭"); fill(); }
    @Override public int read() throws IOException { byte[] one=new byte[1]; int n=read(one,0,1); return n<0?-1:one[0]&255; }
    @Override public int read(byte[] b,int off,int len) throws IOException {
        if(b==null)throw new NullPointerException(); if(off<0||len<0||off>b.length-len)throw new IndexOutOfBoundsException(); if(len==0)return 0;
        if(closed)throw new IOException("流已关闭"); fill(); if(offset==pending.length)return -1;
        int n=Math.min(len,pending.length-offset); System.arraycopy(pending,offset,b,off,n); offset+=n; return n;
    }
    @Override public void close() throws IOException { if(!closed){closed=true;reader.close();} }
    public JSONObject aggregate() throws Exception {
        BufferedReader r=new BufferedReader(new InputStreamReader(this,StandardCharsets.UTF_8));
        StringBuilder content=new StringBuilder(), reasoning=new StringBuilder(); JSONArray calls=new JSONArray();
        JSONObject first=null; String finish=null; int total=0; String line;
        while((line=r.readLine())!=null) {
            total+=line.getBytes(StandardCharsets.UTF_8).length+1; if(total>TOTAL_LIMIT)throw new IOException("Trae 聚合响应超过大小限制");
            if(!line.startsWith("data: ") || line.equals("data: [DONE]"))continue;
            JSONObject chunk=new JSONObject(line.substring(6)); if(first==null)first=chunk;
            JSONArray choices=chunk.optJSONArray("choices"); if(choices==null||choices.length()==0) { if(chunk.optJSONObject("usage")!=null) continue; throw new IOException("Trae chunk has no choice"); } JSONObject choice=choices.getJSONObject(0), delta=choice.getJSONObject("delta");
            content.append(delta.optString("content","")); reasoning.append(delta.optString("reasoning_content",""));
            JSONArray a=delta.optJSONArray("tool_calls"); if(a!=null)for(int i=0;i<a.length();i++){JSONObject c=a.getJSONObject(i); c.remove("index"); calls.put(c);}
            if(!choice.isNull("finish_reason"))finish=choice.optString("finish_reason");
        }
        if(first==null)throw new IOException("Trae 响应为空");
        JSONObject message=new JSONObject().put("role","assistant").put("content",content.length()==0?JSONObject.NULL:content.toString());
        if(reasoning.length()>0)message.put("reasoning_content",reasoning.toString()); if(calls.length()>0)message.put("tool_calls",calls);
        JSONObject result=new JSONObject().put("id",first.get("id")).put("object","chat.completion").put("created",first.get("created")).put("model",first.get("model")).put("choices",new JSONArray().put(new JSONObject().put("index",0).put("message",message).put("finish_reason",finish)));
        if(lastUsage!=null)result.put("usage",lastUsage); return result;
    }
    public static JSONObject aggregate(InputStream in,JSONArray tools,String model,RequestContext ctx,UsageSink sink) throws Exception {
        try(TraeStream stream=new TraeStream(in,tools,model,ctx,sink)){return stream.aggregate();}
    }
}
