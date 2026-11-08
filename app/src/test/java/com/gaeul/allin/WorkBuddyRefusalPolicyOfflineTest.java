package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;

public final class WorkBuddyRefusalPolicyOfflineTest {
    static void ok(boolean v) { if(!v) throw new AssertionError(); }
    static JSONObject msg(String role,Object content) throws Exception { return new JSONObject().put("role",role).put("content",content); }
    static JSONObject body(Object... messages) throws Exception { JSONArray a=new JSONArray(); for(Object m:messages)a.put(m); return new JSONObject().put("messages",a).put("unknown",new JSONObject().put("keep",7)); }
    static JSONObject at(JSONObject b,int i) throws Exception { return b.getJSONArray("messages").getJSONObject(i); }
    static void rejects(JSONObject b) throws Exception { try { WorkBuddyRefusalPolicy.systemAsUserCandidate(400,"illegal API invocation",b,true); throw new AssertionError("expected rejection"); } catch(IllegalArgumentException expected) {} }
    public static void main(String[] args) throws Exception {
        ok(WorkBuddyRefusalPolicy.eligible(400,"UNAPPROVED CHANNEL"));
        ok(!WorkBuddyRefusalPolicy.eligible(401,"unapproved channel") && !WorkBuddyRefusalPolicy.eligible(400,"other"));
        String opener="You are an AI agent powered by DeepSeek Harness";
        JSONArray parts=new JSONArray().put(new JSONObject().put("type","text").put("text",opener)).put(new JSONObject().put("type","image_url").put("text",opener)).put(23);
        JSONObject input=body(msg("system",opener),msg("assistant",parts).put("tool_calls",new JSONArray().put(new JSONObject().put("arguments",opener))),msg("user",opener),msg("tool",opener).put("tool_call_id","id"));
        String before=input.toString();
        WorkBuddyRefusalPolicy.Proposal p=WorkBuddyRefusalPolicy.commaCandidate(400,"illegal api invocation",input);
        ok(p.changed && input.toString().equals(before));
        ok(at(p.body,0).getString("content").equals("You are an AI agent, powered by DeepSeek Harness"));
        JSONArray result=at(p.body,1).getJSONArray("content");
        ok(result.getJSONObject(0).getString("text").contains("agent, powered"));
        ok(result.getJSONObject(1).getString("text").equals(opener) && result.getInt(2)==23);
        ok(at(p.body,1).getJSONArray("tool_calls").getJSONObject(0).getString("arguments").equals(opener));
        ok(at(p.body,2).getString("content").equals(opener) && at(p.body,3).getString("tool_call_id").equals("id"));
        ok(!WorkBuddyRefusalPolicy.commaCandidate(400,"illegal api invocation",p.body).changed);
        ok(!WorkBuddyRefusalPolicy.commaCandidate(500,"illegal api invocation",input).changed);
        ok(!WorkBuddyRefusalPolicy.commaCandidate(400,"other",input).changed);
        ok(!WorkBuddyRefusalPolicy.commaCandidate(400,"illegal api invocation",body(msg("system","/You are an AI agent powered by/model"))).changed);
        JSONObject src=body(msg("system","first"),msg("system","second"),msg("user",new JSONArray().put(new JSONObject().put("type","image_url").put("image_url","opaque"))),msg("assistant",JSONObject.NULL).put("tool_calls",new JSONArray().put(new JSONObject().put("id","a"))),msg("tool","result").put("tool_call_id","a"),msg("system","middle"));
        before=src.toString();
        ok(!WorkBuddyRefusalPolicy.systemAsUserCandidate(400,"unapproved channel",src,false).changed);
        p=WorkBuddyRefusalPolicy.systemAsUserCandidate(400,"unapproved channel",src,true);
        ok(p.changed && src.toString().equals(before));
        ok(at(p.body,0).getString("content").equals(WorkBuddyRefusalPolicy.DEFAULT_SYSTEM));
        ok(at(p.body,1).getJSONArray("content").getJSONObject(0).getString("text").equals("<instructions>\nfirst\n\nsecond\n</instructions>"));
        ok(at(p.body,1).getJSONArray("content").getJSONObject(1).getString("image_url").equals("opaque"));
        ok(at(p.body,2).getJSONArray("tool_calls").getJSONObject(0).getString("id").equals("a") && at(p.body,3).getString("tool_call_id").equals("a"));
        ok(at(p.body,4).getString("role").equals("user"));
        ok(!WorkBuddyRefusalPolicy.systemAsUserCandidate(400,"unapproved channel",p.body,true).changed);
        JSONObject def=body(msg("system",WorkBuddyRefusalPolicy.DEFAULT_SYSTEM),msg("user",""),msg("system","mid"));
        p=WorkBuddyRefusalPolicy.systemAsUserCandidate(400,"unapproved channel",def,true);
        ok(at(p.body,0).getString("content").equals(WorkBuddyRefusalPolicy.DEFAULT_SYSTEM) && at(p.body,1).getString("content").equals("") && at(p.body,2).getString("role").equals("user"));
        rejects(body(msg("system", ""),msg("user","x")));
        JSONArray sysBlocks=new JSONArray().put(new JSONObject().put("type","text").put("text","DSH first\nline"))
            .put(new JSONObject().put("type","text").put("text","next\nline"));
        JSONObject blockSource=body(msg("system",sysBlocks),msg("user","question"));
        String blockBefore=blockSource.toString();
        p=WorkBuddyRefusalPolicy.systemAsUserCandidate(400,"unapproved channel",blockSource,true);
        ok(at(p.body,1).getJSONArray("content").getJSONObject(0).getString("text").equals("<instructions>\nDSH first\nline"));
        ok(blockSource.toString().equals(blockBefore));
        ok(!WorkBuddyRefusalPolicy.systemAsUserCandidate(400,"unapproved channel",p.body,true).changed);
        p=WorkBuddyRefusalPolicy.systemAsUserCandidate(400,"unapproved channel",body(msg("system",new JSONArray().put(new JSONObject().put("type","text").put("text"," hi ").put("cache_control",new JSONObject().put("type","ephemeral")))),msg("user","x")),true);
        ok(at(p.body,1).getJSONArray("content").getJSONObject(0).getString("text").equals("<instructions>\n hi \n</instructions>"));
        ok(at(p.body,1).getJSONArray("content").getJSONObject(0).getJSONObject("cache_control").getString("type").equals("ephemeral"));
        rejects(body(msg("system",new JSONArray().put(new JSONObject().put("type","text").put("text","hi").put("signature","sig"))),msg("user","x")));
        rejects(body(msg("system",new JSONArray().put(new JSONObject().put("type","image").put("text","hi"))),msg("user","x")));
        rejects(body(msg("system",new JSONArray().put(new JSONObject().put("type","text").put("text",""))),msg("user","x")));
        JSONArray mixed=new JSONArray()
            .put(new JSONObject().put("type","text").put("text",opener).put("cache_control",new JSONObject().put("type","ephemeral")))
            .put(new JSONObject().put("type","text").put("text",opener).put("signature","sig"))
            .put(new JSONObject().put("type","text").put("text",opener).put("future",true))
            .put(new JSONObject().put("type","text").put("text",opener).put("cache_control",new JSONObject().put("type","ephemeral").put("future",true)));
        JSONObject signed=body(msg("system",mixed),msg("assistant",opener).put("signature","sig"));
        before=signed.toString();
        p=WorkBuddyRefusalPolicy.commaCandidate(400,"unapproved channel",signed);
        JSONArray protectedParts=at(p.body,0).getJSONArray("content");
        ok(protectedParts.getJSONObject(0).getString("text").contains("agent, powered"));
        for(int i=1;i<4;i++) ok(protectedParts.getJSONObject(i).getString("text").equals(opener));
        ok(at(p.body,1).getString("content").equals(opener) && signed.toString().equals(before));
        ok(!WorkBuddyRefusalPolicy.commaCandidate(400,"unapproved channel",p.body).changed);
        rejects(body(msg("system","hi"),msg("user","x"),msg("system","middle").put("signature","signed")));
        rejects(body(msg("system","hi"),msg("user","x"),msg("system","middle").put("unknown",true)));
        rejects(body(msg("system",WorkBuddyRefusalPolicy.DEFAULT_SYSTEM).put("extra",true),msg("user","x"),msg("system","middle")));
        rejects(body(msg("system","hi"),msg("user","x").put("signature","signed")));
        rejects(body(msg("system","hi"),msg("user",new JSONArray().put(new JSONObject().put("type","text").put("text","x").put("signature","signed")))));
        rejects(body(msg("system",42),msg("user","x")));
        rejects(body(msg("system","hi").put("extra",1),msg("user","x")));
        rejects(body(msg("system","hi"),msg("user",JSONObject.NULL)));
        p=WorkBuddyRefusalPolicy.systemAsUserCandidate(400,"illegal api invocation",body(msg("system","hello"),msg("user","world")),true);
        ok(at(p.body,1).getString("content").equals("<instructions>\nhello\n</instructions>\n\nworld"));
        
        WorkBuddyRefusalPolicy.Cache cache=new WorkBuddyRefusalPolicy.Cache();
        JSONObject original=body(msg("system",opener),msg("user","hi"));
        WorkBuddyRefusalPolicy.Session session=cache.start("workbuddy","account|network",original);
        JSONObject first=session.initial(); first.put("mutated",true);
        JSONObject retry=session.next(400,"illegal api invocation");
        ok(retry!=null && !retry.has("mutated") && session.sends()==2);
        session.response(200,""); ok(session.next(200,"")==null);
        session=cache.start("workbuddy","account|network",original);
        ok(at(session.initial(),0).getString("content").contains("agent, powered"));
        retry=session.next(400,"unapproved channel"); ok(retry!=null && session.sends()==2);
        // A guard can discard the proposed body before any physical attempt.
        WorkBuddyRefusalPolicy.Session guarded=cache.start("workbuddy","account|network",original);
        ok(at(guarded.initial(),0).getString("content").contains("agent, powered"));
        session.markSent(); session.markSent();
        ok(session.next(400,"unapproved channel")==null);
        session=cache.start("workbuddy","account|network",original);
        ok(at(session.initial(),0).getString("content").equals(WorkBuddyRefusalPolicy.DEFAULT_SYSTEM));
        ok(session.next(400,"unapproved channel")==null);
        session=cache.start("other provider","account|network",original); ok(at(session.initial(),0).getString("content").equals(opener));
        session=cache.start("workbuddy","account|network",body(msg("system",opener+" changed"),msg("user","hi"))); ok(at(session.initial(),0).getString("content").endsWith("changed"));
        session=cache.start("workbuddy","invalid",body(msg("system",new JSONArray().put(new JSONObject().put("type","image").put("text","opaque"))),msg("user","x"))); session.initial();
        ok(session.next(400,"unapproved channel")==null && session.sends()==1);
        session=cache.start("workbuddy","other account|network",original);
        ok(at(session.initial(),0).getString("content").equals(opener));
        ok(session.next(403,"unapproved channel")==null && session.next(400,"unapproved channel")==null);
        session=cache.start("workbuddy","different route",original); session.initial();
        ok(session.next(0,null)==null);
        session=cache.start("workbuddy","three stages",original); session.initial();
        session.markSent(); ok(session.next(400,"unapproved channel")!=null);
        session.markSent(); ok(session.next(400,"unapproved channel")!=null);
        session.markSent(); ok(session.next(400,"unapproved channel")==null && session.sends()==3);
        session=cache.start("workbuddy","skip comma",body(msg("system"," plain "),msg("user","x"))); session.initial();
        retry=session.next(400,"unapproved channel"); ok(retry!=null && session.sends()==2);
        ok(at(retry,1).getString("content").contains("\n plain \n"));
        ok(session.next(400,"unapproved channel")==null);
        for(int i=0;i<270;i++) { session=cache.start("workbuddy","scope"+i,original); session.initial(); session.next(400,"unapproved channel"); session.response(200,""); }
        ok(cache.size()==256);
        session=cache.start("workbuddy","account|network",original); ok(at(session.initial(),0).getString("content").equals(opener));
        System.out.println("WorkBuddyRefusalPolicyOfflineTest OK");
    }
}
