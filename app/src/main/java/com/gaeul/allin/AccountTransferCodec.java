package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import com.gaeul.allin.json.JSONTokener;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;

/** Local, bounded credential-file codec. Never performs authentication or vendor requests. */
final class AccountTransferCodec {
    static final String FORMAT = "all-in-accounts";
    static final int VERSION = 1, MAX_BYTES = 4 * 1024 * 1024, MAX_ENTRIES = 1024;
    static final List<String> PROVIDERS = Collections.unmodifiableList(Arrays.asList(
            "zcode", "workbuddy", "workbuddy-ai", "trae-cn", "catpaw", "deepseek_web", "raccoon", "loomy",
            "deepseek", "siliconflow", "openai", "anthropic", "openrouter", "tencent", "qoder_cn", "cline",
            "codex", "antigravity", "github-copilot", "minimax-code"));
    private static final Set<String> KEY_PROVIDERS = new HashSet<>(Arrays.asList(
            "workbuddy-ai", "deepseek", "siliconflow", "openai", "anthropic", "openrouter", "tencent"));
    private static final Set<String> GLOBAL_FIELDS = new HashSet<>(Arrays.asList(
            "server", "providers", "groups", "guard", "privacy", "network", "proxy", "headers", "baseUrl",
            "endpoint", "endpoints", "script", "command", "__proto__", "constructor", "prototype"));
    static final class Entry {
        final int index;
        String provider, hint, kind, source;
        JSONObject value, protection;
        String problem = "", warning = "";
        Entry(int i, String p, String k, JSONObject v, String src) {
            index=i; provider=p; hint=p; kind=k; value=v; source=src;
        }
        String label() {
            String name=value.optString("name", value.optString("label", ""));
            if(name.isEmpty()) name="账号 " + (index+1);
            for(String k : new String[]{"token","access","access_token","accessToken","refresh","refresh_token","refreshToken","jwt","idToken","id_token","key","apiKey","api_key","deviceToken","deviceRefresh","devicePrivateKey"}) {
                String secret=value.optString(k, ""); if(!secret.isEmpty()) name=name.replace(secret,"[隐藏凭据]");
            }
            if(name.contains("allin-secret-ref:") || name.matches(".*eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\..*")) name="账号 " + (index+1);
            name=name.replaceAll("[\\p{Cntrl}]", " ");
            return name.length()>72 ? name.substring(0,72)+"…" : name;
        }
        boolean valid() { return problem.isEmpty() && PROVIDERS.contains(provider); }
    }
    static final class Document {
        final List<Entry> entries = new ArrayList<>();
        String source = "";
        int ignoredContainers;
        void add(Entry entry) {
            if(entries.size() >= MAX_ENTRIES) throw invalid("文件账号数量超过限制");
            entries.add(entry);
        }
    }
    static final class TransferException extends IllegalArgumentException {
        TransferException(String message) { super(message); }
    }
    static TransferException invalid(String message) { return new TransferException(message); }
    static JSONObject copy(JSONObject o) { return new JSONObject(o.toString()); }
    static byte[] digest(byte[] b) {
        try { return MessageDigest.getInstance("SHA-256").digest(b); }
        catch(Exception e) { throw new IllegalStateException("digest unavailable"); }
    }
    static byte[] readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buffer=new byte[8192]; int n,total=0;
        while((n=in.read(buffer))!=-1) {
            if(Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            total+=n; if(total>MAX_BYTES) throw new IOException("账号文件超过4 MiB限制"); out.write(buffer,0,n);
        }
        return out.toByteArray();
    }
    static String decode(byte[] b) {
        if(b==null || b.length==0 || b.length>MAX_BYTES) throw invalid("账号文件为空或超过4 MiB限制");
        Charset charset=StandardCharsets.UTF_8; int skip=0;
        if(b.length>=3 && (b[0]&255)==239 && (b[1]&255)==187 && (b[2]&255)==191) skip=3;
        else if(b.length>=2 && (b[0]&255)==255 && (b[1]&255)==254) { charset=StandardCharsets.UTF_16LE;skip=2; }
        else if(b.length>=2 && (b[0]&255)==254 && (b[1]&255)==255) { charset=StandardCharsets.UTF_16BE;skip=2; }
        try { return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b,skip,b.length-skip)).toString(); }
        catch(CharacterCodingException e) { throw invalid("文件编码无效，请使用UTF-8或带BOM的UTF-16 JSON"); }
    }
    static Object json(byte[] bytes) {
        String text=decode(bytes);
        try {
            if(!StandardCharsets.UTF_8.newEncoder().canEncode(text)) throw invalid("invalid unicode");
            new BoundedStrictJson(text).run();
            if(text.contains("allin-secret-ref:")) throw invalid("masked credential");
            Object value=new JSONTokener(text).nextValue();
            if(!(value instanceof JSONObject) && !(value instanceof JSONArray)) throw invalid("not an account document");
            return value;
        } catch(Exception e) { throw invalid("文件不是完整有效的账号JSON，或含重复字段、脱敏引用、异常嵌套"); }
    }
    static String canonicalProvider(String raw) {
        if(raw==null) return "";
        String p=raw.trim().toLowerCase(Locale.ROOT);
        int slot=p.indexOf('#'); if(slot>=0)p=p.substring(0,slot);
        if(p.startsWith("plugin:"))p=p.substring(7);
        if(p.startsWith("@magpie-community/"))p=p.substring("@magpie-community/".length());
        if(p.startsWith("opencode-"))p=p.substring(9);
        if(p.endsWith("-auth"))p=p.substring(0,p.length()-5);
        switch(p) {
            case "qoder":case "qoder-cn":return "qoder_cn";
            case "trae":case "trae_cn":return "trae-cn";
            case "copilot":case "github_copilot":return "github-copilot";
            case "minimax":case "minimax_code":return "minimax-code";
            case "deepseek-web":return "deepseek_web";
            case "workbuddy-cn":case "workbuddy_cn":return "workbuddy";
            case "raccoon-code":return "raccoon";
            case "chatgpt":return "codex";
            default:return PROVIDERS.contains(p)?p:"";
        }
    }
    private static String explicitProvider(JSONObject o) {
        for(String k:new String[]{"providerId","provider","agent"}) {
            Object raw=o.opt(k); if(raw instanceof String && !((String)raw).trim().isEmpty())return (String)raw;
        }
        return "";
    }
    static Document parse(byte[] bytes,String selectedProvider) {
        Object root=json(bytes); Document d=new Document(); String hint=canonicalProvider(selectedProvider);
        if(root instanceof JSONArray) { d.source="legacy-array"; records(d,(JSONArray)root,hint,"accounts",d.source,null); }
        else {
            JSONObject o=(JSONObject)root;
            if(o.has("format")) {
                if(!FORMAT.equals(o.optString("format")) || !(o.opt("version") instanceof Number)
                        || ((Number)o.opt("version")).doubleValue()!=VERSION || !(o.opt("providers") instanceof JSONArray))
                    throw invalid("不支持此账号文件格式版本；加密备份请使用备份恢复入口");
                d.source="all-in-v1"; providerBlocks(d,o.getJSONArray("providers"),d.source);
            } else if(o.optJSONArray("providers")!=null) {
                d.source="all-in-config"; providerBlocks(d,o.getJSONArray("providers"),d.source);
            } else if(o.optJSONObject("plugins")!=null) {
                d.source="magpie-signins"; dictionary(d,o.getJSONObject("plugins"),d.source);
                if(o.optJSONArray("logins")!=null) records(d,o.getJSONArray("logins"),"","accounts",d.source,null);
            } else if(o.optJSONArray("accounts")!=null) {
                String id=explicitProvider(o); d.source="account-list";
                records(d,o.getJSONArray("accounts"),id.isEmpty()?hint:id,"accounts",d.source,null);
            } else if(o.optJSONObject("tokens")!=null && (o.has("auth_mode") || o.getJSONObject("tokens").has("access_token"))) {
                d.source="codex-auth"; add(d,o,"codex","accounts",d.source,null);
            } else if(hasCredential(o) || o.optJSONObject("auth")!=null || o.optJSONObject("credentials")!=null) {
                d.source="single-account"; String id=explicitProvider(o);add(d,o,id.isEmpty()?hint:id,"accounts",d.source,null);
            } else { d.source="opencode-auth-map"; dictionary(d,o,d.source); }
        }
        if(d.entries.isEmpty())throw invalid("JSON中没有可读取的账号或API Key记录");
        return d;
    }
    private static void providerBlocks(Document d,JSONArray list,String src) {
        for(int i=0;i<list.length();i++) {
            JSONObject p=list.optJSONObject(i); if(p==null) { bad(d,"供应商分组不是对象",src);continue; }
            String id=p.optString("id",explicitProvider(p));JSONObject fences=p.optJSONObject("checkinRecords");
            if(p.optJSONArray("accounts")!=null)records(d,p.getJSONArray("accounts"),id,"accounts",src,fences);
            if(p.optJSONArray("keys")!=null)records(d,p.getJSONArray("keys"),id,"keys",src,null);
        }
    }
    private static void dictionary(Document d,JSONObject o,String src) {
        for(Iterator<String> it=o.keys();it.hasNext();) {
            String key=it.next();Object v=o.opt(key);
            if(v instanceof JSONObject) {
                JSONObject a=(JSONObject)v;
                if(a.optJSONArray("accounts")!=null)records(d,a.getJSONArray("accounts"),key,"accounts",src,null);
                else add(d,a,key,"accounts",src,null);
            } else if(v instanceof JSONArray)records(d,(JSONArray)v,key,"accounts",src,null);
            else { d.ignoredContainers++;bad(d,"该项不包含账号对象",src); }
        }
    }
    private static void records(Document d,JSONArray rows,String p,String kind,String src,JSONObject fences) {
        for(int i=0;i<rows.length();i++) {
            Object v=rows.opt(i);JSONObject a;
            if(v instanceof JSONObject)a=(JSONObject)v;
            else if("keys".equals(kind) && v instanceof String)a=new JSONObject().put("key",v);
            else { bad(d,"账号记录不是对象",src);continue; }
            String own=explicitProvider(a);String provider=own.isEmpty()?p:own;
            JSONObject protection=fences==null?null:fences.optJSONObject(a.optString("id"));
            add(d,a,provider,kind,src,protection);
        }
    }
    private static void bad(Document d,String message,String src) {
        Entry e=new Entry(d.entries.size(),"","accounts",new JSONObject(),src);e.problem=message;d.add(e);
    }
    private static void add(Document d,JSONObject raw,String provider,String kind,String src,JSONObject protection) {
        // Pinned ZCode 3.17.2 stores the full session inside OAuth.refresh, not a
        // refresh token. A dual subscription/session becomes two explicit selectable records.
        if("zcode".equals(canonicalProvider(provider)) && raw.optString("refresh").trim().startsWith("{")) {
            try {
                String encoded=raw.getString("refresh");new BoundedStrictJson(encoded).run();JSONObject state=new JSONObject(encoded);
                if((state.has("jwt")&&!(state.opt("jwt") instanceof String))||(state.has("key")&&!(state.opt("key") instanceof String)))throw invalid("ZCode凭据字段类型错误");
                String jwt=state.optString("jwt"),key=state.optString("key");
                if(jwt.isEmpty()&&key.isEmpty())throw invalid("ZCode状态封装缺少会话或API Key");
                JSONObject base=copy(raw);base.remove("refresh");base.remove("access");base.remove("token");base.remove("key");
                for(String field:new String[]{"site","device","plan","uid"})if(state.has(field))base.put(field,state.opt(field));
                if(!jwt.isEmpty()) {
                    JSONObject session=copy(base).put("type","oauth").put("jwt",jwt).put("token",jwt).put("access",jwt);
                    add(d,session,provider,"accounts",src,protection);
                }
                if(!key.isEmpty()) {
                    JSONObject paid=new JSONObject().put("type","api").put("key",key).put("label",raw.optString("name","ZCode订阅Key"));
                    if(base.has("enabled"))paid.put("enabled",base.opt("enabled"));if(base.has("site"))paid.put("site",base.opt("site"));
                    add(d,paid,provider,"keys",src,null);
                }
            } catch(Exception invalid){bad(d,"ZCode封装状态不完整，请使用完整账号文件",src);}
            return;
        }
        String resolved=canonicalProvider(provider);
        if("openai".equals(resolved)&&"oauth".equals(raw.optString("type"))
                &&(src.equals("opencode-auth-map")||src.equals("magpie-signins")))resolved="codex";
        Entry e=new Entry(d.entries.size(),resolved,kind,copy(raw),src);e.hint=provider;
        e.protection=protection==null?null:copy(protection);d.add(e);
        if(e.provider.isEmpty()) { e.problem="无法确定供应商，请指定或跳过";return; }
        normalize(e);
    }
    static void assign(Entry e,String provider) {
        String p=canonicalProvider(provider);if(p.isEmpty())throw invalid("不支持的供应商");
        e.provider=p;e.problem="";normalize(e);e.warning="用户指定供应商；登录有效性尚未验证";
    }
    private static boolean hasCredential(JSONObject a) {
        for(String k:new String[]{"token","access","accessToken","access_token","refresh","refreshToken","refresh_token","jwt","key","apiKey","api_key","OPENAI_API_KEY","deviceToken"})
            if(a.opt(k) instanceof String && !a.optString(k).trim().isEmpty())return true;
        return false;
    }
    private static void alias(JSONObject a,String dest,String... sources) {
        String selected=a.optString(dest,"");
        for(String source:sources) if(a.has(source)) {
            Object v=a.opt(source);if(v!=JSONObject.NULL && !(v instanceof String))throw invalid("认证字段类型不正确");
            String value=v==JSONObject.NULL?"":((String)v).trim();
            if(!selected.isEmpty()&&!value.isEmpty()&&!selected.equals(value))throw invalid("同一认证字段存在矛盾值");
            if(selected.isEmpty())selected=value;a.remove(source);
        }
        if(!selected.isEmpty())a.put(dest,selected);
    }
    private static void flatten(JSONObject a,String key) {
        JSONObject nested=a.optJSONObject(key);if(nested==null)return;
        for(Iterator<String> it=nested.keys();it.hasNext();) {
            String field=it.next();Object v=nested.opt(field);
            if(a.has(field) && !String.valueOf(a.opt(field)).equals(String.valueOf(v)))throw invalid("嵌套认证资料互相矛盾");
            a.put(field,v);
        }
        a.remove(key);
    }
    static void normalize(Entry e) {
        try {
            JSONObject a=copy(e.value);
            AccountTransferProtection.validate(a);
            flatten(a,"credentials");flatten(a,"auth");flatten(a,"tokens");
            AccountTransferProtection.validate(a);
            for(String k:GLOBAL_FIELDS)a.remove(k);
            for(String k:new String[]{"providerId","provider","agent"})a.remove(k);
            JSONObject metadata=a.optJSONObject("metadata");
            if(metadata!=null)for(String k:new String[]{"uid","accountId","email","domain","enterpriseId","site","deviceId","machineId","projectId"})
                if(!a.has(k)&&metadata.has(k))a.put(k,metadata.opt(k));
            alias(a,"access","accessToken","access_token");alias(a,"refresh","refreshToken","refresh_token");
            alias(a,"idToken","id_token");alias(a,"accountId","account_id");alias(a,"authMode","auth_mode");
            alias(a,"key","apiKey","api_key","OPENAI_API_KEY");
            if("success".equals(a.optString("type")))a.put("type","oauth");
            if(!a.has("uid"))for(String key:new String[]{"userId","userID"})if(a.has(key)){a.put("uid",a.optString(key));break;}
            if("qoder_cn".equals(e.provider)&&!a.has("expiresAt")&&a.has("expires"))a.put("expiresAt",a.opt("expires"));
            if("antigravity".equals(e.provider)) {
                String refresh=a.optString("refresh");int separator=refresh.indexOf('|');
                if(separator>=0) {
                    if(separator==0)throw invalid("Antigravity缺少刷新令牌");
                    String project=refresh.substring(separator+1);
                    if(a.has("project")&&!a.optString("project").equals(project))throw invalid("Antigravity项目资料矛盾");
                    a.put("refresh",refresh.substring(0,separator)).put("project",project);
                }
                if(!a.has("email")&&a.optString("accountId").contains("@"))a.put("email",a.optString("accountId"));
            }
            for(String field:new String[]{"device","deviceId","machineId","clientId","site","domain","enterpriseId","authHost","project"}) {
                Object v=a.opt(field);if(v!=null&&v!=JSONObject.NULL&&(!(v instanceof String)||((String)v).matches("(?s).*[\\x00-\\x1f\\x7f].*")))throw invalid("账号身份或站点字段类型不正确");
            }
            if("Bearer".equalsIgnoreCase(a.optString("tokenType")))a.put("tokenType","Bearer");
            if(a.has("enabled")&&!(a.opt("enabled") instanceof Boolean))throw invalid("启用状态必须是布尔值");
            if(a.has("off")&&!(a.opt("off") instanceof Boolean))throw invalid("停用状态必须是布尔值");
            for(String k:new String[]{"token","access","refresh","jwt","idToken","deviceToken","deviceRefresh","key","devicePrivateKey","devicePublicKey"}) {
                Object v=a.opt(k);if(v!=null&&v!=JSONObject.NULL&&!(v instanceof String))throw invalid("凭据字段类型不正确");
                if(v instanceof String && ((String)v).length()>256*1024)throw invalid("单项凭据过长");
                if(v instanceof String && !k.endsWith("Key") && !k.equals("refresh") && ((String)v).matches("(?s).*[\\x00-\\x1f\\x7f].*"))throw invalid("凭据包含非法控制字符");
            }
            for(String k:new String[]{"expires","expiresAt","refreshExpiresAt","addedAt","updatedAt"}) {
                if(!a.has(k))continue;Object v=a.opt(k);long n;
                try { n=v instanceof Number?((Number)v).longValue():Long.parseLong(String.valueOf(v)); }
                catch(Exception ex){throw invalid("时间字段格式不正确");}
                if(n<0 || (v instanceof Number&&((Number)v).doubleValue()!=n))throw invalid("时间字段格式不正确");a.put(k,n);
            }
            if(KEY_PROVIDERS.contains(e.provider)&&"oauth".equals(a.optString("type")))throw invalid("此条是OAuth会话，不能作为该API供应商的Key，请使用对应账号供应商");
            boolean api="keys".equals(e.kind)||KEY_PROVIDERS.contains(e.provider)||"api".equals(a.optString("type"))||"apikey".equals(a.optString("type"));
            if(api && !a.optString("key").isEmpty()) {
                e.kind="keys";JSONObject key=copy(a).put("key",a.getString("key"));
                for(String k:new String[]{"label","name","enabled","off","site"})if(a.has(k))key.put(k,a.opt(k));
                if(a.optString("key").trim().isEmpty())throw invalid("API Key为空");e.value=key;return;
            }
            if("keys".equals(e.kind))throw invalid("API Key字段缺失");
            if(a.optString("token").isEmpty()&&!a.optString("access").isEmpty())a.put("token",a.optString("access"));
            if(a.optString("token").isEmpty()&&!a.optString("jwt").isEmpty())a.put("token",a.optString("jwt"));
            if(a.optString("token").isEmpty())throw invalid("缺少访问凭据；仅刷新令牌需先在原软件完成续期");
            if(KEY_PROVIDERS.contains(e.provider)) {
                e.kind="keys";e.value=new JSONObject().put("key",a.getString("token")).put("enabled",a.optBoolean("enabled",true));return;
            }
            if("codex".equals(e.provider)) {
                if(a.optString("access").isEmpty())a.put("access",a.getString("token"));
                if(a.optString("authMode").isEmpty())a.put("authMode","chatgpt");
                a.put("type","chatgpt".equals(a.optString("authMode"))?"oauth":"apikey");
                if(a.optString("accountId").isEmpty())e.warning="缺少订阅账号标识，使用前可能需要重新授权";
            }
            if("zcode".equals(e.provider)) {
                if(!a.optString("jwt").isEmpty()&&a.optString("device").isEmpty())throw invalid("ZCode会话缺少原设备标识，请使用完整账号导出");
                if(a.optString("jwt").isEmpty()&&!"api".equals(a.optString("type")))throw invalid("ZCode需要明确的会话与设备资料或API Key字段，不能猜测凭据类型");
            }
            if("trae-cn".equals(e.provider)) {
                String host=a.optString("authHost");if(!host.isEmpty()&&!Arrays.asList("api.trae.cn","api.trae.com.cn").contains(host))throw invalid("Trae认证站点不受支持");
                if(!"browser".equals(a.optString("authMode")) && (a.optString("deviceId").isEmpty()||a.optString("devicePrivateKey").isEmpty()))
                    throw invalid("Trae缺少设备认证资料，请使用完整账号导出或重新登录");
            }
            if(!a.has("name"))a.put("name","导入账号 "+(e.index+1));
            e.kind="accounts";e.value=a;
            if(e.warning.isEmpty())e.warning="凭据已读取，登录有效性尚未验证";
        } catch(IllegalArgumentException ex) { e.problem=ex.getMessage(); }
        catch(Exception ex) { e.problem="账号资料结构无效"; }
    }
    static List<Entry> exportEntries(JSONObject config) {
        List<Entry> result=new ArrayList<>();JSONArray ps=config.optJSONArray("providers");
        for(int i=0;ps!=null&&i<ps.length();i++) {
            JSONObject p=ps.optJSONObject(i);if(p==null||!PROVIDERS.contains(p.optString("id")))continue;
            String id=p.getString("id");JSONArray accounts=p.optJSONArray("accounts");Set<String> mirrored=new HashSet<>();
            for(int j=0;accounts!=null&&j<accounts.length();j++) {
                JSONObject a=accounts.optJSONObject(j);if(a==null)continue;
                for(String k:new String[]{"token","access"})if(!a.optString(k).isEmpty())mirrored.add(a.optString(k));
                Entry e=new Entry(result.size(),id,"accounts",copy(a),"all-in-v1");
                JSONObject checkin=config.optJSONObject("checkin"), records=checkin==null?null:checkin.optJSONObject("records");
                JSONObject r=records==null?null:records.optJSONObject(id+":"+a.optString("id"));if(r!=null)e.protection=copy(r);result.add(e);
            }
            JSONArray keys=p.optJSONArray("keys");
            for(int j=0;keys!=null&&j<keys.length();j++) {
                Object raw=keys.opt(j);JSONObject key=raw instanceof String?new JSONObject().put("key",raw):raw instanceof JSONObject?copy((JSONObject)raw):null;
                if(key==null||key.optString("key").isEmpty()||mirrored.contains(key.optString("key")))continue;
                result.add(new Entry(result.size(),id,"keys",key,"all-in-v1"));
            }
        }
        if(result.size()>MAX_ENTRIES)throw invalid("账号数量超过导出限制");return result;
    }
    static byte[] export(JSONObject config,Set<Integer> selected,long now) {
        List<Entry> rows=exportEntries(config);if(selected.isEmpty())throw invalid("请至少选择一个账号");
        LinkedHashMap<String,JSONObject> providers=new LinkedHashMap<>();int count=0;
        for(Entry e:rows)if(selected.contains(e.index)) {
            JSONObject p=providers.get(e.provider);
            if(p==null){p=new JSONObject().put("id",e.provider).put("accounts",new JSONArray()).put("keys",new JSONArray());providers.put(e.provider,p);}
            p.getJSONArray(e.kind).put(copy(e.value));count++;
            if(e.protection!=null){JSONObject r=p.optJSONObject("checkinRecords");if(r==null){r=new JSONObject();p.put("checkinRecords",r);}r.put(e.value.optString("id"),copy(e.protection));}
        }
        if(count!=selected.size())throw invalid("导出选择已变化，请重新选择");
        JSONArray ps=new JSONArray();for(JSONObject p:providers.values())ps.put(p);
        SimpleDateFormat date=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);date.setTimeZone(TimeZone.getTimeZone("UTC"));
        JSONObject result=new JSONObject().put("format",FORMAT).put("version",VERSION).put("exportedAt",date.format(new Date(now)))
                .put("notice","包含登录凭据；仅保存到可信位置。导入不代表凭据仍有效。").put("providers",ps);
        byte[] bytes=(result.toString(2)+"\n").getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_BYTES)throw invalid("导出文件超过4 MiB，请分批导出");
        json(bytes);return bytes;
    }
}
