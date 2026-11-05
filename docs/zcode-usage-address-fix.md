# ZCode gift accounting and live dashboard/address fixes

Release **1.2.13 / 19**, following 1.2.12 (`b39ab0a`). The user authorized an independent second test cycle and a complete source/APK release after validation.

## Findings

The user's ETA model selection was `zcode/glm-5.3-flash`, using the gateway at `127.0.0.1:3425/v1` in Chat mode. The production ledger stopped at an older failed Coding Plan request, even though later gift calls succeeded. `tryZCodeGiftOpenAI` returns before normal candidate dispatch and previously never initialized or committed usage. Refreshing the frontend alone could not recover missing rows.

The governance page also refreshed only its heatmap periodically. Its summary and recent requests were loaded on entering the page, so they could remain stale while the page stayed open.

The two displayed addresses were intended for different clients: loopback for apps on this phone and the phone's interface address for other devices. The presentation did not explain this. ETA import hard-coded port 3425, while the credentials card used a frontend URL port and could retain an obsolete interface address.

## Changes

- Gift calls create one logical request with actual attempts. A rejected first transport attempt and successful retry remain one logical request. Chat and Messages, streaming and nonstreaming, capture original upstream usage; missing or null dimensions remain unknown/partial. Error-reported usage is retained. Cancellation, truncation, error and normal completion commit exactly one terminal result and clear request-thread state.
- Native Messages gift output retains the original Anthropic stream and A1's safety boundary. Chat conversion no longer invents zero counters. Original gift usage is observed before projection to avoid changing protocol token/cache semantics; content timing still comes from emitted stream content.
- Governance summary, recent requests and heatmap refresh while visible and active. Re-entering or returning to the app refreshes immediately. In-flight coalescing and revision checks prevent stale responses from overwriting a new range/group selection. Refreshes do not reload governance edit forms. Errors identify retained data as stale.
- The credentials card labels the loopback address as recommended for apps on this phone, matching ETA. The detected LAN address is separate, with same-network/access conditions. Both API endpoints use the actual listener port. Missing/invalid interface addresses and service failures clear stale URLs and disable copying unavailable values. No unverified remote-device reachability is promised.

## Verification

`GatewayAddressOfflineTest` checks address validity, bind restrictions and the real HTTP listener port. `ZCodeGiftUsageHttpTest` exercises 39 synthetic cases covering Chat/Messages streaming/nonstreaming, full/partial/missing/null/cache-only usage, failure usage, retry, truncation, cancellation, fallback and thread reuse. Existing protocol tests use an explicit constructor signature rather than relying on reflection order.

`tests/dashboard-live-status.cjs` checks active/hidden polling, visibility restoration, overlapping requests, range races, error states, address updates and copy consistency. All six Node suites are retained. `streamingHttpTests` includes the new gift suite; `offlineTests` includes the address suite.

An isolated Android WebView test used production sources and a loopback gift mock. Without manual refresh or tab switching, completed requests appeared in totals and both recent-request views; the provider grouping selection remained unchanged. The two local addresses matched at the nondefault test port 18765. Two completed fixture calls produced 2 requests, 2 attempts and 36 known input+output tokens. The test package was uninstalled and its mock stopped. No real supplier was charged, and production configuration was not changed.

Past gift calls that never reached the ledger cannot be accurately reconstructed from the old ledger. The fix records subsequent requests; it does not fabricate historical usage or import ETA message counters as supplier billing data. Dashboard refresh is approximately every 3 seconds while visible, after usage becomes available; it is not continuous token-by-token accounting.

## Independent second test cycle

Second review added regression cases for disabled gift providers/accounts, explicit refusal even with a misleading HTTP status, resource cleanup before stream handoff, final downstream framing/flush failure, independently ordered Anthropic cache/input counters, stale ETA key responses and duplicate recent-request fetches. LAN labels now accept RFC 1918 private addresses only, and a listener bound only to a particular interface does not advertise unsupported loopback access. Gift Chat terminal success waits for the tracked HTTP write result. These are part of the release rather than unchecked reviewer notes.

The release gate includes 34 offline JVM suites, the expanded gift HTTP cases, existing native/commercial/streaming/setup HTTP tests, six Node suites, Debug/Release compilation and lint. Windows and Ubuntu CI must pass before publishing the release. Device tests use a separate application, production source and synthetic loopback upstream, without touching real supplier settings or consuming paid quota. Existing missing history is not reconstructed.
