package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;

public class ConfigurationViewOfflineTest {
    static void check(boolean v, String m) { if (!v) throw new AssertionError(m); }
    static void reject(Runnable r) { try { r.run(); throw new AssertionError("accepted invalid input"); } catch (IllegalArgumentException expected) {} }
    static JSONObject copy(JSONObject o) { return new JSONObject(o.toString()); }
    static JSONObject withDeviceKey(JSONObject view, int index, String value) {
        view.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(index).put("devicePrivateKey", value);
        return view;
    }
    public static void main(String[] args) {
        String salt = "test-private-salt";
        JSONObject original = new JSONObject("{\"providers\":[{\"id\":\"p1\",\"keys\":[\"fake-key-one\",{\"key\":\"fake-key-two\",\"label\":\"work\"}],\"accounts\":[{\"id\":\"a1\",\"refresh_token\":\"fake-refresh-one\"},{\"id\":\"a2\",\"refreshToken\":\"fake-refresh-two\"}],\"models\":[\"visible-model\"],\"modelMeta\":{\"price\":1.25},\"quota\":42},{\"id\":\"p2\",\"token\":{\"access\":\"fake-access\"}}],\"server\":{\"authorization\":\"fake-server-auth\"},\"proxy\":{\"password\":\"fake-proxy-password\"},\"empty\":{\"secret\":\"\"},\"sessionId\":\"visible-session\"}");
        JSONObject before = copy(original), view = ConfigurationView.redact(original, salt);
        check(original.similar(before), "redaction mutated source");
        String serialized = view.toString();
        for (String s : new String[]{"fake-key-one", "fake-key-two", "fake-refresh-one", "fake-refresh-two", "fake-access", "fake-server-auth", "fake-proxy-password"}) check(!serialized.contains(s), "leaked " + s);
        check(serialized.contains("visible-model") && !serialized.contains("visible-session") && serialized.contains("1.25"), "metadata lost");
        check(view.getJSONObject("empty").getString("secret").isEmpty(), "empty changed");
        check(ConfigurationView.restore(view, original, salt).similar(original), "round trip");
        check(original.similar(before), "restore mutated current");
        JSONObject reordered = copy(view); JSONArray ps = reordered.getJSONArray("providers");
        JSONObject p1 = ps.getJSONObject(0), p2 = ps.getJSONObject(1);
        JSONArray as = p1.getJSONArray("accounts"); Object a1 = as.get(0), a2 = as.get(1);
        p1.put("accounts", new JSONArray().put(a2).put(a1));
        reordered.put("providers", new JSONArray().put(p2).put(p1));
        JSONObject restored = ConfigurationView.restore(reordered, original, salt);
        check(restored.getJSONArray("providers").getJSONObject(1).getJSONArray("accounts").getJSONObject(0).getString("refreshToken").equals("fake-refresh-two"), "id reordering");
        JSONObject edited = copy(view); edited.getJSONArray("providers").getJSONObject(0).getJSONArray("keys").put("new-plain-key");
        check(ConfigurationView.restore(edited, original, salt).getJSONArray("providers").getJSONObject(0).getJSONArray("keys").getString(2).equals("new-plain-key"), "new key");
        JSONObject forged = copy(view); forged.getJSONObject("server").put("authorization", "allin-secret-ref:bogus");
        reject(() -> ConfigurationView.restore(forged, original, salt));
        JSONObject stale = copy(original); stale.getJSONObject("server").put("authorization", "changed");
        reject(() -> ConfigurationView.restore(view, stale, salt));
        JSONObject moved = copy(view); moved.getJSONObject("proxy").put("password", view.getJSONObject("server").getString("authorization"));
        reject(() -> ConfigurationView.restore(moved, original, salt));
        JSONObject misplaced = copy(view); misplaced.put("name", view.getJSONObject("server").getString("authorization"));
        reject(() -> ConfigurationView.restore(misplaced, original, salt));
        JSONObject noIds = new JSONObject("{\"providers\":[{\"name\":\"x\",\"token\":\"secret-x\"}]}");
        JSONObject noIdsView = ConfigurationView.redact(noIds, salt);
        check(ConfigurationView.restore(noIdsView, noIds, salt).similar(noIds), "no id round trip");
        noIdsView.getJSONArray("providers").getJSONObject(0).put("name", "changed");
        reject(() -> ConfigurationView.restore(noIdsView, noIds, salt));
        reject(() -> ConfigurationView.redact(original, ""));
        reject(() -> ConfigurationView.restore(view, original, null));
        JSONObject mixed = new JSONObject("{\"providers\":[{\"id\":\"one\",\"keys\":[\"alpha\",{\"key\":\"beta\",\"label\":\"old\",\"off\":false},\"gamma\",{\"key\":\"delta\",\"label\":\"second\"}]},{\"id\":\"two\",\"keys\":[\"alpha\",{\"key\":\"beta\"}]}]}");
        JSONObject mixedView = ConfigurationView.redact(mixed, salt);
        JSONArray keys = mixedView.getJSONArray("providers").getJSONObject(0).getJSONArray("keys");
        String alpha = keys.getString(0), gamma = keys.getString(2);
        JSONObject beta = keys.getJSONObject(1), delta = keys.getJSONObject(3);
        beta.put("label", "edited").put("off", true);
        mixedView.getJSONArray("providers").getJSONObject(0).put("keys", new JSONArray().put(delta).put(beta).put(gamma).put(alpha).put("new-key"));
        JSONArray result = ConfigurationView.restore(mixedView, mixed, salt).getJSONArray("providers").getJSONObject(0).getJSONArray("keys");
        check(result.getJSONObject(0).getString("key").equals("delta") && result.getJSONObject(1).getString("key").equals("beta") && result.getJSONObject(1).getString("label").equals("edited") && result.getJSONObject(1).getBoolean("off") && result.getString(2).equals("gamma") && result.getString(3).equals("alpha") && result.getString(4).equals("new-key"), "mixed key reordering and edits");
        mixedView.getJSONArray("providers").getJSONObject(0).put("keys", new JSONArray().put(beta).put("new-key"));
        result = ConfigurationView.restore(mixedView, mixed, salt).getJSONArray("providers").getJSONObject(0).getJSONArray("keys");
        check(result.getJSONObject(0).getString("key").equals("beta") && result.getString(1).equals("new-key"), "filter structured and add");
        JSONObject other = copy(mixedView);
        other.getJSONArray("providers").getJSONObject(1).put("keys", new JSONArray().put(alpha));
        reject(() -> ConfigurationView.restore(other, mixed, salt));
        JSONObject staleKeys = copy(mixed);
        staleKeys.getJSONArray("providers").getJSONObject(0).getJSONArray("keys").getJSONObject(1).put("key", "replacement");
        reject(() -> ConfigurationView.restore(mixedView, staleKeys, salt));
        JSONObject wrongField = copy(mixedView);
        wrongField.getJSONArray("providers").getJSONObject(0).getJSONArray("keys").getJSONObject(0).put("label", beta.getString("key"));
        reject(() -> ConfigurationView.restore(wrongField, mixed, salt));
        JSONObject wrongType = copy(mixedView);
        wrongType.getJSONArray("providers").getJSONObject(0).put("keys", new JSONArray().put(beta.getString("key")));
        reject(() -> ConfigurationView.restore(wrongType, mixed, salt));
        JSONObject accounts = new JSONObject("{\"providers\":[{\"id\":\"p\",\"accounts\":[{\"id\":\"a\",\"keys\":[\"same\"]},{\"id\":\"b\",\"keys\":[\"same\"]}]}]}");
        JSONObject accountView = ConfigurationView.redact(accounts, salt);
        JSONArray accountItems = accountView.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts");
        String aRef = accountItems.getJSONObject(0).getJSONArray("keys").getString(0);
        accountItems.getJSONObject(1).put("keys", new JSONArray().put(aRef));
        reject(() -> ConfigurationView.restore(accountView, accounts, salt));
        JSONObject duplicate = new JSONObject("{\"keys\":[\"same\",\"same\"]}");
        JSONObject duplicateView = ConfigurationView.redact(duplicate, salt);
        duplicateView.put("keys", new JSONArray().put(duplicateView.getJSONArray("keys").get(1)));
        check(ConfigurationView.restore(duplicateView, duplicate, salt).getJSONArray("keys").getString(0).equals("same"), "duplicate keys");
        // Trae device binding: the PEM EC private key is real secret material and used to be copied
        // verbatim into the redacted view served by GET /api/config; the public half is not secret.
        String privPem = "-----BEGIN PRIVATE KEY-----\nMIGTSYNTHETICPRIVATEKEYBODY\n-----END PRIVATE KEY-----";
        String otherPem = "-----BEGIN PRIVATE KEY-----\nMIGTSYNTHETICPRIVATEKEYBODY2\n-----END PRIVATE KEY-----";
        String pubPem = "-----BEGIN PUBLIC KEY-----\nMFkwSYNTHETICPUBLICKEYBODY\n-----END PUBLIC KEY-----";
        String forgedRef = "allin-secret-ref:0000000000000000000000000000000000000000000000000000000000000000";
        JSONObject device = new JSONObject().put("providers", new JSONArray().put(new JSONObject().put("id", "trae-cn")));
        JSONArray deviceAccounts = device.getJSONArray("providers").getJSONObject(0).put("accounts", new JSONArray()).getJSONArray("accounts");
        deviceAccounts.put(new JSONObject().put("id", "acc-a").put("name", "alpha").put("deviceId", "device-a")
                .put("machineId", "machine-a").put("devicePublicKey", pubPem).put("devicePrivateKey", privPem)
                .put("refresh", "fake-device-refresh-a"));
        deviceAccounts.put(new JSONObject().put("id", "acc-b").put("name", "beta").put("deviceId", "device-b")
                .put("machineId", "machine-b").put("devicePublicKey", pubPem).put("devicePrivateKey", otherPem)
                .put("refresh", "fake-device-refresh-b"));
        JSONObject deviceBefore = copy(device), deviceView = ConfigurationView.redact(device, salt);
        JSONArray deviceViewAccounts = deviceView.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts");
        String deviceText = deviceView.toString();
        check(!deviceText.contains("SYNTHETICPRIVATEKEYBODY") && !deviceText.contains("PRIVATE KEY"), "device private key leaked: " + deviceText);
        check(deviceText.contains("SYNTHETICPUBLICKEYBODY") && deviceText.contains("device-a") && deviceText.contains("machine-b") && deviceText.contains("beta"), "device public key or identity lost");
        check(deviceViewAccounts.getJSONObject(0).getString("devicePublicKey").equals(pubPem), "public key must stay plaintext");
        check(device.similar(deviceBefore), "device redaction mutated source");
        String deviceRefA = deviceViewAccounts.getJSONObject(0).getString("devicePrivateKey");
        String deviceRefB = deviceViewAccounts.getJSONObject(1).getString("devicePrivateKey");
        check(deviceRefA.startsWith("allin-secret-ref:") && deviceRefB.startsWith("allin-secret-ref:") && !deviceRefA.equals(deviceRefB), "per account device references");
        JSONObject deviceRestored = ConfigurationView.restore(deviceView, device, salt);
        check(deviceRestored.similar(device), "device round trip");
        check(deviceRestored.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(1)
                .getString("devicePrivateKey").equals(otherPem), "device private key bytes preserved");
        check(device.similar(deviceBefore), "device restore mutated current");
        check(ConfigurationView.redact(deviceRestored, salt).similar(deviceView), "device references stable across round trip");
        JSONObject deviceReordered = copy(deviceView);
        deviceReordered.getJSONArray("providers").getJSONObject(0)
                .put("accounts", new JSONArray().put(deviceViewAccounts.get(1)).put(deviceViewAccounts.get(0)));
        JSONArray resolvedDeviceAccounts = ConfigurationView.restore(deviceReordered, device, salt)
                .getJSONArray("providers").getJSONObject(0).getJSONArray("accounts");
        check(resolvedDeviceAccounts.getJSONObject(0).getString("id").equals("acc-b")
                && resolvedDeviceAccounts.getJSONObject(0).getString("devicePrivateKey").equals(otherPem)
                && resolvedDeviceAccounts.getJSONObject(1).getString("id").equals("acc-a")
                && resolvedDeviceAccounts.getJSONObject(1).getString("devicePrivateKey").equals(privPem)
                && resolvedDeviceAccounts.length() == 2, "device key follows account id after reorder");
        reject(() -> ConfigurationView.restore(withDeviceKey(copy(deviceView), 0, forgedRef), device, salt));
        reject(() -> ConfigurationView.restore(withDeviceKey(copy(deviceView), 0, deviceRefA.substring(0, deviceRefA.length() - 1)), device, salt));
        reject(() -> ConfigurationView.restore(withDeviceKey(copy(deviceView), 0, "allin-secret-ref:"), device, salt));
        JSONObject rotatedDevice = copy(device);
        rotatedDevice.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0)
                .put("devicePrivateKey", "-----BEGIN PRIVATE KEY-----\nMIGTSYNTHETICROTATED\n-----END PRIVATE KEY-----");
        reject(() -> ConfigurationView.restore(deviceView, rotatedDevice, salt));
        reject(() -> ConfigurationView.restore(withDeviceKey(copy(deviceView), 1, deviceRefA), device, salt));
        reject(() -> ConfigurationView.restore(ConfigurationView.redact(device, "another-private-salt"), device, salt));
        // The public key field is not a secret sink, so a secret reference parked there is refused.
        JSONObject publicFieldRef = copy(deviceView);
        publicFieldRef.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0)
                .put("devicePublicKey", deviceRefA);
        reject(() -> ConfigurationView.restore(publicFieldRef, device, salt));
        // An explicit key rotation stays possible: a fresh plaintext PEM is accepted as a new value.
        JSONObject rotatedInView = copy(deviceView);
        rotatedInView.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0)
                .put("devicePrivateKey", "-----BEGIN PRIVATE KEY-----\nMIGTSYNTHETICNEWKEY\n-----END PRIVATE KEY-----");
        check(ConfigurationView.restore(rotatedInView, device, salt).getJSONArray("providers").getJSONObject(0)
                .getJSONArray("accounts").getJSONObject(0).getString("devicePrivateKey").contains("SYNTHETICNEWKEY"), "explicit device key rotation");
        // Accounts without an id are legacy but must stay protected and still round trip.
        JSONObject legacyDevice = new JSONObject().put("providers", new JSONArray().put(new JSONObject().put("id", "trae-cn")
                .put("accounts", new JSONArray().put(new JSONObject().put("name", "legacy").put("devicePrivateKey", privPem)))));
        JSONObject legacyView = ConfigurationView.redact(legacyDevice, salt);
        check(!legacyView.toString().contains("PRIVATE KEY"), "legacy device private key leaked");
        check(ConfigurationView.restore(legacyView, legacyDevice, salt).similar(legacyDevice), "legacy device round trip");
        reject(() -> ConfigurationView.restore(withDeviceKey(copy(legacyView), 0, forgedRef), legacyDevice, salt));
        // Nothing else that merely looks key-ish may be redacted, and an absent key stays empty.
        JSONObject identifiers = new JSONObject("{\"providers\":[{\"id\":\"p\",\"keyId\":\"trace-abc\",\"deviceId\":\"device-keep\",\"machineId\":\"machine-keep\",\"modelMeta\":{\"visible-model\":{\"contextWindow\":128000}}}]}");
        String identifierText = ConfigurationView.redact(identifiers, salt).toString();
        check(identifierText.contains("trace-abc") && identifierText.contains("device-keep") && identifierText.contains("machine-keep")
                && identifierText.contains("128000"), "key-like identifiers must not be redacted: " + identifierText);
        JSONObject emptyDevice = new JSONObject("{\"providers\":[{\"id\":\"trae-cn\",\"accounts\":[{\"id\":\"e\",\"devicePrivateKey\":\"\"}]}]}");
        check(ConfigurationView.redact(emptyDevice, salt).getJSONArray("providers").getJSONObject(0)
                .getJSONArray("accounts").getJSONObject(0).getString("devicePrivateKey").isEmpty(), "empty device key unchanged");
        System.out.println("ConfigurationViewOfflineTest passed");
    }
}
