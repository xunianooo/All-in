package com.gaeul.allin;

import com.gaeul.allin.json.*;

/** Actual Gateway loopback requests for Chat policy and response bridges, on both sites. */
public final class WorkBuddyChatGatewayHttpTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static JSONObject metadata(){return new JSONObject().put("reasoning",new JSONObject().put("defaultEffort","high").put("effort","low").put("supportedEfforts",new JSONArray().put("low").put("high")));}
    static JSONObject request(String id,boolean stream){
        return VendorPolicyGatewayHttpTest.body(id+"/model",stream).put("max_tokens",2048)
            .put("tools",new JSONArray()
                .put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","mcp:读.file").put("parameters",new JSONObject().put("type","OBJECT").put("required",new JSONArray().put("path").put(7)).put("properties",new JSONObject().put("path",new JSONObject().put("type","STRING"))))))
                .put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","mcp___file").put("parameters",new JSONObject().put("type","object")))))
            .put("tool_choice",new JSONObject().put("type","function").put("function",new JSONObject().put("name","mcp:读.file")));
    }
    static String sse(){return WorkBuddyCompletionGatewayHttpTest.chunk(new JSONObject().put("tool_calls",new JSONArray().put(new JSONObject().put("index",0).put("id","call-x").put("type","function").put("function",new JSONObject().put("name","mcp___file_2").put("arguments","{\"path\":")))),null)
        +WorkBuddyCompletionGatewayHttpTest.chunk(new JSONObject().put("tool_calls",new JSONArray().put(new JSONObject().put("index",0).put("function",new JSONObject().put("arguments","\"文档\"}")))),"tool_calls")+"data: [DONE]\n\n";}
    static void exercise(String id,boolean stream,String path)throws Exception{
        JSONObject provider=VendorPolicyGatewayHttpTest.provider(id,"openai",id.equals("workbuddy")?"https://copilot.tencent.com/v2":"https://www.workbuddy.ai/v2","model").put("modelMeta",new JSONObject().put("model",metadata()));
        try(VendorPolicyGatewayHttpTest.Upstream upstream=new VendorPolicyGatewayHttpTest.Upstream((r,n)->new VendorPolicyGatewayHttpTest.Reply(200,"text/event-stream",sse()));
            VendorPolicyGatewayHttpTest.Gateway gateway=new VendorPolicyGatewayHttpTest.Gateway(VendorPolicyGatewayHttpTest.config(provider),upstream,false)){
            JSONObject input=request(id,stream);
            if(path.endsWith("/completions")){
                JSONArray history=input.getJSONArray("messages");
                history.put(new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(new JSONObject().put("id","old-call").put("type","function").put("function",new JSONObject().put("name","mcp:读.file").put("arguments","{\"keep\":\"DeepSeek Harness\"}")))));
                history.put(new JSONObject().put("role","tool").put("tool_call_id","old-call").put("name","mcp:读.file").put("content","DeepSeek Harness result"));
            }
            if(path.endsWith("/messages")){
                JSONObject fn=input.getJSONArray("tools").getJSONObject(0).getJSONObject("function");
                input.put("tools",new JSONArray().put(new JSONObject().put("name",fn.getString("name")).put("input_schema",fn.getJSONObject("parameters"))).put(new JSONObject().put("name","mcp___file").put("input_schema",new JSONObject().put("type","object"))));
                input.put("tool_choice",new JSONObject().put("type","tool").put("name","mcp:读.file"));
            }else if(path.endsWith("/responses")){
                JSONArray tools=new JSONArray();for(int i=0;i<input.getJSONArray("tools").length();i++){JSONObject f=input.getJSONArray("tools").getJSONObject(i).getJSONObject("function");f.put("type","function");tools.put(f);}input.put("tools",tools).put("input","hello");input.remove("messages");input.put("tool_choice",new JSONObject().put("type","function").put("name","mcp:读.file"));
            }
            VendorPolicyGatewayHttpTest.Result result=VendorPolicyGatewayHttpTest.post(gateway,path,input);
            check(result.status==200,"response "+id+" "+path+" "+stream+" "+result.body);
            check(upstream.requests.size()==1,"single physical send");JSONObject sent=upstream.requests.get(0).body;
            check("high".equals(sent.optString("reasoning_effort")),"defaultEffort beats model effort "+sent);
            JSONObject fn=sent.getJSONArray("tools").getJSONObject(0).getJSONObject("function");
            check("mcp___file_2".equals(fn.getString("name")),"collision-safe upstream name "+fn);
            check("mcp___file_2".equals(sent.optString("tool_choice")),"choice follows encoded name");
            JSONObject schema=fn.getJSONObject("parameters");check("object".equals(schema.getString("type"))&&schema.getJSONArray("required").length()==1,"schema fitted");
            check(result.body.contains("mcp:读.file")&&!result.body.contains("mcp___file_2"),"original name restored before client bridge "+result.body);
            check(result.body.contains("文档"),"tool args UTF-8 retained");
            if(path.endsWith("/completions")){
                String history=sent.getJSONArray("messages").toString();
                check(history.contains("DeepSeek Harness result")&&history.contains("DeepSeek Harness"),"DSH tool args/result remain exact");
                check(!history.contains("mcp:读.file")&&history.contains("mcp___file_2"),"history tool and tool result use encoded name");
            }
        }
    }
    static void efforts(String id)throws Exception{
        JSONObject p=VendorPolicyGatewayHttpTest.provider(id,"openai",id.equals("workbuddy")?"https://copilot.tencent.com/v2":"https://www.workbuddy.ai/v2","model").put("modelMeta",new JSONObject().put("model",metadata()));
        try(VendorPolicyGatewayHttpTest.Upstream u=new VendorPolicyGatewayHttpTest.Upstream((r,n)->new VendorPolicyGatewayHttpTest.Reply(200,"text/event-stream",DeepSeekHarnessGatewayHttpTest.sse()));
            VendorPolicyGatewayHttpTest.Gateway g=new VendorPolicyGatewayHttpTest.Gateway(VendorPolicyGatewayHttpTest.config(p),u,false)){
            for(int i=0;i<5;i++){
                JSONObject request=VendorPolicyGatewayHttpTest.body(id+"/model",false).put("max_tokens",2048);
                if(i==0)request.put("reasoning_effort","none");
                if(i==1)request.put("reasoning_effort","low");
                if(i==2)request.put("max_tokens",1024);
                if(i==3)request.remove("max_tokens");
                if(i==3)request.put("max_completion_tokens",64);
                if(i==4)request.put("reasoning",new JSONObject().put("effort","low"));
                VendorPolicyGatewayHttpTest.Result r=VendorPolicyGatewayHttpTest.post(g,"/v1/chat/completions",request);
                check(r.status==200,"effort request "+id+" "+i+" "+r.body);
                JSONObject sent=u.requests.get(i).body;
                if(i<2)check((i==0?"none":"low").equals(sent.optString("reasoning_effort")),"explicit effort beats default");
                else check(!sent.has("reasoning_effort"),"short budget/nested effort suppress default "+i+" "+sent);
            }
            check(u.requests.size()==5,"effort requests never replayed");
        }
    }
    public static void main(String[] args)throws Exception{
        for(String id:new String[]{"workbuddy","workbuddy-ai"})for(boolean stream:new boolean[]{false,true})for(String path:new String[]{"/v1/chat/completions","/v1/messages","/v1/responses"})exercise(id,stream,path);
        for(String id:new String[]{"workbuddy","workbuddy-ai"})efforts(id);
        System.out.println("WorkBuddyChatGatewayHttpTest OK (both sites, stream/nonstream, all output bridges)");
    }
}
