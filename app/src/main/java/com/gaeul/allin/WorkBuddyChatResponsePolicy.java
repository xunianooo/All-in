package com.gaeul.allin;

import java.util.Map;
import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;

/** Response name restoration helpers for the WorkBuddy Chat compatibility policy. */
public final class WorkBuddyChatResponsePolicy {
    private WorkBuddyChatResponsePolicy() {}

    /** Restores names on JSON or SSE response text through its request-local map. */
    public static String restore(WorkBuddyChatPolicy.Prepared prepared, String contentType, String response) {
        return prepared == null ? response : prepared.restoreResponse(contentType, response);
    }

    /** Restores names on a parsed OpenAI response or one streamed Chat chunk. */
    public static void restoreChatObject(JSONObject response, Map<String, String> upstreamToClient) {
        if (response == null || upstreamToClient == null || upstreamToClient.isEmpty()) return;
        JSONArray choices = response.optJSONArray("choices");
        for (int i = 0; choices != null && i < choices.length(); i++) {
            JSONObject choice = choices.optJSONObject(i);
            if (choice == null) continue;
            restoreCalls(choice.optJSONObject("message"), upstreamToClient);
            restoreCalls(choice.optJSONObject("delta"), upstreamToClient);
        }
    }

    /** Decode UTF-8 incrementally, restore whole SSE events, then let existing bridges consume them. */
    public static java.io.InputStream restoreStream(WorkBuddyChatPolicy.Prepared prepared, java.io.InputStream source) {
        if (prepared == null || prepared.upstreamToClientNames().isEmpty()) return source;
        return new java.io.InputStream() {
            final java.io.Reader reader = new java.io.InputStreamReader(source, java.nio.charset.StandardCharsets.UTF_8);
            final WorkBuddyChatPolicy.SseRestorer restorer = prepared.newSseRestorer();
            final char[] chars = new char[2048];
            byte[] ready = new byte[0];
            int position;
            boolean eof;
            private boolean fill() throws java.io.IOException {
                while (position == ready.length && !eof) {
                    int count = reader.read(chars);
                    eof = count < 0;
                    String restored;
                    try { restored = restorer.feed(eof ? null : new String(chars, 0, count), eof); }
                    catch (RuntimeException invalid) { throw new java.io.IOException("Invalid WorkBuddy Chat SSE", invalid); }
                    ready = restored.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    position = 0;
                }
                return position < ready.length;
            }
            @Override public int read() throws java.io.IOException { return fill() ? ready[position++] & 255 : -1; }
            @Override public int read(byte[] bytes, int offset, int length) throws java.io.IOException {
                if (bytes == null) throw new NullPointerException();
                if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
                if (length == 0) return 0;
                if (!fill()) return -1;
                int count = Math.min(length, ready.length-position);
                System.arraycopy(ready,position,bytes,offset,count);position+=count;return count;
            }
            @Override public void close() throws java.io.IOException { reader.close(); }
        };
    }

    private static void restoreCalls(JSONObject message, Map<String, String> names) {
        JSONArray calls = message == null ? null : message.optJSONArray("tool_calls");
        for (int i = 0; calls != null && i < calls.length(); i++) {
            JSONObject call = calls.optJSONObject(i);
            JSONObject fn = call == null ? null : call.optJSONObject("function");
            if (fn == null) continue;
            String original = names.get(fn.optString("name", ""));
            if (original != null) fn.put("name", original);
        }
    }
}
