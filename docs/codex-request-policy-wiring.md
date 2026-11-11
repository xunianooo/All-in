# Codex request policy and Gateway wiring

`CodexRequestPolicy.prepareFinalOutbound(body, constraints)` is wired into `GatewayServer` immediately before the Codex Responses request is serialized. It returns a detached body. Native `/responses/compact` retains its original body; it only runs validation and Guard checks.

## Output limits and compatibility

The audited local Magpie source is `/workspace/magpie-upstream-review/internal/provider/codex_request.go`, `codexBody`: it deletes `max_output_tokens` and `max_completion_tokens` and leaves `max_tokens` unchanged. All In follows those exact body semantics. It does not manufacture `max_tokens` from the removed aliases, and it does not reject an ordinary valid Agent request just because the client supplied an output limit. Chat-to-Responses conversion can produce `max_output_tokens`, which is then removed in the same way.

This is a compatibility contract, not evidence that ChatGPT enforces the client's output cap. Requests with an explicit client cap receive `X-AllIn-Compatibility-Warning: codex-output-limit-not-guaranteed`. The original client cap is captured before middleware/Guard for diagnostics. Backend output can exceed an advisory client limit; deleting a field or retaining `max_tokens` cannot prove otherwise.

A positive, enabled gateway Context Guard `maxOutputTokens` setting is treated separately as an explicit guarantee requested by the operator. Since this backend cannot guarantee enforcement, Codex returns a local HTTP 400 before opening the upstream connection, with an explanation naming Context Guard and the option to set its output cap to zero or choose another provider. This applies even when the client asks for fewer tokens than the configured Guard cap. Disabling Guard removes this guarantee. Guard byte/context-budget checks remain active independently.

`OutputConstraints.guardAppliedLimit` represents an output cap requiring enforcement. The Gateway now supplies the actual enabled configured cap, not only `ContextGuard.Result.appliedMaxOutputTokens`, because a smaller client value would otherwise conceal an explicitly configured guarantee. `fromGateway(...)` remains available for callers that only need actual-applied provenance. Malformed/nonpositive supplied limits still fail local validation.

## Transport and session boundaries

The body `prompt_cache_key` supplies both hyphenated `session-id` and `thread-id`; without it, the inbound hyphenated headers are preserved. Legacy underscored session headers are not sent. Header injection validation remains active.

After the write starts, an interrupted/unknown execution does not trigger another request to the same account or another route. An explicit known model rejection remains eligible for route fallback. These protections are unchanged by the output-cap compatibility fix.

The finalizer also removes unsupported sampling, previous-response, user, safety, and prompt-cache-options fields; permits `service_tier=priority|ultrafast`; forces `stream=true` and `store=false`; supplies instructions/default text/tool fields; normalizes reasoning; and converts string input to a Responses message. This is not a port of all Magpie tool/history adaptation.

## Verification and evidence limits

Focused loopback tests cover ordinary client limits through Chat, Responses, native Codex Responses, and compact with both client stream modes; exact retained/removed limit fields; explicit Guard rejection including a smaller client limit; native image/encrypted-reasoning preservation; session headers; incomplete socket/SSE execution without replay; and known model rejection fallback. The compact mock returns JSON compaction data rather than an SSE response. These mocks do not establish real backend output-cap support.

Read-only Android inspection found the installed package `com.gaeul.allin`, but its reported `/data/user/0/com.gaeul.allin` data directory is absent in this execution namespace. No live client ingress records were obtained; no client names, request field distributions, secrets, or message bodies are inferred from that absence.

```sh
ANDROID_HOME=/workspace/gradle-lab/toolchain/android-sdk ./gradlew \
  :app:offlineTest_CodexRequestPolicyOfflineTest \
  :app:offlineTest_CodexRefreshOfflineTest \
  :app:offlineTest_CodexPersistenceOfflineTest \
  :app:offlineTest_ResponsesGatewayOfflineTest \
  :app:offlineTest_ResponsesAgentRequestOfflineTest \
  --no-daemon -Dorg.gradle.native=false -Dorg.gradle.vfs.watch=false \
  -Pandroid.aapt2FromMavenOverride=/workspace/gradle-lab/native-tools/aapt2
```
