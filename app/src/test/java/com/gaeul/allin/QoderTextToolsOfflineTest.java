package com.gaeul.allin;

import java.util.*;
import java.io.IOException;
import org.json.*;

public class QoderTextToolsOfflineTest {
    static JSONArray tools() throws Exception {
        return new JSONArray("[{\"function\":{\"name\":\"run\",\"parameters\":{\"properties\":{\"s\":{\"type\":\"string\"},\"n\":{\"type\":\"number\"},\"b\":{\"type\":\"boolean\"},\"o\":{\"type\":\"object\"}}}}}]");
    }
    static List<QoderTextTools.Piece> split(String s,int cut) throws Exception {
        QoderTextTools p=new QoderTextTools(tools()); List<QoderTextTools.Piece> out=new ArrayList<>();
        out.addAll(p.feed(s.substring(0,cut))); out.addAll(p.feed(s.substring(cut))); out.addAll(p.flush()); return out;
    }
    static void verify(String s,int expected) throws Exception {
        for(int i=0;i<=s.length();i++) {
            List<QoderTextTools.Piece> pieces=split(s,i); int count=0; StringBuilder text=new StringBuilder();
            for(QoderTextTools.Piece p:pieces) { if(p.call!=null) { count++; if(!p.call.getString("name").equals("run")) throw new AssertionError(); } else text.append(p.text); }
            if(count!=expected || !text.toString().equals("你好前后")) throw new AssertionError("boundary "+i+" count="+count+" text="+text);
        }
    }
    static void fails(String s, boolean noTools) throws Exception {
        try { QoderTextTools p=new QoderTextTools(noTools?new JSONArray():tools()); p.feed(s); p.flush(); throw new AssertionError("accepted "+s); }
        catch(IOException expected) { }
    }
    public static void main(String[] args) throws Exception {
        verify("你好前<tool_call>{\"name\":\"run\",\"arguments\":{\"s\":\"a\\\"</tool_call>b\",\"o\":{\"x\":[1,2]}}}</tool_call>后",1);
        verify("你好前<function=run><parameter=s>[1,2]</parameter><parameter=n>2.5</parameter><parameter=b>true</parameter><parameter=o>{\"x\":1}</parameter></function>后",1);
        verify("你好前<function_calls><invoke name=\"run\"><parameter name=\"s\">x</parameter></invoke><invoke name=\"run\"></invoke></function_calls>后",2);
        verify("你好前<antml:function_calls><antml:invoke name=\"run\"><antml:parameter name=\"s\">x</antml:parameter></antml:invoke></antml:function_calls>后",1);
        JSONObject call=split("<function=run><parameter=s>[1,2]</parameter><parameter=n>2.5</parameter><parameter=b>true</parameter><parameter=o>{\"x\":1}</parameter><parameter=unknown>true</parameter></function>",4).get(0).call;
        JSONObject a=new JSONObject(call.getString("args"));
        if(!a.getString("s").equals("[1,2]") || !(a.get("n") instanceof Number) || !a.getBoolean("b") || a.getJSONObject("o").getInt("x")!=1 || !a.getString("unknown").equals("true")) throw new AssertionError(a);
        fails("<tool_call>{\"name\":\"run\",\"arguments\":{\"a\":1,\"a\":2}}</tool_call>",false);
        fails("<tool_call>{\"name\":\"run\",\"arguments\":{}}",false);
        fails("<function=bad></function>",false);
        fails("<function=run><parameter=b>not-bool</parameter></function>",false);
        fails("<function=run></function>",true);
        fails("<tool_use>anything</tool_use>",false);
        // Upstream parses entity text literally; raw terminators must not become truncated calls.
        String entity="<function=run><parameter=s>&lt;/parameter&gt;</parameter></function>";
        for(int i=0;i<=entity.length();i++) {
            JSONObject c=split(entity,i).get(0).call;
            if(!new JSONObject(c.getString("args")).getString("s").equals("&lt;/parameter&gt;")) throw new AssertionError("entity boundary "+i);
        }
        fails("<function=run><parameter=s>before</parameter>after</parameter></function>",false);
        fails("<function=run><parameter=s>before</function>after</parameter></function>",false);
        fails("<tool_call>{\"function\":{\"name\":\"run\",\"arguments\":{}}}</tool_call>",false);
        fails("<tool_call>{\"name\":\"run\",\"arguments\":\"{}\"}</tool_call>",false);
        fails("<tool_call>{\"name\":\"run\",\"parameters\":{}}</tool_call>",false);
        JSONObject wrapped=split("<tool_call><function=run><parameter=s>  hi  </parameter></function></tool_call>",5).get(0).call;
        if(!new JSONObject(wrapped.getString("args")).getString("s").equals("hi")) throw new AssertionError("wrapped trim");
        String quoted="`<function=run></function>`";
        for(int i=0;i<=quoted.length();i++) { StringBuilder t=new StringBuilder(); for(QoderTextTools.Piece p:split(quoted,i)) { if(p.call!=null) throw new AssertionError(); t.append(p.text); } if(!quoted.equals(t.toString())) throw new AssertionError(); }
        QoderTextTools bytewise=new QoderTextTools(tools()); int calls=0;
        String stream="前<tool_call>{\"name\":\"run\",\"arguments\":{}}</tool_call>后";
        for(int i=0;i<stream.length();i++) for(QoderTextTools.Piece piece:bytewise.feed(stream.substring(i,i+1))) if(piece.call!=null) calls++;
        for(QoderTextTools.Piece piece:bytewise.flush()) if(piece.call!=null) calls++;
        if(calls!=1) throw new AssertionError("bytewise");
        StringBuilder deep=new StringBuilder("<tool_call>{\"name\":\"run\",\"arguments\":{\"x\":");
        for(int i=0;i<80;i++) deep.append('['); deep.append('0'); for(int i=0;i<80;i++) deep.append(']'); deep.append("}}</tool_call>"); fails(deep.toString(),false);
        fails("<tool_call>"+new String(new char[1024*1024+1]).replace('\0','x'),false);
        System.out.println("QoderTextToolsOfflineTest OK");
    }
}
