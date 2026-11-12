package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Measures bytes read and live allocation leases, not just the intended control-flow branch. */
public final class IngressBudgetRegressionTest {
    private static int checks;
    private static void check(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
    private static Map<String,String> headers(long length){Map<String,String> out=new HashMap<>();out.put("content-length",Long.toString(length));out.put("host","127.0.0.1:3425");return out;}
    private static final class Counting extends InputStream {
        final AtomicLong reads=new AtomicLong();
        public int read(){reads.incrementAndGet();return 'x';}
        public int read(byte[] bytes,int offset,int length){reads.addAndGet(length);Arrays.fill(bytes,offset,offset+length,(byte)'x');return length;}
    }
    private static NanoHTTPD.IHTTPSession request(String path,String method,Counting source) {
        return (NanoHTTPD.IHTTPSession)Proxy.newProxyInstance(IngressBudgetRegressionTest.class.getClassLoader(),new Class[]{NanoHTTPD.IHTTPSession.class},(object,called,args)->{
            switch(called.getName()) {
                case "getUri":return path;
                case "getMethod":return NanoHTTPD.Method.valueOf(method);
                case "getHeaders":return headers(1024*1024);
                case "getParameters":return Collections.emptyMap();
                case "getRemoteIpAddress":return "127.0.0.1";
                case "getInputStream":return source;
                default:return null;
            }
        });
    }
    private static void publicRoutes() throws Exception {
        GatewayServer gateway=RoutingSetupOfflineTest.server(new com.gaeul.allin.json.JSONObject()
                .put("setup",new com.gaeul.allin.json.JSONObject().put("done",true))
                .put("server",new com.gaeul.allin.json.JSONObject().put("authKey","fixture")));
        for(String path:new String[]{"/","/index.html","/dashboard.html","/licenses.html","/favicon.ico","/provider-icons/openai.svg"})
            for(String method:new String[]{"POST","PUT","PATCH","DELETE"}) {
                Counting count=new Counting();NanoHTTPD.Response response=gateway.serve(request(path,method,count));
                try {
                    check(response.getStatus().getRequestStatus()==405,"Static wrong method rejected");
                    check("GET, HEAD".equals(response.getHeader("Allow")),"Static Allow header");
                    check(count.reads.get()==0,"Static unauthorized payload bytesRead=0");
                    check(response.isCloseConnection(),"Unread payload cannot be reused as another request");
                } finally {response.close();}
            }
        for(String path:new String[]{"/api/config","/v1/chat/completions"}) {
            Counting count=new Counting();NanoHTTPD.Response response=gateway.serve(request(path,"POST",count));
            try {check(response.getStatus().getRequestStatus()==401,"Protected endpoint still requires auth");check(count.reads.get()==0,"Auth rejection reads zero bytes");}
            finally {response.close();}
        }
    }
    private static IngressBody.Result body() {
        byte[] bytes="中文".getBytes(StandardCharsets.UTF_8);
        return IngressBody.read(new ByteArrayInputStream(bytes),headers(bytes.length),"POST",65536);
    }
    public static void main(String[] args) throws Exception {
        TestPrivacyScope.initialize();
        check(IngressMemoryBudget.usedBytes()==0,"Initial budget empty");
        IngressBody.Result result=body();
        check(result.ok() && "中文".equals(result.text),"Real UTF-8 body decoded");
        check(IngressMemoryBudget.usedBytes()>=result.bytesRead*6,"Lease survives successful String creation");
        result.close();result.close();check(IngressMemoryBudget.usedBytes()==0,"Idempotent close frees budget");
        result=body();IngressBody.Handoff pending=new IngressBody.Handoff(result);pending.cancelPending();
        check(pending.begin()==null && IngressMemoryBudget.usedBytes()==0,"Cancelled queued worker cannot read released body");
        result=body();IngressBody.Handoff running=new IngressBody.Handoff(result);check(running.begin()!=null,"Worker claims body");
        running.cancelPending();check(IngressMemoryBudget.usedBytes()>0,"Cancellation cannot free a running worker's retained bytes");
        running.finish();running.finish();check(IngressMemoryBudget.usedBytes()==0,"Worker exit frees retained bytes exactly once");
        try(IngressMemoryBudget.Lease all=IngressMemoryBudget.acquire(IngressMemoryBudget.MAX_BYTES)) {
            check(all!=null,"Acquire total byte budget");Counting count=new Counting();
            try(IngressBody.Result rejected=IngressBody.read(count,headers(10),"POST",65536)) {
                check(rejected.status==503 && count.reads.get()==0,"Over-budget request rejected before allocation/read");
            }
        }
        List<IngressMemoryBudget.Lease> readers=new ArrayList<>();
        try {
            for(int i=0;i<4;i++){IngressMemoryBudget.Lease lease=IngressMemoryBudget.acquire(8192);check(lease!=null,"Reader admitted within limit");readers.add(lease);}
            Counting count=new Counting();try(IngressBody.Result rejected=IngressBody.read(count,headers(10),"POST",65536)) {
                check(rejected.status==503 && count.reads.get()==0,"Fifth active reader rejected without consuming payload");
            }
        } finally {for(IngressMemoryBudget.Lease lease:readers)lease.close();}
        long tooLarge=IngressMemoryBudget.MAX_BYTES/6+1;Counting count=new Counting();
        try(IngressBody.Result rejected=IngressBody.read(count,headers(tooLarge),"POST",IngressBody.MAX_LIMIT)) {
            check(rejected.status==413 && count.reads.get()==0,"Device heap allocation budget applied, not just wire size");
        }
        InputStream drip=new InputStream(){public int read(){return 'x';}public int read(byte[] bytes,int offset,int length)throws IOException {
            try{Thread.sleep(10);}catch(InterruptedException stop){Thread.currentThread().interrupt();throw new IOException(stop);}bytes[offset]='x';return 1;
        }};
        try(IngressBody.Result timeout=IngressBody.read(drip,headers(100),"POST",65536,null,25)) {
            check(timeout.status==408 && timeout.bytesRead>0 && timeout.bytesRead<100,"Monotonic body deadline interrupts drip before full body");
        }
        try(IngressBody.Result invalid=IngressBody.read(new ByteArrayInputStream(new byte[]{(byte)0xc3,0x28}),headers(2),"POST",65536)) {
            check(invalid.status==400,"Malformed UTF-8 fails closed");
        }
        publicRoutes();
        check(IngressMemoryBudget.usedBytes()==0,"All success, error, cancellation and static paths restore budget to zero");
        System.out.println("IngressBudgetRegressionTest PASS checks="+checks);
    }
}
