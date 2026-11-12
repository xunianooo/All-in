package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

/** Account identity is provider-scoped; a display name is never an identity. */
final class AccountPoolPolicy {
    private AccountPoolPolicy() {}
    static String newId(String prefix) { return prefix + UUID.randomUUID().toString(); }
    private static String value(JSONObject account, String key) {
        return account == null ? "" : account.optString(key, "").trim();
    }
    private static boolean scope(JSONObject a, JSONObject b) {
        for (String key : new String[]{"site", "domain", "enterpriseId"})
            if (!value(a,key).equals(value(b,key))) return false;
        for (String key : new String[]{"authMode", "type"}) {
            String x=value(a,key), y=value(b,key);
            if (!x.isEmpty() && !y.isEmpty() && !x.equals(y)) return false;
        }
        return true;
    }
    static boolean same(JSONObject a, JSONObject b, String provider) {
        if (a == null || b == null || !scope(a,b)) return false;
        String[] identity = "codex".equals(provider)
                ? new String[]{"accountId", "uid", "subject", "githubLogin", "email"}
                : new String[]{"uid", "userId", "userID", "subject", "accountId", "githubLogin", "login", "email"};
        // The most authoritative shared field wins. Never merge a conflicting uid by matching email.
        for (String key : identity) {
            String x=value(a,key), y=value(b,key);
            if (!x.isEmpty() && !y.isEmpty())
                return ("email".equals(key) || "githubLogin".equals(key) || "login".equals(key)) ? x.equalsIgnoreCase(y) : x.equals(y);
        }
        // With no verified owner, only identical credential material can identify a re-import.
        for (String key : new String[]{"token", "access", "refresh", "jwt"}) {
            String x=value(a,key), y=value(b,key);
            if (!x.isEmpty() && x.equals(y)) return true;
        }
        return false;
    }
    static JSONObject addOrUpdate(JSONArray accounts, JSONObject incoming, String provider) {
        JSONObject found=null;
        for (int i=0;i<accounts.length();i++) {
            JSONObject account=accounts.optJSONObject(i);
            if (same(account,incoming,provider)) {
                if (found!=null) throw new IllegalArgumentException("Ambiguous account identity; resolve duplicate records first");
                found=account;
            }
        }
        if (found==null) {
            String id=value(incoming,"id"); boolean duplicate=id.isEmpty();
            for (int i=0;i<accounts.length();i++)
                if (id.equals(value(accounts.optJSONObject(i),"id"))) duplicate=true;
            if (duplicate) incoming.put("id",newId(provider+"_"));
            if (!incoming.has("addedAt")) incoming.put("addedAt",System.currentTimeMillis());
            incoming.put("enabled",true);
            accounts.put(incoming);
            return incoming;
        }
        Iterator<String> names=incoming.keys();
        while (names.hasNext()) {
            String key=names.next();
            if ("id".equals(key) || "addedAt".equals(key)) continue;
            found.put(key,incoming.opt(key));
        }
        if (value(found,"id").isEmpty()) found.put("id",newId(provider+"_"));
        // Called only by explicit sign-in/import, never by token refresh or catalog synchronization.
        found.put("enabled",true).put("updatedAt",System.currentTimeMillis());
        return found;
    }
    /** Eligible persisted accounts plus independent legacy keys (not disabled account mirrors). */
    static java.util.List<JSONObject> routingAccounts(JSONObject provider) {
        java.util.List<JSONObject> result=new java.util.ArrayList<>();
        Set<String> owned=new HashSet<>();
        JSONArray accounts=provider.optJSONArray("accounts");
        for(int i=0;accounts!=null && i<accounts.length();i++) {
            JSONObject account=accounts.optJSONObject(i);
            if(account==null)continue;
            for(String field:new String[]{"token","access"}) {String token=value(account,field);if(!token.isEmpty())owned.add(token);}
            String token=value(account,"access");if(token.isEmpty())token=value(account,"token");
            if(account.optBoolean("enabled",true) && !token.isEmpty())result.add(account);
        }
        JSONArray keys=provider.optJSONArray("keys");
        for(int i=0;keys!=null && i<keys.length();i++) {
            Object raw=keys.opt(i); if(!(raw instanceof String))continue;
            String token=((String)raw).trim();
            if(token.isEmpty() || !owned.add(token))continue;
            String providerId=provider.optString("id","");
            String id="legacy-key_"+UUID.nameUUIDFromBytes((providerId+"\u0000"+token).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String type="workbuddy".equals(providerId) && !token.startsWith("ck_") && !token.startsWith("pt_")?"token":"api";
            JSONObject slot=new JSONObject().put("id",id).put("token",token).put("type",type).put("source","legacy-key").put("enabled",true);
            JSONObject catalogs=provider.optJSONObject("legacyKeyCatalog");
            JSONObject catalog=catalogs==null?null:catalogs.optJSONObject(id);
            if(catalog!=null && catalog.optJSONArray("models")!=null) {
                slot.put("models",catalog.get("models"));
                if(catalog.has("modelMeta"))slot.put("modelMeta",catalog.get("modelMeta"));
            }
            result.add(slot);
        }
        return result;
    }

    static JSONObject upsert(JSONObject provider, JSONObject incoming, String providerId) {
        JSONArray accounts=provider.optJSONArray("accounts");
        if (accounts==null) { accounts=new JSONArray(); provider.put("accounts",accounts); }
        JSONArray before=new JSONArray(accounts.toString());
        JSONObject saved=addOrUpdate(accounts,incoming,providerId);
        synchronizeKeys(provider,before);
        return saved;
    }
    /** Replace only account-owned key mirrors; preserve independent manually configured keys. */
    static void synchronizeKeys(JSONObject provider, JSONArray before) {
        Set<String> previouslyOwned=new HashSet<>();
        for (int i=0;before!=null && i<before.length();i++) {
            JSONObject account=before.optJSONObject(i);
            for (String field:new String[]{"access","token"}) {
                String token=value(account,field);
                if (!token.isEmpty()) previouslyOwned.add(token);
            }
        }
        JSONArray keys=new JSONArray(), old=provider.optJSONArray("keys");
        Set<String> seen=new HashSet<>();
        for (int i=0;old!=null && i<old.length();i++) {
            Object key=old.opt(i);
            if (key instanceof String) {
                String text=((String)key).trim();
                if (!text.isEmpty() && !previouslyOwned.contains(text) && seen.add(text)) keys.put(key);
            } else if (key instanceof JSONObject) keys.put(key);
        }
        JSONArray accounts=provider.optJSONArray("accounts");
        for (int i=0;accounts!=null && i<accounts.length();i++) {
            JSONObject account=accounts.optJSONObject(i);
            if (account==null || !account.optBoolean("enabled",true)) continue;
            String token=value(account,"access"); if (token.isEmpty()) token=value(account,"token");
            if (!token.isEmpty() && seen.add(token)) keys.put(token);
        }
        provider.put("keys",keys);
    }
}
