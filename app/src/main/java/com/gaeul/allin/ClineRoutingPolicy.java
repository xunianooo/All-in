package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;

/** Default request routing constraints for the official Cline endpoint. */
final class ClineRoutingPolicy {
    private ClineRoutingPolicy() {}

    static void apply(JSONObject payload, String model, String exactUrl) {
        // Cline rejects client-supplied providerOptions routing (HTTP 403).
        // Upstream pinning is opt-in; without an explicit Gateway setting, leave
        // both caller routing and the provider's own routing untouched.
    }
}
