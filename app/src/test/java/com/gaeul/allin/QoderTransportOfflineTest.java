package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/** Entirely synthetic transports: no socket and no credentials. */
public final class QoderTransportOfflineTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static class Input extends InputStream {
        final byte[] data; int pos; boolean closed; final boolean endless;
        Input(String s, boolean endless) { data=s.getBytes(StandardCharsets.UTF_8); this.endless=endless; }
        public int read() { if (pos<data.length) return data[pos++] & 255; return endless ? 'x' : -1; }
        public void close() { closed=true; }
    }
    static final class Fake extends HttpURLConnection {
        final Input input; final int status; boolean disconnected, outputClosed; int writes;
        Fake(int status, Input input) throws Exception { super(new URL("http://127.0.0.1/")); this.status=status; this.input=input; }
        public void connect() {}
        public void disconnect() { disconnected=true; }
        public boolean usingProxy() { return false; }
        public OutputStream getOutputStream() { return new OutputStream() {
            public void write(int b) { writes++; }
            public void close() { outputClosed=true; }
        }; }
        public int getResponseCode() { return status; }
        public InputStream getInputStream() { return input; }
        public InputStream getErrorStream() { return input; }
    }
    static QoderAuth.SignIn signIn() {
        QoderAuth.SignIn si=new QoderAuth.SignIn(); si.uid="fixture"; si.access="fixture"; si.machineId="fixture"; return si;
    }
    static JSONObject body() throws Exception { return QoderAuth.buildChatBody(signIn(),new JSONArray(),null,64,"m",new JSONObject().put("key","m")); }
    static RequestContext context(long duration) { return new RequestContext("qoder",NetworkPolicy.capture(),duration); }
    static void expectFailure(Fake f, RequestContext c, boolean sent) throws Exception {
        try { QoderAuth.chatEvents(signIn(),body(),url->f,c); throw new AssertionError("missing transport failure"); }
        catch (QoderAuth.ChatTransportException e) { check(e.sentOrUnknown==sent,"sent flag"); }
        check(f.disconnected,"connection not disconnected");
        if (sent) check(f.outputClosed && f.input.closed,"streams not closed");
    }
    static void codec() throws Exception {
        Method b64=QoderAuth.class.getDeclaredMethod("b64",byte[].class); b64.setAccessible(true);
        Method url=QoderAuth.class.getDeclaredMethod("b64urlNoPad",byte[].class); url.setAccessible(true);
        Method decode=QoderAuth.class.getDeclaredMethod("decodePemBase64",String.class); decode.setAccessible(true);
        for(int n=0;n<260;n++) {
            byte[] data=new byte[n]; for(int i=0;i<n;i++) data[i]=(byte)(i*131+n);
            check(java.util.Base64.getEncoder().encodeToString(data).equals(b64.invoke(null,(Object)data)),"standard base64 "+n);
            check(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(data).equals(url.invoke(null,(Object)data)),"URL base64 "+n);
            if(n>0) check(Arrays.equals(data,(byte[])decode.invoke(null,b64.invoke(null,(Object)data))),"base64 decode "+n);
        }
        check(Arrays.equals(new byte[]{77},(byte[])decode.invoke(null," \tTQ==\r\n")),"PEM whitespace");
        for(String invalid:new String[]{"", "TQ=", "TQ=A", "TR==", "TWF=", "TQ==AAAA", "TQ==\u000b"}) {
            try { decode.invoke(null,invalid); throw new AssertionError("accepted invalid PEM "+invalid); }
            catch(InvocationTargetException e) { check(e.getCause() instanceof IllegalArgumentException,"invalid PEM failure"); }
        }
    }
    public static void main(String[] args) throws Exception {
        codec();
        RequestContext cancelled=context(5000); cancelled.cancel("fixture"); AtomicInteger opened=new AtomicInteger();
        try { QoderAuth.chatEvents(signIn(),body(),url->{opened.incrementAndGet();return new Fake(200,new Input("",false));},cancelled); throw new AssertionError("cancelled request succeeded"); }
        catch(QoderAuth.ChatTransportException e) { check(!e.sentOrUnknown,"pre-cancel sent"); }
        check(opened.get()==0,"factory invoked after cancellation");
        RequestContext expired=context(1); while(expired.remainingMillis()>0) Thread.yield();
        try { QoderAuth.chatEvents(signIn(),body(),url->{opened.incrementAndGet();return new Fake(200,new Input("",false));},expired); throw new AssertionError("expired request succeeded"); }
        catch(QoderAuth.ChatTransportException e) { check(!e.sentOrUnknown,"deadline sent"); }
        check(opened.get()==0,"factory invoked after deadline");
        Fake eof=new Fake(200,new Input("data: {\"body\":\"{\\\"choices\\\":[{\\\"delta\\\":{\\\"content\\\":\\\"partial\\\"}}]}\"}\n\n",false));
        expectFailure(eof,context(5000),true);
        RequestContext duringRead=context(5000);
        Input interrupted=new Input("",true) {
            @Override public int read() { duringRead.cancel("fixture during read"); return 'x'; }
        };
        Fake active=new Fake(200,interrupted); expectFailure(active,duringRead,true);
        Fake big=new Fake(200,new Input("data: ",true)); expectFailure(big,context(5000),true);
        Fake malformed=new Fake(200,new Input("data: {bad}\n\n",false)); expectFailure(malformed,context(5000),true);
        Fake queued=new Fake(403,new Input("{\"code\":10605}",false));
        JSONArray pieces=QoderAuth.chatEvents(signIn(),body(),url->queued,context(5000));
        check(pieces.getJSONObject(0).getInt("status")==503,"queue status");
        check(queued.input.closed && queued.outputClosed && queued.disconnected,"queue resources");
        check(!queued.getInstanceFollowRedirects() && queued.getRequestMethod().equals("POST"),"redirect or method");
        System.out.println("QoderTransportOfflineTest passed");
    }
}
