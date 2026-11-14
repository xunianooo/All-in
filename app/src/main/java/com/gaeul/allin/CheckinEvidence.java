package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;
import java.io.IOException;
import java.net.*;
import javax.net.ssl.SSLException;

/** Non-secret check-in phase evidence: a failed read is not a lost claim. */
final class CheckinEvidence {
    static final class State { String phase="unknown";boolean claimPossible=true;int http;String code=""; }
    private static final ThreadLocal<State> CURRENT=new ThreadLocal<>();
    static void begin(){CURRENT.set(new State());}
    static void prepare(){State s=get();s.phase="credential";s.claimPossible=false;}
    static void query(){get().phase="status";}
    static void claim(){State s=get();s.phase="claim";s.claimPossible=true;}
    static void confirm(){get().phase="confirmation";}
    static void reply(int http,String code){State s=get();s.http=http;s.code=code!=null&&code.matches("[0-9]{1,10}")?code:"";}
    static State get(){State s=CURRENT.get();if(s==null){s=new State();CURRENT.set(s);}return s;}
    static void clear(){CURRENT.remove();}
    static JSONObject failure(Throwable error){
        State s=get();String kind="request_failed",type=error==null?"unclassified":error.getClass().getSimpleName();boolean auth=false;
        int depth=0;for(Throwable e=error; e!=null&&depth++<8; e=e.getCause()){
            if(e instanceof SSLException){kind="tls";break;}
            if(e instanceof UnknownHostException){kind="dns";break;}
            if(e instanceof SocketTimeoutException){kind="timeout";break;}
            if(e instanceof ConnectException){kind="connect";break;}
            if(e instanceof java.io.EOFException){kind="incomplete_response";break;}
            if(e instanceof LoomyAuth.Failure&&((LoomyAuth.Failure)e).expired)auth=true;
            if(e instanceof RaccoonAccountService.Failure&&((RaccoonAccountService.Failure)e).expired)auth=true;
        }
        if(s.http==401||s.http==403)auth=true;
        String why=auth?"登录状态已失效或没有该接口权限，请重新登录后查询"
                :"tls".equals(kind)?"TLS 证书或握手校验失败"
                :"dns".equals(kind)?"域名解析失败"
                :"timeout".equals(kind)?"请求超时"
                :"connect".equals(kind)?"连接未建立"
                :"incomplete_response".equals(kind)?"上游响应未完整接收"
                :s.http>0?"上游 HTTP "+s.http+(s.code.isEmpty()?"":"，业务码 "+s.code)
                :"请求或响应解析失败（"+type+"）";
        boolean queryOnly=!s.claimPossible;
        String outcome=auth?"auth_required":queryOnly?"failed":"unknown";
        return new JSONObject().put("outcome",outcome).put("phase",s.phase).put("claimPossible",s.claimPossible)
                .put("reasonCode",kind).put("exceptionType",type).put("httpStatus",s.http).put("businessCode",s.code)
                .put("safeToRetry",queryOnly&&!auth).put("message",(queryOnly?"查询失败：":"领取后的结果未能确认：")+why+(queryOnly?"；未发送领取请求":"；可查询状态确认，不盲目重复领取"));
    }
    static JSONObject resultFailure(JSONObject raw){
        State s=get();if(raw.has("claimStatus"))s.http=raw.optInt("claimStatus");else if(raw.has("status"))s.http=raw.optInt("status");
        return failure(new IOException("provider response rejected"));
    }
}
