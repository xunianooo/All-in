package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import com.gaeul.allin.json.JSONTokener;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Incremental, fail-closed reader for Qoder's text-encoded tool calls. */
public final class QoderTextTools {
    public static final class Piece {
        public final String text;
        public final JSONObject call;
        private Piece(String text, JSONObject call) { this.text = text; this.call = call; }
    }
    private static final int LIMIT = 1024 * 1024;
    private static final String[] MARKS = {"<tool_call>", "<tool_use>", "<function=", "<invoke ", "<antml:invoke ", "<function_calls>", "<antml:function_calls>"};
    private static final Pattern OPEN = Pattern.compile("^<(?:function=([^\\s<>]+)\\s*|(?:antml:)?invoke\\s+name\\s*=\\s*\"([^\"<>]+)\"\\s*)>");
    private static final Pattern PARAM = Pattern.compile("<(?:antml:)?parameter(?:\\s+name\\s*=\\s*\"([^\"<>]+)\"|=([^\\s<>]+))\\s*>");
    private final JSONArray tools;
    private final StringBuilder pending = new StringBuilder();
    private boolean fence, inline, failed, ended;
    private int ticks;
    private String wrapper;

    public QoderTextTools(JSONArray tools) { this.tools = tools == null ? new JSONArray() : tools; }
    public List<Piece> feed(String input) throws IOException {
        if (failed || ended) throw new IOException("text tool parser already closed");
        if (input != null) pending.append(input);
        return process(false);
    }
    /** npm Splitter.flush before a native header drains a section without ending the whole stream. */
    List<Piece> checkpoint() throws IOException {
        if (failed || ended) throw new IOException("text tool parser already closed");
        return process(true);
    }
    public List<Piece> flush() throws IOException {
        if (failed || ended) throw new IOException("text tool parser already closed");
        ended = true;
        return process(true);
    }
    private IOException error(String message) { failed = true; return new IOException(message); }
    private void bound(String s) throws IOException {
        if (s.length() > LIMIT || s.getBytes(StandardCharsets.UTF_8).length > LIMIT) throw error("text tool call exceeds 1 MiB");
    }
    private void tickFinish() {
        if (ticks >= 3) { fence = !fence; inline = false; }
        else if ((ticks & 1) != 0 && !fence) inline = !inline;
        ticks = 0;
    }
    private void emit(List<Piece> out, String s) {
        if (s.isEmpty()) return;
        out.add(new Piece(s, null));
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '`') ticks++;
            else { tickFinish(); if (c == '\n' && !fence) inline = false; }
        }
    }
    private JSONObject schema(String name) {
        for (int i=0;i<tools.length();i++) {
            JSONObject t=tools.optJSONObject(i); if(t==null) continue;
            JSONObject f=t.optJSONObject("function"); if(f==null) f=t;
            if(name.equals(f.optString("name"))) return f;
        }
        return null;
    }
    private JSONObject makeCall(String name, JSONObject args) throws IOException {
        if (name == null || name.trim().isEmpty() || schema(name) == null) throw error("undeclared text tool");
        try { bound(args.toString()); return new JSONObject().put("name", name).put("args", args.toString()); }
        catch (com.gaeul.allin.json.JSONException e) { throw error("invalid tool call"); }
    }
    private Object strict(String s) throws IOException {
        bound(s);
        try { new BoundedStrictJson(s).run(); return new JSONTokener(s).nextValue(); }
        catch (Exception e) { throw error("invalid or too deeply nested JSON in text tool call"); }
    }
    private JSONObject parseJson(String body) throws IOException {
        Object v = strict(body.trim());
        if (!(v instanceof JSONObject)) throw error("tool call must be an object");
        JSONObject j=(JSONObject)v; Object n=j.opt("name"), a=j.opt("arguments");
        if (!(n instanceof String) || !(a instanceof JSONObject)) throw error("tool call requires name and object arguments");
        return makeCall(((String)n).trim(), (JSONObject)a);
    }
    private JSONObject parseXml(String all, boolean wrapped) throws IOException {
        bound(all); Matcher m=OPEN.matcher(all);
        if (!m.find()) throw error("malformed XML tool opener");
        String name=m.group(1)!=null ? m.group(1) : m.group(2);
        JSONObject tool=schema(name); if(tool==null) throw error("undeclared text tool");
        String close=m.group(1)!=null ? "</function>" : (all.startsWith("<antml:") ? "</antml:invoke>" : "</invoke>");
        if (!all.endsWith(close)) throw error("unclosed XML tool call");
        String body=all.substring(m.end(), all.length()-close.length());
        JSONObject args=new JSONObject(); JSONObject parameters=tool.optJSONObject("parameters");
        JSONObject props=parameters==null?null:parameters.optJSONObject("properties");
        int pos=0;
        while(pos<body.length()) {
            Matcher p=PARAM.matcher(body); p.region(pos,body.length());
            if(!p.find()) { if(!body.substring(pos).trim().isEmpty()) throw error("malformed XML parameters"); break; }
            if(!body.substring(pos,p.start()).trim().isEmpty()) throw error("malformed XML parameters");
            String key=p.group(1)!=null?p.group(1):p.group(2);
            if(args.has(key)) throw error("duplicate XML parameter");
            String end=p.group().startsWith("<antml:")?"</antml:parameter>":"</parameter>";
            int at=body.indexOf(end,p.end()); if(at<0) throw error("unclosed XML parameter");
            String value=body.substring(p.end(),at);
            if(value.startsWith("\r\n")) value=value.substring(2); else if(value.startsWith("\n")) value=value.substring(1);
            if(value.endsWith("\r\n")) value=value.substring(0,value.length()-2); else if(value.endsWith("\n")) value=value.substring(0,value.length()-1);
            if (wrapped) value=value.trim();
            JSONObject prop=props==null?null:props.optJSONObject(key); String type=prop==null?"":prop.optString("type", "");
            Object parsed=value;
            if (!type.isEmpty() && !type.equals("string")) {
                parsed=strict(value.trim());
                boolean ok=type.equals("object") ? parsed instanceof JSONObject : type.equals("array") ? parsed instanceof JSONArray : type.equals("boolean") ? parsed instanceof Boolean : type.equals("number") ? parsed instanceof Number : type.equals("integer") ? parsed instanceof Number && ((Number)parsed).doubleValue()==Math.rint(((Number)parsed).doubleValue()) : type.equals("null") && parsed==JSONObject.NULL;
                if(!ok) throw error("XML parameter does not match declared type");
            }
            try { args.put(key,parsed); } catch (com.gaeul.allin.json.JSONException e) { throw error("invalid XML parameter"); }
            pos=at+end.length();
        }
        // Validate the combined result too, including nesting and encoded size.
        strict(args.toString());
        return makeCall(name,args);
    }
    private int jsonClose(String s, String close) {
        boolean quote=false, escape=false;
        for(int i=0;i<s.length();i++) {
            char c=s.charAt(i);
            if(quote) { if(escape) escape=false; else if(c=='\\') escape=true; else if(c=='"') quote=false; }
            else { if(c=='"') quote=true; else if(s.startsWith(close,i)) return i; }
        }
        return -1;
    }
    private List<Piece> process(boolean end) throws IOException {
        List<Piece> out=new ArrayList<>();
        while(pending.length()>0) {
            String s=pending.toString();
            if (wrapper != null && s.charAt(0) == '<') {
                if (s.startsWith(wrapper)) { pending.delete(0,wrapper.length()); wrapper=null; continue; }
                if (wrapper.startsWith(s) && !end) break;
            }
            int at=s.indexOf('<');
            if(at<0) { emit(out,s); pending.setLength(0); break; }
            if(at>0) { emit(out,s.substring(0,at)); pending.delete(0,at); continue; }
            tickFinish();
            if(fence || inline) { emit(out,"<"); pending.deleteCharAt(0); continue; }
            String mark=null; boolean partial=false;
            for(String x:MARKS) { if(s.startsWith(x)) { mark=x; break; } if(x.startsWith(s)) partial=true; }
            if(mark==null) {
                if(partial && !end) break;
                if(partial && end && s.length()>1) throw error("truncated text tool marker");
                // Recognize malformed invoke and function tags as tool-like, never leak them.
                if(s.startsWith("<invoke") || s.startsWith("<antml:invoke") || s.startsWith("<function=") || s.startsWith("<tool_call") || s.startsWith("<tool_use")) throw error("malformed text tool marker");
                emit(out,"<"); pending.deleteCharAt(0); continue;
            }
            if(mark.equals("<tool_use>")) throw error("unsupported tool_use text wrapper");
            if(mark.endsWith("function_calls>")) {
                if(wrapper!=null) throw error("nested tool wrapper");
                wrapper=mark.startsWith("<antml:")?"</antml:function_calls>":"</function_calls>";
                pending.delete(0,mark.length()); continue;
            }
            if(mark.equals("<tool_call>")) {
                String rest=s.substring(mark.length()); int j=jsonClose(rest,"</tool_call>");
                if(j<0) { bound(rest); if(end) throw error("unclosed tool_call"); break; }
                String body=rest.substring(0,j).trim(); JSONObject call;
                if(body.startsWith("{")) call=parseJson(body);
                else if(body.startsWith("<function=")) call=parseXml(body, true);
                else throw error("unreadable tool_call");
                out.add(new Piece(null,call)); pending.delete(0,mark.length()+j+"</tool_call>".length()); continue;
            }
            Matcher op=OPEN.matcher(s);
            if(!op.find()) { bound(s); if(!end && s.indexOf('>')<0) break; throw error("malformed XML tool opener"); }
            String close=mark.equals("<function=")?"</function>":mark.startsWith("<antml:")?"</antml:invoke>":"</invoke>";
            int j=s.indexOf(close,op.end());
            if(j<0) { bound(s); if(end) throw error("unclosed XML tool call"); break; }
            int n=j+close.length(); out.add(new Piece(null,parseXml(s.substring(0,n), false))); pending.delete(0,n);
        }
        // Wrapper closing tags may arrive incrementally, including between calls.
        if(wrapper!=null && pending.length()>0 && pending.toString().equals(wrapper)) { pending.setLength(0); wrapper=null; }
        if(end && wrapper!=null) throw error("unclosed function_calls wrapper");
        return out;
    }
}
