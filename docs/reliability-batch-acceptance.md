# A1 + A2 + B reliability batch: local acceptance

Release **1.2.12 / 18** contains this batch, developed from `12e395271a39fc44bce5036525a487918774ed66`. The earlier same-version local installation was 1.2.11 / 17; release authorization now includes source synchronization, CI and a separately signed release asset. A1 is documented separately in `anthropic-native-a1-acceptance.md`.

## A2 privacy

An installation-scoped random 32-byte key is created lazily in the application's private files directory. HMAC-SHA256 covers type, a NUL separator and the value. Normal placeholders use 96 bits / 24 hex digits. Collisions are checked against request-local reverse maps and expand deterministically; a full digest collision fails rather than overwriting another value. Types have separate identities. The mappings stay in the request and are not persisted. The key helps placeholder stability inside one installation; it does not guarantee a provider prompt-cache hit or restore absent historical mappings.

Key creation uses an interprocess lock, private file permissions, a synced temporary file and rename. Missing/corrupt/unreadable key storage, unavailable crypto, mapping limits and invalid enabled custom regexes fail closed; there is no fixed, MD5 or unkeyed fallback. Privacy disabled still sends the configured unmasked request, preserving the user's setting. Key material never goes into config JSON, logs or build artifacts. Uninstalling/clearing app data loses the key; there is currently no key backup/rotation UI. The directory itself is not fsynced, so the last rename has a sudden-power-loss durability limit.

Each request has bounded mappings (16,384 entries, approximately 1 MiB of stored source characters). Existing placeholder-shaped input is reserved, not remasked; unsafe ambiguity with an existing request mapping fails explicitly. Restoration scans original input once, supports known old mappings and does not recursively expand newly restored text. A1 working sessions copy all collision/reservation metadata. Native signed blocks and their prefix retain A1's reject-on-change policy.

## B1/B2 commercial credentials and accounting

Only ordinary OpenAI, Anthropic and Responses API-key targets use the commercial pool. Known subscription, website, gift and account-backed paths remain outside that pool. Empty account arrays on standard providers are allowed; actual account entries exclude commercial scheduling. String keys and enabled `{key, off/enabled}` entries are filtered and deduplicated. Stable credential IDs use full SHA-256; raw keys are never used as ledger identifiers.

Scheduling has two levels: keep the configured model target order, then try available keys inside that target before route fallback. `maxTargets` still limits model targets. `maxAttempts` defaults to 8 (bounded 1..64); `attemptBudgetMs` defaults to 60,000 (bounded 1,000..300,000). Disabling failover permits one attempt only. The budget stops new attempts and cannot retract a request already in flight.

Key, key+model and provider-wide cooldowns are separate. Deadline extensions take the maximum atomically. Retry-After supports seconds and HTTP dates, saturates huge deadlines and does not sleep. Sending rechecks availability; already-running calls cannot be recalled. All-cooling requests return a readable 429 with Retry-After without touching an upstream. This first batch keeps cooldowns in memory, has no half-open probing and does not refresh subscription account pools.

Confirmed authentication, rate and recognized balance refusals may try another credential. Explicit organization/provider-wide limits avoid hammering every key. Invalid/context/model request errors do not churn keys. Ambiguous network/read failures do not expand replay, and streams already handed off never switch keys. Generic commercial POSTs use fixed-length streaming and disable redirects to stop lower-level buffered POST replay. Unknown generic 5xx responses remain conservative; only recognized shared outage responses permit route fallback.

Every dispatcher request has a logical request ID. Each actual attempt retains its ordinal, credential ID, error and usage. Intermediate failure attempts do not increment logical request count. Final failure has a separate attempt-0 terminal marker, which is not an upstream attempt. A successful final response or stream owns its terminal attempt. Request/failed counts, attempts/attemptFailed, daily heatmap, provider/session/hourly summaries and CSV use the same definitions. Old rows have no reliable linkage and retain their legacy semantics; historical files are never rewritten to guess deduplication.

Usage source is known, partial, estimated, unknown or legacy. Input/output availability is separate: missing usage is not presented as measured zero. Known usage on failed attempts is retained. Cache coverage is explicit; hit-rate denominator is protocol-aware: OpenAI cached tokens are an input subset, while Anthropic cache read/write are included in its normalized cache-input denominator. This does not add cache tokens again to the dashboard's existing input+output total. These figures are not a provider invoice and do not implement monetary hard limits.

OpenAI stream cancellation now commits one failed terminal row and closes the upstream. A1 native Anthropic also waits for HTTP framing/final write confirmation. Existing non-native streams still primarily classify completion from upstream finish events; they do not prove that a remote application consumed the complete response. A process crash can leave intermediate attempt rows without a terminal; the ledger exposes attempts and does not invent a recovered outcome.

Upstream error reads are limited to 64 KiB. Structured JSON is parsed before credential removal, including escaped string values; unstructured, truncated or read-failed errors become fixed redacted envelopes. Raw commercial connection exceptions are not logged or returned. Retry-After forwarding is bounded and checked.

## B3 limited Zhipu envelope adapter

The adapter accepts only the exact trusted hosts `open.bigmodel.cn` and `api.z.ai`, approved `/api/v1` Responses and `/api/monitor` paths, 2xx JSON, non-SSE and uncompressed/identity encoding. A strict Boolean `success:false` plus business code/message is normalized before response handoff. The business code is preserved for diagnosis and retry classification. Normal bodies retain bytes; large bodies replay the inspected prefix, and a read exception reappears after the prefix rather than being swallowed.

The common `/api/paas/v4/chat/completions` path is not broadly scanned. There is no new monitor caller, global `success:false` heuristic, SSE lookahead or subscription/gift retry. No paid supplier was dynamically queried; B3 is verified using synthetic fixtures and the inspected upstream implementation, not all GLM deployments.

## Tests and device evidence

```sh
python3 /workspace/allin-package-maintenance/build.py --offline clean offlineTests streamingHttpTests setupKeyHttpTests assembleDebug assembleRelease lintRelease
node tests/dashboard-background.cjs
node tests/dashboard-setup-key.cjs
node tests/dashboard-routing-audit.cjs
node tests/dashboard-exposed.cjs
node tests/dashboard-heatmap.cjs
```

`offlineTests` has 32 suites (26 existing, two A1, four A2/B). `streamingHttpTests` depends on the native Anthropic and commercial failover HTTP matrices. Tests exercise same-model key relay, route ordering, failover disabled, budgets, cooling, failure classification, error usage, unknown execution without replay, stream truncation/cancellation, exact terminal rows, restarts, source coverage and cross-day ledger consistency. Key-store tests include concurrent child JVM initialization and a fresh reader process. Expected injected cancellation/write errors may log socket failures while assertions pass.

An isolated Android application (`com.gaeul.allin.batchaudit`) used production Java sources and synthetic configuration. Real device checks passed: 429 key relay, email privacy roundtrip, no implicit POST replay after a dropped connection, immediate all-cooling responses, logical usage, and key file size/permissions (32 bytes, 0600). Its gateway/mock ports were 18765/18766. The test app was uninstalled and its mock stopped. Its source and results remain outside the main repository under `/workspace/allin-package-maintenance/batch-audit`; no user provider credentials were copied.

The final production APK must be verified against the existing Gaeul v2/v3 certificate, installed with replacement, and checked against the immediately preceding config hash, APK hash, health endpoint and specialUse foreground-service state. Local APKs stay separate from the published 1.2.11 asset. C/D features and subscription multi-account scheduling are outside this acceptance batch.
