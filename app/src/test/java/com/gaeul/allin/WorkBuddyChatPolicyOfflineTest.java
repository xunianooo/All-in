package com.gaeul.allin;

import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Offline request and response tests for the pinned WorkBuddy 0.2.26 Chat policy. */
public final class WorkBuddyChatPolicyOfflineTest {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        JSONObject metadata=new JSONObject("{\"reasoning\":{\"defaultEffort\":\"high\",\"supportedEfforts\":[\"low\",\"high\"]}}");
        JSONObject input=new JSONObject("{\"model\":\"workbuddy/claude-opus-5.5\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}");
        String original=input.toString();
        WorkBuddyChatPolicy.Prepared prepared=WorkBuddyChatPolicy.prepare(input,metadata);
        check("high".equals(prepared.body().optString("reasoning_effort")),"model default effort copied to Chat request");
        JSONObject catalog=new JSONObject().put("claude-opus-5.5",metadata);
        WorkBuddyChatPolicy.Prepared wired=WorkBuddyChatGatewayAdapter.prepare("workbuddy",input,catalog,"claude-opus-5.5");
        check("high".equals(wired.body().optString("reasoning_effort")),"gateway adapter resolves selected model metadata");
        check(original.equals(input.toString()),"request is immutable");
        JSONObject explicit=new JSONObject(input.toString()).put("reasoning_effort","low");
        check("low".equals(WorkBuddyChatPolicy.prepare(explicit,metadata).body().optString("reasoning_effort")),"explicit effort wins");
        JSONObject shortBudget=new JSONObject(input.toString()).put("max_tokens",64);
        check(!WorkBuddyChatPolicy.prepare(shortBudget,metadata).body().has("reasoning_effort"),"small output budgets do not get default effort");
        JSONObject unsupported=new JSONObject("{\"reasoning\":{\"defaultEffort\":\"xhigh\",\"supportedEfforts\":[\"low\",\"high\"]}}");
        check(!WorkBuddyChatPolicy.prepare(input,unsupported).body().has("reasoning_effort"),"unsupported default is omitted");

        JSONObject priorities=new JSONObject().put("supportsReasoning",true).put("reasoning",new JSONObject().put("defaultEffort","high").put("effort","low"));
        check("high".equals(WorkBuddyChatPolicy.prepare(input,priorities).body().optString("reasoning_effort")),"defaultEffort precedes effort");
        priorities.getJSONObject("reasoning").put("defaultEffort",JSONObject.NULL);
        check("low".equals(WorkBuddyChatPolicy.prepare(input,priorities).body().optString("reasoning_effort")),"null default falls back to effort");
        check(!WorkBuddyChatPolicy.prepare(input,new JSONObject().put("supportsReasoning",true).put("effort","high")).body().has("reasoning_effort"),"top-level effort is not a config default in 0.2.26");
        check(!WorkBuddyChatPolicy.prepare(new JSONObject(input.toString()).put("reasoning",new JSONObject().put("effort","none")),metadata).body().has("reasoning_effort"),"nested explicit effort retained");
        check("none".equals(WorkBuddyChatPolicy.prepare(new JSONObject(input.toString()).put("reasoning_effort","none"),metadata).body().optString("reasoning_effort")),"explicit off wins");
        check(!WorkBuddyChatPolicy.prepare(new JSONObject(input.toString()).put("max_completion_tokens",1024),metadata).body().has("reasoning_effort"),"alternate small budget prevents default");
        check("high".equals(WorkBuddyChatPolicy.prepare(new JSONObject(input.toString()).put("max_tokens",2048).put("max_completion_tokens",64),metadata).body().optString("reasoning_effort")),"max_tokens has reference budget precedence");

