# C2 explicit outbound network configuration (local acceptance)

Implemented locally on released 1.2.13 / versionCode 19, main baseline `147b02acfb82189669c39bcb40b3b3706171c102`. This uncommitted batch includes and preserves the preceding provider-management work. No version bump, source upload, remote CI run or release is implied.

## User behavior

The Overview page has a collapsible **出站网络设置** card with three global modes:

- `default`: platform default HTTP networking. It no longer probes or implicitly selects port 7890. Platform proxy/VPN behavior still applies.
- `direct`: explicit `Proxy.NO_PROXY`; this does not bypass Android VPN routing.
- `http_proxy`: an explicit HTTP proxy host and integer port. A failure never causes application code to retry via direct/default networking. SOCKS and proxy authentication are outside this batch.

Only literal loopback destinations (`localhost`, dotted 127/8, IPv6 loopback) bypass the proxy, including internal control and health requests. Hostnames are not resolved to discover bypass eligibility. Existing fake-IP users who relied on implicit 7890 selection can explicitly set their local HTTP proxy. This is a documented behavior change, not automatic migration to an unverified open port.

Network changes apply to new logical requests. A request, its native refresh/forward work and its connection retries retain a captured immutable policy. DeepSeek's streaming worker explicitly carries this scope across threads. Native LoginActivity work keeps the Activity-start policy; reopening the login screen starts a new policy scope. Browser OAuth pages and login WebView page traffic are controlled by their own/system networking; the app setting cannot control them.

## Configuration and management API

Top-level `network` is optional. A missing member means default and is not added on startup/read, preserving existing configuration bytes when no migration is otherwise needed. Values are exactly `{mode:"default"}`, `{mode:"direct"}`, or `{mode:"http_proxy",host:"127.0.0.1",port:7890}`. Schemes, user-info, paths, invalid hosts, fractional/out-of-range ports, unknown modes and extra fields are rejected. Bracketed IPv6 is accepted and normalized in runtime responses.

Authenticated `GET /api/network` returns `{ok:true,network,blocked,stateToken}`. `POST /api/network` accepts the network object and an optional `expectedStateToken`. The dashboard always reads a token first and sends it. A stale token returns 409 without writing; successful saves, including no-ops, advance the instance revision. The process-random epoch/revision is response-only. Legacy callers omitting the token remain unconditional. Tokens are not authentication credentials.

The endpoint copies current configuration and changes only `network`, persists atomically, then publishes the policy. A failed write leaves active policy, token and configuration unchanged. Legacy full configuration POST retains the authoritative network member so a stale dashboard snapshot cannot undo the dedicated setting. This also means editing `network` through legacy full-config import is not supported; use the dedicated endpoint.

Malformed stored network settings block outbound requests while retaining configuration and the local management page. GET reports `blocked:true`; the default-mode repair form is a placeholder, not an active default policy. Saving a valid setting repairs it. Unrelated writes reject the invalid network rather than silently publishing direct networking.

The dashboard isolates these drafts from the three-second config poll, preserves them across navigation and overlapping reads, retains continued edits during saving and prevents duplicate saves. Unknown results are read back without automatic replay. A differing readback describes current state, not proof that an earlier POST has terminated; conditional writes stop two writes from succeeding from the same old version.

## Connection coverage

`NetworkPolicy` centralizes all production external `HttpURLConnection` creation. Native wrappers and callers cover Cline, Codex, CatPaw, Copilot, MiniMax, Qoder, ZCode auth/gift, DeepSeek Web, login native state/token requests, GatewayServer commercial/native/Responses inference, model catalog and auxiliary usage/refresh requests. LocalBridge, MainActivity's loopback sockets and HealthProbe remain local. Search for `openConnection`, `Socket` and the removed `getLocalProxy` is part of verification; there is no remaining automatic 7890 probe.

Antigravity's hand-written HTTP/1.1 TLS transport establishes a CONNECT tunnel for explicit HTTP proxies without locally resolving the target hostname. It retains chunked/stream handling and verifies certificate chains and HTTPS endpoint identity. The current default SSLContext supplies its TLS factory; this avoids Android's cached default-factory behavior during isolated trust-context testing while keeping production platform trust. CONNECT response headers have a 32 KiB bound and a total read deadline. Diagnostics use the policy and reject address-family measurements when the target is reached through a proxy.

Boundaries: default-mode ordinary HTTP follows the platform HTTP stack; manual TLS uses the first ProxySelector result and supports HTTP/DIRECT, rejecting SOCKS rather than bypassing it. OS/system proxy and VPN combinations were not exhaustively changed or tested on the user's device. Synchronous system DNS resolution before raw Socket.connect is not independently bounded by connectMs; the TCP connect and CONNECT header phase are bounded. Global networking assumes one active GatewayServer configuration per Android process; service lifecycle serializes the production engine. Concurrent different-config gateways in one process are unsupported. Existing admitted streams are not cancelled by configuration changes.

## Validation and local device evidence

Final full gate `job_804362dd`: clean + **38 JVM suites**, all existing native/commercial/gift/streaming/setup HTTP fixtures, Debug/Release and lint passed. Lint XML reports **94 Warning, 0 Error/Fatal**. Eight independent Node suites pass, including new `tests/dashboard-network.cjs`; HTTP setup fixture remains covered by its Gradle task. CI registers the new tests, but no remote CI has run for these uncommitted changes.

`NetworkPolicyOfflineTest` covers schema, direct/default/explicit proxy, literal-loopback rules, immutable scope, CONNECT rejection/truncation/oversized/slow headers, blocked recovery access, target DNS behavior and real synthetic TLS success/hostname mismatch/untrusted certificate rejection. `NetworkSettingsOfflineTest` covers auth, scoped persistence, tokens/conflict/no-op, rollback, legacy snapshots, invalid stored settings and repair. DeepSeek's stream test uses latches to switch process policy while retaining its captured worker policy.

Android independent `com.gaeul.allin.batchaudit` uses production sources/assets and synthetic configuration. Final native test `job_1277e4a3` passed all 16 checks, including Android HTTP/HTTPS CONNECT and Antigravity TLS success and rejection. `job_2afae9b9` passed real WebView/touch network save, polling/navigation draft retention, real 409/re-read/edit recovery and 360 CSS-pixel light/dark layouts, plus the complete preceding provider-management touch/HTTP retest. `job_df7fdb6a` forwarded a real Android gateway request through a synthetic proxy to an unresolvable fixture hostname, then verified dead-proxy failure, protected network state against legacy config writes and preservation of unrelated providers. No paid model calls were made.

Evidence lives outside the repository in `/workspace/allin-package-maintenance/batch-audit`: `network-native-result.json`, `network-ui-result.json`, `network-forward-result.json`, `network-light.png`, `network-dark.png` and the verify scripts. The isolated APK has been uninstalled and fixture ports 18765/18767 are closed.

Local replacement APK: `AllIn-1.2.13-Gaeul-v19-local-network.apk`, 2562644 bytes, SHA256 `63eaf1c9d463e051514053249004e34be448119a9ab63b9ae7b4057c18451e52`, same Gaeul v2/v3 certificate. Installed APK hash matches. Configuration hashes immediately before/after installation and final startup match; production network mode is default, `/api/health` and `/api/network` return 200, served dashboard matches source, and foreground service type remains specialUse. Configuration preservation does not assert all real provider credentials were revalidated online.

C1 recent-usage routing is not part of this implementation.
