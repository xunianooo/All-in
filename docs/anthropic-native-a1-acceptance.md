# A1: Native Anthropic streaming fidelity

Implemented against main `12e395271a39fc44bce5036525a487918774ed66` and included in release 1.2.12 / 18. Earlier local validation used 1.2.11 / 17; see `reliability-batch-acceptance.md` for the combined release scope.

`/v1/messages` routed to an Anthropic provider now uses `NativeAnthropicStream` directly rather than translating native SSE through OpenAI Chat. MiniMax's native Messages output uses the same stream. Generic Anthropic preserves the inbound `anthropic-version` and `anthropic-beta` protocol headers; credentials still come exclusively from the configured target.

The stream preserves thinking, signature deltas, redacted thinking, block indexes, event order, compatible unknown fields and unknown events. Unmodified frames retain their bytes, including multiline data, CRLF, comments and SSE metadata. Input uses 8192-byte bulk reads, with a 4 MiB frame bound and at most 65,536 block indexes per stream. It does not aggregate the complete answer. Bare CR line endings may wait for the next byte to distinguish CRLF. A nonempty initial `message_start.content` is explicitly rejected rather than silently reconstructed.

An upstream error is forwarded once. EOF, malformed known events, incomplete blocks, missing stop reason, oversize frames and interrupted streams emit an error without inventing `message_stop`. Once forwarding starts, the stream does not select another key or splice another response. HTTP admission stays held through final framing and flush. Native usage is captured from upstream message events, committed once, and marked failed on interruption/cancellation or a detected downstream write failure. A successful local socket write cannot prove that the remote application consumed the answer.

## Signed history and policy boundary

Native Messages requests use field-aware privacy processing. Thinking/signature/redacted/unknown blocks remain indivisible: a privacy match that would change them fails before an upstream request. A changed system/tools or message prefix through the last protected block also fails. Ordinary subsequent user text can still be masked. Matching sensitive object keys or numeric tool data is rejected when masking would alter JSON structure or types. Tool names and schema structure are preserved or rejected on a policy match. Schema descriptions and annotations can be masked. Image sources remain opaque, as with the existing image support; this is not image-content redaction.

Signed/unknown history requires an unchanged, guard-approved native request. Middleware/history pruning, invalid tool pairing or a removed/increased output limit cannot silently fall back to a lossy projection. A non-Anthropic route returns a readable 400; the ZCode gift adapter does not receive protected history through its Chat projection. Routing can replace the target model and lower output limits, but no signature is fabricated, verified locally or reassigned to another model. The upstream remains responsible for validating a signature against the chosen model and history.

Response privacy restoration applies only to known ordinary text and tool input. Thinking, signature, redacted data and unknown fields remain exactly as supplied by the upstream; a placeholder in thinking deliberately stays a placeholder. Replaying a locally restored transcript may require a fresh unsigned conversation if a later signed prefix would need changing. The gateway does not retain a cross-request signature cache or recover vanished privacy mappings.

Native Messages output with enabled response word-guard or think-tags is rejected before contacting the upstream. This conservative limitation avoids silently bypassing response transforms or editing signed/opaque content. It applies even when a particular response might not have needed a transform. Normal request model mapping and compatible output caps remain available.

OpenAI reasoning has no native Anthropic signature. Existing bridge output retains its compatibility behavior, with `X-AllIn-Compatibility-Warning: thinking-signature-unavailable`; it must not be treated as a signed native transcript. Real native responses carry `X-AllIn-Native-Anthropic: true`. An unknown data event that would pass a partially held privacy placeholder fails explicitly rather than reordering content.

## Verification

Run the complete local gate with the configured signing environment:

```sh
python3 /workspace/allin-package-maintenance/build.py --offline clean offlineTests streamingHttpTests setupKeyHttpTests assembleDebug assembleRelease lintRelease
node tests/dashboard-background.cjs
node tests/dashboard-setup-key.cjs
node tests/dashboard-routing-audit.cjs
node tests/dashboard-exposed.cjs
node tests/dashboard-heatmap.cjs
```

`offlineTests` retains the existing 26 suites and adds native stream and policy suites. `streamingHttpTests` also depends on `nativeAnthropicHttpTests`. The native HTTP fixture uses real loopback sockets and the production gateway pipeline, including production gzip selection, a gated first thinking event, multiple signature deltas, empty thinking, redacted blocks, text/tool mixtures, unknown events, repeated requests, tool-result history, request protocol headers, native nonstream output, privacy restoration, guard/policy rejection, abrupt EOF, upstream errors, client cancellation, usage and admission release. Separate response tests inject failure on the final chunk and flush.

Expected cancellation and injected write failures produce NanoHTTPD error logs during tests; they must accompany passing assertions, not be interpreted as successful HTTP delivery.

All test data and keys are synthetic. No paid upstream call is used. These fixtures do not establish compatibility with every online Claude/Beta/provider version. Device installation must use the matching Gaeul certificate, compare config hashes before/after and retain the same version. Keep local APKs separate from the published 1.2.11 release asset.
