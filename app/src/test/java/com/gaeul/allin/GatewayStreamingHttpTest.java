package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Real loopback HTTP pacing, measured at complete SSE events, never at TCP packet boundaries. */
public final class GatewayStreamingHttpTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static final String FIRST = "{\"x\":";
    static final String REST = "1}";
    static String event(JSONObject o) { return "data: " + o.toString() + "\n\n"; }
    static JSONObject chat(JSONObject delta, Object finish) {
        return new JSONObject().put("id", "c1").put("model", "m").put("choices", new JSONArray().put(
                new JSONObject().put("index", 0).put("delta", delta).put("finish_reason", finish)));
    }
    static String chatDelta(JSONObject delta) { return event(chat(delta, JSONObject.NULL)); }
    static JSONObject call(String part, boolean first) {
        JSONObject fn = new JSONObject().put("arguments", part);
        JSONObject item = new JSONObject().put("index", 0).put("function", fn);
        if (first) { fn.put("name", "lookup"); item.put("id", "call1").put("type", "function"); }
        return new JSONObject().put("tool_calls", new JSONArray().put(item));
    }
    static String responses(String type, JSONObject fields) { return event(fields.put("type", type)); }

    /** Source with normal InputStream bulk semantics: return currently queued bytes immediately. */
    static final class Fragments extends InputStream {
        final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        byte[] current; int pos; boolean ended;
        void put(String text) { queue.add(text.getBytes(StandardCharsets.UTF_8)); }
        void finish() { queue.add(new byte[0]); }
        @Override public int read() throws IOException { byte[] b = new byte[1]; return read(b, 0, 1) < 0 ? -1 : b[0] & 255; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (ended) return -1;
            if (current == null || pos == current.length) {
                try { current = queue.poll(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                if (current == null) throw new IOException("fragment timeout");
                pos = 0;
                if (current.length == 0) { ended = true; return -1; }
            }
            int count = Math.min(len, current.length - pos);
            System.arraycopy(current, pos, b, off, count); pos += count; return count;
        }
    }

    static final class Upstream implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final Fragments source = new Fragments();
        final CountDownLatch gate = new CountDownLatch(1);
        final Thread producer, serverThread;
        final boolean nativeResponses, gated;
        volatile Throwable error; volatile long firstSent, lastSent; volatile String path;
        Upstream(boolean nativeResponses, boolean gated) throws Exception { this(nativeResponses, gated, false); }
        Upstream(boolean nativeResponses, boolean gated, boolean truncated) throws Exception {
            this.nativeResponses = nativeResponses; this.gated = gated;
            server.setSoTimeout(10000);
            producer = new Thread(() -> {
                try {
                    if (nativeResponses) source.put(responses("response.created", new JSONObject().put("response", new JSONObject().put("id", "r1").put("status", "in_progress"))));
                    if (!gated) {
                        source.put(nativeResponses ? responses("response.reasoning_summary_text.delta", new JSONObject().put("item_id", "reason1").put("output_index", 0).put("delta", "thought")) : chatDelta(new JSONObject().put("reasoning_content", "thought")));
                        for (int i = 0; i < 20; i++) {
                            if (i > 0) Thread.sleep(50);
                            String text = "word" + i + ";";
                            source.put(nativeResponses ? responses("response.output_text.delta", new JSONObject().put("item_id", "msg1").put("output_index", 1).put("content_index", 0).put("delta", text)) : chatDelta(new JSONObject().put("content", text)));
                            // Actual wire timestamps are recorded by the server writer below.
                        }
                    } else if (nativeResponses) {
                        source.put(responses("response.output_item.added", new JSONObject().put("output_index", 0).put("item", new JSONObject().put("id", "fc1").put("type", "function_call").put("call_id", "call1").put("name", "lookup").put("arguments", ""))));
                        source.put(responses("response.function_call_arguments.delta", new JSONObject().put("item_id", "fc1").put("output_index", 0).put("delta", FIRST)));
                    } else source.put(chatDelta(call(FIRST, true)));
                    if (gated) {
                        if (!gate.await(5, TimeUnit.SECONDS)) throw new IOException("client did not observe first tool argument before gate timeout");
                        if (nativeResponses) {
                            source.put(responses("response.function_call_arguments.delta", new JSONObject().put("item_id", "fc1").put("output_index", 0).put("delta", REST)));
                            source.put(responses("response.function_call_arguments.done", new JSONObject().put("item_id", "fc1").put("output_index", 0).put("arguments", FIRST + REST)));
                            source.put(responses("response.output_item.done", new JSONObject().put("output_index", 0).put("item", new JSONObject().put("id", "fc1").put("type", "function_call").put("call_id", "call1").put("name", "lookup").put("arguments", FIRST + REST).put("status", "completed"))));
                        } else source.put(chatDelta(call(REST, false)));
                    }
                    if (!truncated) {
                        if (nativeResponses) source.put(responses("response.completed", new JSONObject().put("response", new JSONObject().put("id", "r1").put("status", "completed").put("output", new JSONArray()))));
                        else { source.put(event(chat(new JSONObject(), gated ? "tool_calls" : "stop"))); source.put("data: [DONE]\n\n"); }
                    }
                } catch (Throwable e) { error = e; }
                finally { source.finish(); }
            }, "stream-producer");
            serverThread = new Thread(() -> {
                try (Socket s = server.accept()) {
                    s.setSoTimeout(10000); InputStream in = s.getInputStream();
                    String line = readLine(in); path = line.split(" ")[1]; int length = 0;
                    while (!(line = readLine(in)).isEmpty()) if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                    while (length-- > 0) if (in.read() < 0) throw new EOFException();
                    OutputStream out = s.getOutputStream();
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); out.flush();
                    producer.start(); byte[] bytes = new byte[8192]; int n;
                    while ((n = source.read(bytes, 0, bytes.length)) != -1) {
                        out.write(bytes, 0, n); out.flush();
                        String text = new String(bytes, 0, n, StandardCharsets.UTF_8);
                        if (text.contains("word0;")) firstSent = System.nanoTime();
                        if (text.contains("word19;")) lastSent = System.nanoTime();
                    }
                } catch (Throwable e) { error = e; }
            }, "stream-upstream");
            serverThread.start();
        }
        public void close() throws Exception {
            gate.countDown(); server.close(); serverThread.join(8000);
            if (producer.getState() != Thread.State.NEW) producer.join(8000);
            check(!serverThread.isAlive() && !producer.isAlive(), "upstream threads did not stop");
            if (error != null) throw new AssertionError("upstream failure", error);
        }
    }
    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); int c;
        while ((c = in.read()) != -1 && c != '\n') if (c != '\r') b.write(c);
        if (c == -1 && b.size() == 0) throw new EOFException();
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }
    static final class Chunked extends InputStream {
        final InputStream in; int remaining; boolean end;
        Chunked(InputStream in) { this.in = in; }
        public int read() throws IOException {
            if (end) return -1;
            if (remaining == 0) {
                String line = readLine(in); int semi = line.indexOf(';');
                remaining = Integer.parseInt((semi < 0 ? line : line.substring(0, semi)).trim(), 16);
                if (remaining == 0) { end = true; return -1; }
            }
            int c = in.read(); if (c < 0) throw new EOFException("truncated HTTP chunk");
            if (--remaining == 0 && !readLine(in).isEmpty()) throw new IOException("invalid chunk delimiter");
            return c;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0; int c = read(); if (c < 0) return -1; b[off] = (byte)c; return 1;
        }
    }
    static final class Fixture extends NanoHTTPD implements AutoCloseable {
        final GatewayServer gateway;
        Fixture(int upstream, String protocol) throws Exception {
            super("127.0.0.1", 0); gateway = ResponsesGatewayOfflineTest.gateway(upstream, protocol);
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
            Field socket = NanoHTTPD.class.getDeclaredField("myServerSocket"); socket.setAccessible(true); socket.set(gateway, socket.get(this));
        }
        @Override public Response serve(IHTTPSession session) { return gateway.serve(session); }
        public void close() { stop(); }
    }
    static final class Client implements AutoCloseable {
        final Socket socket; final BufferedReader reader;
        Client(int port, String uri) throws Exception {
            socket = new Socket("127.0.0.1", port); socket.setSoTimeout(7000);
            String payload = uri.endsWith("/responses") ? "{\"model\":\"mock/m\",\"input\":\"hi\",\"stream\":true}" :
                    uri.endsWith("/messages") ? "{\"model\":\"mock/m\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":true}" :
                    "{\"model\":\"mock/m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":true}";
            byte[] body = payload.getBytes(StandardCharsets.UTF_8); OutputStream out = socket.getOutputStream();
            out.write(("POST " + uri + " HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer secret\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.write(body); out.flush();
            InputStream in = socket.getInputStream(); String status = readLine(in); check(status.contains(" 200 "), "HTTP status " + status);
            boolean chunked = false; String line;
            while (!(line = readLine(in)).isEmpty()) if (line.toLowerCase(Locale.ROOT).startsWith("transfer-encoding:") && line.toLowerCase(Locale.ROOT).contains("chunked")) chunked = true;
            reader = new BufferedReader(new InputStreamReader(chunked ? new Chunked(in) : in, StandardCharsets.UTF_8));
        }
        String next() throws Exception {
            String line; StringBuilder data = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() && data.length() > 0) return data.toString();
                if (line.startsWith("data:")) { if (data.length() > 0) data.append('\n'); data.append(line.substring(5).trim()); }
            }
            return data.length() == 0 ? null : data.toString();
        }
        public void close() throws Exception { socket.close(); }
    }
    static String delta(String uri, JSONObject o) {
        if (uri.endsWith("/responses")) return "response.output_text.delta".equals(o.optString("type")) ? o.optString("delta") : "";
        if (uri.endsWith("/messages")) { JSONObject d = o.optJSONObject("delta"); return d != null && "text_delta".equals(d.optString("type")) ? d.optString("text") : ""; }
        JSONArray choices = o.optJSONArray("choices"); if (choices == null || choices.length() == 0) return "";
        JSONObject d = choices.getJSONObject(0).optJSONObject("delta"); return d == null ? "" : d.optString("content");
    }
    static void paced(String label, String uri, boolean nativeResponses) throws Exception {
        try (Upstream upstream = new Upstream(nativeResponses, false); Fixture fixture = new Fixture(upstream.server.getLocalPort(), nativeResponses ? "responses" : "openai"); Client client = new Client(fixture.getListeningPort(), uri)) {
            StringBuilder content = new StringBuilder(), all = new StringBuilder(); List<Long> times = new ArrayList<>(); String data;
            while ((data = client.next()) != null) {
                all.append(data).append('\n'); if (data.equals("[DONE]")) continue;
                JSONObject o = new JSONObject(data); String d = delta(uri, o);
                if (!d.isEmpty()) { content.append(d); times.add(System.nanoTime()); }
            }
            StringBuilder expected = new StringBuilder(); for (int i=0;i<20;i++) expected.append("word").append(i).append(';');
            check(content.toString().equals(expected.toString()), label + " content " + content);
            check(times.size() >= 4, label + " expected multiple SSE content events: " + times.size());
            check(upstream.firstSent != 0 && upstream.lastSent > upstream.firstSent, label + " missing upstream timestamps");
            check(times.get(0) - upstream.firstSent < TimeUnit.MILLISECONDS.toNanos(700), label + " first content delayed");
            check(times.get(0) < upstream.lastSent, label + " first content arrived after upstream finished");
            check(times.get(times.size()-1) - times.get(0) >= TimeUnit.MILLISECONDS.toNanos(400), label + " output was batched");
            long beforeLast = times.stream().filter(t -> t < upstream.lastSent).count();
            check(beforeLast >= 3, label + " insufficient content before upstream last event: " + beforeLast);
            int gaps = 0;
            for (int i = 1; i < times.size(); i++)
                if (times.get(i) - times.get(i-1) >= TimeUnit.MILLISECONDS.toNanos(20)) gaps++;
            check(gaps >= 4, label + " insufficient paced content intervals: " + gaps);
            check(all.toString().contains("thought"), label + " lost reasoning");
            String terminal = uri.endsWith("/responses") ? "response.completed" : uri.endsWith("/messages") ? "message_stop" : "[DONE]";
            check(all.toString().contains(terminal) && !all.toString().contains("response.failed"), label + " terminal " + all);
            check(upstream.path.endsWith(nativeResponses ? "/responses" : "/chat/completions"), label + " upstream path " + upstream.path);
            long lag = TimeUnit.NANOSECONDS.toMillis(times.get(0) - upstream.firstSent);
            long span = TimeUnit.NANOSECONDS.toMillis(times.get(times.size()-1) - times.get(0));
            System.out.println(label + " pacing OK (" + times.size() + " content events, first lag=" + lag + "ms, span=" + span + "ms)");
        }
    }
    static void gated(String label, String uri, boolean nativeResponses) throws Exception {
        try (Upstream upstream = new Upstream(nativeResponses, true); Fixture fixture = new Fixture(upstream.server.getLocalPort(), nativeResponses ? "responses" : "openai")) {
            // Messages intentionally defers tool blocks until fragmented identities
            // are complete. Preserve that compatibility; only text pacing and final
            // tool integrity are promised for this adapter. Chat/Responses must
            // expose the first argument while the upstream still holds the rest.
            boolean early = !uri.endsWith("/messages");
            if (!early) upstream.gate.countDown();
            try (Client client = new Client(fixture.getListeningPort(), uri)) {
            StringBuilder arguments = new StringBuilder(), all = new StringBuilder(); boolean opened = false; String data;
            while ((data = client.next()) != null) {
                all.append(data).append('\n'); if (data.equals("[DONE]")) continue;
                JSONObject o = new JSONObject(data); String part = "";
                if (uri.endsWith("/responses") && "response.function_call_arguments.delta".equals(o.optString("type"))) part = o.optString("delta");
                else if (uri.endsWith("/messages")) { JSONObject d = o.optJSONObject("delta"); if (d != null && "input_json_delta".equals(d.optString("type"))) part = d.optString("partial_json"); }
                else if (uri.endsWith("/completions")) {
                    JSONArray c = o.optJSONArray("choices"); JSONObject d = c == null || c.length()==0 ? null : c.getJSONObject(0).optJSONObject("delta");
                    JSONArray tools = d == null ? null : d.optJSONArray("tool_calls");
                    if (tools != null && tools.length()>0) { JSONObject fn = tools.getJSONObject(0).optJSONObject("function"); if (fn != null) part = fn.optString("arguments"); }
                }
                if (!part.isEmpty()) { arguments.append(part); if (!opened && arguments.toString().contains(FIRST)) { opened = true; upstream.gate.countDown(); } }
            }
            check(opened, label + " first tool arguments did not reach client before gate");
            check(arguments.toString().equals(FIRST + REST), label + " tool arguments " + arguments);
            check(all.toString().contains("lookup") && all.toString().contains("call1"), label + " tool identity missing");
            String terminal = uri.endsWith("/responses") ? "response.completed" : uri.endsWith("/messages") ? "message_stop" : "[DONE]";
            check(all.toString().contains(terminal) && !all.toString().contains("response.failed"), label + " terminal missing");
            System.out.println(label + (early ? " gated tool OK" : " deferred tool integrity OK"));
            }
        }
    }
    static void truncated(String label, String uri, boolean nativeResponses) throws Exception {
        try (Upstream upstream = new Upstream(nativeResponses, false, true); Fixture fixture = new Fixture(upstream.server.getLocalPort(), nativeResponses ? "responses" : "openai"); Client client = new Client(fixture.getListeningPort(), uri)) {
            StringBuilder all = new StringBuilder(); String data;
            while ((data = client.next()) != null) all.append(data).append('\n');
            String body = all.toString();
            check(body.contains("word0;") && !body.contains("response.completed"), label + " truncated output " + body);
            check(body.contains(uri.endsWith("/responses") ? "response.failed" : "error"), label + " missing error terminal " + body);
            System.out.println(label + " truncated stream OK");
        }
    }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("gateway-streaming-http-");
        try {
            UsageLedger.init(dir.toFile());
            Files.write(dir.resolve("config.json"), "{\"server\":{\"authKey\":\"secret\"}}".getBytes(StandardCharsets.UTF_8));
            GatewayAuthKeyHolder.init(dir.toFile());
            String chat = "/v1/chat/completions", messages = "/v1/messages", responses = "/v1/responses";
            paced("Chat native from Chat", chat, false);
            paced("Messages converted from Chat", messages, false);
            paced("Responses converted from Chat", responses, false);
            paced("Responses native", responses, true);
            paced("Chat converted from Responses", chat, true);
            gated("Chat native from Chat", chat, false);
            gated("Messages converted from Chat", messages, false);
            gated("Responses converted from Chat", responses, false);
            gated("Responses native", responses, true);
            gated("Chat converted from Responses", chat, true);
            truncated("Chat native from Chat", chat, false);
            truncated("Responses converted from Chat", responses, false);
            truncated("Responses native", responses, true);
        } finally {
            GatewayStreamReadOfflineTest.closeLedger();
            try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
                for (Path p : (Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(p);
            }
        }
    }
}
