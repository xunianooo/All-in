# WorkBuddy Chat strategy wiring

Pinned comparison source: `/workspace/magpie-community-reference/opencode-workbuddy-auth/source/index.mjs`, package version 0.2.26.

This batch adds WorkBuddy-specific policy classes and tests. It does not edit `GatewayServer.java`, change the gateway's active route, install an APK, or commit.

## Request preparation

Keep the selected model's provider `modelMeta` object and call the adapter at the WorkBuddy Chat request boundary:

```java
WorkBuddyChatPolicy.Prepared workbuddyChat = WorkBuddyChatGatewayAdapter.prepare(
        target.providerId, payload, selectedProviderModelMeta, target.upstreamModel);
payload = workbuddyChat.body();
```

The adapter accepts `workbuddy` and `workbuddy-ai`. Call it on the original Chat body before generic WorkBuddy request normalization so an object `tool_choice` remains available; it also maps a named string choice if an earlier compatibility layer has already normalized it. The catalog argument is the existing model metadata map keyed by model ID, such as `target.catalogMetadata`; the adapter selects `target.upstreamModel` internally. It adds `reasoning_effort` from `reasoning.defaultEffort` or `reasoning.effort` only when the request does not specify effort, the selected metadata supports that effort, and the output budget is greater than 1024. Explicit effort and small-budget requests are kept as supplied.

The returned `Prepared` object owns the request's name maps. Tool function names are made WorkBuddy-safe with collision suffixes, schemas follow the 0.2.26 fitting rules, and tool choices, assistant tool-call history, and tool-result names follow the renamed tool. Do not put the maps in shared provider state.

## Response restoration

For a buffered JSON or SSE body, pass the same `Prepared` object to `WorkBuddyChatGatewayAdapter.restoreResponse(prepared, contentType, body)`.

For a gateway that has parsed each OpenAI chunk already, call `WorkBuddyChatResponsePolicy.restoreChatObject(chunk, prepared.upstreamToClientNames())` before serialization. It updates only `choices[].message/delta.tool_calls[].function.name`.

For raw incremental SSE, use `prepared.newSseRestorer()` and forward each completed event returned from `feed(chunk, false)`, then send `feed(null, true)` at EOF. It buffers one event (maximum 8 MiB) so JSON can be parsed safely. If there are no renamed names, chunks pass through without buffering.

## Signature rejection seam

The 0.2.26 reference retries a definitive signature rejection by removing assistant thinking blocks. Removing non-empty reasoning changes conversation context; removing signed or redacted blocks changes protected opaque history. This implementation allows a one-time retry candidate only when explicitly enabled, after a complete first HTTP 400 signature rejection, and when the only removable material is an empty unsigned thinking block containing exactly `{type, thinking}`. All other transformations remain a safety refusal.

```java
WorkBuddySignatureRetryPolicy.Session signatureRetry =
        WorkBuddyChatGatewayAdapter.newSignatureRetrySession(target.account, signatureRetryEnabled);
WorkBuddySignatureRetryPolicy.Decision d = signatureRetry.decide(
        sentBody, status, completeErrorBody, outcomeUnknown);
if (d.retry) {
    // Perform at most one fresh physical attempt using d.body.
    // The session has already spent its retry budget.
}
```

The returned session owns the one-candidate budget for that logical request. The gateway must send a candidate on a fresh physical attempt and must not bypass the session with another retry. Do not call the seam after an unknown outcome, partial error body, accepted response, or transport error. Accounts with a nonempty `enterpriseId` are skipped. Signed `signature` and `redacted_thinking` history remain unchanged and refused; nonempty or opaque thinking also remains refused because dropping it would be lossy.
