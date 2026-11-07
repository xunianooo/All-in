package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;

/** Pure conversion for WorkBuddy chat bodies. The caller must use the returned copy. */
public final class WorkBuddyRequestPolicy {
    private WorkBuddyRequestPolicy() {}
    private static IOException bad(String s) { return new IOException("WorkBuddy: " + s); }
    private static long limit(Object v) throws IOException {
        if (!(v instanceof Number)) throw bad("invalid output limit");
        double d=((Number)v).doubleValue();
        if (!Double.isFinite(d) || d<1 || d>Integer.MAX_VALUE || d!=Math.rint(d)) throw bad("invalid output limit");
        return (long)d;
    }
    public static JSONObject transform(JSONObject input) throws Exception {
        if (input==null) throw bad("missing request");
        JSONObject out=new JSONObject(input.toString());
        if(out.has("n") && !out.isNull("n") && (!(out.opt("n") instanceof Number) || ((Number)out.opt("n")).doubleValue()!=1)) throw bad("only n=1 supported");
        boolean a=out.has("max_tokens")&&!out.isNull("max_tokens"), b=out.has("max_completion_tokens")&&!out.isNull("max_completion_tokens");
        if(a||b) { long v=a?limit(out.opt("max_tokens")):limit(out.opt("max_completion_tokens")); if(b)v=Math.min(v,limit(out.opt("max_completion_tokens")));out.put("max_tokens",v); }
        out.remove("max_completion_tokens");
        JSONArray messages=out.optJSONArray("messages");
        if(messages==null) throw bad("missing messages");
        for(int i=0;i<messages.length();i++) {
            JSONObject m=messages.optJSONObject(i); if(m==null)throw bad("invalid message");
            if("developer".equals(m.optString("role"))) m.put("role","system");
            if(!"assistant".equals(m.optString("role")))continue;
            Object existing=m.opt("reasoning");
            if(existing!=null && existing!=JSONObject.NULL && !(existing instanceof String))throw bad("invalid reasoning");
            String text=""; Object rc=m.opt("reasoning_content"), rd=m.opt("reasoning_details");
            if(rc!=null&&rc!=JSONObject.NULL&&!(rc instanceof String))throw bad("invalid reasoning_content");
            if(rc instanceof String) text=(String)rc;
            if(rd!=null&&rd!=JSONObject.NULL) {
                if(!(rd instanceof JSONArray))throw bad("invalid reasoning_details");
                StringBuilder pieces=new StringBuilder(); JSONArray arr=(JSONArray)rd;
                for(int j=0;j<arr.length();j++) { JSONObject item=arr.optJSONObject(j); if(item==null)throw bad("invalid reasoning_details"); Object part=item.has("text")?item.opt("text"):item.opt("summary"); if(!(part instanceof String))throw bad("unsupported reasoning detail"); pieces.append((String)part); }
                if(text.isEmpty())text=pieces.toString();
            }
            if((existing==null||existing==JSONObject.NULL||((String)existing).isEmpty())&&!text.isEmpty())m.put("reasoning",text);
            m.remove("reasoning_content");m.remove("reasoning_details");
        }
        if(messages.length()>0 && !"system".equals(messages.getJSONObject(0).optString("role"))) {
            JSONArray replacement=new JSONArray().put(new JSONObject().put("role","system").put("content","You are a helpful assistant."));
            for(int i=0;i<messages.length();i++) replacement.put(messages.get(i));out.put("messages",replacement);
        }
        Object choice=out.opt("tool_choice");
        if(choice!=null && choice!=JSONObject.NULL) {
            String c;
            if(choice instanceof String) c=((String)choice).trim();
            else if(choice instanceof JSONObject) {
                JSONObject obj=(JSONObject)choice; String type=obj.optString("type");
                if("function".equals(type)) { JSONObject fn=obj.optJSONObject("function"); c=fn==null?"":fn.optString("name").trim(); if(c.isEmpty())throw bad("invalid forced tool"); }
                else if("none".equals(type)||"auto".equals(type)||"required".equals(type)) c=type;
                else throw bad("unsupported tool choice");
            } else throw bad("unsupported tool choice");
            if("none".equals(c)){out.remove("tool_choice");out.remove("tools");out.remove("functions");}
            else if("auto".equals(c)||"required".equals(c))out.put("tool_choice",c);
            else if(c.matches("[A-Za-z0-9_-]+") && choice instanceof JSONObject)out.put("tool_choice",c);
            else throw bad("unsupported tool choice");
        }
        return out;
    }
}
