package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** chat.deepseek.com web adapter. Protocol and PoW derived from volcanicll's MIT
 * magpie-deepseek-web-auth, commit e360be09485d603f338517af15015324b0ed0d2f.
 * Web mode aliases, not vendor foundation-model identifiers. Text only. */
public final class DeepSeekWeb {
    public static final String ORIGIN="https://chat.deepseek.com";
    public static final String COMPLETION="/api/v0/chat/completion";
    public static final String[] MODELS={"deepseek-chat","deepseek-reasoner"};
    public static final int MAX_DIFFICULTY=5000000;
    /** Failure details are retained separately from the message for safe handling by callers. */
    public static final class UpstreamException extends IOException {
        public final int upstreamStatus, gatewayStatus;
        public final String responseBody;
        public final boolean bodyTruncated;
        UpstreamException(String message,int upstreamStatus,int gatewayStatus,String responseBody,boolean truncated) {
            super(message); this.upstreamStatus=upstreamStatus; this.gatewayStatus=gatewayStatus;
            this.responseBody=responseBody; this.bodyTruncated=truncated;
        }
    }
    private static UpstreamException httpFailure(Response response,String operation) throws IOException {
        // Bound memory while retaining the upstream error body for the gateway to inspect.
        final int limit=1048576; ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] buf=new byte[4096]; boolean truncated=false; int n;
        if(response.body!=null) while((n=response.body.read(buf))!=-1) {
            int take=Math.min(n,limit-out.size()); if(take>0)out.write(buf,0,take);
            if(take<n){truncated=true;break;}
        }
        int code=response.status, mapped=(code==401||code==403)?401:(code==429?429:502);
        return new UpstreamException("DeepSeek "+operation+" HTTP "+code,code,mapped,
                new String(out.toByteArray(),StandardCharsets.UTF_8),truncated);
    }
    private static UpstreamException protocolFailure(String description,String body) {
        return new UpstreamException(description,0,502,body,false);
    }
    private static void checkFrameError(JSONObject frame) throws IOException {
        Object error=frame.opt("error");
        if(error!=null&&error!=JSONObject.NULL)throw protocolFailure("DeepSeek stream error",frame.toString());
        String path=frame.optString("p","");
        if(path.equals("error")||path.equals("response/error")||path.equals("data/error")) {
            Object value=frame.opt("v"); if(value!=null&&value!=JSONObject.NULL)throw protocolFailure("DeepSeek stream error patch",frame.toString());
        }
        for(String key:new String[]{"code","biz_code"}) {
            Object code=frame.opt(key);
            if(code!=null&&code!=JSONObject.NULL&&!"0".equals(code.toString()))
                throw protocolFailure("DeepSeek stream refused ("+key+")",frame.toString());
        }
        JSONObject data=frame.optJSONObject("data");
        if(data!=null){Object nestedError=data.opt("error");Object code=data.opt("biz_code");
            if(nestedError!=null&&nestedError!=JSONObject.NULL || code!=null&&code!=JSONObject.NULL&&!"0".equals(code.toString()))
                throw protocolFailure("DeepSeek stream refused",frame.toString());
        }
    }
    public interface Transport { Response request(String method,String url,Map<String,String> headers,byte[] body) throws IOException; }
    public interface Listener { void onText(String delta) throws IOException; void onReasoning(String delta) throws IOException; }
    public static final class Response implements Closeable {
        public final int status; public final String contentType; public final InputStream body;
        public Response(int status,String contentType,InputStream body){this.status=status;this.contentType=contentType==null?"":contentType;this.body=body;}
        public void close() throws IOException { if(body!=null)body.close(); }
    }
    public static final class HttpTransport implements Transport {
        public Response request(String method,String url,Map<String,String> headers,byte[] body) throws IOException {
            final HttpURLConnection c=NetworkPolicy.openHttp(url);
            c.setRequestMethod(method); c.setConnectTimeout(15000);c.setReadTimeout(120000);c.setInstanceFollowRedirects(false);
            for(Map.Entry<String,String> h:headers.entrySet())c.setRequestProperty(h.getKey(),h.getValue());
            try { if(body!=null){c.setDoOutput(true);try(OutputStream o=c.getOutputStream()){o.write(body);}}
                int status=c.getResponseCode(); InputStream in=status>=400?c.getErrorStream():c.getInputStream();
                if(in==null)in=new ByteArrayInputStream(new byte[0]);
                return new Response(status,c.getContentType(),new FilterInputStream(in){public void close() throws IOException {try{super.close();}finally{c.disconnect();}}});
            } catch(IOException e){c.disconnect();throw e;}
        }
    }
    private final Transport transport; private final String token;
    public DeepSeekWeb(Transport transport,String token) throws IOException {
        if(transport==null)throw new IOException("transport required");
        if(token==null || token.trim().isEmpty() || !token.matches("[\\x21-\\x7e]+"))throw new IOException("Invalid web token");
        this.transport=transport;this.token=token;
    }
    public static Map<String,String> headers(String token){
        Map<String,String> h=new LinkedHashMap<>(); h.put("accept","*/*");h.put("content-type","application/json");
        h.put("authorization","Bearer "+token);h.put("origin",ORIGIN);h.put("referer",ORIGIN+"/a/chat/");
        h.put("x-client-platform","web");h.put("x-client-version","2.4.0");h.put("x-client-bundle-id","com.deepseek.chat");h.put("x-client-locale","en_US");return h;
    }
    static JSONObject object(String text) throws IOException {try{return new JSONObject(text);}catch(Exception e){throw new IOException("Invalid DeepSeek JSON",e);}}
    static byte[] bytes(JSONObject x){return x.toString().getBytes(StandardCharsets.UTF_8);}
    static String read(InputStream in,int limit) throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;
        while((n=in.read(buf))!=-1){if(b.size()+n>limit)throw new IOException("DeepSeek response too large");b.write(buf,0,n);}return new String(b.toByteArray(),StandardCharsets.UTF_8);
    }
    static JSONObject unwrap(JSONObject value,String operation,boolean allow22) throws IOException {
        if(value.has("error")&&!value.isNull("error"))throw protocolFailure("DeepSeek "+operation+" error",value.toString());
        Object outer=value.opt("code");if(outer!=null&&outer!=JSONObject.NULL&&!"0".equals(outer.toString())&&!(allow22&&"22".equals(outer.toString())))throw protocolFailure("DeepSeek "+operation+" refused ("+outer+")",value.toString());
        JSONObject data=value.optJSONObject("data");Object code=data!=null&&data.has("biz_code")?data.opt("biz_code"):value.opt("code");
        if(code!=null&&code!=JSONObject.NULL&&!"0".equals(code.toString())&&!(allow22&&"22".equals(code.toString())))
            throw protocolFailure("DeepSeek "+operation+" refused ("+code+")",value.toString());
        return data==null?null:data.optJSONObject("biz_data");
    }
    private JSONObject json(String method,String path,JSONObject body,String op) throws IOException {
        try(Response r=transport.request(method,ORIGIN+path,headers(token),body==null?null:bytes(body))){
            if(r.status<200||r.status>=300)throw httpFailure(r,op);
            return object(read(r.body,1048576));
        }
    }
    /** Performs the web client's read-only /users/current token check. */
    public JSONObject validateToken() throws IOException {
        JSONObject v=json("GET","/api/v0/users/current",null,"token validation");JSONObject b=unwrap(v,"token validation",false);
        if(b==null)throw new IOException("DeepSeek token validation returned no user");
        JSONObject u=b.optJSONObject("user"); if(u==null)u=b;
        for(String key:new String[]{"id","user_id","mobile_number","mobile","email"}) {
            Object identity=u.opt(key);
            if(identity instanceof String && !((String)identity).trim().isEmpty())return u;
            if((key.equals("id")||key.equals("user_id")) && identity instanceof Number && ((Number)identity).longValue()>0)return u;
        }
        throw new IOException("DeepSeek token validation returned no user identity");
    }
    public String createSession() throws IOException {
        JSONObject b=unwrap(json("POST","/api/v0/chat_session/create",new JSONObject(),"session"),"session",false);
        JSONObject s=b==null?null:b.optJSONObject("chat_session");String id=s==null?(b==null?"":b.optString("id")):s.optString("id");
        if(id.isEmpty())throw new IOException("DeepSeek session id missing");return id;
    }
    public void deleteSession(String id) throws IOException {JSONObject b=new JSONObject();put(b,"chat_session_id",id);unwrap(json("POST","/api/v0/chat_session/delete",b,"delete session"),"delete session",false);}
    static void put(JSONObject o,String k,Object v) throws IOException {try{o.put(k,v==null?JSONObject.NULL:v);}catch(JSONException e){throw new IOException(e);}}
    /** Reject capabilities rather than silently discarding them. Full history is flattened for a fresh session. */
    public static JSONObject prepare(JSONObject request,String sessionId,Long parentId) throws IOException {
        String model=request.optString("model"); if(!Arrays.asList(MODELS).contains(model))throw new IOException("Invalid model for DeepSeek web");
        if(request.has("functions") || request.has("function_call")) throw new IOException("Unsupported functions or function_call");
        JSONArray messages=request.optJSONArray("messages");if(messages==null||messages.length()==0)throw new IOException("messages required");
        for(int i=0;i<messages.length();i++) {JSONObject m=messages.optJSONObject(i);if(m==null)throw new IOException("Invalid messages entry");
            String role=m.optString("role");if(!role.equals("system")&&!role.equals("developer")&&!role.equals("user")&&!role.equals("assistant")&&!role.equals("tool"))throw new IOException("Unsupported messages.role for DeepSeek web");
            if(m.has("function_call"))throw new IOException("Unsupported messages.function_call");
            Object content=m.opt("content");
            if(content instanceof JSONArray){JSONArray parts=(JSONArray)content;for(int j=0;j<parts.length();j++){JSONObject part=parts.optJSONObject(j);if(part==null||!"text".equals(part.optString("type"))||!(part.opt("text") instanceof String))throw new IOException("Unsupported messages.content: only text is supported");}}
            else if(!(content instanceof String) && !("assistant".equals(role) && m.optJSONArray("tool_calls")!=null && (content==null||content==JSONObject.NULL)))throw new IOException("Invalid messages.content");
            if(m.has("tool_calls")){JSONArray calls=m.optJSONArray("tool_calls");if(!role.equals("assistant")||calls==null)throw new IOException("Invalid messages.tool_calls");for(int j=0;j<calls.length();j++){JSONObject c=calls.optJSONObject(j),f=c==null?null:c.optJSONObject("function");if(c==null||!"function".equals(c.optString("type"))||c.optString("id").isEmpty()||f==null||f.optString("name").isEmpty()||!(f.opt("arguments") instanceof String))throw new IOException("Invalid messages.tool_calls");try{new JSONObject(f.optString("arguments"));}catch(Exception e){throw new IOException("Invalid messages.tool_calls.function.arguments");}}}
            if(role.equals("tool") && m.optString("tool_call_id").isEmpty())throw new IOException("Invalid messages.tool_call_id");
        }
        DeepSeekToolBridge.Prepared bridge;
        try {bridge=DeepSeekToolBridge.analyze(request);}catch(IllegalArgumentException e){throw new IOException(e.getMessage());}
        String prompt=bridge.buildPrompt();
        JSONObject out=new JSONObject();put(out,"action",null);put(out,"chat_session_id",sessionId);put(out,"parent_message_id",parentId);
        put(out,"model_type",model.equals("deepseek-reasoner")?"expert":"default");put(out,"prompt",prompt);put(out,"ref_file_ids",new JSONArray());
        put(out,"thinking_enabled",model.equals("deepseek-reasoner"));put(out,"search_enabled",request.optBoolean("search_enabled",true));put(out,"preempt",false);return out;
    }
    private JSONObject challenge() throws IOException {
        JSONObject body=new JSONObject();put(body,"target_path",COMPLETION);JSONObject b=unwrap(json("POST","/api/v0/chat/create_pow_challenge",body,"proof of work"),"proof of work",false);
        JSONObject c=b==null?null:b.optJSONObject("challenge");if(c==null)throw new IOException("DeepSeek PoW challenge missing");return c;
    }
    private static final int[] ROT={0,1,62,28,27,36,44,6,55,20,3,10,43,25,39,41,45,15,21,8,18,2,61,56,14};
    private static final long[] RC={0x0000000000000001L,0x0000000000008082L,0x800000000000808aL,0x8000000080008000L,0x000000000000808bL,0x0000000080000001L,0x8000000080008081L,0x8000000000008009L,0x000000000000008aL,0x0000000000000088L,0x0000000080008009L,0x000000008000000aL,0x000000008000808bL,0x800000000000008bL,0x8000000000008089L,0x8000000000008003L,0x8000000000008002L,0x8000000000000080L,0x000000000000800aL,0x800000008000000aL,0x8000000080008081L,0x8000000000008080L,0x0000000080000001L,0x8000000080008008L};
    private static void permute(long[] s,int rounds,long[] c,long[] d,long[] b){for(int r=24-rounds;r<24;r++){
        for(int x=0;x<5;x++)c[x]=s[x]^s[x+5]^s[x+10]^s[x+15]^s[x+20];
        for(int x=0;x<5;x++)d[x]=c[(x+4)%5]^Long.rotateLeft(c[(x+1)%5],1);
        for(int y=0;y<5;y++)for(int x=0;x<5;x++){int i=x+5*y;s[i]^=d[x];b[y+5*((2*x+3*y)%5)]=Long.rotateLeft(s[i],ROT[i]);}
        for(int y=0;y<5;y++)for(int x=0;x<5;x++)s[x+5*y]=b[x+5*y]^((~b[(x+1)%5+5*y])&b[(x+2)%5+5*y]);s[0]^=RC[r];}}
    public static byte[] hash(String input,int rounds){byte[] in=input.getBytes(StandardCharsets.UTF_8);long[] s=new long[25],c=new long[5],d=new long[5],b=new long[25];int pos=0;
        for(byte value:in){s[pos/8]^=(long)(value&255)<<((pos%8)*8);if(++pos==136){permute(s,rounds,c,d,b);pos=0;}}
        s[pos/8]^=6L<<((pos%8)*8);s[16]^=0x8000000000000000L;permute(s,rounds,c,d,b);byte[] out=new byte[32];for(int i=0;i<32;i++)out[i]=(byte)(s[i/8]>>>((i%8)*8));return out;}
    public static int solvePow(JSONObject c,long timeoutMs) throws IOException { return solvePow(c,timeoutMs,null); }
    static int solvePow(JSONObject c,long timeoutMs,CancelToken cancel) throws IOException {
        String algorithm=c.optString("algorithm"),target=c.optString("challenge"),salt=c.optString("salt");long difficulty=c.optLong("difficulty",0),expiry=c.optLong("expire_at",0);
        if(!algorithm.equals("DeepSeekHashV1")||!target.matches("(?i)[0-9a-f]{64}")||salt.isEmpty()||salt.length()>1024||difficulty<1||difficulty>MAX_DIFFICULTY||expiry<=0||timeoutMs<=0||timeoutMs>120000)throw new IOException("Invalid or excessive DeepSeek PoW challenge");
        byte[] want=new byte[32];for(int i=0;i<32;i++)want[i]=(byte)Integer.parseInt(target.substring(i*2,i*2+2),16);
        long deadline=System.nanoTime()+timeoutMs*1000000L;
        byte[] prefix=(salt+"_"+expiry+"_").getBytes(StandardCharsets.UTF_8);
        long[] base=new long[25],state=new long[25],col=new long[5],delta=new long[5],tmp=new long[25];
        int pos=0;
        for(byte value:prefix){base[pos/8]^=(long)(value&255)<<((pos%8)*8);if(++pos==136){permute(base,23,col,delta,tmp);pos=0;}}
        long[] targetLanes=new long[4];for(int j=0;j<32;j++)targetLanes[j/8]|=(long)(want[j]&255)<<((j%8)*8);
        byte[] digits=new byte[10];
        for(int i=0;i<difficulty;i++){
            if((i&63)==0){if(cancel!=null)cancel.check();if(Thread.currentThread().isInterrupted()||System.nanoTime()-deadline>=0)throw new IOException("DeepSeek PoW timed out or interrupted");}
            System.arraycopy(base,0,state,0,25);int p=pos,n=i,k=digits.length;
            do {digits[--k]=(byte)('0'+n%10);n/=10;} while(n!=0);
            while(k<digits.length){state[p/8]^=(long)digits[k++]<<((p%8)*8);if(++p==136){permute(state,23,col,delta,tmp);p=0;}}
            state[p/8]^=6L<<((p%8)*8);state[16]^=0x8000000000000000L;permute(state,23,col,delta,tmp);
            if(state[0]==targetLanes[0]&&state[1]==targetLanes[1]&&state[2]==targetLanes[2]&&state[3]==targetLanes[3]){
                if(cancel!=null)cancel.check();if(Thread.currentThread().isInterrupted()||System.nanoTime()-deadline>=0)throw new IOException("DeepSeek PoW timed out or interrupted");return i;
            }
        }
        if(cancel!=null)cancel.check();if(Thread.currentThread().isInterrupted()||System.nanoTime()-deadline>=0)throw new IOException("DeepSeek PoW timed out or interrupted");
        throw new IOException("DeepSeek PoW not solved within challenge difficulty");
    }
    public static String powHeader(JSONObject c,int answer) throws IOException {JSONObject h=new JSONObject();for(String k:new String[]{"algorithm","challenge","salt","signature"}){if(!c.has(k))throw new IOException("PoW missing "+k);put(h,k,c.opt(k));}put(h,"answer",answer);put(h,"target_path",COMPLETION);return base64(bytes(h));}
    private static String base64(byte[] in) {String alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";StringBuilder out=new StringBuilder();for(int i=0;i<in.length;i+=3){int x=(in[i]&255)<<16;int left=in.length-i;if(left>1)x|=(in[i+1]&255)<<8;if(left>2)x|=in[i+2]&255;out.append(alphabet.charAt((x>>>18)&63)).append(alphabet.charAt((x>>>12)&63)).append(left>1?alphabet.charAt((x>>>6)&63):'=').append(left>2?alphabet.charAt(x&63):'=');}return out.toString();}
    /** Accumulates upstream SSE patches. Listener receives only newly appended text. */
    public static final class StreamState {
        public final StringBuilder text=new StringBuilder(),reasoning=new StringBuilder();
        public Long messageId; public String status;public Long usage;public boolean closed;
        private String kind="RESPONSE";private final Listener listener;
        private final ArrayList<String> contents=new ArrayList<>(), kinds=new ArrayList<>();
        public StreamState(Listener listener){this.listener=listener;}
        private void append(String type,String s) throws IOException {
            if(s==null||s.isEmpty())return;String t=type==null?"":type.toUpperCase(Locale.ROOT);
            boolean think=t.equals("THINK")||t.equals("THINKING")||t.equals("REASONING")||t.equals("SEARCH")||t.startsWith("TOOL_");
            if(think){reasoning.append(s);if(listener!=null)listener.onReasoning(s);}
            else if(t.isEmpty()||t.equals("RESPONSE")||t.equals("TEMPLATE_RESPONSE")){text.append(s);if(listener!=null)listener.onText(s);}
            else throw new IOException("Unsupported DeepSeek fragment type: "+t);
        }
        private void writeFragment(int index,String type,String value,boolean snapshot) throws IOException {
            if(index<0 || index>=contents.size())throw new IOException("Unknown DeepSeek fragment index");
            String old=contents.get(index); String delta=value;
            if(snapshot){if(!value.startsWith(old))throw new IOException("DeepSeek replaced already emitted text");delta=value.substring(old.length());}
            contents.set(index,snapshot?value:old+value); append(type,delta);
        }
        private void fragments(JSONArray a,boolean snapshot) throws IOException {
            if(a==null)return;
            for(int i=0;i<a.length();i++){JSONObject f=a.optJSONObject(i);if(f==null)throw new IOException("Invalid DeepSeek fragment");
                int index=snapshot?i:contents.size(); String type=f.optString("type",kind);kind=type;
                while(contents.size()<=index){contents.add("");kinds.add(type);} kinds.set(index,type);
                Object value=f.opt("content");if(value!=null&&value!=JSONObject.NULL){if(!(value instanceof String))throw new IOException("Invalid fragment content");writeFragment(index,type,(String)value,true);}
            }
        }
        private void tree(JSONObject v) throws IOException {JSONObject response=v.optJSONObject("response");if(response==null){JSONObject inner=v.optJSONObject("v");if(inner!=null)response=inner.optJSONObject("response");}
            if(response!=null){if(response.has("message_id")&&!response.isNull("message_id"))messageId=response.optLong("message_id");if(response.has("status"))status=response.optString("status");if(response.has("accumulated_token_usage"))usage=response.optLong("accumulated_token_usage");fragments(response.optJSONArray("fragments"),true);}
            if(v.has("status"))status=v.optString("status");if(v.has("message_id")&&!v.isNull("message_id"))messageId=v.optLong("message_id");}
        public void apply(JSONObject frame) throws IOException {checkFrameError(frame); JSONObject nested=frame.optJSONObject("v"); if(nested!=null)checkFrameError(nested); String p=frame.optString("p","");Object v=frame.opt("v");String op=frame.optString("o","");
            if(op.equals("BATCH")&&v instanceof JSONArray){JSONArray a=(JSONArray)v;for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x==null)throw new IOException("Invalid DeepSeek batch patch");String child=x.optString("p",""); if(!p.isEmpty()&&!child.isEmpty()&&!child.startsWith(p+"/")){JSONObject y=object(x.toString());put(y,"p",p+"/"+child);apply(y);}else apply(x);}return;}
            if(!p.isEmpty()){
                if(p.equals("response/status")&&v instanceof String)status=(String)v;
                else if(p.equals("response/message_id")&&v instanceof Number)messageId=((Number)v).longValue();
                else if(p.contains("accumulated_token_usage")&&v instanceof Number)usage=((Number)v).longValue();
                else if(p.equals("response/fragments")&&v instanceof JSONArray)fragments((JSONArray)v,op.equals("SET"));
                else if(p.matches("response/fragments/-?[0-9]+(/content)?")&&v instanceof String&&(op.isEmpty()||op.equals("APPEND")||op.equals("SET"))) {
                    String number=p.substring("response/fragments/".length()).split("/")[0]; int index;
                    try{index=Integer.parseInt(number);}catch(NumberFormatException e){throw new IOException("Invalid fragment index",e);}
                    if(index==-1)index=contents.size()-1;
                    if(index<0||index>=kinds.size())throw new IOException("Unknown DeepSeek fragment index");
                    // Upstream uses APPEND or omits op for deltas; SET is a snapshot.
                    writeFragment(index,kinds.get(index),(String)v,op.equals("SET"));
                }
                else if(v instanceof JSONObject&&((JSONObject)v).optJSONArray("fragments")!=null)fragments(((JSONObject)v).optJSONArray("fragments"),true);
                else if(p.startsWith("response/fragments/")&&p.endsWith("/content")&&v instanceof String)throw new IOException("Unsupported DeepSeek content patch: "+p);
                return;
            }
            if(v instanceof String){if(contents.isEmpty())throw new IOException("Text without fragment");int last=contents.size()-1;writeFragment(last,kinds.get(last),(String)v,false);}else if(v instanceof JSONObject)tree((JSONObject)v);
        }
        public void event(String event,String data) throws IOException {
            if("error".equalsIgnoreCase(event))throw protocolFailure("DeepSeek stream error event",data);
            JSONObject frame=null;
            if(!data.isEmpty()&&!data.equals("[DONE]")){frame=object(data);checkFrameError(frame);JSONObject nested=frame.optJSONObject("v");if(nested!=null)checkFrameError(nested);}
            if("close".equals(event)){closed=true;return;}
            if(frame!=null&&(event.isEmpty()||event.equals("message")||event.equals("ready")))apply(frame);
        }
    }
    public static void readSse(InputStream in,StreamState state) throws IOException {
        BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8));String line,event="";StringBuilder data=new StringBuilder();
        while((line=r.readLine())!=null){if(line.length()>1048576)throw new IOException("DeepSeek SSE line too large");
            if(line.isEmpty()){state.event(event,data.toString());if(state.closed)return;event="";data.setLength(0);}
            else if(line.startsWith("event:"))event=line.substring(6).trim();
            else if(line.startsWith("data:")){if(data.length()>0)data.append('\n');String v=line.substring(5);if(v.startsWith(" "))v=v.substring(1);data.append(v);if(data.length()>1048576)throw new IOException("DeepSeek SSE event too large");}}
        if(data.length()>0||!event.isEmpty())state.event(event,data.toString());
    }
    public static final class TurnResult {public final String sessionId;public final StreamState stream;TurnResult(String id,StreamState stream){this.sessionId=id;this.stream=stream;}}
    /** Caller owns created session and may explicitly delete it. Existing sessions can be chained. */
    public static final class CancelToken {
        private boolean cancelled; private Response active;
        public synchronized void cancel() { cancelled=true; if(active!=null)try{active.close();}catch(IOException ignored){} }
        synchronized void check() throws IOException { if(cancelled)throw new IOException("DeepSeek request cancelled"); }
        synchronized void attach(Response r) throws IOException { if(cancelled){r.close();throw new IOException("DeepSeek request cancelled");} active=r; }
        synchronized void detach(Response r) { if(active==r)active=null; }
    }
    public TurnResult run(JSONObject request,String sessionId,Long parentId,Listener listener,long powTimeoutMs) throws IOException { return run(request,sessionId,parentId,listener,powTimeoutMs,new CancelToken()); }
    public TurnResult run(JSONObject request,String sessionId,Long parentId,Listener listener,long powTimeoutMs,CancelToken cancel) throws IOException {
        if(cancel==null)throw new IOException("CancelToken required"); cancel.check();
        // Validate before making any network calls.
        prepare(request,sessionId,parentId);
        String id=sessionId==null?createSession():sessionId;JSONObject body=prepare(request,id,parentId);String path=COMPLETION;
        StreamState state=new StreamState(listener);int continues=0;
        for(;;){cancel.check();Map<String,String> h=headers(token);if(path.equals(COMPLETION)){JSONObject c=challenge();int answer=solvePow(c,powTimeoutMs,cancel);h.put("x-ds-pow-response",powHeader(c,answer));}
            try(Response r=transport.request("POST",ORIGIN+path,h,bytes(body))){
                cancel.attach(r); try {
                if(r.status<200||r.status>=300)throw httpFailure(r,"completion");
                state.status=null;state.closed=false;
                if(r.contentType.toLowerCase(Locale.ROOT).contains("text/event-stream"))readSse(r.body,state);
                else {JSONObject biz=unwrap(object(read(r.body,1048576)),"completion",true);JSONObject response=biz==null?null:biz.optJSONObject("response");if(response==null)throw new IOException("DeepSeek completion did not return a stream");JSONObject wrap=new JSONObject(),v=new JSONObject();put(v,"response",response);put(wrap,"v",v);state.apply(wrap);cancel.check();if(!"FINISHED".equals(state.status))throw new IOException("DeepSeek JSON response not finished: "+state.status);return new TurnResult(id,state);}
                cancel.check();
                } finally { cancel.detach(r); }
            }
            if("FINISHED".equals(state.status)&&state.text.length()==0&&state.reasoning.length()==0&&state.messageId!=null&&continues==0){path="/api/v0/chat/resume_stream";body=new JSONObject();put(body,"chat_session_id",id);put(body,"message_id",state.messageId);continues++;continue;}
            if("INCOMPLETE".equals(state.status)&&state.messageId!=null&&continues<20){path="/api/v0/chat/continue";body=new JSONObject();put(body,"chat_session_id",id);put(body,"message_id",state.messageId);put(body,"fallback_to_resume",true);continues++;continue;}
            if(!"FINISHED".equals(state.status))throw new IOException("DeepSeek stream ended without FINISHED status: "+state.status);
            return new TurnResult(id,state);
        }
    }
}
