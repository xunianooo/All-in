package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Stateless public view of configuration; callers must keep the salt private and stable. */
public final class ConfigurationView {
    private static final String PREFIX = "allin-secret-ref:";
    private ConfigurationView() {}

    public static JSONObject redact(JSONObject source, String privateSalt) {
        require(source, privateSalt);
        return (JSONObject) transform(source, null, "", privateSalt, false, false, false);
    }

    public static JSONObject restore(JSONObject incoming, JSONObject current, String privateSalt) {
        require(incoming, privateSalt);
        if (current == null) throw new IllegalArgumentException("current configuration required");
        return (JSONObject) transform(incoming, current, "", privateSalt, true, false, false);
    }

    private static void require(JSONObject object, String salt) {
        if (object == null || salt == null || salt.isEmpty())
            throw new IllegalArgumentException("configuration and private salt required");
    }

    private static String part(String tag, String value) {
        return "/" + tag + value.length() + ":" + value;
    }

    // Names are matched exactly after folding case and separators. A broader rule such as "any name
    // containing key" would also redact public keys, key ids and the provider "keys" pool, so a new
    // secret field must be listed here explicitly.
    private static boolean sensitive(String key) {
        String normalized=key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        if(normalized.endsWith("token") || normalized.endsWith("secret") || normalized.equals("devicerefresh")) return true;
        switch (normalized) {
            case "token": case "access": case "accesstoken": case "refresh":
            case "refreshtoken": case "jwt": case "apikey": case "authkey":
            case "password": case "passwd": case "secret": case "clientsecret":
            case "idtoken": case "cookie": case "authorization": case "sessionid": case "session":
            case "sid": case "credential": case "accesskey": case "privatekey": return true;
            // Trae device binding stores a PEM EC private key next to its public key; only the
            // private half is secret, so the public half stays readable for pairing and diagnostics.
            case "deviceprivatekey": return true;
            default: return false;
        }
    }

    private static String reference(String salt, String path, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = mac.doFinal((part("p", path) + part("v", value)).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(PREFIX);
            for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException("HMAC unavailable", e); }
    }

    private static Object transform(Object value, Object current, String path, String salt,
                                    boolean restoring, boolean secret, boolean keys) {
        if (value instanceof String) {
            String text = (String) value;
            if (restoring && text.startsWith(PREFIX)) {
                if (!secret || !(current instanceof String) || ((String) current).isEmpty()
                        || !MessageDigest.isEqual(text.getBytes(StandardCharsets.UTF_8),
                                reference(salt, path, (String) current).getBytes(StandardCharsets.UTF_8)))
                    throw new IllegalArgumentException("invalid or stale secret reference");
                return current;
            }
            return !restoring && secret && !text.isEmpty() ? reference(salt, path, text) : text;
        }
        if (value instanceof JSONObject) {
            JSONObject result = new JSONObject();
            JSONObject old = current instanceof JSONObject ? (JSONObject) current : null;
            JSONObject object = (JSONObject) value;
            for (Iterator<String> it = object.keys(); it.hasNext();) {
                String key = it.next();
                boolean isKeys = key.equalsIgnoreCase("keys") && object.opt(key) instanceof JSONArray;
                result.put(key, transform(object.get(key), old == null ? null : old.opt(key),
                        path + part("f", key), salt, restoring,
                        sensitive(key) || (keys && key.equalsIgnoreCase("key"))
                                || (path.contains(part("d","zcode")) && ("plan".equals(key) || "gift".equals(key))), isKeys));
            }
            return result;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            JSONArray old = current instanceof JSONArray ? (JSONArray) current : null;
            if (keys) return transformKeys(array, old, path, salt, restoring);
            boolean identities = path.endsWith(part("f", "providers")) || path.endsWith(part("f", "accounts"));
            if (identities) { checkIds(array); if (old != null) checkIds(old); }
            JSONArray result = new JSONArray();
            for (int i = 0; i < array.length(); i++) {
                Object item = array.get(i);
                Object previous = old != null && i < old.length() ? old.get(i) : null;
                String id = identities ? id(item) : null;
                if (id != null) previous = findById(old, id);
                String itemPath = path + (id == null ? part("i", Integer.toString(i)) : part("d", id));
                if (restoring && identities && id == null && containsReference(item)) {
                    if (previous == null || !equivalent(item, transform(previous, null, itemPath, salt, false, false, false)))
                        throw new IllegalArgumentException("changed unidentifiable array item");
                }
                result.put(transform(item, previous, itemPath, salt, restoring, keys && item instanceof String, keys));
            }
            return result;
        }
        return value;
    }

