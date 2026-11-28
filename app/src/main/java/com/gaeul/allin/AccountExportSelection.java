package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** A volatile export recipe. Retaining it does not retain plaintext credentials. */
final class AccountExportSelection {
    private final byte[] initialRevision;
    private final Map<Integer, String> selected;

    AccountExportSelection(byte[] revision, List<AccountTransferCodec.Entry> rows, Set<Integer> indexes) {
        initialRevision=revision.clone();
        selected=new LinkedHashMap<>();
        for(AccountTransferCodec.Entry row:rows)if(indexes.contains(row.index))selected.put(row.index,fingerprint(row));
        if(selected.isEmpty()||selected.size()!=indexes.size())throw changed();
    }

    int size() { return selected.size(); }

    byte[] prepare(AccountTransferPlan.Backend backend) throws Exception {
        // A config write between readConfig and export is fenced again by the backend.
        // One retry handles a concurrent write without revalidating stale credentials.
        for(int attempt=0;attempt<2;attempt++) {
            byte[] config=backend.readConfig();
            try {
                byte[] revision=AccountTransferCodec.digest(config);
                Set<Integer> indexes=MessageDigest.isEqual(initialRevision,revision) ? selected.keySet() :
                        resolve(AccountTransferCodec.exportEntries(new JSONObject(AccountTransferCodec.decode(config))));
                try {
                    byte[] bytes=backend.export(revision,indexes,System.currentTimeMillis());
                    if(bytes==null||bytes.length==0)throw new java.io.IOException("empty prepared export");
                    if(bytes.length>AccountTransferCodec.MAX_BYTES){Arrays.fill(bytes,(byte)0);throw new java.io.IOException("oversize export");}
                    return bytes;
                } catch(AccountTransferCodec.TransferException e) {
                    if(attempt!=0)throw e;
                    byte[] latest=backend.readConfig();
                    try { if(MessageDigest.isEqual(revision,AccountTransferCodec.digest(latest)))throw e; }
                    finally { Arrays.fill(latest,(byte)0); }
                }
            } finally { Arrays.fill(config,(byte)0); }
        }
        throw changed();
    }

    private Set<Integer> resolve(List<AccountTransferCodec.Entry> rows) {
        Set<String> wanted=new HashSet<>(selected.values());
        // Selection indexes alone are no longer safe when the configuration changes.
        if(wanted.size()!=selected.size())throw changed();
        Map<String,Integer> matches=new HashMap<>();
        for(AccountTransferCodec.Entry row:rows) {
            String key=fingerprint(row);
            if(wanted.contains(key)&&matches.put(key,row.index)!=null)throw changed();
        }
        if(matches.size()!=selected.size())throw changed();
        return new LinkedHashSet<>(matches.values());
    }

    private static AccountTransferCodec.TransferException changed() {
        return AccountTransferCodec.invalid("所选账号或密钥已变化，请返回重新选择后再导出");
    }

    private static String fingerprint(AccountTransferCodec.Entry row) {
        StringBuilder source=new StringBuilder();appendCanonical(source,row.value);
        byte[] bytes=source.toString().getBytes(StandardCharsets.UTF_8);
        try {
            return row.provider+":"+row.kind+":"+Arrays.toString(AccountTransferCodec.digest(bytes));
        } finally { Arrays.fill(bytes,(byte)0); }
    }

    private static void appendCanonical(StringBuilder out,Object value) {
        if(value instanceof JSONObject) {
            JSONObject o=(JSONObject)value;List<String> keys=new ArrayList<>();o.keys().forEachRemaining(keys::add);Collections.sort(keys);
            out.append('{');for(String key:keys){out.append(JSONObject.quote(key)).append(':');appendCanonical(out,o.opt(key));out.append(',');}out.append('}');
        } else if(value instanceof JSONArray) {
            JSONArray a=(JSONArray)value;out.append('[');for(int i=0;i<a.length();i++){appendCanonical(out,a.opt(i));out.append(',');}out.append(']');
        } else if(value instanceof String)out.append(JSONObject.quote((String)value));
        else out.append(value==null?"null":value.toString());
    }
}
