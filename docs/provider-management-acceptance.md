# Configured provider management (local work)

Based on released 1.2.13 / versionCode 19 (`147b02a`). This change is local until a separate release instruction; it does not change the version or publish an APK.

The existing Providers bottom-navigation entry now opens a compact **configured-provider list**. Search matches only provider ID/name. Providers with credentials remain listed when disabled, under a collapsible disabled section. A separate Add view reuses the original login/import/API-key controls; opening a configured provider reuses its original detail card. There is no extra bottom-navigation item and no automatic paid connectivity test triggered by list navigation.

Configured means at least one nonempty string/object API key or an account with a recognized credential field. It does not establish authentication validity, remaining quota or online health. Keys are deduplicated for the overview, including disabled entries. Models are configuration counts, not a promise that all models are callable. No token prefixes, full account identity or invented subscription plan appears in the compact list. Existing account management defaults to anonymous numbered labels rather than credential prefixes and unverified ACTIVE badges.

Ordinary commercial providers, including eligible custom providers, have a password-type key editor. String-key drafts are separate from authoritative configuration and survive polling/navigation; they do not make an unsaved provider appear configured. Existing structured key objects and their disabled/metadata fields are retained unchanged. The current editor does not modify structured key entries. A subsequent edit while saving remains an unsaved draft. Details for an unusual account-backed provider without a built-in card provide account management and a read-only model summary rather than claiming an unsupported login/configuration flow exists.

## Scoped writes and enablement

`POST /api/providers/enabled` accepts exactly `{providerId, enabled}` with a string ID and Boolean enabled. `POST /api/providers/keys` accepts exactly `{providerId, keys}` for ordinary commercial providers; strings and supported structured key entries are validated. Success responses contain status/ID and an enabled value or raw saved-entry count, never credentials. These endpoints modify only the selected field on the current provider object, preserve account/key metadata, and roll back the field if atomic persistence fails. Unknown IDs, duplicate IDs, invalid input and unsupported types return explicit errors.

The UI no longer posts a complete configuration to change a provider's keys or switch. Pending writes disable the switch and wait for acknowledgement. Unknown outcomes are read back, not blindly replayed. Failed readback leaves a visible unknown state and a reread action. Configuration request revisions and per-provider write tracking prevent older poll responses from overwriting current actions. Different provider writes use the same server configuration lock. The existing legacy full-configuration import API remains available; external stale full snapshots are not made conflict-safe by this feature. Account-refresh implementations outside these new writes retain their existing lock boundaries; this batch does not claim a complete transaction redesign.

Disabling affects subsequent requests; already admitted/in-flight work is allowed to finish. Ordinary candidate construction, gift, Codex native and Copilot direct paths check provider status. Codex/Copilot no longer select explicitly disabled accounts on their direct paths, and selected ordinary attempts check provider status again before forwarding. A narrow concurrent switch-versus-send window remains; this is not cancellation of already scheduled network activity.

## Validation

The complete existing Java, HTTP, frontend, build and lint gates are retained. `ProviderEnabledOfflineTest` and `ProviderKeysOfflineTest` cover validation/auth, duplicate/missing IDs, save failure rollback, original object/metadata preservation, concurrent provider writes, disabled native entry points, bare-model fallback and preselected-disabled candidates. The setup HTTP fixture now verifies that a scoped provider-key save persists without altering the gateway key.

```sh
python3 /workspace/allin-package-maintenance/build.py --offline clean offlineTests streamingHttpTests setupKeyHttpTests assembleDebug assembleRelease lintRelease
node tests/dashboard-background.cjs
node tests/dashboard-setup-key.cjs
node tests/dashboard-routing-audit.cjs
node tests/dashboard-exposed.cjs
node tests/dashboard-heatmap.cjs
node tests/dashboard-live-status.cjs
node tests/dashboard-provider-management.cjs
```

The management DOM test includes schema/counting, disabled presence, built-in card identity after logo replacement, list/detail/back/add/search, secret-free overview, switch failures and unknown results, overlapping provider writes, stale config reads, recovery after read failure, ID-based edits, structured key preservation and edits during saving.

An isolated Android application used production sources, synthetic providers and production WebView assets. Real touch interactions verified list-to-detail/back, stopped-group toggle persistence, custom-provider editor, drafts across a real 3-second poll, key save preserving another provider's structured key, Add filtering, and light/dark 360-CSS-pixel layouts without page overflow. It did not change real provider credentials or consume paid model quota. The final APK is to be replacement-installed only after certificate validation, checking production config hashes before/after and service health; test application cleanup is part of acceptance.

## Local closeout retest (2026-11-05)

The closeout review found missing failure/concurrency feedback, so the same local batch now includes conditional provider writes. Configuration GET returns an in-memory `providerStateTokens` map generated alongside its snapshot under the scoped-write lock. Each token is an HMAC over provider ID, a per-provider write revision and provider state, with a random process-local secret. Local writes accept `expectedStateToken` and reject stale state with HTTP 409; successful writes advance only that provider's revision, including no-op writes. Another provider's write does not invalidate this token. The map/secret/revisions are not persisted; the legacy full-config POST removes the response-only map. Server restarts invalidate outstanding tokens and require a fresh read.

The dashboard sends tokens when available. Key drafts retain the token at edit start, so polling cannot silently rebase old structured key objects onto newer server state. A conflict preserves the draft and requires review/re-editing; re-editing retains the current authoritative structured objects. Edits made during a successful save remain drafts and receive that acknowledgement's new token. Unknown Key results are read back rather than automatically replayed. Matching content confirms the submitted snapshot, ignoring JSON object member order but retaining array order; differing content preserves the draft with a conflict notice. Pending/unknown writes and same-provider overlapping actions give explicit feedback; different providers may save concurrently.

Compatibility boundaries remain: legacy callers that omit the token still perform unconditional scoped writes, and external full-config snapshots retain their documented limitations. Account refresh paths are not comprehensively redesigned. An unknown POST may still arrive after a readback; conditional subsequent writes ensure that two writes using the same state cannot both succeed, rather than claiming a readback proves the first request has terminated. No cancellation of in-flight model calls is implied.

The frontend regression suite adds lost response/readback success and mismatch, malformed acknowledgements, pending same-provider actions, independent providers, external updates/conflict/re-edit, continued typing during save and reordered structured-object properties. JVM regressions add opaque nonpersistent snapshots, invalid/precondition failures, old-token rejection after no-op and return-to-original-state writes, and independent conditional writes from the same snapshot. Final build/device evidence is recorded in the local maintenance closeout report; no remote CI run or publication is claimed for this uncommitted batch.
