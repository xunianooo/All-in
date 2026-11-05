# C1 recent-usage routing: local acceptance

This local batch follows the user-reported computer-agent acceptance of provider management and C2 networking. Main remains at released `147b02acfb82189669c39bcb40b3b3706171c102`, version 1.2.13 / code 19. Prior local changes remain in the working tree. No commit, push, release or version increment is authorized by this batch.

## Behavior and scope

Route groups gain an optional fourth strategy, `usage`, displayed as **近期用量优先**. Existing groups and new-group defaults retain `order`. `rotate` and `manual` behavior remains; manual means selected target first, then existing fallback candidates.

Usage sorts actual eligible `provider/model` targets, not API keys or subscription accounts. A target shares its load across all groups and ordinary explicit-provider calls. Commercial credential rotation, per-key cooldown, target fallback and attempt/time budgets remain responsible for their existing decisions. Target selection and reservation are atomic; candidate-list inspection does not reserve or advance a credential position. Disabled providers and cooling commercial credentials are filtered, and noncommercial resting targets remain last-resort candidates. Equal loads retain configured order. Each distinct target is tried at most once in a usage group, with existing Key retries within that target.

The load is the sum of exponentially decayed successful trusted token usage and `1024 × in-flight attempts`. The half-life is one hour, evaluated lazily with a monotonic clock; there is no background polling/thread. Known input/output dimensions count, partial records count only known dimensions, independently known Anthropic cache read/write count separately when input excludes cache. OpenAI cached input is already included and is not added again; reasoning is not added over output. Estimated dimensions do not count; a successful attempt with no positive trusted quantity contributes 1. Failed, cancelled, interrupted or rejected attempts contribute no successful usage, even when an error reports tokens.

This is a lightweight scheduling preference, not a provider balance/quota predictor, price optimizer, hard rate limit or reproduction of Magpie's full quota-aware strategy. It is memory-only and starts empty after process restart; historical ledger files are neither replayed nor rewritten. A service recreation within the same process shares existing memory. Ordinary dispatched requests contribute load even before a group enables usage. Direct ZCode gift shortcuts and special legacy/native entry points that bypass ordinary dispatch are outside this scheduler; selecting a ZCode target through an ordinary group follows the group's existing normal provider path, not a newly added gift fallback.

State has at most 4096 distinct tracked targets. Old idle entries can be evicted; active entries are never evicted. If all entries are active and an unseen usage target cannot reserve a slot, that usage request returns 503 without forwarding. Ordinary direct/order/rotate/manual requests continue without optional tracking when capacity is exhausted.

## Completion and lifecycle

Each actual attempt owns an idempotent lease. Nonstream completion follows the existing successfully constructed response/usage row boundary; it does not claim delivery of every final nonstream byte. A failed attempt releases before Key retry or target fallback; attempt-0 logical-failure markers never carry a lease.

Stream success requires both a successful usage/protocol terminal result and successful HTTP framing/final flush. Either can arrive first, including asynchronous DeepSeek workers; until both succeed the in-flight reservation remains. Failure, body-read error, client reset, unsent response close and final-write failure release without adding successful load. Duplicate callbacks do not add load twice. These scheduler rules do not retroactively redefine every ledger row as fully delivered: some established nonnative ledger paths still record at read completion.

`UsageLedger.Row.recentUsageLease` is transient and explicitly excluded from serialized records. `UsageLedger.record` settles the in-memory attempt before disk/wait handling so ledger I/O failure cannot strand a scheduling lease. Existing totals/history are otherwise unchanged.

`AllInTrackedResponse.WriteCompletion` has an optional start notification. It distinguishes an unsent body close from NanoHTTPD's normal body close before its final flush. Completion notifications survive Responses projection wrappers. OpenAI-to-Anthropic conversion now captures usage and records one terminal row on success/error/cancel; otherwise that path would never complete a stream lease. Antigravity's parent dispatch marks a successful streaming handoff rather than prematurely committing and later recording the same attempt again. Prebuffered SSE responses retain the delivery gate even when their row is ready before sending.

## Relevant files

