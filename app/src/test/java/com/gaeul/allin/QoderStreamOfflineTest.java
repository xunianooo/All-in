package com.gaeul.allin;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import org.json.*;

/** Synthetic transports; no socket or credentials. */
public final class QoderStreamOfflineTest {
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    static final class Source extends InputStream {
        final byte[] bytes;
        int at, gate = -1;
        boolean closed, released;
        Source(String text) { this(text.getBytes(StandardCharsets.UTF_8)); }
        Source(byte[] bytes) { this.bytes = bytes; }
        @Override public int read() throws IOException {
            if (at == gate && !released) throw new IOException("read beyond first business event");
            return at < bytes.length ? bytes[at++] & 255 : -1;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (at == gate && !released) throw new IOException("read beyond first business event");
            if (at >= bytes.length) return -1;
            int count = Math.min(len, bytes.length - at);
            if (!released && gate >= at) count = Math.min(count, gate - at);
            if (count == 0) throw new IOException("read beyond first business event");
            System.arraycopy(bytes, at, b, off, count); at += count; return count;
        }
        @Override public void close() { closed = true; }
    }

    static final class Fake extends HttpURLConnection {
        final Source source;
        final int status;
        final ByteArrayOutputStream request = new ByteArrayOutputStream();
        String mime = "text/event-stream; charset=utf-8", encoding;
        boolean disconnected, outputCalled, failOutput;
        Fake(int status, Source source) throws Exception {
            super(new URL("http://localhost/")); this.status = status; this.source = source;
        }
        @Override public void connect() {}
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public OutputStream getOutputStream() throws IOException {
            outputCalled = true;
            if (failOutput) throw new IOException("secret upstream detail");
            return request;
        }
        @Override public int getResponseCode() { return status; }
        @Override public InputStream getInputStream() { return source; }
        @Override public InputStream getErrorStream() { return source; }
        @Override public String getContentType() { return mime; }
        @Override public String getContentEncoding() { return encoding; }
    }

