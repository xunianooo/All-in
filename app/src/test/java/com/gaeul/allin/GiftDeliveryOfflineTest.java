package com.gaeul.allin;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import fi.iki.elonen.*;
import org.json.*;

/** Final HTTP framing, early close and usage-event ordering on the gift path. */
public final class GiftDeliveryOfflineTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void delivery(boolean writeFailure, List<UsageLedger.Row> rows) throws Exception {
        UsageLedger.Row row = new UsageLedger.Row(); row.requestId = "fixture-" + UUID.randomUUID(); row.prov = "zcode"; row.ep = "gift";
        String body = GatewayStreamReadOfflineTest.content("hello") + GatewayStreamReadOfflineTest.done();
        InputStream data = GatewayStreamReadOfflineTest.stream(ResponsesGatewayOfflineTest.gateway(1),
                new GatewayStreamReadOfflineTest.Source(body), new GatewayStreamReadOfflineTest.Connection(), null, row);
        String read = ResponsesGatewayOfflineTest.read(data);
        check(read.contains("[DONE]") && !rows.contains(row), "success waits for final HTTP write confirmation");
        AtomicInteger released = new AtomicInteger();
        NanoHTTPD.Response source = NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "text/event-stream", data);
        AllInTrackedResponse tracked = new AllInTrackedResponse(source, released::incrementAndGet);
        tracked.setRequestMethod(NanoHTTPD.Method.GET);
        Method send = AllInTrackedResponse.class.getDeclaredMethod("send", OutputStream.class); send.setAccessible(true);
        OutputStream sink = new ByteArrayOutputStream() {
            @Override public void flush() throws IOException { if (writeFailure) throw new IOException("injected final flush"); }
        };
        send.invoke(tracked, sink); tracked.close(); tracked.close();
        check(rows.contains(row) && Collections.frequency(rows, row) == 1, "delivery result recorded once");
        check(row.status == (writeFailure ? 502 : 200) && released.get() == 1, "HTTP write result and request release");
    }
    static void cacheOrder() throws Exception {
        Method absorb = GatewayServer.class.getDeclaredMethod("absorbUsage", UsageLedger.Row.class, JSONObject.class); absorb.setAccessible(true);
        for (boolean cacheFirst : new boolean[]{false, true}) {
            UsageLedger.Row row = new UsageLedger.Row(); row.requestId = "ordered";
            JSONObject cache = new JSONObject().put("cache_read_input_tokens", 3).put("cache_creation_input_tokens", 4);
            JSONObject input = new JSONObject().put("input_tokens", 11);
            absorb.invoke(null, row, cacheFirst ? cache : input); absorb.invoke(null, row, cacheFirst ? input : cache);
            absorb.invoke(null, row, new JSONObject().put("output_tokens", 7));
            check(row.cacheInputTokens == 18 && row.cacheRead == 3 && row.cacheWrite == 4, "cache semantics survive independent event order");
        }
    }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("gift-delivery-"); List<UsageLedger.Row> rows = new ArrayList<>();
        try {
            UsageLedger.init(dir.toFile()); UsageLedger.setHooks(rows::add);
            delivery(false, rows); delivery(true, rows); cacheOrder();
            System.out.println("GiftDeliveryOfflineTest OK");
        } finally {
            UsageLedger.setHooks(null); GatewayStreamReadOfflineTest.closeLedger();
            try (java.util.stream.Stream<Path> files = Files.walk(dir)) {
                for (Path file : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(file);
            }
        }
    }
}
