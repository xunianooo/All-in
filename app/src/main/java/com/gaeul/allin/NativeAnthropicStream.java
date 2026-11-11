package com.gaeul.allin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Passes native Anthropic SSE through, changing only explicitly unmasked text. */
final class NativeAnthropicStream extends InputStream implements fi.iki.elonen.AllInTrackedResponse.WriteCompletion {
    interface Listener {
        void onEvent(JSONObject event);
        void onComplete(boolean success);
    }
    private static final int MAX_FRAME = 4 * 1024 * 1024;
    private static final byte[] ERROR = ("event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"upstream_error\",\"message\":\"Invalid or incomplete upstream stream\"}}\n\n").getBytes(StandardCharsets.UTF_8);
    private final InputStream upstream;
    private final PrivacyMasker.Session session;
    private final Listener listener;
    private final Runnable closeAction;
    private final boolean thinkingOnlyTruncationCompat;
    private boolean hasThinking, hasText, hasToolOrRefusal;
    private final Map<Integer, Block> blocks = new HashMap<>();
    private final Set<Integer> used = new HashSet<>();
    private byte[] output = new byte[0];
    private int position;
    private volatile boolean closed;
    private boolean finished, completed, started, stopReason;
    private boolean waitForDelivery, deliveryConfirmed, cleanupSucceeded;
    private final byte[] inputBuffer = new byte[8192];
    private int inputPosition, inputLimit;
    private int pushed = -1;

