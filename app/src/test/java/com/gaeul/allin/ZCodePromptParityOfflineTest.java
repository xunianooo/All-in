package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;

/** Oracle is fixed upstream data, not constants copied from the implementation. */
public final class ZCodePromptParityOfflineTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static String hash(String text)throws Exception {StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)))s.append(String.format("%02x",b));return s.substring(0,16);}
    static String constant(String name)throws Exception{return (String)ZCodePrompt.class.getDeclaredField(name).get(null);}
    static JSONObject request(boolean skill){JSONObject r=new JSONObject().put("model","glm-5.3-flash").put("max_tokens",128).put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","fixture question"))).put("system",new JSONArray().put(new JSONObject().put("type","text").put("text","extra instruction")));if(skill)r.put("tools",new JSONArray().put(new JSONObject().put("name","Skill").put("input_schema",new JSONObject().put("type","object"))));return r;}
    static String environment(JSONObject e){return e.getString("heading")+"\n"+e.getString("invokedLine")+"\n - "+e.getString("platformLabel")+": linux\n - "+e.getString("osVersionLabel")+": linux "+System.getProperty("os.version","unknown")+" "+System.getProperty("os.arch","")+"\n - "+e.getString("untrustedNote");}
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("upstream fixture path required");
        JSONObject p=new JSONObject(new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8));JSONArray stable=p.getJSONArray("stableSections");
        String[] texts={p.getString("cliPrefix"),stable.getString(0),stable.getString(1),p.getString("dynamicBehavior"),p.getString("sessionGuidance"),p.getString("contextManagement")};
        String[] sha={"7779642ea44a4696","aac4ba4cc7b555b3","86e0396775d5ed0e","7d843753f3adae93","6596a0131800c779","406a0480d88086cd"};
        for(int i=0;i<texts.length;i++)check(hash(texts[i]).equals(sha[i]),"fixed upstream hash "+i);
        check(ZCodePrompt.CLI_PREFIX.equals(texts[0]),"CLI prefix differs from fixed upstream 3.17.2");
        check(ZCodePrompt.STABLE_SECTIONS.length==2,"exactly two stable sections");for(int i=0;i<2;i++)check(ZCodePrompt.STABLE_SECTIONS[i].equals(stable.getString(i)),"stable text "+i);
        check(constant("BEFORE_ENVIRONMENT").equals(texts[3]),"dynamic behavior");check(constant("SESSION_GUIDANCE").equals(texts[4]),"optional Skill guidance");check(constant("AFTER_ENVIRONMENT").equals(texts[5]),"context management");
        for(boolean skill:new boolean[]{false,true}){
            JSONObject dressed=ZCodeGift.dress(request(skill),"zai","glm-5.3-flash","fixture-device","fixture-session");JSONArray system=dressed.getJSONArray("system");check(system.length()==3,"exact three system blocks");
            check(system.getJSONObject(0).getString("text").equals(texts[0]),"wire prefix");check(system.getJSONObject(1).getString("text").equals(texts[1]+"\n\n"+texts[2]),"wire stable blocks");
            String expected="\n\n"+texts[3]+"\n\n"+(skill?texts[4]+"\n\n":"")+environment(p.getJSONObject("environment"))+"\n\n"+p.getString("poweredBy").replace("{provider}","zai-api").replace("{model}","glm-5.3-flash")+"\n\n"+texts[5]+"\n\nextra instruction";
            check(system.getJSONObject(2).getString("text").equals(expected),"complete dynamic block matches oracle, skill="+skill);
            for(int i=0;i<3;i++)check(system.getJSONObject(i).getJSONObject("cache_control").getString("type").equals("ephemeral"),"stable caching "+i);
            String date=new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());String prefix=dressed.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text");check(prefix.equals("<system-reminder>"+p.getJSONObject("context").getString("currentDateLine").replace("{date}",date)+"</system-reminder>"),"date prefix has no legacy extra text");
        }
        for(String metadata:new String[]{"null","[]","\"opaque\"","{\"user_id\":\"foreign-id\",\"trace\":7}","{\"user_id\":null,\"trace\":7}"}){
            JSONObject r=request(false).put("metadata",new JSONObject("{\"metadata\":"+metadata+"}").opt("metadata"));JSONObject copy=new JSONObject(r.toString());JSONObject dressed=ZCodeGift.dress(r,"zai","glm-5.3-flash","fixture-device","fixture-session");JSONObject m=dressed.getJSONObject("metadata"),uid=new JSONObject(m.getString("user_id"));
            check(uid.getString("device_id").equals("fixture-device")&&uid.getString("session_id").equals("fixture-session")&&uid.getString("account_uuid").isEmpty(),"gift metadata belongs to current authenticated session");if(copy.optJSONObject("metadata")!=null&&copy.getJSONObject("metadata").has("trace"))check(m.getInt("trace")==7,"unrelated trace retained");
        }
        JSONObject bare=new JSONObject("{\"messages\":[],\"metadata\":{\"user_id\":\"caller-id\",\"trace\":7}}");check(ZCodeRequestPolicy.marked(bare,"fixture-device","fixture-session").getJSONObject("metadata").getString("user_id").equals("caller-id"),"non-gift marking keeps caller metadata");
        System.out.println("ZCodePromptParityOfflineTest PASS: "+checks+" checks");
    }
}
