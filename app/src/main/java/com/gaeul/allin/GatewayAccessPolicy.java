package com.gaeul.allin;

import java.net.URI;
import java.util.Map;

/** One route classification for auth and compatibility aliases. No implicit loopback privilege. */
final class GatewayAccessPolicy {
    static String canonical(String path) { return "/responses".equals(path) ? "/v1/responses" : path; }
    static boolean responses(String path) {return "/responses".equals(path) || "/v1/responses".equals(path);}
    static boolean model(String path) {
        path = canonical(path);
        return "/v1/chat/completions".equals(path) || "/v1/messages".equals(path)
                || "/v1/responses".equals(path) || "/v1/models".equals(path)
                || "/v1/images/generations".equals(path)
                || "/backend-api/codex/responses".equals(path)
                || "/backend-api/codex/responses/compact".equals(path)
                || "/backend-api/codex/models".equals(path);
    }
    static boolean management(String path) { return path != null && ("/api".equals(path) || path.startsWith("/api/")) && !"/api/health".equals(path); }
    static boolean staticRoute(String path) {
        return "/".equals(path) || "/index.html".equals(path) || "/dashboard.html".equals(path) || "/licenses.html".equals(path)
                || "/icon.png".equals(path) || "/favicon.ico".equals(path) || "/favicon.png".equals(path)
                || (path != null && path.matches("/provider-icons/[a-z0-9_-]+[.](svg|png)"));
    }
    static boolean publicRoute(String path) {
        return "/".equals(path) || "/index.html".equals(path) || "/dashboard.html".equals(path) || "/licenses.html".equals(path)
                || "/icon.png".equals(path) || "/favicon.ico".equals(path) || "/favicon.png".equals(path)
                || "/auth/callback".equals(path) || "/api/health".equals(path)
                || (path != null && path.matches("/provider-icons/[a-z0-9_-]+\\.(svg|png)"));
    }
    static boolean known(String path) { return model(path) || management(path) || publicRoute(path); }
    static boolean managementCredential(Map<String,String> headers) {
        if (headers == null) return false;
        if (GatewayManagementCredential.matches(headers.get("x-all-in-management"))) return true;
        String auth = headers.get("authorization");
        return auth != null && auth.regionMatches(true,0,"Bearer ",0,7)
                && GatewayManagementCredential.matches(auth.substring(7).trim());
    }
    static boolean hostAllowed(String authority) {
        if (authority == null || authority.isEmpty()) return false;
        try {
            URI uri = new URI("http://" + authority);
            String host = uri.getHost();
            if (uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null
                    || !"".equals(uri.getRawPath()) || host==null || uri.getPort() < -1 || uri.getPort() > 65535) return false;
            if ("localhost".equalsIgnoreCase(host)) return true;
            if (host.startsWith("[") && host.endsWith("]")) host=host.substring(1,host.length()-1);
            if (host.indexOf(':')>=0) return host.matches("[0-9a-fA-F:.]+");
            String[] parts=host.split("\\.",-1); if(parts.length!=4)return false;
            for(String p:parts) if(!p.matches("[0-9]{1,3}") || Integer.parseInt(p)>255)return false;
            return true;
        } catch(Exception invalid) { return false; }
    }
    static boolean originAllowed(Map<String,String> headers) {
        if (headers == null) return false;
        String host=headers.get("host"), origin=headers.get("origin");
        if (host == null || !hostAllowed(host)) return false;
        if (origin==null) return true; // Native/SDK callers still need an actual credential.
        if ("null".equals(origin)) return managementCredential(headers);
        if (host==null || !hostAllowed(host)) return false;
        try {
            URI u=new URI(origin), target=new URI("http://"+host);
            if(!"http".equals(u.getScheme()) || u.getUserInfo()!=null || u.getRawQuery()!=null
                    || u.getRawFragment()!=null || !u.getRawPath().isEmpty())return false;
            int a=u.getPort()<0?80:u.getPort(), b=target.getPort()<0?80:target.getPort();
            return u.getHost()!=null && u.getHost().equalsIgnoreCase(target.getHost()) && a==b;
        } catch(Exception invalid) { return false; }
    }
}
