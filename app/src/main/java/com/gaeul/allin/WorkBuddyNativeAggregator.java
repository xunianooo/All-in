package com.gaeul.allin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.TreeMap;
import java.util.LinkedHashMap;
import java.util.Map;
import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;

/** Offline, lossless-in-shape aggregator for Anthropic Messages SSE events. */
public final class WorkBuddyNativeAggregator {
    private static final int MAX_LINE_CHARS = 4 * 1024 * 1024;
    private static final int MAX_EVENT_CHARS = 8 * 1024 * 1024;
    private WorkBuddyNativeAggregator() {}

    /** Reads a complete SSE response and returns an Anthropic Messages JSON response. */
    public static JSONObject aggregate(InputStream stream) throws IOException {
        if (stream == null) throw new IllegalArgumentException("stream is required");
        State state = new State();
        InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
        StringBuilder line = new StringBuilder();
        StringBuilder data = new StringBuilder();
        int ch; boolean afterCr = false;
        while ((ch = reader.read()) != -1) {
            if (ch == '\n') {
                if (afterCr) { afterCr = false; continue; }
                consumeLine(line.toString(), data, state); line.setLength(0); continue;
            }
            if (ch == '\r') {
                consumeLine(line.toString(), data, state); line.setLength(0); afterCr = true; continue;
            }
            afterCr = false;
            if (line.length() >= MAX_LINE_CHARS) throw new IOException("Anthropic SSE line too large");
            line.append((char) ch);
        }
        if (line.length() > 0) consumeLine(line.toString(), data, state);
        if (data.length() > 0) state.event(data.toString());
        return state.finish();
    }

    private static void consumeLine(String line, StringBuilder data, State state) throws IOException {
        if (line.isEmpty()) {
            if (data.length() > 0) { state.event(data.toString()); data.setLength(0); }
            return;
        }
        if (line.startsWith("data:")) {
            String value=line.substring(5); if(value.startsWith(" "))value=value.substring(1);
            int extra=value.length()+(data.length()==0?0:1);
            if(data.length()+extra>MAX_EVENT_CHARS)throw new IOException("Anthropic SSE event too large");
            if(data.length()>0)data.append('\n'); data.append(value);
        }
    }

