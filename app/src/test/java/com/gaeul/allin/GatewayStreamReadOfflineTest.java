package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Direct contracts of GatewayServer's Chat forwarding stream; no network or Android context. */
public final class GatewayStreamReadOfflineTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static final class Source extends ByteArrayInputStream {
        int reads, closes;
        int maxRead = Integer.MAX_VALUE;
        boolean failClose;
        Source(String data) { super(data.getBytes(StandardCharsets.UTF_8)); }
        @Override public synchronized int read() { reads++; return super.read(); }
        @Override public synchronized int read(byte[] b, int off, int len) { reads++; return super.read(b, off, Math.min(len, maxRead)); }
        @Override public void close() throws IOException { closes++; if (failClose) throw new IOException("fixture close failure"); super.close(); }
    }
    static final class Connection extends HttpURLConnection {
        int disconnects;
        Connection() throws Exception { super(new URL("http://127.0.0.1/")); }
        public void connect() {}
        public void disconnect() { disconnects++; }
        public boolean usingProxy() { return false; }
    }
    static String event(JSONObject delta, Object reason) {
        return "data: " + new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("index",0).put("delta",delta).put("finish_reason",reason))) + "\n\n";
    }
    static String done() { return event(new JSONObject(), "stop") + "data: [DONE]\n\n"; }
    static String content(String value) { return event(new JSONObject().put("content",value), JSONObject.NULL); }
    static InputStream stream(GatewayServer g, InputStream source, Connection connection, PrivacyMasker.Session mask, UsageLedger.Row row) throws Exception {
        Field rf=GatewayServer.class.getDeclaredField("U_ROW"), sf=GatewayServer.class.getDeclaredField("U_START");
        rf.setAccessible(true); sf.setAccessible(true);
        @SuppressWarnings("unchecked") ThreadLocal<UsageLedger.Row> rows=(ThreadLocal<UsageLedger.Row>)rf.get(null);
        @SuppressWarnings("unchecked") ThreadLocal<Long> starts=(ThreadLocal<Long>)sf.get(null);
        if(row!=null) { rows.set(row); starts.set(System.currentTimeMillis()); }
        Method method=GatewayServer.class.getDeclaredMethod("streamForwardResponse",InputStream.class,PrivacyMasker.Session.class,ContextGuard.Result.class,HttpURLConnection.class);
        method.setAccessible(true);
        try { return ((NanoHTTPD.Response)method.invoke(g,source,mask,null,connection)).getData(); }
        finally { rows.remove(); starts.remove(); }
    }
    static GatewayServer gateway() throws Exception { return ResponsesGatewayOfflineTest.gateway(1); }
    interface Throwing { void run() throws Exception; }
    static void throwsType(Class<? extends Throwable> expected, Throwing action) throws Exception {
        try { action.run(); } catch(Throwable e) { if(expected.isInstance(e)) return; throw new AssertionError("expected " + expected.getName() + ", got " + e, e); }
        throw new AssertionError("expected " + expected.getName());
    }
    static void contracts() throws Exception {
        String data=content("汉字 é 😀")+done(); Source source=new Source(data); Connection conn=new Connection();
        source.maxRead=1; // Force UTF-8 code points across upstream bulk-read boundaries.
        InputStream in=stream(gateway(),source,conn,null,null); byte[] buffer=new byte[9];
        check(in.read(buffer,buffer.length,0)==0 && source.reads==0,"zero length read consumed input");
        throwsType(NullPointerException.class, () -> in.read(null,0,0));
        throwsType(IndexOutOfBoundsException.class, () -> in.read(buffer,-1,1));
        throwsType(IndexOutOfBoundsException.class, () -> in.read(buffer,0,-1));
        throwsType(IndexOutOfBoundsException.class, () -> in.read(buffer,buffer.length,1));
        throwsType(IndexOutOfBoundsException.class, () -> in.read(buffer,Integer.MAX_VALUE,1));
        check(source.reads==0,"invalid read consumed input");
        ByteArrayOutputStream out=new ByteArrayOutputStream(); int count, turn=0;
        while((count=in.read(buffer,2,1+(turn++%3)))!=-1) { check(count>0,"non-progress read"); out.write(buffer,2,count); }
        check(Arrays.equals(data.getBytes(StandardCharsets.UTF_8),out.toByteArray()),"tiny reads changed or lost UTF-8 bytes");
        check(in.read(buffer,0,0)==0 && in.read(buffer,0,1)==-1,"EOF read contract");
        in.close(); check(source.closes==1 && conn.disconnects==1,"normal close must release source and connection");
    }
    static JSONObject tool(String arguments) {
        return new JSONObject().put("tool_calls",new JSONArray().put(new JSONObject().put("index",0).put("function",new JSONObject().put("arguments",arguments))));
    }
    static List<JSONObject> jsonEvents(String data) {
        List<JSONObject> events=new ArrayList<>();
        for(String line:data.split("\n")) if(line.startsWith("data: ") && !line.equals("data: [DONE]")) events.add(new JSONObject(line.substring(6)));
        return events;
    }
    static void fragmentedUnmask() throws Exception {
        PrivacyMasker.Session mask=new PrivacyMasker.Session();
        mask.placeholderToReal.put("{{MASK_PHONE_a}}","real-phone");
        mask.placeholderToReal.put("{{MASK_EMAIL_b}}","real-email");
        mask.placeholderToReal.put("{{MASK_NAME_c}}","real-name");
        String wire=event(new JSONObject().put("content","C{{MASK_PHO").put("reasoning_content","R{{MASK_EMA"),JSONObject.NULL)
                +event(tool("{\"name\":\"{{MASK_NA"),JSONObject.NULL)
                +event(new JSONObject().put("content","NE_a}}!").put("reasoning_content","IL_b}}!"),JSONObject.NULL)
                +event(tool("ME_c}}\"}"),JSONObject.NULL)+done();
        Source source=new Source(wire); InputStream in=stream(gateway(),source,new Connection(),mask,null);
        String data=ResponsesGatewayOfflineTest.read(in); in.close();
        StringBuilder content=new StringBuilder(), reasoning=new StringBuilder(), args=new StringBuilder();
        for(JSONObject o:jsonEvents(data)) {
            JSONArray choices=o.optJSONArray("choices"); if(choices==null || choices.length()==0) continue;
            JSONObject delta=choices.getJSONObject(0).optJSONObject("delta"); if(delta==null) continue;
            content.append(delta.optString("content","")); reasoning.append(delta.optString("reasoning_content",""));
            JSONArray calls=delta.optJSONArray("tool_calls"); if(calls!=null) for(int i=0;i<calls.length();i++) {
                JSONObject f=calls.getJSONObject(i).optJSONObject("function"); if(f!=null) args.append(f.optString("arguments",""));
            }
        }
        check(content.toString().equals("Creal-phone!"),"fragmented content unmask: "+content);
        check(reasoning.toString().equals("Rreal-email!"),"fragmented reasoning unmask: "+reasoning);
        check(args.toString().equals("{\"name\":\"real-name\"}"),"fragmented tool unmask: "+args);
        check(data.contains("[DONE]") && !data.contains("{{MASK_"),"unmask terminal or placeholder leak");
    }
    static void middleware() throws Exception {
        GatewayServer g=gateway();
        ResponsesGatewayOfflineTest.config(g).put("middleware",new JSONObject()
                .put("thinkTags",new JSONObject().put("enabled",true).put("mode","strip"))
                .put("wordGuard",new JSONObject().put("enabled",true).put("maskWith","***").put("words",new JSONArray().put("secret"))));
        String wire=content("hello <thi")+content("nk>hidden</thi")+content("nk> secret!")
                +event(new JSONObject().put("reasoning_content","reason survives"),JSONObject.NULL)+done();
        InputStream in=stream(g,new Source(wire),new Connection(),null,null); String data=ResponsesGatewayOfflineTest.read(in); in.close();
        StringBuilder result=new StringBuilder(), reason=new StringBuilder();
        for(JSONObject o:jsonEvents(data)) { JSONArray c=o.optJSONArray("choices"); if(c==null || c.length()==0) continue;
            JSONObject delta=c.getJSONObject(0).optJSONObject("delta"); if(delta!=null) { result.append(delta.optString("content","")); reason.append(delta.optString("reasoning_content","")); }
        }
        check(result.toString().equals("hello  ***!"),"think/word middleware content: "+result);
        check(reason.toString().equals("reason survives"),"middleware changed reasoning: "+reason);
        check(data.contains("[DONE]"),"middleware lost terminal");
    }
    static List<String> ledgerLines(Path dir) throws Exception {
        List<String> result=new ArrayList<>(); Path usage=dir.resolve("usage");
        if(Files.isDirectory(usage)) try(DirectoryStream<Path> files=Files.newDirectoryStream(usage,"*.jsonl")) {
            for(Path file:files) result.addAll(Files.readAllLines(file,StandardCharsets.UTF_8));
        }
        return result;
    }
    static void usage(Path dir) throws Exception {
        int before=ledgerLines(dir).size(); UsageLedger.Row row=new UsageLedger.Row(); row.prov="fixture";
        JSONObject stats=new JSONObject().put("prompt_tokens",7).put("completion_tokens",3);
        String terminal="data: "+new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("index",0).put("delta",new JSONObject()).put("finish_reason","stop"))).put("usage",stats)+"\n\n";
        Source source=new Source(content("hello")+terminal+"data: [DONE]\n\n"); Connection conn=new Connection();
        InputStream in=stream(gateway(),source,conn,null,row); String output=ResponsesGatewayOfflineTest.read(in);
        check(output.contains("[DONE]"),"usage fixture terminal missing");
        check(in.read()==-1 && in.read(new byte[8])==-1,"repeated EOF"); in.close();
        List<String> rows=ledgerLines(dir); check(rows.size()==before+1,"normal stream usage must commit exactly once: "+(rows.size()-before));
        JSONObject saved=new JSONObject(rows.get(rows.size()-1));
        check(saved.optInt("st")==200 && saved.optInt("in")==7 && saved.optInt("out")==3,"usage values "+saved);
        check(row.ttft>=0,"usage first-content timing missing");
    }
    static void cancellation() throws Exception {
        Source source=new Source(content("partial")+done()); Connection connection=new Connection();
        InputStream in=stream(gateway(),source,connection,null,null); check(in.read()!=-1,"cancel initial content"); in.close();
        check(source.closes==1 && connection.disconnects==1,"cancel must close upstream and disconnect");
        Source failing=new Source(""); failing.failClose=true; Connection second=new Connection();
        InputStream failed=stream(gateway(),failing,second,null,null);
        throwsType(IOException.class, failed::close);
        check(failing.closes==1 && second.disconnects==1,"disconnect required even when source close throws");
    }
    static void readFailure() throws Exception {
        Connection conn = new Connection();
        InputStream failing = new InputStream() {
            public int read() throws IOException { throw new IOException("fixture read failure"); }
            public int read(byte[] b, int off, int len) throws IOException { throw new IOException("fixture read failure"); }
        };
        InputStream in = stream(gateway(), failing, conn, null, null);
        String body = ResponsesGatewayOfflineTest.read(in);
        check(body.contains("Upstream stream read failed") && !body.contains("[DONE]"), "read failure must produce an error terminal");
        check(in.read() == -1, "read failure must terminate");
        in.close(); check(conn.disconnects == 1, "read failure disconnect");
    }
    // The production ledger lives for the app process. Tests must close it before
    // deleting fixtures, particularly on Windows where open files cannot unlink.
    static void closeLedger() throws Exception {
        Field lock = UsageLedger.class.getDeclaredField("LOCK"), writer = UsageLedger.class.getDeclaredField("writer");
        lock.setAccessible(true); writer.setAccessible(true);
        synchronized (lock.get(null)) {
            Writer current = (Writer) writer.get(null);
            try { if (current != null) current.close(); }
            finally { writer.set(null, null); }
        }
    }
    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory("gateway-stream-read-");
        try {
            UsageLedger.init(dir.toFile());
            contracts(); fragmentedUnmask(); middleware(); usage(dir); cancellation(); readFailure();
            System.out.println("GatewayStreamReadOfflineTest OK");
        } finally {
            closeLedger();
            try(java.util.stream.Stream<Path> paths=Files.walk(dir)) {
                for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(p);
            }
        }
    }
}
