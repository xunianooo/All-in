package com.gaeul.allin;
import com.gaeul.allin.json.*;

/** Request-local WorkBuddy AI activity mark from pinned 0.2.34. No separate report/claim. */
final class WorkBuddyGrowth {
    private WorkBuddyGrowth() {}
    private static boolean block(JSONObject m,String type) {
        JSONArray c=m==null?null:m.optJSONArray("content");
        for(int i=0;c!=null&&i<c.length();i++){JSONObject p=c.optJSONObject(i);if(p!=null&&type.equals(p.optString("type")))return true;}return false;
    }
    static int inputLength(JSONArray messages) {
        for(int i=messages.length()-1;i>=0;i--){
            JSONObject m=messages.optJSONObject(i);if(m==null||!"user".equals(m.optString("role"))||block(m,"tool_result"))continue;
            int j=i-1;while(j>=0&&"system".equals(messages.optJSONObject(j)==null?"":messages.optJSONObject(j).optString("role")))j--;
            JSONObject p=j<0?null:messages.optJSONObject(j);
            if(p!=null&&( "tool".equals(p.optString("role"))||block(p,"tool_result")||("assistant".equals(p.optString("role"))&&((p.optJSONArray("tool_calls")!=null&&p.optJSONArray("tool_calls").length()>0)||block(p,"tool_use")))))continue;
            Object c=m.opt("content");if(c instanceof String)return ((String)c).length();if(!(c instanceof JSONArray))return 0;
            int n=0;JSONArray parts=(JSONArray)c;for(int k=0;k<parts.length();k++){JSONObject part=parts.optJSONObject(k);if(part!=null&&"text".equals(part.optString("type","text"))&&part.opt("text") instanceof String)n+=part.optString("text").length();}return n;
        }return 0;
    }
    static boolean apply(String provider,JSONObject chat,String conversation,String previous) {
        if(!"workbuddy-ai".equals(provider)||conversation==null||conversation.isEmpty()||chat.optJSONArray("messages")==null)return false;
        JSONObject vars=chat.optJSONObject("extra_vars");vars=vars==null?new JSONObject():new JSONObject(vars.toString());JSONArray events=new JSONArray();
        Object raw=vars.opt("growthEvent");if(raw instanceof String&&!((String)raw).trim().isEmpty())try{Object parsed=new JSONTokener((String)raw).nextValue();events=parsed instanceof JSONArray?(JSONArray)parsed:new JSONArray().put(parsed);}catch(Exception ignored){}
        JSONArray kept=new JSONArray();boolean present=false;
        for(int i=0;i<events.length();i++){Object e=events.opt(i);JSONObject item=events.optJSONObject(i);boolean send=item!=null&&"chat_request_send".equals(item.optString("eventCode"));if(send&&previous!=null&&!previous.isEmpty()&&previous.equals(item.optString("id")))continue;if(send&&conversation.equals(item.optString("id")))present=true;kept.put(e);}
        String model=chat.optString("model","");
        if(!present)kept.put(new JSONObject().put("eventCode","chat_request_send").put("id",conversation).put("extra",new JSONObject().put("inputLength",inputLength(chat.getJSONArray("messages"))).put("requestModelId",model).put("requestModelName",name(model)).put("mode","craft").put("command","")));
        vars.put("growthEvent",kept.toString());if(vars.isNull("initiator"))vars.put("initiator","user");chat.put("extra_vars",vars);return true;
    }
    private static String name(String model) {
        String[] ids={"default-model","fast-model","balanced-model","primary-model","deep-model","gpt-5.5","gpt-5.4","gpt-5.3-codex","gemini-3.1-pro","gemini-3.5-flash","glm-5.3","glm-5.2","hy3","kimi-k3","kimi-k2.6","minimax-m3"};
        String[] names={"Default","Fast","Balanced","Primary","Deep","GPT-5.5","GPT-5.4","GPT-5.3-Codex","Gemini-3.1-Pro","Gemini-3.5-Flash","GLM-5.3","GLM-5.2","Hy3","Kimi-K3","Kimi-K2.6","MiniMax-M3"};
        for(int i=0;i<ids.length;i++)if(ids[i].equals(model))return names[i];return model;
    }
}