    static String envelope(JSONObject chunk) throws Exception {
        return "data: " + new JSONObject().put("statusCodeValue", 200).put("body", chunk.toString()) + "\n\n";
    }
    static String delta(JSONObject delta, String finish) throws Exception {
        return envelope(new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("delta", delta)
                .put("finish_reason", finish == null ? JSONObject.NULL : finish))));
    }
    static String done() throws Exception { return "data: " + new JSONObject().put("body", "[DONE]") + "\n\n"; }
    static JSONObject tool(int index, String id, String name, String args) throws Exception {
        JSONObject fn = new JSONObject();
        if (name != null) fn.put("name", name);
        if (args != null) fn.put("arguments", args);
        JSONObject call = new JSONObject().put("index", index).put("function", fn);
        if (id != null) call.put("id", id);
        return call;
    }
    static String tools(JSONObject... calls) throws Exception {
        JSONArray array = new JSONArray();
        for (JSONObject call : calls) array.put(call);
        return delta(new JSONObject().put("tool_calls", array), null);
    }
    static JSONObject original() throws Exception {
        JSONArray tools = new JSONArray();
        for (String name : new String[]{"one", "two"})
            tools.put(new JSONObject().put("type", "function").put("function", new JSONObject().put("name", name)));
        return new JSONObject().put("tools", tools);
    }
    static QoderStream.Stream open(Fake fake, RequestContext context, QoderStream.Observer observer,
                                   JSONObject request) throws Exception {
        QoderAuth.SignIn si = new QoderAuth.SignIn();
        si.access = "fixture"; si.uid = "fixture"; si.machineId = "fixture";
        return QoderStream.open(si, new JSONObject().put("model_config", new JSONObject().put("key", "m")),
                request, "m", url -> fake, context, observer);
    }
    static QoderStream.Stream open(Fake fake) throws Exception { return open(fake, null, null, original()); }
    static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] bytes = new byte[1024];
        int n;
        while ((n = in.read(bytes)) >= 0) out.write(bytes, 0, n);
        return out.toString("UTF-8");
    }
    static byte[] gzip(String text) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) { gzip.write(text.getBytes(StandardCharsets.UTF_8)); }
        return out.toByteArray();
    }
    static void invalid(String wire, JSONObject original) throws Exception {
        Source source = new Source(wire);
        Fake fake = new Fake(200, source);
        try (QoderStream.Stream stream = open(fake, null, null, original)) {
            try { read(stream); throw new AssertionError("invalid stream accepted"); }
            catch (IOException expected) {
                try { stream.read(); throw new AssertionError("failure was not sticky"); }
                catch (IOException again) { check(expected == again, "failure identity changed"); }
            }
        } catch (QoderStream.Failure expectedDuringPrime) { }
        check(fake.disconnected && source.closed, "invalid stream closure");
    }

    static void streamingAndUsage() throws Exception {
        String first = delta(new JSONObject().put("content", "hello 世界"), null);
        String usage = envelope(new JSONObject().put("choices", new JSONArray()).put("usage",
                new JSONObject().put("prompt_tokens", 12).put("prompt_tokens_details", new JSONObject().put("cached_tokens", 4))));
        Source source = new Source(first + delta(new JSONObject().put("reasoning_content", "thinking"), "length") + usage + done());
        source.gate = first.getBytes(StandardCharsets.UTF_8).length;
        Fake fake = new Fake(200, source);
        final boolean[] sent = {false};
        final JSONObject[] seen = {null};
        QoderStream.Observer observer = new QoderStream.Observer() {
            public void sent() { check(!fake.outputCalled, "sent must precede output stream"); sent[0] = true; }
            public void usage(JSONObject value) { seen[0] = value; }
        };
        try (QoderStream.Stream stream = open(fake, null, observer, original())) {
            byte[] b = new byte[2048]; int n = stream.read(b);
            check(new String(b, 0, n, StandardCharsets.UTF_8).contains("hello 世界"), "early text");
            source.released = true;
            String rest = read(stream);
            check(rest.contains("thinking") && rest.contains("length") && rest.contains("[DONE]"), "terminal output");
            check(seen[0].getInt("prompt_tokens") == 12 && !seen[0].has("completion_tokens"), "usage fabricated fields");
        }
        check(sent[0] && fake.disconnected && source.closed && fake.request.size() > 0, "transport closure");
        Fake eof = new Fake(200, new Source(first + delta(new JSONObject(), "stop")));
        try (QoderStream.Stream stream = open(eof)) { check(read(stream).contains("[DONE]"), "clean EOF after finish"); }
        invalid(first + delta(new JSONObject(), "stop") + "data: {", original());
    }

    static void nativeTools() throws Exception {
        String wire = tools(tool(1, "id-b", null, "{\"b\":"), tool(0, "id-a", "one", "{\"a\":"))
                + tools(tool(0, null, null, "1}"), tool(1, null, "two", "2}"))
                + delta(new JSONObject(), "tool_calls") + done();
        try (QoderStream.Stream stream = open(new Fake(200, new Source(wire)))) {
            JSONObject result = stream.aggregate();
            JSONArray calls = result.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getJSONArray("tool_calls");
            check(calls.length() == 2 && calls.getJSONObject(0).getString("id").equals("id-a")
                    && calls.getJSONObject(1).getString("id").equals("id-b"), "parallel IDs");
        }
        invalid(tools(tool(0, null, "one", "{}")) + delta(new JSONObject(), "tool_calls") + done(), original());
        invalid(tools(tool(0, "x", "one", "{bad:1}")) + delta(new JSONObject(), "tool_calls") + done(), original());
        invalid(tools(tool(0, "x", "unknown", "{}")) + delta(new JSONObject(), "tool_calls") + done(), original());
        invalid(wire, original().put("parallel_tool_calls", false));
        invalid(wire, original().put("tool_choice", "none"));
        invalid(delta(new JSONObject().put("content", "text"), "stop") + done(), original().put("tool_choice", "required"));
        invalid(tools(tool(0, "x", "one", "{}")) + delta(new JSONObject(), "length") + done(), original());
    }

    static void failures() throws Exception {
        String queue = "data: " + new JSONObject().put("statusCodeValue", 403).put("body",
                new JSONObject().put("code", 10605).put("queue", new JSONObject().put("retryAfterSeconds", 7)).toString()) + "\n\n";
        Source source = new Source(queue); Fake fake = new Fake(200, source);
        try { open(fake); throw new AssertionError("queue accepted"); }
        catch (QoderStream.Failure ex) { check(ex.status == 503 && ex.code == 10605 && ex.retryAfter == 7 && ex.sentOrUnknown, "queue mapping"); }
        check(source.closed && fake.disconnected, "prime failure closure");
        fake = new Fake(403, new Source("{\"code\":10605}"));
        try { open(fake); throw new AssertionError("HTTP queue accepted"); }
        catch (QoderStream.Failure ex) { check(ex.status == 503, "HTTP queue mapping"); }
        String first = delta(new JSONObject().put("content", "hello"), null);
        invalid(first + queue, original());
        invalid(first, original());
        fake = new Fake(200, new Source("")); fake.failOutput = true;
        try { open(fake); throw new AssertionError("write failure accepted"); }
        catch (QoderStream.Failure ex) { check(ex.sentOrUnknown && !ex.getMessage().contains("secret"), "unsafe failure"); }
        RequestContext ctx = new RequestContext("qoder", NetworkPolicy.capture(), 5000);
        source = new Source(first + done()); fake = new Fake(200, source);
        try (QoderStream.Stream stream = open(fake, ctx, null, original())) {
            ctx.cancel("test");
            try { stream.read(); throw new AssertionError("cancel accepted"); } catch (IOException expected) { }
        }
        check(fake.disconnected && source.closed, "cancel closure");
    }

    static void contentEncoding() throws Exception {
        String wire = delta(new JSONObject().put("content", "gzip"), "stop") + done();
        Fake fake = new Fake(200, new Source(gzip(wire))); fake.encoding = "gzip";
        try (QoderStream.Stream stream = open(fake)) { check(read(stream).contains("gzip"), "gzip response"); }
        fake = new Fake(200, new Source(wire)); fake.mime = "application/json";
        try { open(fake); throw new AssertionError("invalid MIME accepted"); } catch (QoderStream.Failure expected) { }
        fake = new Fake(200, new Source(wire)); fake.encoding = "br";
        try { open(fake); throw new AssertionError("invalid encoding accepted"); } catch (QoderStream.Failure expected) { }
    }

    static void textTools() throws Exception {
        JSONObject request = original();
        request.getJSONArray("tools").getJSONObject(1).getJSONObject("function").put("parameters",
                new JSONObject().put("properties", new JSONObject().put("count", new JSONObject().put("type", "integer"))
                        .put("label", new JSONObject().put("type", "string"))));
        String first = delta(new JSONObject().put("content", "before <tool_"), null);
        String second = delta(new JSONObject().put("content", "call>{\"name\":\"one\",\"arguments\":{\"x\":1}}</tool_call> after "), null);
        String third = delta(new JSONObject().put("content", "<function=two><parameter=count>2</parameter><parameter=label>3</parameter></function>"), "stop");
        String usage = envelope(new JSONObject().put("usage", new JSONObject().put("completion_tokens", 9)));
        Source source = new Source(first + second + third + usage + done());
        source.gate = first.getBytes(StandardCharsets.UTF_8).length;
        try (QoderStream.Stream stream = open(new Fake(200, source), null, null, request)) {
            byte[] buffer = new byte[2048];
            int n = stream.read(buffer);
            String early = new String(buffer, 0, n, StandardCharsets.UTF_8);
            check(early.contains("before ") && !early.contains("<tool_"), "split marker leaked");
            source.released = true;
            String rest = read(stream);
            check(rest.contains(" after ") && !rest.contains("<function=") && rest.contains("completion_tokens"), "text tool output");
            check(rest.contains("tool_calls") && rest.contains("call_"), "text tools missing");
        }
        String wire = first + second + third + usage + done();
        try (QoderStream.Stream stream = open(new Fake(200, new Source(wire)), null, null, request)) {
            JSONObject result = stream.aggregate();
            JSONObject message = result.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
            JSONArray calls = message.getJSONArray("tool_calls");
            check(calls.length() == 2 && !calls.getJSONObject(0).getString("id").equals(calls.getJSONObject(1).getString("id")), "text call identity");
            JSONObject args = new JSONObject(calls.getJSONObject(1).getJSONObject("function").getString("arguments"));
            check(args.getInt("count") == 2 && args.get("label") instanceof String, "XML parameter types");
            check(message.getString("content").equals("before  after "), "surrounding text");
        }
        String textCall = delta(new JSONObject().put("content", "<tool_call>{\"name\":\"one\",\"arguments\":{}}</tool_call>"), null);
        invalid(textCall + tools(tool(0, "native", "one", "{}")) + delta(new JSONObject(), "tool_calls") + done(), request);
        invalid(tools(tool(0, "native", "one", "{}")) + textCall + delta(new JSONObject(), "tool_calls") + done(), request);
        invalid(delta(new JSONObject().put("content", "<tool_call>{\"name\":\"one\""), "stop") + usage + done(), request);
        invalid(textCall + delta(new JSONObject(), "stop") + done(), new JSONObject());
        request.put("tool_choice", "none");
        try (QoderStream.Stream stream = open(new Fake(200, new Source(textCall + delta(new JSONObject(), "stop") + done())), null, null, request)) {
            JSONObject message = stream.aggregate().getJSONArray("choices").getJSONObject(0).getJSONObject("message");
            check(message.getString("content").contains("<tool_call>") && !message.has("tool_calls"), "none must preserve text");
        }
    }

    static void strictAndDelivery() throws Exception {
        String first = delta(new JSONObject().put("content", "visible"), null);
        String partial = tools(tool(0, "id", "one", "{\"x\":"));
        String bad = "data: {\"statusCodeValue\":200,\"body\":\"{broken}\"}\n\n";
        try (QoderStream.Stream stream = open(new Fake(200, new Source(first + partial + bad)))) {
            byte[] b = new byte[4096]; int n = stream.read(b);
            String emitted = new String(b, 0, n, StandardCharsets.UTF_8);
            check(emitted.contains("visible") && !emitted.contains("tool_calls"), "premature tool dispatch");
            try { read(stream); throw new AssertionError("late error accepted"); }
            catch (IOException expected) {
                try { stream.read(); throw new AssertionError("late error resumed"); }
                catch (IOException again) { check(expected == again, "late error not sticky"); }
            }
        }
        invalid("data: {body:'{}'}\n\n", original());
        invalid("data: {\"body\":\"{choices:[]}\"}\n\n", original());
        invalid(first + envelope(new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("index", 1)))) , original());
        invalid(first + envelope(new JSONObject().put("choices", new JSONArray().put(new JSONObject()).put(new JSONObject()))), original());
        invalid(first + envelope(new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("finish_reason", true)))), original());
    }

    static void cumulativeUsageAndFinishValidation() throws Exception {
        JSONObject firstUsage = new JSONObject().put("prompt_tokens", 12).put("details",
                new JSONObject().put("cached_tokens", 4));
        JSONObject nextUsage = new JSONObject().put("completion_tokens", 7).put("details",
                new JSONObject().put("reasoning_tokens", 3));
        String wire = delta(new JSONObject().put("content", "hi"), null)
                + envelope(new JSONObject().put("usage", firstUsage))
                + delta(new JSONObject(), "stop")
                + envelope(new JSONObject().put("usage", nextUsage)) + done();
        final JSONObject[] observed = {null};
        try (QoderStream.Stream stream = open(new Fake(200, new Source(wire)), null, u -> observed[0] = u, original())) {
            JSONObject result = stream.aggregate();
            JSONObject usage = result.getJSONObject("usage"), details = usage.getJSONObject("details");
            check(usage.getInt("prompt_tokens") == 12 && usage.getInt("completion_tokens") == 7,
                    "partial usage merge");
            check(details.getInt("cached_tokens") == 4 && details.getInt("reasoning_tokens") == 3,
                    "nested usage merge");
            check(!usage.has("total_tokens") && observed[0].getInt("prompt_tokens") == 12,
                    "invented total or observer missed input");
        }
        String invalid = delta(new JSONObject().put("content", "visible"), null)
                + tools(tool(0, "native", "one", "{}")) + delta(new JSONObject(), "unrecognized") + done();
        try (QoderStream.Stream stream = open(new Fake(200, new Source(invalid)))) {
            ByteArrayOutputStream delivered = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            try {
                int n;
                while ((n = stream.read(buffer)) >= 0) delivered.write(buffer, 0, n);
                throw new AssertionError("invalid finish accepted");
            } catch (IOException expected) {
                String output = delivered.toString("UTF-8");
                check(output.contains("visible") && !output.contains("tool_calls") && !output.contains("[DONE]"),
                        "invalid finish emitted tool or success");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        streamingAndUsage(); nativeTools(); failures(); contentEncoding(); textTools(); strictAndDelivery(); cumulativeUsageAndFinishValidation();
        System.out.println("QoderStreamOfflineTest OK");
    }
}
