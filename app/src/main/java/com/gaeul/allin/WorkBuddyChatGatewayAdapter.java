package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;

/** Request-local wiring surface for WorkBuddy Chat gateways. */
public final class WorkBuddyChatGatewayAdapter {
    private WorkBuddyChatGatewayAdapter() {}

    public static boolean supports(String providerId) {
        return "workbuddy".equals(providerId) || "workbuddy-ai".equals(providerId);
    }

    /**
     * Prepares one outbound Chat body with selected-model metadata. Keep this returned
     * object with the logical request and use it to restore tool names on its response.
     */
    public static WorkBuddyChatPolicy.Prepared prepare(String providerId, JSONObject chat,
            JSONObject modelCatalog, String selectedModel) throws Exception {
        if (!supports(providerId)) throw new IllegalArgumentException("not a WorkBuddy Chat provider");
        JSONObject metadata = WorkBuddyChatPolicy.catalogEntry(modelCatalog, selectedModel);
        return WorkBuddyChatPolicy.prepare(chat, metadata);
    }

    /** Restores names in a complete Chat response or SSE body for the same request. */
    public static String restoreResponse(WorkBuddyChatPolicy.Prepared prepared,
            String contentType, String response) {
        return WorkBuddyChatResponsePolicy.restore(prepared, contentType, response);
    }

    /** Enterprise WorkBuddy accounts are intentionally excluded from signature retries. */
    public static boolean isEnterpriseAccount(JSONObject account) {
        return account != null && !account.optString("enterpriseId", "").trim().isEmpty();
    }

    /**
     * Gateway retry seam. The returned request-local session issues at most one retry
     * candidate; the caller must send that candidate on a fresh transport attempt.
     */
    public static WorkBuddySignatureRetryPolicy.Session newSignatureRetrySession(
            JSONObject account, boolean retryEnabled) {
        return WorkBuddySignatureRetryPolicy.newSession(retryEnabled, isEnterpriseAccount(account));
    }
}
