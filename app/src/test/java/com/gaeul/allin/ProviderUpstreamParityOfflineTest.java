package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.net.*;
import java.util.*;

/** Independent expectations generated from the pinned upstream, not our implementation. */
public final class ProviderUpstreamParityOfflineTest {
    static int checks;
    static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
    static JSONArray messages(){return new JSONArray().put(new JSONObject().put("role","user").put("content","abc"));}
    static String catFrame(String text,String model){JSONObject msg=new JSONObject().put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",text)));if(model!=null)msg.put("model",model);return "data: "+new JSONObject().put("message",msg)+"\n\n";}
    static JSONObject cat(String s)throws Exception{return CatPawAuth.parseTurn(s.getBytes(StandardCharsets.UTF_8));}
    static void refusedCat(String s,int status)throws Exception{try{cat(s);throw new AssertionError("gate passed as text");}catch(CatPawAuth.Failure e){check(CatPawAuth.failureStatus(e)==status,"CatPaw precise refusal");check(e.sentOrUnknown,"no blind replay");}}
    static String tool(String id,String args,String name,int index){JSONObject call=new JSONObject().put("index",index).put("function",new JSONObject().put("arguments",args));if(id!=null)call.put("id",id);if(name!=null)call.getJSONObject("function").put("name",name);return envelope(new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("delta",new JSONObject().put("tool_calls",new JSONArray().put(call))))));}
    static String envelope(JSONObject chunk){return "data: "+new JSONObject().put("statusCodeValue",200).put("body",chunk.toString())+"\n\n";}
    static String end(){return envelope(new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("delta",new JSONObject()).put("finish_reason","tool_calls"))))+"data: [DONE]\n\n";}
    static JSONObject tools(String text)throws Exception{
        JSONObject original=new JSONObject().put("tools",new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","f").put("parameters",new JSONObject().put("type","object")))));
        HttpURLConnection fake=new HttpURLConnection(new URL("http://127.0.0.1:1")){public void connect(){throw new AssertionError("no network");}public void disconnect(){}public boolean usingProxy(){return false;}};
        try(QoderStream.Stream stream=new QoderStream.Stream(fake,new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),original,"fixture",null,null)){return stream.aggregate();}
    }
    static void badTools(String s)throws Exception{try{tools(s);throw new AssertionError("unsafe call passed");}catch(IOException e){checks++;}}
    public static void main(String[] args)throws Exception{
        JSONObject oracle=new JSONObject(new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8));
        JSONObject meta=new JSONObject();List<String> ids=MiniMaxAuth.parseModelCatalog(oracle.getJSONObject("miniCatalog"),meta);JSONArray expected=oracle.getJSONArray("miniParsed");
        for(int i=0;i<expected.length();i++){
            JSONObject row=expected.getJSONObject(i),actual=meta.getJSONObject(row.getString("id"));
            check(ids.get(i).equals(row.getString("id")),"upstream model order");
            check(actual.getJSONObject("modalities").getJSONArray("input").toString().equals(row.getJSONArray("inputs").toString()),"native input declarations");
            if(row.has("attachment"))check(actual.getBoolean("attachment")==row.getBoolean("attachment"),"explicit attachment false survives");
            if(!row.getString("id").equals("MiniMax-M3"))check(actual.getLong("maxOutput")==row.getLong("output"),"unknown zero not fake low cap");
        }
        for(int i=0;i<oracle.getJSONArray("miniFallback").length();i++){
            JSONObject row=oracle.getJSONArray("miniFallback").getJSONObject(i);String id=row.getString("id");
            if(MiniMaxAuth.verifiedModelOutputLimit(id)>0){check(MiniMaxAuth.verifiedModelOutputLimit(id)==row.getLong("output"),"524288 intact");check(MiniMaxAuth.verifiedModelContextLimit(id)==row.getLong("context"),"1048576 intact");}
        }
        check(meta.getJSONObject("MiniMax-M3").getLong("maxOutput")==524288,"verified limit overrides conservative listing, not vice versa");
        JSONObject staged=new JSONObject().put("sentinel",7),bad=new JSONObject(oracle.getJSONObject("miniCatalog").toString());bad.getJSONArray("providers").getJSONObject(0).getJSONObject("config").getJSONObject("models").getJSONObject("attachment-only").put("limit",new JSONObject().put("output",-1));
        try{MiniMaxAuth.parseModelCatalog(bad,staged);throw new AssertionError();}catch(IOException e){check(staged.length()==1,"malformed catalog does not partially publish");}
        JSONObject q=ProviderAllowance.fromQoder(oracle.getJSONObject("qoderCatalog"),1),qExpected=oracle.getJSONObject("qoderUsage");
        check(q.getDouble("remaining")==1980,"pool sum unchanged");check(q.getJSONArray("windows").length()==qExpected.getJSONArray("windows").length(),"one aggregate plus aside pools");
        JSONObject total=q.getJSONArray("windows").getJSONObject(0),totalExpected=qExpected.getJSONArray("windows").getJSONObject(0);
        check(total.getDouble("total")==totalExpected.getDouble("limit"),"same upstream total");check(total.getString("resetAt").equals(totalExpected.getString("resetsAt")),"combined window keeps plan reset");
        for(int i=1;i<q.getJSONArray("windows").length();i++)check(q.getJSONArray("windows").getJSONObject(i).getBoolean("aside"),"no double counted windows");
        JSONObject mixed=new JSONObject(oracle.getJSONObject("qoderCatalog").toString());mixed.getJSONObject("qoderUsage").getJSONObject("addOnQuota").put("unit","USD");check(ProviderAllowance.fromQoder(mixed,1)==null,"do not sum different units");
        String gate=oracle.getString("catQuotaGate");refusedCat(catFrame(gate,null)+"data: [DONE]\n",402);refusedCat(catFrame(gate.substring(0,4),null)+catFrame(gate,null)+"data: [DONE]\n",402);
        check(cat(catFrame(gate,"longcat-2.5")+"data: [DONE]\n").getString("text").equals(gate),"model quotation is not credit refusal");
        check(cat(catFrame("normal answer",null)+catFrame(gate,null)+"data: [DONE]\n").getString("text").equals(gate),"not an initial gate");
        check(CatPawAuth.failureStatus(new CatPawAuth.Failure(429,null,true))==oracle.getJSONObject("catRate").getInt("status"),"rate limit stays 429");
        check(oracle.getJSONObject("catRate").getInt("retryAfter")==45,"fixed source retry time");
        JSONObject response=tools(tool("one","{\"a\":1}","f",0)+tool("two","{\"a\":2}","f",0)+end());JSONArray calls=response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getJSONArray("tool_calls");
        check(calls.length()==2,"new id on same index creates second call");check(calls.getJSONObject(0).getString("id").equals("one"),"first call retained");check(calls.getJSONObject(1).getJSONObject("function").getString("arguments").equals("{\"a\":2}"),"not concatenated JSON");
        response=tools(tool("one","{\"a\":","f",0)+tool(null,"1}",null,0)+tool("two","{}","f",0)+end());check(response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getJSONArray("tool_calls").length()==2,"continuation and reuse both work");
        badTools(tool("one","{}",null,0)+tool("two","{}","f",0)+end());badTools(tool("one","{}","f",0)+tool("one","{}","f",1)+end());badTools(tool("one","{","f",0)+tool("two","{}","f",0)+end());
        JSONObject grow=new JSONObject(oracle.getJSONObject("workbuddyGrowth").toString());grow.remove("extra_vars");WorkBuddyGrowth.apply("workbuddy-ai",grow,"fixture-conversation",null);
        check(new JSONArray(grow.getJSONObject("extra_vars").getString("growthEvent")).toString().equals(new JSONArray(oracle.getJSONObject("workbuddyGrowth").getJSONObject("extra_vars").getString("growthEvent")).toString()),"growth mark matches upstream pure function");check(grow.getInt("max_tokens")==524288,"growth never shrinks output");
        WorkBuddyGrowth.apply("workbuddy-ai",grow,"fixture-conversation",null);check(new JSONArray(grow.getJSONObject("extra_vars").getString("growthEvent")).length()==1,"growth not duplicated");
        WorkBuddyGrowth.apply("workbuddy-ai",grow,"second","fixture-conversation");check(new JSONArray(grow.getJSONObject("extra_vars").getString("growthEvent")).getJSONObject(0).getString("id").equals("second"),"own rebind mark replaced");
        JSONObject untouched=new JSONObject().put("messages",messages()).put("max_tokens",524288);String before=untouched.toString();check(!WorkBuddyGrowth.apply("workbuddy",untouched,"conv",null)&&before.equals(untouched.toString()),"CN does not acquire AI telemetry");
        LoomyAuth.Wallets wallet=new LoomyAuth.Wallets(5000.,4200.,5000.,4200.);JSONObject loomy=ProviderAllowance.fromLoomy(wallet,1794596400000L).getJSONArray("windows").getJSONObject(0);
        check(loomy.getDouble("used")==16,"800 credits is 16%, not 800%");check(loomy.getDouble("amount")==800&&loomy.getDouble("remaining")==4200,"spent is not left");check(loomy.has("resetAt"),"Beijing daily reset retained");
        check(ProviderAllowance.fromLoomy(new LoomyAuth.Wallets(null,null,null,null),1)==null,"unknown wallets not zero");
        JSONObject cfg=new JSONObject("{\"agents\":[{\"name\":\"cli\",\"models\":[\"model\"]}],\"models\":[{\"id\":\"model\",\"name\":\"Same\",\"maxOutputTokens\":524288},{\"id\":\"model-sg\",\"name\":\"Same\"},{\"id\":\"custom:Team|v1\",\"maxOutputTokens\":384000},{\"id\":\"local\",\"modelType\":\"chat\"}]}");JSONObject wm=new JSONObject();List<String> wids=UpstreamCatalog.workbuddy(cfg,wm);check(wids.equals(Arrays.asList("model","custom:Team|v1")),"server custom model appended, local model excluded");check(wm.getJSONObject("custom:Team|v1").getLong("maxOutput")==384000,"custom max output intact");
        cfg.getJSONArray("agents").getJSONObject(0).getJSONArray("models").put("model-sg");wm=new JSONObject();UpstreamCatalog.workbuddy(cfg,wm);check(wm.getJSONObject("model-sg").getString("name").equals("Same (SG)"),"same-named models distinguished");
        for(String provider:new String[]{"loomy","raccoon","workbuddy","qoder_cn"})for(int cap:new int[]{63999,128000,131072,384000,512000,524288}){
            String field=provider.equals("workbuddy")?"maxOutputTokens":provider.equals("qoder_cn")?"max_output_tokens":"max_tokens";
            JSONObject raw=new JSONObject().put(field,cap);check(UpstreamCatalog.metadata(provider,raw).getInt("maxOutput")==cap,"exact budget alias "+provider+" "+cap);check(!raw.has("maxOutput"),"original catalog unchanged");
        }
        JSONObject ordered=tools(oracle.getString("qoderOrderWire"));JSONArray orderedCalls=ordered.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getJSONArray("tool_calls");
        check(orderedCalls.length()==oracle.getJSONArray("qoderNamedOrder").length(),"independent upstream named order count");
        for(int i=0;i<orderedCalls.length();i++)check(orderedCalls.getJSONObject(i).getString("id").equals(oracle.getJSONArray("qoderNamedOrder").getString(i)),"first-name order follows actual upstream events");
        check(AntigravityAuth.APP_VERSION.equals(oracle.getString("antigravityVersion")),"catalog fingerprint follows pinned upstream");
        java.lang.reflect.Method safe=GatewayServer.class.getDeclaredMethod("safeAllowance",String.class,JSONObject.class);safe.setAccessible(true);JSONObject clean=(JSONObject)safe.invoke(null,"qoder_cn",q);check(clean.getJSONArray("windows").getJSONObject(0).getString("resetAt").equals(total.getString("resetAt")),"actual gateway preserves reset");check(clean.getJSONArray("windows").getJSONObject(1).getBoolean("aside"),"actual gateway preserves aside");
        clean=(JSONObject)safe.invoke(null,"loomy",ProviderAllowance.fromLoomy(wallet,1794596400000L));check(clean.getJSONArray("windows").getJSONObject(0).getDouble("amount")==800,"actual gateway keeps spent unit");
        System.out.println("ProviderUpstreamParityOfflineTest PASS: "+checks+" checks");
    }
}
