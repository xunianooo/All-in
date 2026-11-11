# Raccoon refresh service (local, not wired to Gateway)

This change adds an isolated refresh service for a future Gateway integration. It does not change `GatewayServer`, enable any provider flow, or make a live request. `RaccoonRefreshService.Transport` is injected; the offline fixture transport is the only implementation used by its test.

## Protocol contract found in the local OpenCode reference

The reference source uses `POST /api/web/auth/v1/refresh` with JSON `{ "refresh_token": "…" }`. A successful response has `code: 0` and `data.access_token`; it reads `data.refresh_token` if present and keeps the prior refresh token if rotation is omitted. The returned access JWT supplies `exp` when available. The separate authorization-code exchange is `POST /api/web/auth/v1/login_with_authorization_code` with `{ "authorization_code": "…" }`; its success path requires both `data.access_token` and `data.refresh_token`. The reference marks HTTP 4xx refresh failures and missing access tokens as requiring sign-in; it specifically exercises code `100002` / `params_invalid_error` on HTTP 400. These are repository-reference contracts, not independently verified by a request to the vendor.

## Proposed account shape

Use an account object in the existing provider's `accounts` array, retaining its account-local settings and unknown fields:

```json
{
  "id": "stable-local-account-id",
  "accountId": "Raccoon-user-id-or-name",
  "uid": "vendor-user-id",
  "name": "vendor-display-name",
  "enabled": true,
  "type": "oauth",
  "source": "browser",
  "access": "access-token",
  "token": "access-token",
  "refresh": "refresh-token",
  "expires": 0,
  "lastRefresh": 0
}
```

`access` is the refresh service's canonical field; it also updates `token` when that legacy alias exists. `expires` is epoch milliseconds; it is derived from `data.expires_in` if a future response provides it, otherwise from the access JWT `exp`, and otherwise retains the prior value. Keep the access-only legacy account unchanged: no refresh token means no refresh call, so its currently usable access credential remains available to the existing caller. Mark desktop-imported credentials with `source: "desktop"` (or `desktop: true`) to keep refresh disabled.

## Persistence and later Gateway wiring

Call `ensureFresh` before forwarding when expiry is within `RaccoonRefreshPolicy.EARLY_REFRESH_MILLIS` (**five minutes**, `5 * 60 * 1000L`). On a 401, call `refreshAfter401` with the failed access token and attempt count; the policy permits one refresh/replay only. Both methods use `AccountTokenCoordinator` single-flight coordination.

### Which reference number is which

The local reference `opencode-raccoon-auth/source/index.mjs` carries two distinct margins, and only the second one is the behaviour adopted here:

- `const EARLY_MS = 60 * 1000` (line 23) is the plugin's own in-process `isStale(a, marginMs = EARLY_MS)` margin (line 296), used only to decide whether an already-cached sign-in counts as stale for local use.
- `const LEAD_MS = 5 * 60 * 1000` (line 24) is published as `auth.refreshLead` (line 1080), the window in which the host is expected to renew the sign-in before the token ends.

All-in is its own host for this account, so the pre-flight window follows the lead, not the plugin's cache-staleness margin: `RaccoonRefreshPolicy.EARLY_REFRESH_MILLIS` is `5 * 60 * 1000L`, equal to `LEAD_MS`. The earlier wording of this document described it as 60 seconds, which conflated it with `EARLY_MS`. The same five-minute value is passed as `AccountTokenCoordinator.ensure`'s margin so the coordination re-check stays exactly as permissive as `shouldRefresh` and cannot silently swallow a refresh that is already due.

The eventual `AccountTokenCoordinator.Store.commit` adapter must perform an atomic compare-and-set against the complete account snapshot (plus provider/account enabled state and a generation or equivalent replacement check), persist the candidate before publishing it, and fail on deletion, disablement, replacement, or any concurrent account edit. Refresh the account by stable `id`; never replace the provider's whole account array from a stale snapshot. The service itself does not implement or assume that Gateway-owned persistence adapter.

## 发送阶段证据（后续补充）

刷新 POST 的失败现在区分「可证明未发送」与「执行结果未知」，判定与注入测试见
`docs/raccoon-refresh-send-stage-local.md`；该能力同样尚未接入 `GatewayServer`，本文的接法仍然有效，
只是失败时多了一层可据以决定「是否释放一次性围栏」的证据。

## Offline verification

Run only the isolated test with:

```sh
./gradlew :app:raccoonRefreshOfflineTest
```

The test injects fixed responses and checks request fields, token rotation, expiry, legacy accounts, desktop and disabled accounts, 401 attempt bounds, malformed/error responses, and a CAS conflict. No test contacts the vendor.
