# Protocol tool contract and bounded diagnostics — local batch 1

Continues the computer-accepted local R2 closeout on formal baseline d4c2aad, version 1.2.14/code20. No version change, commit, push or release is authorized. Existing R2 changes are retained.

## Contract

Chat/Responses/Messages conversion preserves opaque existing tool-call IDs, names, arguments and result references. Missing/blank/nonstring inbound references and missing or duplicate output identity fail rather than invent positional IDs. Chat delta fields concatenate per exact nonnegative integer tool index; Responses cumulative item snapshots reconcile by prefix and reject conflicting identity. Two-tool interleaving, late identity, repeated legitimate fragments and reverse-order results have replay coverage.

Chat-to-Responses defers function items until finish because Responses cannot revise already published identity using name/ID deltas. Its added/done/final identities agree; text/reasoning/refusal remain paced. The deferred tool budget is 1024 tools and 1 MiB characters including identity/arguments, not a UTF-8 byte cap. Native Responses arguments keep their prior streaming behavior. This changes tool argument latency on converted Chat-to-Responses; it does not promise incremental execution before identity completion.

Messages requires object input. Strict JSON validation rejects permissive org.json extensions, incomplete objects, nonobjects and nesting beyond 128 levels, without replacing arguments with {}. Valid Chat/Responses argument strings remain unchanged; Messages JSON object formatting can change but values are preserved. Index conversion uses BigDecimal.intValueExact rather than double rounding. Signature/thinking/redacted/encrypted policies remain unchanged. Native pass-through adapters and all provider-specific malformed payloads are not claimed universally validated. Native Responses-to-Chat accumulated arguments retain the existing resource-boundary limitation.

A received successful upstream response that cannot be converted into Messages is a protocol failure: HTTP502 for nonstream, no automatic fallback or patience replay. Committed streams use existing protocol failure semantics, never fake success.

## Diagnostics

The existing usage row/JSONL owns an immutable diagnostic snapshot; no separate event ledger, post-record mutation or second logical terminal is introduced. /api/sessions and /api/usage recent share a whitelist projection: schemaVersion, requestedModel, target(provider/model), logicalDurationMs, queuedMs, patienceWaitMs, failureKind, responsePhase, executionState, deliveryConfirmed and rulesVersion.

Target means configured routing target, not a supplier-confirmed execution model. Times are monotonic deltas through row settlement: queue is outbound-admission elapsed including checks, patience is actual waiting, logical duration ends at settlement and may precede final writer completion. Fields unsupported by old rows or uncovered channels are null, never inferred from legacy zero counters. The response milestone preserves trustworthy headers across terminal cancellation; the generic observer includes chunk framing so this batch does not call it diagnostic BUSINESS.

Execution facts not_sent/rejected/unknown_execution/delivered are diagnostic only, never replay authorization. Commercial handoff and finite structured rejection evidence are observed; special adapters with no boundary observation stay null. Only existing NativeAnthropic terminal-validator + final-writer success callback can confirm delivered here. Early-settled Chat/converted/native Responses paths do not become delivered merely from HTTP200. rulesVersion remains null until an actual request-scope rules engine exists.

New diagnostics exclude prompt, raw errors, session, credential/account identifiers, URLs and headers. Existing historical ledger fields are not redefined as sanitized. Recent requests are ledger rows, not complete logical groups: limit can omit prior attempts. The dashboard exposes a collapsible diagnostic view, explicitly labels unknown/partial usage and unknown execution, escapes identifiers, preserves prior data on refresh errors, and never colors an unconfirmed HTTP200 as delivered.

## Verification

25 two-bridge contract groups; strict syntax/index negative replay (13 previously accepted malformed inputs now rejected); 26 loopback tool-history failover groups covering 18 protocol/stream combinations, two late-error no-splice cases, and six unknown-execution no-replay cases. Diagnostic tests cover immutable persistence/reload, old nullable data, negative monotonic origin, queue failures, patience waits, native writer evidence, one logical terminal, no prompt/session/secret projection, and Messages502 with first upstream once/backup zero.

All 46 JVM offline suites and existing/new HTTP gate, 12 standalone dashboard suites and Debug/Release/lint run locally. Initial streaming gate caught a previous expectation of early converted Responses tool arguments; its fixture now explicitly tests deferred tool integrity while retaining text pacing/native streaming requirements. Final outcomes, exact jobs, Android checks and APK hashes are kept in maintenance PROTOCOL-DIAGNOSTICS-HANDOFF.md/artifact-local-protocol-diagnostics.json. No local test substitutes for a real paid supplier, full ETA Agent tool roundtrip or new computer acceptance.
