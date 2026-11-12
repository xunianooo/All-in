package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Offline contract tests. Every HTTP response is a local fixture; no network transport is used. */
public final class RaccoonRefreshServiceOfflineTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static JSONObject copy(JSONObject x) throws Exception { return x == null ? null : new JSONObject(x.toString()); }
    static String jwt(long expSeconds) {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"exp\":" + expSeconds + ",\"name\":\"RaccoonUser\"}").getBytes(StandardCharsets.UTF_8));
        return "header." + payload + ".signature";
    }
    static JSONObject account(String id, String access, String refresh, long expires) throws Exception {
        return new JSONObject().put("id", id).put("accountId", "Raccoon-user-1").put("uid", "user-1")
                .put("name", "RaccoonUser").put("enabled", true).put("type", "oauth").put("source", "browser")
                .put("access", access).put("token", access).put("refresh", refresh).put("expires", expires)
                .put("lastRefresh", 12L).put("custom", new JSONObject().put("keep", "value"));
    }

    static final class Store implements AccountTokenCoordinator.Store {
        final String provider = RaccoonRefreshService.PROVIDER;
        final String id;
        JSONObject value;
        boolean providerEnabled = true;
        int generation;
        int generationAtRead;
        Store(String id, JSONObject value) throws Exception { this.id = id; this.value = copy(value); }
        public synchronized JSONObject read(String provider, String accountId) throws Exception {
            if (!this.provider.equals(provider) || !id.equals(accountId) || !providerEnabled) return null;
            generationAtRead = generation;
            return copy(value);
        }
        public synchronized JSONObject commit(String provider, String accountId, JSONObject expected,
                                              JSONObject candidate) throws Exception {
            if (!this.provider.equals(provider) || !id.equals(accountId) || !providerEnabled || value == null
                    || !value.optBoolean("enabled", true) || generationAtRead != generation
                    || !value.toString().equals(expected.toString())) throw new IOException("account CAS conflict");
            value = copy(candidate);
            return copy(value);
        }
        synchronized void replace(JSONObject replacement) throws Exception { value = copy(replacement); generation++; }
    }

    static final class FixtureTransport implements RaccoonRefreshService.Transport {
        int calls;
        String path;
        JSONObject body;
        String sentRefresh;
        RaccoonRefreshService.Response response;
        IOException failure;
        RuntimeException crash;
        FixtureTransport(RaccoonRefreshService.Response response) { this.response = response; }
        public RaccoonRefreshService.Response postJson(String path, JSONObject body) throws IOException {
            calls++; this.path = path; this.body = new JSONObject(body.toString());
            this.sentRefresh = body.optString("refresh_token", "");
            if (crash != null) throw crash;
            if (failure != null) throw failure;
            return response;
        }
    }

    static RaccoonRefreshService.Response success(String access, String refresh) throws Exception {
        return new RaccoonRefreshService.Response(200, new JSONObject().put("code", 0).put("data",
                new JSONObject().put("access_token", access).put("refresh_token", refresh)));
    }

    static void proactiveRefreshAndRotation() throws Exception {
        long now = System.currentTimeMillis();
        Store store = new Store("a1", account("a1", jwt(now / 1000L - 10), "refresh-old", now - 10_000));
        String before = store.value.toString();
        FixtureTransport transport = new FixtureTransport(success("Bearer " + jwt(now / 1000L + 3600), "refresh-new"));
        JSONObject result = new RaccoonRefreshService(new AccountTokenCoordinator(), transport).ensureFresh("a1", store);
        check(transport.calls == 1, "due account refreshed once");
        check(transport.path.equals(RaccoonRefreshService.REFRESH_PATH), "refresh endpoint");
        check(transport.body.optString("refresh_token").equals("refresh-old") && transport.body.length() == 1,
                "refresh request body");
        check(result.optString("access").equals(result.optString("token")), "legacy gateway access alias updated");
        check(result.optString("refresh").equals("refresh-new"), "rotated refresh token persisted");
        check(result.optLong("expires") > now && result.optLong("lastRefresh") >= now,
                "expiry and refresh time persisted: expires=" + result.optLong("expires") + " now=" + now
                        + " lastRefresh=" + result.optLong("lastRefresh"));
        check(result.getJSONObject("custom").optString("keep").equals("value"), "unknown account data retained");
        check(store.value.toString().equals(result.toString()), "committed snapshot returned");
        check(before.contains("refresh-old") && !before.contains("refresh-new"), "input snapshot was not mutated");
    }

    static String jwtWithUrlAlphabet(long expSeconds) {
        // These UTF-8 bytes produce both URL-only symbols at this JSON alignment.
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"exp\":" + expSeconds + ",\"name\":\"a\u083e\u083f\"}").getBytes(StandardCharsets.UTF_8));
        check(payload.contains("-") && payload.contains("_"), "JWT fixture exercises both base64url symbols");
        check(!payload.contains("="), "JWT fixture is unpadded");
        return "header." + payload + ".signature";
    }

    static void jwtExpiryFallbackUsesUrlAlphabet() throws Exception {
        long now = System.currentTimeMillis();
        for (long expSeconds : new long[]{2_000_000_000L, 1_600_000_000L}) {
            String id = "jwt-" + expSeconds;
            long oldExpiry = now - 10_000;
            Store store = new Store(id, account(id, "old-access", "keep-refresh", oldExpiry));
            String access = jwtWithUrlAlphabet(expSeconds);
            RaccoonRefreshService.Response response = success("Bearer " + access, "");
            check(!response.body.getJSONObject("data").has("expires_in"), "JWT fallback response omits expires_in");
            FixtureTransport transport = new FixtureTransport(response);
            JSONObject result = new RaccoonRefreshService(new AccountTokenCoordinator(), transport).ensureFresh(id, store);
            check(transport.calls == 1, "JWT fallback makes one refresh request");
            check(result.optLong("expires") == expSeconds * 1000L, "JWT exp replaces prior expiry exactly: " + expSeconds);
            check(store.value.optLong("expires") == expSeconds * 1000L, "JWT expiry persisted, including expired claims");
            check(result.optString("access").equals(access) && result.optString("token").equals(access), "JWT access aliases persisted");
            check(result.optString("refresh").equals("keep-refresh"), "empty rotation keeps prior refresh token");
        }
    }

    static void unusableJwtRetainsPriorExpiry() throws Exception {
        long now = System.currentTimeMillis();
        String[] unusable = {"opaque-access", "header.invalid!.signature", "header.e30.signature",
                jwt(0), jwt(-1), jwt(Long.MAX_VALUE)};
        for (long oldExpiry : new long[]{now + 3_600_000, now - 10_000}) {
            for (int i = 0; i < unusable.length; i++) {
                String id = "fallback-" + oldExpiry + "-" + i;
                Store store = new Store(id, account(id, "old-access", "keep-refresh", oldExpiry));
                FixtureTransport transport = new FixtureTransport(success(unusable[i], "new-refresh"));
                JSONObject result = new RaccoonRefreshService(new AccountTokenCoordinator(), transport)
                        .refreshAfter401(id, "old-access", 0, store);
                check(transport.calls == 1, "401 fallback makes one refresh request");
                check(result.optLong("expires") == oldExpiry && store.value.optLong("expires") == oldExpiry,
                        "unusable JWT retains exact prior expiry, including expired values");
            }
        }
    }

    static void serviceRefreshCasPreservesConcurrentAccountAndExpiry() throws Exception {
        long now = System.currentTimeMillis();
        for (long replacementExpiry : new long[]{now + 7_200_000, now - 20_000}) {
            String id = "service-cas-" + replacementExpiry;
            Store store = new Store(id, account(id, "old-access", "old-refresh", now - 10_000));
            JSONObject replacement = account(id, "newer-access", "newer-refresh", replacementExpiry)
                    .put("lastRefresh", 345L).put("custom", new JSONObject().put("keep", "concurrent-edit"));
            int[] calls = {0};
            RaccoonRefreshService.Transport transport = (path, body) -> {
                calls[0]++;
                try {
                    store.replace(replacement);
                    return success(jwtWithUrlAlphabet(2_000_000_000L), "candidate-refresh");
                } catch (Exception e) { throw new IOException(e); }
            };
            try {
                new RaccoonRefreshService(new AccountTokenCoordinator(), transport).ensureFresh(id, store);
                throw new AssertionError("service refresh must report CAS conflict");
            } catch (IOException expected) {
                check(expected.getMessage().contains("CAS conflict"), "service propagates CAS conflict");
            }
            check(calls[0] == 1, "conflicting service refresh makes one request");
            check(store.value.toString().equals(replacement.toString()), "CAS preserves complete concurrent account");
            check(store.value.optLong("expires") == replacementExpiry, "CAS preserves concurrent future or expired value");
        }
    }

    static void optionalRotationAndLegacyAccounts() throws Exception {
        long now = System.currentTimeMillis();
        String freshAccess = jwt(now / 1000L + 3600);
        Store store = new Store("a2", account("a2", "old", "keep-refresh", now - 1000));
        FixtureTransport transport = new FixtureTransport(new RaccoonRefreshService.Response(200,
                new JSONObject().put("code", 0).put("data", new JSONObject().put("access_token", freshAccess))));
        JSONObject result = new RaccoonRefreshService(new AccountTokenCoordinator(), transport).ensureFresh("a2", store);
        check(transport.calls == 1 && result.optString("refresh").equals("keep-refresh"), "omitted rotation retains refresh token");

        Store accessOnly = new Store("legacy", account("legacy", "legacy-access", "", 0));
        String original = accessOnly.value.toString();
        transport = new FixtureTransport(success("unused", "unused"));
        result = new RaccoonRefreshService(new AccountTokenCoordinator(), transport).ensureFresh("legacy", accessOnly);
        check(transport.calls == 0 && result.optString("access").equals("legacy-access"), "legacy account remains usable without refresh");
        check(accessOnly.value.toString().equals(original), "legacy account left untouched");

        Store unknownExpiry = new Store("legacy-exp", account("legacy-exp", "opaque-access", "refresh", 0));
        transport = new FixtureTransport(success("unused", "unused"));
        result = new RaccoonRefreshService(new AccountTokenCoordinator(), transport).ensureFresh("legacy-exp", unknownExpiry);
        check(transport.calls == 0 && result.optString("access").equals("opaque-access"), "unknown expiry does not trigger refresh");
    }

    static void desktopDisabledAndNotDue() throws Exception {
        long now = System.currentTimeMillis();
        JSONObject desktop = account("desk", "desktop-access", "desktop-refresh", now - 10_000).put("source", "desktop");
        Store store = new Store("desk", desktop);
        FixtureTransport transport = new FixtureTransport(success("unused", "unused"));
        JSONObject result = new RaccoonRefreshService(new AccountTokenCoordinator(), transport).ensureFresh("desk", store);
        check(transport.calls == 0 && result.optString("access").equals("desktop-access"), "desktop token never refreshed");

        Store future = new Store("future", account("future", "future-access", "refresh", now + 3_600_000));
        result = new RaccoonRefreshService(new AccountTokenCoordinator(), transport).ensureFresh("future", future);
        check(transport.calls == 0 && result.optString("access").equals("future-access"), "not-due account unchanged");

        Store disabled = new Store("disabled", account("disabled", "access", "refresh", now - 1));
        disabled.value.put("enabled", false);
        result = new RaccoonRefreshService(new AccountTokenCoordinator(), transport).ensureFresh("disabled", disabled);
        check(result == null && transport.calls == 0, "disabled account not refreshed");
    }

    static void refreshAfter401IsBoundedAndRaceSafe() throws Exception {
        long now = System.currentTimeMillis();
        Store store = new Store("a401", account("a401", "failed-access", "refresh", now + 3_600_000));
        FixtureTransport transport = new FixtureTransport(success("new-access", "new-refresh"));
        RaccoonRefreshService service = new RaccoonRefreshService(new AccountTokenCoordinator(), transport);
        JSONObject result = service.refreshAfter401("a401", "failed-access", 0, store);
        check(transport.calls == 1 && result.optString("access").equals("new-access"), "401 forced one refresh");
        result = service.refreshAfter401("a401", "new-access", 1, store);
        check(transport.calls == 1 && result.optString("access").equals("new-access"), "retry count prevents another refresh");
        result = service.refreshAfter401("a401", "failed-access", 0, store);
        check(transport.calls == 1 && result.optString("access").equals("new-access"), "stale 401 uses already refreshed account");

        Store raced = new Store("race", account("race", "old-access", "old-refresh", now - 1));
        FixtureTransport never = new FixtureTransport(success("unused", "unused"));
        RaccoonRefreshService raceService = new RaccoonRefreshService(new AccountTokenCoordinator(), never);
        try {
            new AccountTokenCoordinator().ensure(RaccoonRefreshService.PROVIDER, "race", 0, null, raced,
                    (snapshot, force) -> {
                        snapshot.put("access", "candidate-access");
                        raced.replace(account("race", "newer-account-access", "newer-refresh", now + 7_200_000));
                        return snapshot;
                    });
            throw new AssertionError("expected CAS conflict");
        } catch (IOException expected) { check(expected.getMessage().contains("CAS conflict"), "CAS conflict reported"); }
        check(raced.value.optString("access").equals("newer-account-access"), "conflicting refresh cannot overwrite account");
        check(raceService != null && never.calls == 0, "race fixture made no network request");
    }

    static void vendorErrorsAndMalformedResponses() throws Exception {
        long now = System.currentTimeMillis();
        for (int status : new int[]{400, 401, 429, 500}) {
            Store store = new Store("err" + status, account("err" + status, "old", "refresh", now - 1));
            FixtureTransport transport = new FixtureTransport(new RaccoonRefreshService.Response(status,
                    new JSONObject().put("code", 100002).put("message", "params_invalid_error")
                            .put("details", "refresh token is invalid or expired")));
            try {
                new RaccoonRefreshService(new AccountTokenCoordinator(), transport).ensureFresh(store.id, store);
                throw new AssertionError("HTTP " + status + " should fail");
            } catch (RaccoonAccountService.Failure expected) {
                check(expected.expired == (status < 500), "HTTP status expiry classification " + status);
                check(expected.getMessage().contains("refresh token is invalid"), "vendor details surfaced");
            }
            check(transport.calls == 1 && store.value.optString("access").equals("old"), "failure did not persist candidate");
        }
        Store malformed = new Store("malformed", account("malformed", "old", "refresh", now - 1));
        FixtureTransport transport = new FixtureTransport(new RaccoonRefreshService.Response(200,
                new JSONObject().put("code", 0).put("data", new JSONObject().put("refresh_token", "rotated"))));
        try {
            new RaccoonRefreshService(new AccountTokenCoordinator(), transport).ensureFresh("malformed", malformed);
            throw new AssertionError("missing access token should fail");
        } catch (RaccoonAccountService.Failure expected) { check(expected.expired, "malformed success requires reauth"); }
        check(malformed.value.optString("access").equals("old"), "malformed response not persisted");
    }

    // ------------------------------------------------- send stage: provable unsent vs unknown

    /** Runs one refresh attempt and returns what it threw (null when it succeeded). */
    static Throwable refreshFailure(Store store, RaccoonRefreshService.Transport transport) throws Exception {
        try {
            new RaccoonRefreshService(new AccountTokenCoordinator(), transport).ensureFresh(store.id, store);
            return null;
        } catch (Throwable failure) { return failure; }
    }

    static void classificationIsStrictAndFailClosed() {
        for (RaccoonRefreshSend.Stage stage : RaccoonRefreshSend.Stage.values()) {
            for (long bytes : new long[]{0, 1, 60, -1, Long.MAX_VALUE}) {
                boolean provable = (stage == RaccoonRefreshSend.Stage.NOT_STARTED
                        || stage == RaccoonRefreshSend.Stage.NOT_CONNECTED) && bytes == 0;
                check((RaccoonRefreshSend.executionOf(stage, bytes) == RaccoonRefreshSend.Execution.NOT_EXECUTED)
                        == provable, "only an unwritten pre-send stage proves non-execution: " + stage + "/" + bytes);
            }
        }
        check(RaccoonRefreshSend.executionOf(null, 0) == RaccoonRefreshSend.Execution.UNKNOWN,
                "an absent stage can prove nothing");
        try {
            RaccoonRefreshSend.notSent(RaccoonRefreshSend.Stage.WRITING, 10, "contradiction", null);
            throw new AssertionError("notSent must reject a write stage");
        } catch (IllegalArgumentException expected) { }
        try {
            RaccoonRefreshSend.unknown(RaccoonRefreshSend.Stage.NOT_CONNECTED, 0, 10, "contradiction", null);
            throw new AssertionError("unknown must reject a pre-send stage");
        } catch (IllegalArgumentException expected) { }
        RaccoonRefreshSend.Failure declared = RaccoonRefreshSend.notSent(
                RaccoonRefreshSend.Stage.NOT_CONNECTED, 41, "connection refused", null);
        check(RaccoonRefreshSend.wrap(declared) == declared, "declared evidence is never re-classified");
        RaccoonRefreshSend.Failure undeclared = RaccoonRefreshSend.wrap(new IOException("no evidence"));
        check(undeclared.execution() == RaccoonRefreshSend.Execution.UNKNOWN, "an undeclared transport error is unknown");
        check(!undeclared.declaredEvidence() && undeclared.requestBytesWritten == -1,
                "an undeclared error invents no byte evidence");
        check(!RaccoonRefreshSend.releasesReservation(undeclared), "an undeclared error never releases a reservation");
        check(!RaccoonRefreshSend.releasesReservation(new IOException("other")), "another IOException never releases a reservation");
        check(!RaccoonRefreshSend.releasesReservation(new RaccoonAccountService.Failure("vendor", false)),
                "a received vendor error never releases a reservation");
        check(!RaccoonRefreshSend.releasesReservation(null), "a missing failure never releases a reservation");
        check(declared.diagnostic().contains("NOT_CONNECTED") && declared.diagnostic().contains("provably not executed"),
                "diagnostic names the stage: " + declared.diagnostic());
        check(!declared.diagnostic().contains("refresh-old"), "diagnostic carries no credential");
        // Existing callers surface the transport's own text; classification must not rewrite it.
        RaccoonRefreshSend.Failure preserved = RaccoonRefreshSend.wrap(new IOException("围栏已拦下，请重新登录"));
        check("围栏已拦下，请重新登录".equals(preserved.getMessage()), "the transport text is preserved");
        check(RaccoonRefreshSend.wrap(new IOException()).getMessage().contains("Raccoon refresh transport failed"),
                "an empty message falls back to a neutral one");
    }

    static void provablePreSendFailureIsNotExecutedAndRetryable() throws Exception {
        long now = System.currentTimeMillis();
        Store store = new Store("pre", account("pre", jwt(now / 1000L + 60), "refresh-old", now - 10_000));
        String original = store.value.toString();
        FixtureTransport transport = new FixtureTransport(null);
        transport.failure = RaccoonRefreshSend.notSent(RaccoonRefreshSend.Stage.NOT_CONNECTED, 41,
                "connection refused", new java.net.ConnectException("fixture refused"));
        RaccoonRefreshService service = new RaccoonRefreshService(new AccountTokenCoordinator(), transport);
        try {
            service.ensureFresh("pre", store);
            throw new AssertionError("a pre-send failure must fail the refresh");
        } catch (RaccoonRefreshSend.Failure expected) {
            check(expected.execution() == RaccoonRefreshSend.Execution.NOT_EXECUTED, "pre-send failure is provably unsent");
            check(expected.provablyNotExecuted(), "provablyNotExecuted agrees");
            check(RaccoonRefreshSend.releasesReservation(expected), "the gateway may release its reservation");
            check(expected.stage == RaccoonRefreshSend.Stage.NOT_CONNECTED, "stage survives the service boundary");
            check(expected.requestBytesWritten == 0 && expected.requestBytesTotal == 41, "byte evidence survives");
            check(expected.declaredEvidence(), "declared evidence is reported as declared");
        }
        check(transport.calls == 1 && "refresh-old".equals(transport.sentRefresh), "one attempt with the current value");
        check(store.value.toString().equals(original), "an unsent attempt leaves the account untouched");
        // The value is still current, so the retry is the same exchange - not a replay.
        transport.failure = null;
        transport.response = success("fresh-access", "refresh-new");
        JSONObject result = service.ensureFresh("pre", store);
        check(transport.calls == 2 && "refresh-old".equals(transport.sentRefresh), "the retry reuses the same value");
        check(result.optString("refresh").equals("refresh-new"), "the retry completes the rotation");
    }

    static void writeStageAndUndeclaredFailuresAreUnknown() throws Exception {
        long now = System.currentTimeMillis();
        IOException[] failures = {
                RaccoonRefreshSend.unknown(RaccoonRefreshSend.Stage.WRITING, 0, 41, "broken pipe", null),
                RaccoonRefreshSend.unknown(RaccoonRefreshSend.Stage.WRITING, 12, 41, "reset during body", null),
                RaccoonRefreshSend.unknown(RaccoonRefreshSend.Stage.WRITTEN, 41, 41, "reset before status", null),
                RaccoonRefreshSend.unknown(RaccoonRefreshSend.Stage.READING_STATUS, 41, 41, "timeout", null),
                RaccoonRefreshSend.unknown(RaccoonRefreshSend.Stage.READING_BODY, 41, 41, "truncated", null),
                RaccoonRefreshSend.unknown(RaccoonRefreshSend.Stage.UNSPECIFIED, -1, -1, "collapsed", null),
                new java.net.SocketTimeoutException("read timed out"),
                new IOException("plain transport failure"),
        };
        for (int i = 0; i < failures.length; i++) {
            String id = "unknown-" + i;
            Store store = new Store(id, account(id, "old-access", "refresh-current", now - 10_000));
            String original = store.value.toString();
            FixtureTransport transport = new FixtureTransport(success("unused", "unused"));
            transport.failure = failures[i];
            Throwable thrown = refreshFailure(store, transport);
            check(thrown instanceof RaccoonRefreshSend.Failure, "case " + i + " must be classified, got " + thrown);
            RaccoonRefreshSend.Failure classified = (RaccoonRefreshSend.Failure) thrown;
            check(classified.execution() == RaccoonRefreshSend.Execution.UNKNOWN, "case " + i + " is an unknown outcome");
            check(!classified.provablyNotExecuted(), "case " + i + " must never claim it was unsent");
            check(!RaccoonRefreshSend.releasesReservation(classified), "case " + i + " must keep the reservation");
            check(transport.calls == 1 && "refresh-current".equals(transport.sentRefresh), "case " + i + " sent once");
            check(store.value.toString().equals(original), "case " + i + " left the account untouched");
            check(!classified.diagnostic().contains("refresh-current"), "case " + i + " diagnostic carries no credential");
        }
        // A transport that crashes mid-send proves nothing either: it is still an unknown outcome.
        Store crashed = new Store("crash", account("crash", "old-access", "refresh-crash", now - 10_000));
        FixtureTransport crashTransport = new FixtureTransport(null);
        crashTransport.crash = new IllegalStateException("fixture crash after write");
        Throwable crashFailure = refreshFailure(crashed, crashTransport);
        check(crashFailure instanceof RaccoonRefreshSend.Failure
                && ((RaccoonRefreshSend.Failure) crashFailure).execution() == RaccoonRefreshSend.Execution.UNKNOWN,
                "a transport crash is an unknown outcome");
        check(!RaccoonRefreshSend.releasesReservation(crashFailure), "a crash never releases a reservation");
        check(crashed.value.optString("refresh").equals("refresh-crash"), "a crash leaves the account untouched");
        check(crashFailure.getMessage().contains("fixture crash after write"), "a crash keeps its own text");
        // Compatibility with the existing gateway fence: its sign-in text must survive classification,
        // because the response the user sees is built from the exception message.
        Store fencedValue = new Store("fence-text", account("fence-text", "old-access", "fence-value", now - 10_000));
        FixtureTransport fenceTransport = new FixtureTransport(null);
        fenceTransport.failure = new IOException("小浣熊 上次刷新的结果未知（一次性凭据可能已被消费），已停止重试，请重新登录");
        Throwable fenceThrown = refreshFailure(fencedValue, fenceTransport);
        check(fenceThrown instanceof RaccoonRefreshSend.Failure, "a fenced value is classified, got " + fenceThrown);
        check(fenceThrown.getMessage().contains("请重新登录"), "the fence sign-in text survives: " + fenceThrown.getMessage());
        check(!RaccoonRefreshSend.releasesReservation(fenceThrown), "a fenced value is never released");
        check(fencedValue.value.optString("refresh").equals("fence-value"), "the fenced value stays stored");
    }

    static void responsesThatProveNothingAreUnknown() throws Exception {
        long now = System.currentTimeMillis();
        RaccoonRefreshService.Response[] responses = {null,
                new RaccoonRefreshService.Response(200, new JSONObject(), false),
                new RaccoonRefreshService.Response(200, null)};
        String[] labels = {"missing response", "incomplete body", "success without a body"};
        for (int i = 0; i < responses.length; i++) {
            String id = "proofless-" + i;
            Store store = new Store(id, account(id, "old-access", "refresh-proofless", now - 10_000));
            String original = store.value.toString();
            FixtureTransport transport = new FixtureTransport(responses[i]);
            Throwable thrown = refreshFailure(store, transport);
            check(thrown instanceof RaccoonRefreshSend.Failure, labels[i] + " must be classified, got " + thrown);
            RaccoonRefreshSend.Failure classified = (RaccoonRefreshSend.Failure) thrown;
            check(classified.execution() == RaccoonRefreshSend.Execution.UNKNOWN,
                    labels[i] + " cannot prove the exchange did not happen");
            check(!RaccoonRefreshSend.releasesReservation(classified), labels[i] + " must keep the reservation");
            check(transport.calls == 1, labels[i] + " sent the refresh once");
            check(store.value.toString().equals(original), labels[i] + " left the account untouched");
        }
    }

    static void receivedResponsesStayExecuted() throws Exception {
        long now = System.currentTimeMillis();
        for (int status : new int[]{400, 401, 429, 500, 503}) {
            String id = "received-" + status;
            Store store = new Store(id, account(id, "old", "refresh-kept", now - 1));
            FixtureTransport transport = new FixtureTransport(new RaccoonRefreshService.Response(status,
                    new JSONObject().put("code", 100002).put("details", "refresh token is invalid or expired")));
            Throwable thrown = refreshFailure(store, transport);
            check(thrown instanceof RaccoonAccountService.Failure,
                    "HTTP " + status + " was received, so it is an executed failure, got " + thrown);
            check(!(thrown instanceof RaccoonRefreshSend.Failure), "HTTP " + status + " must not look like a send failure");
            check(!RaccoonRefreshSend.releasesReservation(thrown), "HTTP " + status + " never releases the reservation");
            check(((RaccoonAccountService.Failure) thrown).expired == (status < 500),
                    "HTTP " + status + " keeps its sign-in classification");
            check(transport.calls == 1 && "refresh-kept".equals(store.value.optString("refresh")),
                    "HTTP " + status + " left the stored value in place");
        }
        Store malformed = new Store("received-malformed", account("received-malformed", "old", "refresh-malformed", now - 1));
        Throwable malformedFailure = refreshFailure(malformed, new FixtureTransport(new RaccoonRefreshService.Response(200,
                new JSONObject().put("code", 0).put("data", new JSONObject().put("refresh_token", "rotated")))));
        check(malformedFailure instanceof RaccoonAccountService.Failure
                && ((RaccoonAccountService.Failure) malformedFailure).expired,
                "a complete 200 that cannot be used still requires sign-in");
        check(!(malformedFailure instanceof RaccoonRefreshSend.Failure),
                "an unusable success was still received, so it is not a send failure");
    }

    /** Models the gateway's one-use fence: reserve before send, release only on proof. */
    static final class Fence {
        final java.util.Set<String> reserved = new java.util.HashSet<String>();
        synchronized void reserve(String value) throws IOException {
            if (!reserved.add(value)) throw new IOException("value already fenced");
        }
        synchronized void release(String value) { reserved.remove(value); }
        synchronized boolean blocked(String value) { return reserved.contains(value); }
    }

    static void fenceReleasesOnlyProvenNotSentAndBlocksReplay() throws Exception {
        long now = System.currentTimeMillis();
        // 1. A provably unsent attempt is releasable, so the same one-use value can be exchanged.
        Store store = new Store("fence-ok", account("fence-ok", "old-access", "one-use", now - 10_000));
        final Fence fence = new Fence();
        final int[] sends = {0};
        RaccoonRefreshService.Transport refuseConnection = (path, body) -> {
            fence.reserve(body == null ? "" : body.optString("refresh_token", ""));
            sends[0]++;
            throw RaccoonRefreshSend.notSent(RaccoonRefreshSend.Stage.NOT_CONNECTED, 41,
                    "connection refused", new java.net.ConnectException("fixture refused"));
        };
        Throwable refused = refreshFailure(store, refuseConnection);
        check(RaccoonRefreshSend.releasesReservation(refused), "the unsent attempt is releasable");
        fence.release("one-use");
        check(!fence.blocked("one-use"), "the released value is current again");
        RaccoonRefreshService.Transport working = (path, body) -> {
            String value = body == null ? "" : body.optString("refresh_token", "");
            if (fence.blocked(value)) throw RaccoonRefreshSend.unknown(RaccoonRefreshSend.Stage.UNSPECIFIED, -1, -1,
                    "already sent", null);
            fence.reserve(value);
            sends[0]++;
            return new RaccoonRefreshService.Response(200, new JSONObject().put("code", 0).put("data",
                    new JSONObject().put("access_token", "fresh-access").put("refresh_token", "one-use-rotated")));
        };
        JSONObject refreshed = new RaccoonRefreshService(new AccountTokenCoordinator(), working)
                .ensureFresh("fence-ok", store);
        check(sends[0] == 2 && refreshed.optString("refresh").equals("one-use-rotated"),
                "a released value may be exchanged exactly once more");

        // 2. An unknown outcome keeps the fence, and the replay is refused before it can be sent.
        Store stuck = new Store("fence-unknown", account("fence-unknown", "old-access", "one-use-2", now - 10_000));
        final Fence stuckFence = new Fence();
        final int[] stuckSends = {0};
        RaccoonRefreshService.Transport brokenPipe = (path, body) -> {
            stuckFence.reserve(body == null ? "" : body.optString("refresh_token", ""));
            stuckSends[0]++;
            throw RaccoonRefreshSend.unknown(RaccoonRefreshSend.Stage.WRITING, 12, 41, "reset during body", null);
        };
        Throwable unknown = refreshFailure(stuck, brokenPipe);
        check(!RaccoonRefreshSend.releasesReservation(unknown), "an unknown outcome keeps the reservation");
        check(stuckFence.blocked("one-use-2"), "the possibly spent value stays fenced");
        RaccoonRefreshService.Transport guarded = (path, body) -> {
            String value = body == null ? "" : body.optString("refresh_token", "");
            if (stuckFence.blocked(value)) throw RaccoonRefreshSend.unknown(RaccoonRefreshSend.Stage.UNSPECIFIED, -1, -1,
                    "already sent", null);
            stuckFence.reserve(value);
            stuckSends[0]++;
            throw new AssertionError("a fenced value must never reach the wire");
        };
        Throwable replayed = refreshFailure(stuck, guarded);
        check(replayed instanceof RaccoonRefreshSend.Failure && stuckSends[0] == 1,
                "a fenced value is refused before any send");
        check(stuck.value.optString("refresh").equals("one-use-2"), "the fenced account is untouched");
    }

    public static void main(String[] args) throws Exception {
        jwtExpiryFallbackUsesUrlAlphabet();
        unusableJwtRetainsPriorExpiry();
        serviceRefreshCasPreservesConcurrentAccountAndExpiry();
        proactiveRefreshAndRotation();
        optionalRotationAndLegacyAccounts();
        desktopDisabledAndNotDue();
        refreshAfter401IsBoundedAndRaceSafe();
        vendorErrorsAndMalformedResponses();
        classificationIsStrictAndFailClosed();
        provablePreSendFailureIsNotExecutedAndRetryable();
        writeStageAndUndeclaredFailuresAreUnknown();
        responsesThatProveNothingAreUnknown();
        receivedResponsesStayExecuted();
        fenceReleasesOnlyProvenNotSentAndBlocksReplay();
        System.out.println("RaccoonRefreshServiceOfflineTest passed");
    }
}
