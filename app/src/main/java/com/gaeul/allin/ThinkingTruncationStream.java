package com.gaeul.allin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import com.gaeul.allin.json.*;

/** Narrow client compatibility for a reasoning-only Chat SSE stopped by token limit. */
final class ThinkingTruncationStream extends InputStream {
    static final String NOTICE="（思考输出已达到上限，未生成正文）";
    private final BufferedReader in;
    private byte[] buffer=new byte[0]; private int offset;
    private boolean reasoning, content, tools;
    ThinkingTruncationStream(InputStream source) { in=new BufferedReader(new InputStreamReader(source,StandardCharsets.UTF_8)); }
    private boolean fill() throws IOException {
        while(offset>=buffer.length) {
            String line=in.readLine(); if(line==null)return false;
            if(line.startsWith("data:")) {
                String raw=line.substring(5).trim();
                if(!raw.isEmpty() && !"[DONE]".equals(raw)) try {
                    JSONObject j=new JSONObject(raw); JSONArray cs=j.optJSONArray("choices");
                    if(j.has("error") && !j.isNull("error")) tools=true;
                    if(cs!=null && cs.length()==1) {
                        JSONObject c=cs.optJSONObject(0); JSONObject d=c==null?null:c.optJSONObject("delta");
                        if(d!=null) {
                            Object r=d.opt("reasoning_content"), t=d.opt("content");
                            if(r instanceof String&&!((String)r).isEmpty())reasoning=true;
                            if(t instanceof String&&!((String)t).isEmpty())content=true;
                            if((d.optJSONArray("tool_calls")!=null&&d.optJSONArray("tool_calls").length()>0)
                                    || (d.has("function_call")&&!d.isNull("function_call"))
                                    || (d.has("refusal")&&!d.isNull("refusal"))) tools=true;
                        }
                        if(c!=null && "length".equals(c.optString("finish_reason"))&&reasoning&&!content&&!tools) {
                            JSONObject injected=new JSONObject(j.toString());
                            JSONObject ic=injected.getJSONArray("choices").getJSONObject(0);
                            ic.put("delta",new JSONObject().put("content",NOTICE));
                            ic.put("finish_reason",JSONObject.NULL);
                            c.put("finish_reason","stop");
                            line="data: "+injected.toString()+"\n\ndata: "+j.toString(); content=true;
                        }
                    } else if(cs!=null&&cs.length()>1) tools=true;
                } catch(RuntimeException ignored) { tools=true; }
            }
            buffer=(line+"\n").getBytes(StandardCharsets.UTF_8);offset=0;
        }
        return true;
    }
    public int read()throws IOException{return fill()?buffer[offset++]&255:-1;}
    public int read(byte[] b,int off,int len)throws IOException{if(len==0)return 0;if(!fill())return -1;int n=Math.min(len,buffer.length-offset);System.arraycopy(buffer,offset,b,off,n);offset+=n;return n;}
    public void close()throws IOException{in.close();}
}