        JSONObject tools=new JSONObject("{\"model\":\"m\",\"messages\":[{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"MCP:Read.File\",\"arguments\":\"{}\"}}]},{\"role\":\"tool\",\"tool_call_id\":\"c1\",\"name\":\"MCP:Read.File\",\"content\":\"ok\"}],\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"MCP:Read.File\",\"description\":\"read\",\"parameters\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}}},{\"type\":\"function\",\"function\":{\"name\":\"MCP_Read_File\",\"parameters\":{\"type\":\"object\"}}}],\"tool_choice\":{\"type\":\"function\",\"function\":{\"name\":\"MCP:Read.File\"}}}");
        WorkBuddyChatPolicy.Prepared fitted=WorkBuddyChatPolicy.prepare(tools,null);JSONObject outbound=fitted.body();
        String safe=outbound.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name");
        check("MCP_Read_File_2".equals(safe),"sanitized name avoids collision");
        check("MCP_Read_File_2".equals(outbound.getString("tool_choice")),"forced choice follows sanitized tool");
        JSONObject stringChoice=new JSONObject(tools.toString()).put("tool_choice","MCP:Read.File");
        check("MCP_Read_File_2".equals(WorkBuddyChatPolicy.prepare(stringChoice,null).body().optString("tool_choice")),"pre-normalized string choice follows sanitized tool");
        check("MCP_Read_File_2".equals(outbound.getJSONArray("messages").getJSONObject(0).getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("name")),"assistant history name follows tool");
        check("MCP_Read_File_2".equals(outbound.getJSONArray("messages").getJSONObject(1).getString("name")),"tool result name follows tool");
        Map<String,String> reverse=fitted.upstreamToClientNames();
        check("MCP:Read.File".equals(reverse.get(safe)),"response reverse map is request scoped");
        String response="{\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"c2\",\"function\":{\"name\":\""+safe+"\",\"arguments\":\"{}\"}}]}}]}";
        String restored=WorkBuddyChatGatewayAdapter.restoreResponse(fitted,"application/json",response);
        check(restored.contains("MCP:Read.File")&&!restored.contains(safe+"\",\"arguments"),"JSON response maps tool name back through gateway seam");
        String sse="data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\""+safe+"\"}}]}}]}\n\ndata: [DONE]\n\n";
        WorkBuddyChatPolicy.SseRestorer restorer=fitted.newSseRestorer();
        String first=restorer.feed(sse.substring(0,31),false);String second=restorer.feed(sse.substring(31),true);
        check((first+second).contains("MCP:Read.File")&&(first+second).contains("[DONE]"),"incremental SSE response maps names");

        JSONObject schema=new JSONObject().put("properties",new JSONObject()
                .put("tuple",new JSONObject().put("type","array").put("items",new JSONArray()
                        .put(new JSONObject().put("type","string")).put(new JSONObject().put("type","integer"))))
                .put("choice",new JSONObject().put("oneOf",new JSONArray()
                        .put(new JSONObject().put("type","object").put("properties",new JSONObject().put("a",new JSONObject().put("type","string"))).put("required",new JSONArray().put("a")))
                        .put(new JSONObject().put("type","object").put("properties",new JSONObject().put("b",new JSONObject().put("type","boolean"))).put("required",new JSONArray().put("b")))))
                .put("flag",true).put("forbidden",false).put("pattern",new JSONObject().put("type","string").put("pattern","^\\0$")))
                .put("enum",new JSONArray().put("x").put(2).put(JSONObject.NULL));
        JSONObject schemaRequest=new JSONObject().put("tools",new JSONArray().put(new JSONObject().put("type","function")
                .put("function",new JSONObject().put("name","run").put("parameters",schema))));
        JSONObject fittedSchema=WorkBuddyChatPolicy.prepare(schemaRequest,null).body().getJSONArray("tools").getJSONObject(0).getJSONObject("function").getJSONObject("parameters");
        check("object".equals(fittedSchema.optString("type")),"parameters root becomes object");
        check(fittedSchema.optJSONObject("properties").optJSONObject("tuple").optJSONObject("items").has("anyOf"),"tuple items preserve alternatives");
        JSONObject choiceSchema=fittedSchema.optJSONObject("properties").optJSONObject("choice");
        check("object".equals(choiceSchema.optString("type")),"object-only union gets object type");
        check(fittedSchema.optJSONObject("properties").optJSONObject("forbidden").has("not"),"boolean subschema converted without widening");
        check(fittedSchema.optJSONObject("properties").optJSONObject("pattern").optString("pattern").contains("u0000"),"NUL regex escape normalized");

        JSONObject choice=new JSONObject(input.toString()).put("tool_choice",new JSONObject().put("type","function").put("function",new JSONObject().put("name","read file")));
        check("read file".equals(WorkBuddyChatPolicy.prepare(choice,null).body().optString("tool_choice")),"tool choice without declared rename is kept by name");
        JSONObject none=new JSONObject(input.toString()).put("tools",new JSONArray().put(new JSONObject())).put("tool_choice","none");
        JSONObject noTools=WorkBuddyChatPolicy.prepare(none,null).body();check(!noTools.has("tools")&&!noTools.has("tool_choice"),"none removes unused tools");
        check(WorkBuddyChatGatewayAdapter.supports("workbuddy")&&WorkBuddyChatGatewayAdapter.supports("workbuddy-ai")&&!WorkBuddyChatGatewayAdapter.supports("zcode"),"adapter is provider scoped");
        System.out.println("WorkBuddyChatPolicyOfflineTest OK");
    }
}