    private static final class Block {
        final String type;
        final GatewayServer.StreamUnmask unmask;
        Block(String type, PrivacyMasker.Session session) {
            this.type = type;
            this.unmask = new GatewayServer.StreamUnmask(session);
        }
    }
    NativeAnthropicStream(InputStream upstream, PrivacyMasker.Session session, Listener listener, Runnable closeAction) {
        this(upstream, session, listener, closeAction, false);
    }
    NativeAnthropicStream(InputStream upstream, PrivacyMasker.Session session, Listener listener, Runnable closeAction, boolean thinkingOnlyTruncationCompat) {
        if (upstream == null) throw new NullPointerException("upstream");
        this.thinkingOnlyTruncationCompat = thinkingOnlyTruncationCompat;
        this.upstream = upstream; this.session = session; this.listener = listener; this.closeAction = closeAction;
    }
    private boolean masking() { return session != null && session.placeholderToReal != null && !session.placeholderToReal.isEmpty(); }
    void requireDeliveryConfirmation() { synchronized (this) { if (completed) throw new IllegalStateException("stream already completed"); waitForDelivery = true; } }
    @Override public synchronized void onWriteComplete(boolean success) {
        if (!success || (closed && !cleanupSucceeded)) { complete(false); return; }
        deliveryConfirmed = true;
        if (finished && stopReason && position >= output.length) complete(true);
    }
    private synchronized void complete(boolean success) {
        if (completed || (success && waitForDelivery && !deliveryConfirmed)) return;
        completed = true;
        if (listener != null) try { listener.onComplete(success); } catch (RuntimeException ignored) { }
    }
    @Override public void close() throws IOException {
        synchronized (this) { if (closed) return; closed = true; }
        boolean success = false;
        try {
            try { upstream.close(); }
            finally { if (closeAction != null) closeAction.run(); }
            cleanupSucceeded = true;
            success = finished && position >= output.length && stopReason;
        } finally {
            // NanoHTTPD closes a successfully read body inside send(), before the
            // outer response can confirm its final HTTP flush. Keep that result pending.
            if (!(cleanupSucceeded && success && waitForDelivery && !deliveryConfirmed)) complete(success);
        }
    }
    private void fail() { output = ERROR; position = 0; finished = true; stopReason = false; complete(false); }
    private int nextByte() throws IOException {
        if (pushed >= 0) { int value = pushed; pushed = -1; return value; }
        if (inputPosition == inputLimit) {
            inputLimit = upstream.read(inputBuffer, 0, inputBuffer.length);
            inputPosition = 0;
            if (inputLimit < 0) return -1;
            if (inputLimit == 0) throw new IOException("empty upstream read");
        }
        return inputBuffer[inputPosition++] & 255;
    }
    private byte[] frame() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int lineLength = 0;
        while (true) {
            int ch = nextByte();
            if (ch < 0) { if (b.size() == 0) return null; throw new IOException("incomplete frame"); }
            b.write(ch);
            if (b.size() > MAX_FRAME) throw new IOException("frame too large");
            if (ch == '\r' || ch == '\n') {
                // A bare CR may wait for the next byte to distinguish CRLF.
                if (ch == '\r') {
                    int next = nextByte();
                    if (next == '\n') {
                        b.write(next);
                        if (b.size() > MAX_FRAME) throw new IOException("frame too large");
                    } else pushed = next;
                }
                if (lineLength == 0) return b.toByteArray();
                lineLength = 0;
            } else lineLength++;
        }
    }
    private static Object unmaskInput(Object value, PrivacyMasker.Session session) throws Exception {
        if (value instanceof String) return PrivacyMasker.unmaskText((String)value, session);
        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject)value;
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) { String key = keys.next(); obj.put(key, unmaskInput(obj.get(key), session)); }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray)value;
            for (int i = 0; i < array.length(); i++) array.put(i, unmaskInput(array.get(i), session));
        }
        return value;
    }
    private static int index(JSONObject obj) throws Exception {
        Object v = obj.get("index");
        if (!(v instanceof Number) || ((Number)v).longValue() < 0 || ((Number)v).longValue() > Integer.MAX_VALUE || ((Number)v).doubleValue() != ((Number)v).intValue()) throw new Exception();
        return ((Number)v).intValue();
    }
    private static String string(JSONObject obj, String key) throws Exception {
        Object v = obj.get(key); if (!(v instanceof String)) throw new Exception(); return (String)v;
    }
    private static boolean known(String t) {
        return "message_start".equals(t) || "message_delta".equals(t) || "message_stop".equals(t) || "content_block_start".equals(t) || "content_block_delta".equals(t) || "content_block_stop".equals(t) || "ping".equals(t) || "error".equals(t);
    }
    private static byte[] eventBytes(String event, JSONObject json) { return ("event: " + event + "\ndata: " + json.toString() + "\n\n").getBytes(StandardCharsets.UTF_8); }
    private byte[] process(byte[] raw) throws Exception {
        String text = new String(raw, StandardCharsets.UTF_8);
        String[] lines = text.split("\\r\\n|\\r|\\n", -1);
        String event = ""; StringBuilder data = new StringBuilder(); boolean hasData = false;
        for (String line : lines) {
            if (line.isEmpty() || line.charAt(0) == ':') continue;
            int p = line.indexOf(':'); String key = p < 0 ? line : line.substring(0,p);
            String value = p < 0 ? "" : line.substring(p+1); if (value.startsWith(" ")) value = value.substring(1);
            if ("event".equals(key)) event = value;
            else if ("data".equals(key)) { if (hasData) data.append('\n'); data.append(value); hasData = true; }
        }
        if (!hasData) return raw;
        JSONObject obj = null;
        try { obj = new JSONObject(data.toString()); } catch (Exception ignored) { }
        String type = obj == null ? "" : obj.optString("type", "");
        if (known(event) && (!event.equals(type) || obj == null)) throw new Exception();
        if (!known(type)) {
            hasToolOrRefusal = true;
            // Unknown data events cannot cross withheld placeholder fragments: their
            // ordering relative to that text is unknowable until the fragment resolves.
            for (Block block : blocks.values()) if (block.unmask.hasPending()) throw new Exception();
            return raw;
        }
        if (!event.isEmpty() && !event.equals(type)) throw new Exception();
        boolean changed = false;
        byte[] prefix = null;
        if ("error".equals(type)) {
            finished = true; stopReason = false; complete(false);
        } else if ("ping".equals(type)) {
            // Ping may occur at any point.
        } else if ("message_start".equals(type)) {
            if (started) throw new Exception();
            JSONObject message = obj.getJSONObject("message");
            JSONArray content = message.optJSONArray("content");
            if (message.has("content") && content == null) throw new Exception();
            if (content != null && content.length() != 0) throw new Exception();
            started = true;
        } else {
            if (!started) throw new Exception();
            if ("content_block_start".equals(type)) {
                if (stopReason) throw new Exception();
                int i = index(obj); if (used.size() >= 65536 || !used.add(i)) throw new Exception();
                JSONObject block = obj.getJSONObject("content_block"); String kind = string(block, "type");
                Block state = new Block(kind, session); blocks.put(i, state);
                if ("thinking".equals(kind)) hasThinking |= !block.optString("thinking", "").isEmpty();
                else if ("text".equals(kind)) hasText |= !block.optString("text", "").isEmpty();
                else hasToolOrRefusal = true;
                if ("tool_use".equals(kind)) {
                    JSONObject input = block.getJSONObject("input");
                    if (masking()) { String before = input.toString(); unmaskInput(input, session); changed = !before.equals(input.toString()); }
                }
                if ("text".equals(kind)) {
                    String before = string(block, "text");
                    if (masking()) { String after = state.unmask.feed(before); if (!before.equals(after)) { block.put("text", after); changed = true; } }
                }
            } else if ("content_block_delta".equals(type)) {
                int i = index(obj); Block state = blocks.get(i); if (state == null) throw new Exception();
                JSONObject delta = obj.getJSONObject("delta"); String kind = string(delta, "type");
                String field = null;
                if ("text_delta".equals(kind)) { if (!"text".equals(state.type)) throw new Exception(); field = "text"; }
                if ("input_json_delta".equals(kind)) { if (!"tool_use".equals(state.type)) throw new Exception(); field = "partial_json"; }
                if ("thinking_delta".equals(kind) || "signature_delta".equals(kind)) {
                    if (!"thinking".equals(state.type)) throw new Exception();
                    String value = string(delta, "thinking_delta".equals(kind) ? "thinking" : "signature");
                    if ("thinking_delta".equals(kind) && !value.isEmpty()) hasThinking = true;
                }
                if (!"text_delta".equals(kind) && !"thinking_delta".equals(kind)
                        && !"signature_delta".equals(kind)) hasToolOrRefusal = true;
                if (field != null) {
                    String before = string(delta, field);
                    if ("text_delta".equals(kind) && !before.isEmpty()) hasText = true;
                    if (masking()) { String after = state.unmask.feed(before); if (!before.equals(after)) { delta.put(field, after); changed = true; } }
                }
            } else if ("content_block_stop".equals(type)) {
                int i = index(obj); Block state = blocks.remove(i); if (state == null) throw new Exception();
                String tail = state.unmask.flush();
                if (!tail.isEmpty()) {
                    JSONObject d = new JSONObject();
                    if ("text".equals(state.type)) { d.put("type", "text_delta"); d.put("text", tail); }
                    else if ("tool_use".equals(state.type)) { d.put("type", "input_json_delta"); d.put("partial_json", tail); }
                    else throw new Exception();
                    JSONObject e = new JSONObject(); e.put("type", "content_block_delta"); e.put("index", i); e.put("delta", d);
                    prefix = eventBytes("content_block_delta", e);
                }
            } else if ("message_delta".equals(type)) {
                JSONObject delta = obj.getJSONObject("delta");
                if (delta.has("stop_reason") && !delta.isNull("stop_reason")) {
                    ZCodeAnthropic.validateStopReason(delta);
                    if (!blocks.isEmpty() || stopReason) throw new Exception();
                    if (thinkingOnlyTruncationCompat && "max_tokens".equals(delta.optString("stop_reason"))
                            && hasThinking && !hasText && !hasToolOrRefusal && used.size() < 65536) {
                        int i = 0; while (used.contains(i)) i++;
                        JSONObject block = new JSONObject().put("type", "text").put("text", ThinkingTruncationStream.NOTICE);
                        JSONObject start = new JSONObject().put("type", "content_block_start").put("index", i).put("content_block", block);
                        JSONObject end = new JSONObject().put("type", "content_block_stop").put("index", i);
                        byte[] a = eventBytes("content_block_start", start), b = eventBytes("content_block_stop", end);
                        prefix = new byte[a.length + b.length];
                        System.arraycopy(a, 0, prefix, 0, a.length); System.arraycopy(b, 0, prefix, a.length, b.length);
                        delta.put("stop_reason", "end_turn"); changed = true;
                    }
                    stopReason = true;
                }
            } else if ("message_stop".equals(type)) {
                if (!stopReason || !blocks.isEmpty()) throw new Exception();
                finished = true;
            }
        }
        if (listener != null) try { listener.onEvent(obj); } catch (RuntimeException ignored) { }
        byte[] result = changed ? rewrite(lines, obj) : raw;
        if (prefix != null) { byte[] combined = new byte[prefix.length + result.length]; System.arraycopy(prefix, 0, combined, 0, prefix.length); System.arraycopy(result, 0, combined, prefix.length, result.length); result = combined; }
        return result;
    }
    private static byte[] rewrite(String[] lines, JSONObject obj) {
        StringBuilder out = new StringBuilder(); boolean inserted = false;
        for (String line : lines) {
            if (line.isEmpty()) continue;
            int p = line.indexOf(':'); String key = p < 0 ? line : line.substring(0,p);
            if ("data".equals(key)) { if (!inserted) { out.append("data: ").append(obj.toString()).append('\n'); inserted = true; } }
            else out.append(line).append('\n');
        }
        out.append('\n'); return out.toString().getBytes(StandardCharsets.UTF_8);
    }
    @Override public int read() throws IOException { byte[] b = new byte[1]; int n = read(b, 0, 1); return n < 0 ? -1 : b[0] & 255; }
    @Override public int read(byte[] b, int off, int len) throws IOException {
        if (b == null) throw new NullPointerException();
        if (off < 0 || len < 0 || off > b.length - len) throw new IndexOutOfBoundsException();
        if (len == 0) return 0;
        if (closed) throw new IOException("stream closed");
        if (position >= output.length) {
            if (finished) { complete(stopReason); return -1; }
            try { byte[] f = frame(); if (f == null) fail(); else { output = process(f); position = 0; } }
            catch (Exception e) { if (closed) throw new IOException("stream closed", e); fail(); }
        }
        int n = Math.min(len, output.length - position); System.arraycopy(output, position, b, off, n); position += n;
        if (finished && position == output.length) complete(stopReason);
        return n;
    }
}
