package com.gaeul.allin;

import java.util.Locale;
import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;

/** Pure policy for the CN Claude Messages route. No network calls or retry state. */
public final class WorkBuddyNativePolicy {
    public static final String URL = "https://copilot.tencent.com/v1/messages";
    private WorkBuddyNativePolicy() {}

    /** model is the resolved upstream model id, without the provider prefix. */
    public static boolean useNative(String provider, String model) {
        return "workbuddy".equals(provider) && model != null
                && model.toLowerCase(Locale.ROOT).startsWith("claude-") && model.length() > 7;
    }

    /** Caller supplies the same DSH decision used for its current chat path. */
    public static JSONObject prepare(JSONObject input, boolean hideDsh, WorkBuddyHarnessPolicy.Report report) throws Exception {
        return prepare(input, hideDsh, report, null);
    }

    /**
     * Prepares the final native Messages request. It relays what the client asked for: unsigned
     * thinking blocks are dropped and no reasoning is invented. A Messages body is not a chat on
     * WorkBuddy's side, so the model's configured default effort is never injected here — the
     * plugin applies it to chats only (`const completion = !native && isCompletion(...)`,
     * opencode-workbuddy-auth index.mjs:3837-3838, withDefaultEffort:3395-3402), and the vendor's
     * own Claude models take thinking adaptively from what the request names.
     *
     * <p>modelMetadata is the selected model's catalog entry, as the gateway holds it
     * (provider modelMeta, keyed by model id, carrying /v3/config's maxOutputTokens). Anthropic's
     * API needs a reply limit, and magpie fills one the client did not name with the model's own
     * (fillsOutput: "to == Anthropic" always, then askedOutput;
     * internal/gateway/maxoutput.go:263-268 and gateway.go:7583-7592). A request that names one is
     * left exactly as it came.
     */
    public static JSONObject prepare(JSONObject input, boolean hideDsh,
            WorkBuddyHarnessPolicy.Report report, JSONObject modelMetadata) throws Exception {
        if (input == null || input.optJSONArray("messages") == null) throw new IllegalArgumentException("missing messages");
        JSONObject out = new JSONObject(input.toString());
        if (hideDsh) {
            WorkBuddyHarnessPolicy.Report r = WorkBuddyHarnessPolicy.apply(out);
            JSONArray before = input.optJSONArray("messages"), after = out.optJSONArray("messages");
            for (int i = 0; i < before.length(); i++) {
                JSONObject message = before.optJSONObject(i);
                if (protectedMessage(message) && !message.toString().equals(after.optJSONObject(i).toString()))
                    throw new IllegalArgumentException("DSH rewrite would modify protected history");
            }
            if (report != null) { report.messages = r.messages; report.descriptions = r.descriptions; }
        }
        removeThinking(out, true);
        if (!(out.opt("max_tokens") instanceof Number && out.optInt("max_tokens", 0) > 0)) {
            int own = catalogOutputLimit(modelMetadata);
            if (own > 0) out.put("max_tokens", own);
        }
        out.put("stream", true);
        return out;
    }


