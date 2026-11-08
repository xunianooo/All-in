package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Preserve native Responses fields; configured transformations must never silently discard them. */
final class NativeResponsesPolicy {
    static final class Prepared {
        final JSONObject body;
        final ContextGuard.Result guard;
        Prepared(JSONObject b,ContextGuard.Result g) {body=b;guard=g;}
    }
    static Prepared prepare(String raw,PrivacyMasker.Session session,PrivacyMasker.Config privacy,
                            JSONObject middleware,ContextGuard.Config guard) throws Exception {
        JSONObject body=new JSONObject(raw);
        if(!(body.opt("model") instanceof String))throw new IllegalArgumentException("Responses model must be a string");
        String clientModel=body.getString("model"), provider=clientModel.contains("/")?clientModel.substring(0,clientModel.indexOf('/')):"";
        body.put("model",Middleware.mapModel(clientModel,provider,middleware));
        Middleware.applyParamOverride(body,provider,middleware);
        // Native Responses has instructions, not Chat messages; do not manufacture a projection.
        String prompt=middleware==null?"":middleware.optString("systemPrompt","");
        JSONArray skip=middleware==null?null:middleware.optJSONArray("systemPromptSkipProviders");
        boolean skipPrompt=false;
        for(int i=0;skip!=null&&i<skip.length();i++)if(provider.equals(skip.optString(i)))skipPrompt=true;
        if(!prompt.trim().isEmpty() && !skipPrompt) {
            Object old=body.opt("instructions");
            if(old!=null && old!=JSONObject.NULL && !(old instanceof String))throw new IllegalArgumentException("Native instructions cannot be safely transformed");
            String current=old instanceof String?(String)old:"", mode=middleware.optString("systemPromptMode","prepend");
            body.put("instructions","replace".equals(mode)||current.isEmpty()?prompt:"append".equals(mode)?current+"\n\n"+prompt:prompt+"\n\n"+current);
        }
        if(Middleware.thinkTagsEnabled(middleware))throw new IllegalArgumentException("Native Responses cannot safely apply configured think-tag rewriting");
        String limitName=body.has("max_completion_tokens")?"max_completion_tokens":"max_tokens";
        if(body.has(limitName)) {
            int cap=body.optInt(limitName,0);
            int original=body.optInt("max_output_tokens",0);
            if(cap>0 && (original<=0 || cap<original))body.put("max_output_tokens",cap);
            body.remove("max_tokens");body.remove("max_completion_tokens");
        }
        if(guard.enabled && guard.maxOutputTokens>0) {
            int original=body.optInt("max_output_tokens",0);
            if(original<=0 || original>guard.maxOutputTokens)body.put("max_output_tokens",guard.maxOutputTokens);
        }
        transform(body,session,privacy,middleware,false);
        JSONObject guardInput=new JSONObject(body.toString());
        if(body.optInt("max_output_tokens",0)>0)guardInput.put("max_tokens",body.getInt("max_output_tokens"));
        ContextGuard.Result checked=ContextGuard.fit(guardInput,guard);
        if(checked.error!=null || checked.budgetExceeded || checked.changed)
            throw new IllegalArgumentException("Native Responses exceeds configured context/body budget; request was not sent");
        return new Prepared(body,checked);
    }
    private static Object transform(Object value,PrivacyMasker.Session session,PrivacyMasker.Config privacy,
                                    JSONObject middleware,boolean protocol) throws Exception {
        if(value instanceof String) {
            String original=(String)value;
            String masked=PrivacyMasker.maskText(original,session,privacy);
            String hit=Middleware.hitWord(original,middleware);
            if(hit!=null && Middleware.wordGuardBlocks(middleware))throw new IllegalArgumentException("Native Responses rejected by word guard");
            if(protocol && (!original.equals(masked) || hit!=null))throw new IllegalArgumentException("Native Responses protocol field cannot be safely transformed");
            return hit!=null?Middleware.maskWords(masked,middleware):masked;
        }
        if(value instanceof JSONObject) {
            JSONObject o=(JSONObject)value;
            List<String> names=new ArrayList<>();for(Iterator<String> it=o.keys();it.hasNext();)names.add(it.next());
            for(String k:names) {
                boolean control=protocol || java.util.Arrays.asList("model","type","role","id","call_id","tool_call_id","name","previous_response_id","conversation","encrypted_content","image_url","file_id","url").contains(k);
                // Structural objects retain their keys. Sensitive keys cannot be rewritten safely.
                if(PrivacyMasker.checkMatch(k,privacy))throw new IllegalArgumentException("Native Responses key cannot be safely transformed");
                o.put(k,transform(o.opt(k),session,privacy,middleware,control));
            }
        } else if(value instanceof JSONArray) {
            JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++)a.put(i,transform(a.opt(i),session,privacy,middleware,protocol));
        }
        return value;
    }
}
