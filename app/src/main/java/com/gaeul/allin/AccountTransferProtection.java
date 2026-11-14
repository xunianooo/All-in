package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Carries only relevant spent/unknown credential digests, never a whole device's private fence file. */
final class AccountTransferProtection {
    static final String FIELD="transferRefreshFences", FILE="refresh-uncertain.fence";
    static final String[] MATERIAL={"access","token","refresh","jwt","idToken","deviceToken","deviceRefresh"};
    private AccountTransferProtection(){}
    static String digest(String provider,String value) {
        if(value==null)return null;
        if("raccoon".equals(provider))value=value.trim().replaceFirst("(?i)^Bearer\\s+","").trim();
        if(value.isEmpty())return null;
        try {
            MessageDigest md=MessageDigest.getInstance("SHA-256");md.update("allin-refresh-uncertain".getBytes(StandardCharsets.UTF_8));
            for(String part:new String[]{provider==null?"":provider,value}){md.update((byte)0);md.update(part.getBytes(StandardCharsets.UTF_8));}
            StringBuilder result=new StringBuilder();for(byte b:md.digest())result.append(String.format(Locale.US,"%02x",b&255));return result.toString();
        }catch(Exception impossible){throw new IllegalStateException("digest unavailable");}
    }
    static void validate(JSONObject account) {
        if(!account.has(FIELD))return;
        JSONObject map=account.optJSONObject(FIELD);if(map==null||map.length()>2048)throw AccountTransferCodec.invalid("迁移刷新保护记录无效");
        for(Iterator<String> it=map.keys();it.hasNext();){String key=it.next();Object tag=map.opt(key);
            if(!key.matches("[0-9a-f]{64}") || !(tag instanceof String) || !Arrays.asList("unknown","spent").contains(tag))
                throw AccountTransferCodec.invalid("迁移刷新保护记录无效");
        }
    }
    static Map<String,String> read(File file)throws IOException {
        LinkedHashMap<String,String> result=new LinkedHashMap<>();if(!file.exists())return result;
        if(!file.isFile()||file.length()>512*1024)throw new IOException("credential protection unavailable");
        byte[] bytes;try(InputStream in=new FileInputStream(file)){bytes=AccountTransferCodec.readBounded(in);}
        if(bytes.length>512*1024)throw new IOException("credential protection unavailable");
        for(String line:new String(bytes,StandardCharsets.US_ASCII).split("\n")) {
            if(line.trim().isEmpty())continue;String[] fields=line.trim().split(" ");String hash,tag;
            if(fields.length==1&&fields[0].matches("[0-9a-f]{64}")){hash=fields[0];tag="unknown";}
            else if(fields.length==2&&fields[1].matches("[0-9a-f]{64}")){hash=fields[1];tag="unknown";}
            else if(fields.length==3&&fields[2].matches("[0-9a-f]{64}")&&Arrays.asList("unknown","spent").contains(fields[0])){hash=fields[2];tag=fields[0];}
            else throw new IOException("credential protection unavailable");
            if(!result.containsKey(hash))result.put(hash,tag);
        }
        return result;
    }
    static JSONObject decorate(JSONObject original,Map<String,String> nativeRecords) {
        JSONObject config=AccountTransferCodec.copy(original);JSONArray ps=config.optJSONArray("providers");
        for(int i=0;ps!=null&&i<ps.length();i++) {
            JSONObject p=ps.optJSONObject(i);if(p==null)continue;String provider=p.optString("id");JSONArray aa=p.optJSONArray("accounts");
            for(int j=0;aa!=null&&j<aa.length();j++) {
                JSONObject a=aa.optJSONObject(j);if(a==null)continue;validate(a);
                JSONObject own=a.optJSONObject(FIELD);own=own==null?new JSONObject():AccountTransferCodec.copy(own);
                for(String field:MATERIAL) {
                    String hash=digest(provider,a.optString(field));String tag=hash==null?null:nativeRecords.get(hash);
                    if(tag!=null)own.put(hash,tag.startsWith("spent ")||tag.equals("spent")?"spent":"unknown");
                }
                if(own.length()>0)a.put(FIELD,own);
            }
        }
        return config;
    }
    static JSONObject decorateMiniMax(JSONObject config,Set<Integer> selected,MiniMaxRefreshJournal journal) throws IOException {
        JSONObject copy=AccountTransferCodec.copy(config);
        List<AccountTransferCodec.Entry> rows=AccountTransferCodec.exportEntries(copy);
        for(AccountTransferCodec.Entry row:rows) {
            if(!selected.contains(row.index)||!"minimax-code".equals(row.provider)||!"accounts".equals(row.kind))continue;
            String refresh=row.value.optString("refresh");if(refresh.isEmpty())continue;
            String tag=journal.protectionTag(refresh);if(tag.isEmpty())continue;
            JSONArray ps=copy.getJSONArray("providers");
            for(int i=0;i<ps.length();i++) {
                JSONObject p=ps.getJSONObject(i);if(!row.provider.equals(p.optString("id")))continue;JSONArray aa=p.optJSONArray("accounts");
                for(int j=0;aa!=null&&j<aa.length();j++) {
                    JSONObject a=aa.optJSONObject(j);if(a==null||!refresh.equals(a.optString("refresh")))continue;
                    JSONObject map=a.optJSONObject(FIELD);map=map==null?new JSONObject():map;
                    map.put(digest(row.provider,refresh),tag);a.put(FIELD,map);
                }
            }
        }
        return copy;
    }
    static String tag(JSONObject config,String provider,String value) {
        String hash=digest(provider,value);if(hash==null||config==null)return "";String found="";
        JSONArray ps=config.optJSONArray("providers");
        for(int i=0;ps!=null&&i<ps.length();i++) {
            JSONObject p=ps.optJSONObject(i);if(p==null||!provider.equals(p.optString("id")))continue;JSONArray aa=p.optJSONArray("accounts");
            for(int j=0;aa!=null&&j<aa.length();j++) {
                JSONObject a=aa.optJSONObject(j);if(a==null)continue;JSONObject map=a.optJSONObject(FIELD);
                if(map==null)continue;String tag=map.optString(hash);
                if("spent".equals(tag))return "spent";if("unknown".equals(tag))found="unknown";
            }
        }
        return found;
    }
    static void preserve(JSONObject old,JSONObject incoming) {
        JSONObject a=old==null?null:old.optJSONObject(FIELD),b=incoming.optJSONObject(FIELD);
        if(a==null&&b==null)return;
        JSONObject merged=b==null?new JSONObject():AccountTransferCodec.copy(b);
        if(a!=null)for(Iterator<String> it=a.keys();it.hasNext();) {
            String key=it.next();if(!merged.has(key)||"spent".equals(a.optString(key)))merged.put(key,a.opt(key));
        }
        incoming.put(FIELD,merged);validate(incoming);
    }
}
