package com.gaeul.allin;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Internal opaque domain IDs; never emitted in dashboard, errors or diagnostics. */
public final class CredentialIdentity {
    private static final java.util.Map<JSONObject,String> LEGACY=new java.util.WeakHashMap<>();
    private CredentialIdentity() { }
    public static String origin(String provider,String baseUrl) {
        String origin=baseUrl==null?"":baseUrl.trim();
        try { URI u=new URI(origin); origin=new URI(u.getScheme()==null?null:u.getScheme().toLowerCase(java.util.Locale.ROOT),null,
                u.getHost()==null?null:u.getHost().toLowerCase(java.util.Locale.ROOT),u.getPort(),u.getPath(),null,null).toString(); } catch(Exception ignored) { }
        return digest(provider+"\n"+origin);
    }
    public static String credential(String provider,String baseUrl,String credentialId,JSONObject account,String key) {
        String id=credentialId;
        if(account!=null) {
            id="";
            for(String field:new String[]{"id","accountId","uid","userId"}) if(!account.optString(field,"").isEmpty()) { id=field+":"+account.optString(field); break; }
            // Legacy account objects keep a process-private ID; never modify persisted account data.
            if(id.isEmpty()) synchronized(LEGACY) {
                id=LEGACY.get(account);
                if(id==null) { id=java.util.UUID.randomUUID().toString(); LEGACY.put(account,id); }
            }
        }
        if(id==null || id.isEmpty()) id=digest(key==null?"":key);
        return digest(origin(provider,baseUrl)+"\n"+id);
    }
    private static String digest(String text) {
        try { byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder s=new StringBuilder(64); for(byte b:bytes) s.append(String.format(java.util.Locale.ROOT,"%02x",b&255)); return s.toString();
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
}