    /**
     * Resolve a single model entry from the raw WorkBuddy /v3/config response.
     * Returns null when the id or config is absent, so callers can safely fail open.
     */
    public static JSONObject modelMetadata(JSONObject config, String modelId) {
        if (config == null || modelId == null || modelId.isEmpty()) return null;
        JSONObject data = config.optJSONObject("data");
        JSONArray models = data == null ? config.optJSONArray("models") : data.optJSONArray("models");
        if (models == null) return null;
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.optJSONObject(i);
            if (model != null && modelId.equals(model.optString("id", ""))) return model;
        }
        return null;
    }

    /**
     * The model's own output limit from the vendor catalog, read in the same order the gateway's
     * own filler reads it (GatewayServer.catalogOutputDefault: maxOutput, then output, then
     * maxOutputTokens for this provider), so this never picks a different number than the fallback
     * it stands in for. /v3/config names the field maxOutputTokens.
     */
    private static int catalogOutputLimit(JSONObject metadata) {
        if (metadata == null) return 0;
        for (String key : new String[]{"maxOutput", "output", "maxOutputTokens"}) {
            long value = metadata.optLong(key, 0);
            if (value > 0 && value <= Integer.MAX_VALUE) return (int) value;
        }
        return 0;
    }

    /** Converts the gateway's Chat projection into Anthropic Messages for Claude models. */
    public static JSONObject fromOpenAIChat(JSONObject chat, String model, JSONObject modelMetadata,
            boolean hideDsh, WorkBuddyHarnessPolicy.Report report) throws Exception {
        if(chat==null || model==null || !model.toLowerCase(Locale.ROOT).startsWith("claude-"))
            throw new IllegalArgumentException("not a WorkBuddy Claude chat request");
        JSONObject openAi=new JSONObject(chat.toString());
        openAi.put("model",model);
        JSONObject prepared=WorkBuddyRequestPolicy.transform(openAi,false,null);
        prepared.put("model",model);
        // Structural Chat→Messages projection only: Claude effort semantics are applied below,
        // never ZCode/MiniMax variant budgets. The model's catalog output limit is the default the
        // vendor's own client sends; the 16384 translation fallback is only for an unknown model.
        JSONObject nativeBody=ZCodeAnthropic.toAnthropicStructural(prepared,catalogOutputLimit(modelMetadata));
        nativeBody.put("model",model);
        Object explicit=chat.opt("reasoning_effort");
        if(!(explicit instanceof String)) {
            JSONObject reasoning=chat.optJSONObject("reasoning");
            if(reasoning!=null) explicit=reasoning.opt("effort");
        }
        JSONObject originalOutput=chat.optJSONObject("output_config");
        JSONObject originalThinking=chat.optJSONObject("thinking");
        if(originalOutput!=null) nativeBody.put("output_config",new JSONObject(originalOutput.toString()));
        if(originalThinking!=null) nativeBody.put("thinking",new JSONObject(originalThinking.toString()));
        else if(originalOutput!=null && originalOutput.has("effort")) nativeBody.put("thinking",new JSONObject().put("type","adaptive"));
        else if(explicit instanceof String) {
            String effort=(String)explicit;
            if("none".equals(effort)) nativeBody.put("thinking",new JSONObject().put("type","disabled"));
            else if(validEffort(effort)) {
                nativeBody.put("thinking",new JSONObject().put("type","adaptive"));
                JSONObject output=nativeBody.optJSONObject("output_config");
                if(output==null)output=new JSONObject();
                if(!output.has("effort"))output.put("effort",effort);
                nativeBody.put("output_config",output);
            }
        }
        return prepare(nativeBody,false,null,modelMetadata);
    }

    /** Resolve metadata saved by the model catalog, which is keyed by model id. */
    public static JSONObject catalogEntry(JSONObject catalog, String modelId) {
        if(catalog==null || modelId==null)return null;
        JSONObject exact=catalog.optJSONObject(modelId); if(exact!=null)return exact;
        for(java.util.Iterator<String> keys=catalog.keys();keys.hasNext();) {
            String key=keys.next(); if(key.equalsIgnoreCase(modelId))return catalog.optJSONObject(key);
        }
        return null;
    }

    private static boolean validEffort(String effort) {
        return "low".equals(effort) || "medium".equals(effort) || "high".equals(effort)
                || "xhigh".equals(effort) || "max".equals(effort);
    }

    /**
     * Compatibility hook for the gateway's existing retry-policy seam. Native Messages
     * requests are never rewritten or retried after signature rejection; null means stop.
     */
    public static JSONObject signatureRetry(JSONObject sent, int status, String error, boolean alreadyRetried) throws Exception {
        return null;
    }

    private static boolean protectedMessage(JSONObject m) {
        if (m == null) return false;
        if (m.has("signature")) return true;
        JSONArray parts = m.optJSONArray("content");
        for (int i = 0; parts != null && i < parts.length(); i++) {
            JSONObject block = parts.optJSONObject(i);
            if (block != null && (block.has("signature") || "redacted_thinking".equals(block.optString("type")))) return true;
        }
        return false;
    }

    private static boolean removeThinking(JSONObject body, boolean unsignedOnly) {
        JSONArray messages = body.optJSONArray("messages");
        boolean changed = false;
        for (int i = 0; messages != null && i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null || !"assistant".equals(m.optString("role")) || protectedMessage(m)) continue;
            JSONArray parts = m.optJSONArray("content");
            if (parts == null) continue;
            JSONArray kept = new JSONArray(); boolean removed = false;
            for (int j = 0; j < parts.length(); j++) {
                Object p = parts.opt(j);
                JSONObject block = p instanceof JSONObject ? (JSONObject)p : null;
                boolean thinking = block != null && "thinking".equals(block.optString("type"));
                Object sig = block == null ? null : block.opt("signature");
                if (thinking && (!unsignedOnly || !(sig instanceof String) || ((String)sig).isEmpty())) removed = true;
                else kept.put(p);
            }
            if (removed) {
                if (kept.length() == 0) kept.put(new JSONObject().put("type", "text").put("text", ""));
                m.put("content", kept); changed = true;
            }
        }
        return changed;
    }
}