- New `RecentUsageScheduler.java`: immutable target identity, lazy decay, bounded state, atomic pick/reserve and double-gated stream completion.
- `GatewayServer.java`: accepts `usage`, preserves configured ties, atomically selects actual targets, attaches leases, forwards completion through protocol projection and closes all attempt lifecycles.
- `UsageLedger.java`: transient lease and record callback only; no schema rewrite or new fake token totals.
- `fi/iki/elonen/AllInTrackedResponse.java`: start/completion forwarding without changing HTTP framing.
- `dashboard.html`: fourth strategy, explanatory details, preserves usage in group drafts/saves, and constrains long native select labels inside narrow cards.
- New `RecentUsageSchedulerOfflineTest.java`, `RecentUsageRoutingHttpTest.java`, `tests/dashboard-usage-routing.cjs`; Gradle and CI retain all previous gates and register the additions.

## Validation and evidence

Final exact job IDs, APK hash and device confirmation are recorded in the local maintenance artifact/handoff, not inferred from an older build. Required gates are clean + 39 JVM suites, all existing HTTP fixtures plus `recentUsageRoutingHttpTests`, Debug/Release/lint and nine independent Node regressions.

Core tests use an injected clock for exact half-life/backward-clock behavior, concurrent reservations, token/cache/source normalization, overflow-sized counters, capacity/eviction, idempotence, interrupted ledger waits, serialization and both stream gate arrival orders.

Routing tests use real loopback HTTP and synthetic providers for usage API acceptance, ordinary direct preload, lower-load selection, partial/cache/unknown usage, reported-token errors not counting, Key relay, fallback attribution, concurrent held streams, OpenAI/native Anthropic and both projection directions, truncated streams, downstream reset, never-sent close, final-flush failure, configured ties, nonmutating Key probing and full-capacity compatibility. Prebuffered SSE tests exercise the production transport wrapper with a ready synthetic row; they are not live Qoder/CatPaw service tests. Direct Antigravity/provider accounts were not exercised against paid/external services.

Independent Android `com.gaeul.allin.batchaudit` uses production sources/assets and only synthetic configuration. Its C1 audit verifies persisted strategy selection, polling, light/dark 360 CSS-pixel layout, lower-load target selection, shared usage across groups, unchanged order preference and concurrent held streams splitting across targets and finishing. Native Android select interaction is additionally verified with GUI tools; programmatic DOM change checks alone are not called real touch. Network/provider regressions remain available in that fixture. The user's real route strategy is not changed for testing.

Reproduce from project root (JDK 17, matching Android SDK, Node):

```sh
./gradlew clean offlineTests streamingHttpTests setupKeyHttpTests assembleDebug lintRelease
node tests/dashboard-background.cjs
node tests/dashboard-setup-key.cjs
node tests/dashboard-routing-audit.cjs
node tests/dashboard-exposed.cjs
node tests/dashboard-heatmap.cjs
node tests/dashboard-live-status.cjs
node tests/dashboard-provider-management.cjs
node tests/dashboard-network.cjs
node tests/dashboard-usage-routing.cjs
```

Windows uses `gradlew.bat`. Remote CI has not run for this uncommitted work. Real provider balance, billing or all vendor networks are not implied by synthetic success. Other C/D roadmap items remain out of scope.

## Final local run

`job_345322b4` completed the full clean 39-suite JVM gate, all old HTTP groups and C1 routing HTTP tests, Debug/Release/lint and final audit APK build. Nine Node regressions passed; lint has 94 Warning and no Error/Fatal. Final Android combined C1/provider/network run `job_e5ed5b9e` passed, as did all 16 native network checks. Native Android strategy selection saved `usage`; the strategy select stays inside its card after the narrow-layout correction.

The installed local APK is `AllIn-1.2.13-Gaeul-v19-local-recent-usage.apk`, 2569488 bytes, SHA256 `7c9a075323f949b68f162be304487f7939355dd99d6dcfc33b8019fb1f978491`, same Gaeul v2/v3 signing certificate. Installed base.apk matches; configuration hashes immediately before/after and final startup match; health/network endpoints return 200 and the foreground service remains specialUse. The real user's group strategies were not changed. Audit package removed, ports 18765/18767/18768 closed. User-reported computer acceptance applies to the preceding provider/C2 batch, not yet to this C1 batch.