    // Key references identify the secret within this container and field, independent of order.
    private static JSONArray transformKeys(JSONArray array, JSONArray old, String path,
                                           String salt, boolean restoring) {
        JSONArray result = new JSONArray();
        for (int i = 0; i < array.length(); i++) {
            Object item = array.get(i);
            Object previous = old != null && i < old.length() ? old.get(i) : null;
            String indexedPath = path + part("i", Integer.toString(i));
            if (item instanceof String) {
                result.put(keyValue((String) item, old, path + part("k", "string"), null, salt, restoring));
            } else if (item instanceof JSONObject) {
                JSONObject object = (JSONObject) item;
                JSONObject prior = previous instanceof JSONObject ? (JSONObject) previous : null;
                JSONObject out = new JSONObject();
                for (Iterator<String> it = object.keys(); it.hasNext();) {
                    String name = it.next();
                    Object field = object.get(name);
                    if (name.equalsIgnoreCase("key") && field instanceof String) {
                        out.put(name, keyValue((String) field, old,
                                path + part("k", "object") + part("f", name), name, salt, restoring));
                    } else {
                        boolean childKeys = name.equalsIgnoreCase("keys") && field instanceof JSONArray;
                        out.put(name, transform(field, prior == null ? null : prior.opt(name),
                                indexedPath + part("f", name), salt, restoring,
                                sensitive(name) || name.equalsIgnoreCase("key")
                                        || (path.contains(part("d", "zcode")) && ("plan".equals(name) || "gift".equals(name))), childKeys));
                    }
                }
                result.put(out);
            } else {
                result.put(transform(item, previous, indexedPath, salt, restoring, false, true));
            }
        }
        return result;
    }

    private static String keyValue(String value, JSONArray old, String path, String field,
                                   String salt, boolean restoring) {
        if (!restoring) return value.isEmpty() ? value : reference(salt, path, value);
        if (!value.startsWith(PREFIX)) return value;
        if (old != null) for (int i = 0; i < old.length(); i++) {
            Object item = old.get(i);
            Object candidate = field == null ? item : item instanceof JSONObject ? ((JSONObject) item).opt(field) : null;
            if (candidate instanceof String && !((String) candidate).isEmpty()
                    && MessageDigest.isEqual(value.getBytes(StandardCharsets.UTF_8),
                            reference(salt, path, (String) candidate).getBytes(StandardCharsets.UTF_8)))
                return (String) candidate;
        }
        throw new IllegalArgumentException("invalid or stale key reference");
    }

    private static String id(Object value) {
        if (!(value instanceof JSONObject)) return null;
        Object id = ((JSONObject) value).opt("id");
        return id instanceof String && !((String) id).isEmpty() ? (String) id : null;
    }
    private static void checkIds(JSONArray array) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            String id = id(array.get(i));
            if (id != null && !seen.add(id)) throw new IllegalArgumentException("duplicate array id");
        }
    }
    private static Object findById(JSONArray array, String id) {
        if (array != null) for (int i = 0; i < array.length(); i++)
            if (id.equals(id(array.get(i)))) return array.get(i);
        return null;
    }
    private static boolean containsReference(Object value) {
        if (value instanceof String) return ((String) value).startsWith(PREFIX);
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject) value;
            for (Iterator<String> it = o.keys(); it.hasNext();) if (containsReference(o.get(it.next()))) return true;
        } else if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value;
            for (int i = 0; i < a.length(); i++) if (containsReference(a.get(i))) return true;
        }
        return false;
    }
    private static boolean equivalent(Object a, Object b) {
        if (a instanceof JSONObject && b instanceof JSONObject) return ((JSONObject) a).similar(b);
        if (a instanceof JSONArray && b instanceof JSONArray) return ((JSONArray) a).similar(b);
        return a == null ? b == null : a.equals(b);
    }
}
