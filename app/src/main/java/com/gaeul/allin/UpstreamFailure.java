package com.gaeul.allin;

import org.json.JSONObject;
import java.util.Locale;

/** A rejection is different from an unknown result after sending. No general inference retry. */
public final class UpstreamFailure {
    public enum Kind { RATE, ACCOUNT_THROUGHPUT, QUOTA, AUTH, REQUEST, OVERLOAD, NETWORK, PROXY, UNKNOWN_EXECUTION, OTHER }
    public final Kind kind;
    public final String code;
    public final boolean fallback, sameCredentialReplay;
    private UpstreamFailure(Kind kind,String code,boolean fallback,boolean replay) {
        this.kind=kind; this.code=code; this.fallback=fallback; this.sameCredentialReplay=replay;
    }
    public static UpstreamFailure classify(String provider,int status,String body,boolean sent,boolean business) {
        String code="", text=body==null?"":body.toLowerCase(Locale.ROOT);
        try { JSONObject j=new JSONObject(body); JSONObject e=j.optJSONObject("error"); if(e!=null) code=e.optString("code",""); else code=j.optString("code",""); } catch(Exception ignored) { }
        if(business) return new UpstreamFailure(Kind.UNKNOWN_EXECUTION,code,false,false);
        if(status<=0) return new UpstreamFailure(sent?Kind.UNKNOWN_EXECUTION:Kind.NETWORK,code,false,false);
        if(status==407) return new UpstreamFailure(Kind.PROXY,code,false,false);
        if("raccoon".equals(provider) && "user_model_rate_limited".equals(code)) return new UpstreamFailure(Kind.ACCOUNT_THROUGHPUT,code,true,false);
        if(text.contains("insufficient_quota") || text.contains("balance") || text.contains("credit") || status==402) return new UpstreamFailure(Kind.QUOTA,code,true,false);
        if(status==401 || status==403) return new UpstreamFailure(Kind.AUTH,code,true,false);
        if(status==400 || status==413 || status==422) return new UpstreamFailure(Kind.REQUEST,code,false,false);
        if(status==429) return new UpstreamFailure(Kind.RATE,code,true,false);
        if(status==503 || status==502) return new UpstreamFailure(Kind.OVERLOAD,code,false,false);
        return new UpstreamFailure(Kind.OTHER,code,false,false);
    }
}