    /** Accepts one decoded data payload, useful to gateways with their own bounded SSE reader. */
    public static final class State {
        private JSONObject message;
        private JSONArray content;
        private final Map<Integer, JSONObject> blocks = new LinkedHashMap<>();
        private final Map<Integer, JSONObject> completed = new TreeMap<>();
        private final java.util.Set<Integer> usedIndexes = new java.util.HashSet<>();
        private JSONObject usage = new JSONObject();
        private String stopReason;
        private String stopSequence;
        private boolean stopped;
        private boolean finalized;
        private boolean seenStop;
        private int lastUsageInput=-1,lastUsageOutput=-1;
        public void event(String payload) throws IOException {
            if (payload == null || payload.isEmpty() || "[DONE]".equals(payload)) return;
            if(seenStop)throw new IOException("event after message_stop");
            if(finalized)throw new IOException("event after finalized Anthropic response");
            final JSONObject e;
            try { e = new JSONObject(payload); } catch (Exception ex) { throw new IOException("invalid Anthropic SSE event", ex); }
            String type = e.optString("type", "");
            try {
                if ("message_start".equals(type)) {
                    if (message != null || stopped) throw new IOException("duplicate message_start");
                    message = new JSONObject(e.getJSONObject("message").toString());
                    content = new JSONArray(); message.put("content", content);
                    absorb(e.getJSONObject("message").optJSONObject("usage"));
                } else if ("content_block_start".equals(type)) {
                    if (message == null || stopped) throw new IOException("content block outside message");
                    int i = index(e); if (!usedIndexes.add(i)) throw new IOException("duplicate content block index");
                    JSONObject b = new JSONObject(e.getJSONObject("content_block").toString()); blocks.put(i,b);
                } else if ("content_block_delta".equals(type)) {
                    JSONObject b = blocks.get(index(e)); if (b == null) throw new IOException("delta for unknown content block");
                    JSONObject d=e.getJSONObject("delta"); String dt=d.optString("type","");
                    if ("text_delta".equals(dt)) append(b,"text",d.getString("text"));
                    else if ("thinking_delta".equals(dt)) append(b,"thinking",d.getString("thinking"));
                    else if ("signature_delta".equals(dt)) append(b,"signature",d.getString("signature"));
                    else if ("input_json_delta".equals(dt)) append(b,"partial_json",d.getString("partial_json"));
                    else if ("citations_delta".equals(dt)) {
                        Object citation=d.opt("citation"); if(citation==null)throw new IOException("citation delta missing citation");
                        JSONArray citations=b.optJSONArray("citations"); if(citations==null){citations=new JSONArray();b.put("citations",citations);} citations.put(citation);
                    } else throw new IOException("unsupported Anthropic content delta: " + dt);
                } else if ("content_block_stop".equals(type)) {
                    JSONObject b=blocks.remove(index(e)); if(b==null)throw new IOException("stop for unknown content block");
                    if ("tool_use".equals(b.optString("type")) && b.has("partial_json")) {
                        String raw=b.getString("partial_json"); b.remove("partial_json");
                        try { b.put("input", new JSONObject(raw)); } catch(Exception bad) { throw new IOException("invalid tool input JSON",bad); }
                    }
                    completed.put(index(e), b);
                } else if ("message_delta".equals(type)) {
                    if(message==null || stopped)throw new IOException("message_delta outside message");
                    JSONObject delta=e.optJSONObject("delta");
                    if(delta!=null){
                        if(delta.has("stop_reason")) stopReason=nullableString(delta.opt("stop_reason"));
                        if(delta.has("stop_sequence")) stopSequence=nullableString(delta.opt("stop_sequence"));
                        for(String key:JSONObject.getNames(delta)==null?new String[0]:JSONObject.getNames(delta))
                            if(!"stop_reason".equals(key)&&!"stop_sequence".equals(key)) message.put(key,delta.opt(key));
                    }
                    absorb(e.optJSONObject("usage"));
                } else if ("message_stop".equals(type)) {
                    if(message==null || stopped || !blocks.isEmpty())throw new IOException("invalid message_stop");
                    stopped=true; seenStop=true;
                }
                else if ("ping".equals(type)) { }
                else if ("error".equals(type)) throw new IOException("upstream Anthropic SSE error");
                else throw new IOException("unknown Anthropic SSE event: " + type);
            } catch (IOException ex) { throw ex; } catch (Exception ex) { throw new IOException("malformed Anthropic SSE event: " + type, ex); }
        }
        private void absorb(JSONObject u) {
            if(u==null)return;
            String[] names=JSONObject.getNames(u);if(names==null)return;
            for(String key:names) {
                Object value=u.opt(key);
                if(("input_tokens".equals(key)||"output_tokens".equals(key)) && value instanceof Number) {
                    int current=((Number)value).intValue();
                    if("input_tokens".equals(key)) { if(current>=lastUsageInput){usage.put(key,value);lastUsageInput=current;} }
                    else if(current>=lastUsageOutput){usage.put(key,value);lastUsageOutput=current;}
                } else if(value instanceof JSONObject && usage.optJSONObject(key)!=null) {
                    JSONObject target=usage.optJSONObject(key),delta=(JSONObject)value;String[] sub=JSONObject.getNames(delta);
                    if(sub!=null)for(String field:sub)target.put(field,delta.opt(field));
                } else usage.put(key,value);
            }
        }
        private static int index(JSONObject e) throws IOException { Object x=e.opt("index"); if(!(x instanceof Number)||((Number)x).intValue()<0||((Number)x).doubleValue()!=((Number)x).intValue())throw new IOException("invalid content block index"); return ((Number)x).intValue(); }
        private static String nullableString(Object o) { return o==null||o==JSONObject.NULL?null:String.valueOf(o); }
        private static void append(JSONObject b,String key,String s) { b.put(key,b.optString(key,"")+s); }
        public JSONObject finish() throws IOException {
            if(message==null||!stopped||!blocks.isEmpty()) throw new IOException("incomplete Anthropic SSE response");
            if(!finalized) {
                for(JSONObject block:completed.values()) content.put(block);
                if(stopReason!=null) message.put("stop_reason",stopReason); if(stopSequence!=null)message.put("stop_sequence",stopSequence);
                message.put("usage",usage); finalized=true;
            }
            return new JSONObject(message.toString());
        }
    }
}
