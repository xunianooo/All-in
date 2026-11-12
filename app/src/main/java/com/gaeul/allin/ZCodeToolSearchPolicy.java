package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONException;
import com.gaeul.allin.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Request-local ToolSearch compatibility for the final Anthropic message body. */
public final class ZCodeToolSearchPolicy {
    private static final String SEARCH = "ToolSearch", ALIAS = "ToolSearchTool";
    private ZCodeToolSearchPolicy() {}

    /** The stored JSON is private; body() returns a fresh copy on every call. */
    public static final class Result {
        private final String json;
        public final boolean renamed;
        private Result(JSONObject body, boolean renamed) { this.json = body.toString(); this.renamed = renamed; }
        public JSONObject body() { try { return new JSONObject(json); } catch (JSONException e) { throw new IllegalStateException(e); } }
        public JSONObject restoreJson(JSONObject response) { return ZCodeToolSearchPolicy.restoreJson(response, renamed); }
        /** Call only for SSE; for JSON use restoreJson after normal decoding. */
        public InputStream restoreStream(InputStream in, boolean eventStream) {
            return renamed && eventStream ? new EventStream(in) : in;
        }
    }

    public static Result transform(JSONObject body) {
        if (body == null) throw new IllegalArgumentException("body == null");
        JSONArray tools = body.optJSONArray("tools");
        boolean found = false, collision = false;
        if (tools != null) for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.optJSONObject(i);
            if (tool != null) { found |= SEARCH.equals(tool.optString("name")); collision |= ALIAS.equals(tool.optString("name")); }
        }
        if (!found || collision) return new Result(body, false);
        try {
            JSONObject copy = new JSONObject(body.toString());
            JSONArray source = copy.getJSONArray("tools"), filtered = new JSONArray();
            for (int i = 0; i < source.length(); i++) {
                Object value = source.get(i);
                if (value instanceof JSONObject) {
                    JSONObject tool = (JSONObject) value;
                    if ("DeferredToolPlaceholder".equals(tool.optString("name"))) continue;
                    tool.remove("defer_loading");
                    if (SEARCH.equals(tool.optString("name"))) tool.put("name", ALIAS);
                }
                filtered.put(value);
            }
            copy.put("tools", filtered);
            JSONArray messages = copy.optJSONArray("messages");
            if (messages != null) for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.optJSONObject(i);
                if (message == null || !"assistant".equals(message.optString("role"))) continue;
                JSONArray content = message.optJSONArray("content");
                if (content != null) for (int j = 0; j < content.length(); j++) rename(content.optJSONObject(j), SEARCH, ALIAS);
            }
            JSONObject choice = copy.optJSONObject("tool_choice");
            if (choice != null && "tool".equals(choice.optString("type")) && SEARCH.equals(choice.optString("name"))) choice.put("name", ALIAS);
            return new Result(copy, true);
        } catch (JSONException e) { throw new IllegalArgumentException("Invalid JSON body", e); }
    }
    private static boolean rename(JSONObject block, String from, String to) throws JSONException {
        if (block != null && "tool_use".equals(block.optString("type")) && from.equals(block.optString("name"))) {
            block.put("name", to); return true;
        }
        return false;
    }
    /** Returns the original response when inactive; otherwise a detached response copy. */
    public static JSONObject restoreJson(JSONObject response, boolean renamed) {
        if (response == null || !renamed) return response;
        try {
            JSONObject out = new JSONObject(response.toString());
            if (renamed) {
                JSONArray content = out.optJSONArray("content");
                if (content != null) for (int i = 0; i < content.length(); i++) rename(content.optJSONObject(i), ALIAS, SEARCH);
            }
            return out;
        } catch (JSONException e) { throw new IllegalArgumentException("Invalid JSON response", e); }
    }

    /** Supports CRLF, LF and CR event separators; rejects events over 1 MiB. */
    private static final class EventStream extends InputStream {
        private static final int LIMIT = 1024 * 1024;
        private final java.io.PushbackInputStream in;
        private byte[] ready = new byte[0];
        private int pos;
        private boolean eof, closed;
        private IOException failure;
        private int lineLength;
        private boolean pendingCR;
        EventStream(InputStream in) { if (in == null) throw new NullPointerException("in"); this.in = new java.io.PushbackInputStream(in, 1); }
        // Returns true at an empty line; handles CR, LF and CRLF without decoding bytes.
        private boolean boundary(int b) {
            if (pendingCR) {
                pendingCR = false;
                if (b == '\n') return false;
            }
            if (b == '\r' || b == '\n') {
                boolean empty = lineLength == 0;
                lineLength = 0;
                if (b == '\r') pendingCR = true;
                return empty;
            }
            lineLength++; return false;
        }
        private void fill() throws IOException {
            if (failure != null) throw failure;
            if (pos < ready.length || eof) return;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        while (true) {
                int b = in.read();
                if (b < 0) { eof = true; break; }
                bytes.write(b);
                if (bytes.size() > LIMIT) throw fail("SSE event exceeds 1 MiB");
                if (boundary(b)) {
                    if (b == '\r') { int next = in.read(); if (next == '\n') { bytes.write(next); if (bytes.size() > LIMIT) throw fail("SSE event exceeds 1 MiB"); } else if (next >= 0) in.unread(next); pendingCR = false; }
                    break;
                }
            }
            byte[] raw = bytes.toByteArray();
            try { ready = rewriteEvent(raw); } catch (IOException e) { failure = e; throw e; }
            pos = 0;
        }
        private IOException fail(String message) { failure = new IOException(message); return failure; }
        @Override public int read() throws IOException {
            if (closed) throw new IOException("stream closed");
            fill(); return pos < ready.length ? ready[pos++] & 255 : -1;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (b == null) throw new NullPointerException();
            if (off < 0 || len < 0 || off > b.length - len) throw new IndexOutOfBoundsException();
            if (closed) throw new IOException("stream closed");
            if (len == 0) return 0;
            fill(); if (pos == ready.length) return -1;
            int n = Math.min(len, ready.length - pos); System.arraycopy(ready, pos, b, off, n); pos += n; return n;
        }
        @Override public int available() throws IOException { if (closed) throw new IOException("stream closed"); return ready.length - pos; }
        @Override public void close() throws IOException { if (!closed) { closed = true; in.close(); } }
    }
    private static byte[] rewriteEvent(byte[] raw) throws IOException {
        // Never decode malformed UTF-8 into replacement characters.
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(raw)).toString(); }
        catch (java.nio.charset.CharacterCodingException e) { throw new IOException("Invalid UTF-8 in SSE event", e); }
        String[] lines = text.split("(?<=\\n)|(?<=\\r)(?!\\n)", -1);
        StringBuilder data = new StringBuilder(); int first = -1;
        for (int i = 0; i < lines.length; i++) {
            String s = lines[i]; int end = s.length();
            while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) end--;
            String line = s.substring(0, end);
            if (line.equals("data") || line.startsWith("data:")) {
                if (first < 0) first = i; else data.append('\n');
                String value = line.length() == 4 ? "" : line.substring(5);
                if (value.startsWith(" ")) value = value.substring(1);
                data.append(value);
            }
        }
        if (first < 0 || "[DONE]".equals(data.toString())) return raw;
        try {
            JSONObject event = new JSONObject(data.toString());
            JSONObject block = event.optJSONObject("content_block");
            if ("content_block_start".equals(event.optString("type")) && (block == null || !(block.opt("type") instanceof String) || ("tool_use".equals(block.optString("type")) && !(block.opt("name") instanceof String))))
                throw new IOException("Malformed SSE content block");
            if (!rename(block, ALIAS, SEARCH)) return raw;
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                String s = lines[i]; int end = s.length();
                while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) end--;
                String line = s.substring(0, end), ending = s.substring(end);
                if (line.equals("data") || line.startsWith("data:")) {
                    if (i == first) out.append("data: ").append(event.toString());
                    else continue;
                    out.append(ending);
                } else out.append(s);
            }
            return out.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) { throw new IOException("Invalid JSON in SSE data", e); }
    }
}
