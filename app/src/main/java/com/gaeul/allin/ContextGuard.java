package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 上下文保护器（Context Guard）——对齐 yetone/magpie 的 maxInputTokens / maxOutputTokens 语义。
 *
 * 两个作用：
 *  1. 按用户设置的「网关最大上下文 token」与「请求体字节上限」在发送前裁剪历史消息，
 *     避免上游因请求过大直接 502 断流；
 *  2. 按「最大输出 token」给请求里的 max_tokens 封顶。
 *
 * 设计原则：
 *  - system、最新 user 和带图片消息及关联工具组不得丢弃；可截断的文字仍可能被截断；
 *  - 图片 token 未计量，文本估算不保证完整模型上下文在预算内；
 *  - 只裁掉较早的历史，或截断超长文本，不改变消息的角色顺序；
 *  - 不修改用户本地会话，裁剪情况通过响应头 X-Magpie-Context-* 回传。
 */
public class ContextGuard {

    public static final int DEFAULT_MAX_BODY_BYTES = 1900 * 1024;
    public static final int DEFAULT_KEEP_RECENT = 12;
    public static final int DEFAULT_RESERVE_TOKENS = 4096;
    private static final String TRUNCATE_MARKER = "\n\n…[内容过长，已由掌上网关截断]";

    /** 用户可自由设置的网关上下文参数 */
    public static class Config {
        public boolean enabled = true;
        /** 输入上下文 token 上限，0 = 不限制 */
        public int maxContextTokens = 0;
        /** 输出 token 上限，0 = 不改写请求里的 max_tokens */
        public int maxOutputTokens = 0;
        /** 单次请求体字节上限，0 = 不限制 */
        public int maxBodyBytes = DEFAULT_MAX_BODY_BYTES;
        /** 优先保留的最近消息条数；超限时仍可能被截断或丢弃 */
        public int keepRecent = DEFAULT_KEEP_RECENT;
        /** 给输出预留的 token，计算输入预算时扣除 */
        public int reserveTokens = DEFAULT_RESERVE_TOKENS;
        /** 上游读超时（秒） */
        public int requestTimeoutSeconds = 120;
        /** 上游连接超时（秒） */
        public int connectTimeoutSeconds = 30;

        public static Config fromJson(JSONObject o) {
            Config c = new Config();
            if (o == null) return c;
            c.enabled = o.optBoolean("enabled", true);
            c.maxContextTokens = clamp(o.optInt("maxContextTokens", 0), 0, 2000000);
            c.maxOutputTokens = clamp(o.optInt("maxOutputTokens", 0), 0, 2000000);
            c.maxBodyBytes = clamp(o.optInt("maxBodyBytes", DEFAULT_MAX_BODY_BYTES), 0, 64 * 1024 * 1024);
            c.keepRecent = clamp(o.optInt("keepRecent", DEFAULT_KEEP_RECENT), 0, 500);
            c.reserveTokens = clamp(o.optInt("reserveTokens", DEFAULT_RESERVE_TOKENS), 0, 200000);
            c.requestTimeoutSeconds = clamp(o.optInt("requestTimeoutSeconds", 120), 15, 900);
            c.connectTimeoutSeconds = clamp(o.optInt("connectTimeoutSeconds", 30), 5, 300);
            return c;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("enabled", enabled);
            o.put("maxContextTokens", maxContextTokens);
            o.put("maxOutputTokens", maxOutputTokens);
            o.put("maxBodyBytes", maxBodyBytes);
            o.put("keepRecent", keepRecent);
            o.put("reserveTokens", reserveTokens);
            o.put("requestTimeoutSeconds", requestTimeoutSeconds);
            o.put("connectTimeoutSeconds", connectTimeoutSeconds);
            return o;
        }
    }

    /** 一次裁剪的结果，同时用于响应头与后台统计 */
    public static class Result {
        public JSONObject payload;
        public boolean changed;
        public int dropped;
        public int truncated;
        public long originalBytes;
        public long finalBytes;
        public int originalTokens;
        public int finalTokens;
        public int budgetTokens;
        public long budgetBytes;
        public Integer appliedMaxOutputTokens;
        /** Non-null means the gateway must reject this request instead of forwarding it. */
        public String error;
        public boolean budgetExceeded;
        public int textTokenEstimate;
        public int imageCount;
        public long imageBytes;
        public boolean imageTokenEstimateKnown = true;

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("changed", changed);
            o.put("dropped", dropped);
            o.put("truncated", truncated);
            o.put("originalBytes", originalBytes);
            o.put("finalBytes", finalBytes);
            o.put("originalTokens", originalTokens);
            o.put("finalTokens", finalTokens);
            o.put("budgetTokens", budgetTokens);
            o.put("budgetBytes", budgetBytes);
            o.put("budgetExceeded", budgetExceeded);
            o.put("textTokenEstimate", textTokenEstimate);
            o.put("imageCount", imageCount);
            o.put("imageBytes", imageBytes);
            o.put("imageTokenEstimateKnown", imageTokenEstimateKnown);
            if (error != null) o.put("error", error);
            if (appliedMaxOutputTokens != null) o.put("appliedMaxOutputTokens", appliedMaxOutputTokens.intValue());
            return o;
        }
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    /** 粗略 token 估算：中日韩字符按 1 token，其余按 4 字符折 1 token */
    public static int estimateTokens(String s) {
        if (s == null || s.isEmpty()) return 0;
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x2E80) cjk++;
            else other++;
        }
        return cjk + (other + 3) / 4;
    }

    private static long bytes(String s) {
        return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 按配置裁剪 messages，并给 max_tokens 封顶。
     * 返回的 payload 是新的 JSONObject；原 payload 不被修改。
     */
    public static Result fit(JSONObject payload, Config cfg) {
        Result r = new Result();
        r.payload = payload;
        String serialized = payload.toString();
        r.originalBytes = bytes(serialized);
        serialized = null;
        r.originalTokens = textTokens(payload);
        updateImageStats(r, payload);
        r.finalBytes = r.originalBytes;
        r.finalTokens = r.originalTokens;
        r.budgetBytes = (cfg != null) ? cfg.maxBodyBytes : 0;
        r.budgetTokens = (cfg != null) ? cfg.maxContextTokens : 0;
        r.changed = false;

        if (cfg == null) return r;

        if (!cfg.enabled) return r;

        JSONObject out = new JSONObject(payload.toString());
        applyOutputCap(out, r, cfg);

        int tokenBudget = Integer.MAX_VALUE;
        if (cfg.maxContextTokens > 0) {
            int reserve = Math.min(cfg.reserveTokens, Math.max(0, cfg.maxContextTokens / 4));
            tokenBudget = cfg.maxContextTokens - reserve;
        }
        long byteBudget = cfg.maxBodyBytes > 0 ? cfg.maxBodyBytes : Long.MAX_VALUE;
        r.budgetTokens = tokenBudget == Integer.MAX_VALUE ? 0 : tokenBudget;

        JSONArray msgs = out.optJSONArray("messages");
        if (out.has("messages") && !(out.opt("messages") instanceof JSONArray)) {
            r.error = "invalid_messages";
        } else if (msgs != null && msgs.length() > 0) {
            for (int i = 0; i < msgs.length(); i++) {
                if (msgs.optJSONObject(i) == null) {
                    r.error = "invalid_message";
                    break;
                }
            }
        }
        if (r.error == null && msgs != null && msgs.length() > 0) {
            // 除 messages 之外的基础开销（tools / 参数等）
            JSONObject shell = shallowCopy(out);
            shell.put("messages", new JSONArray());
            String shellStr = shell.toString();
            long baseBytes = bytes(shellStr);
            int baseTokens = textTokens(shell);

            int n = msgs.length();
            List<Integer> systemIdx = new ArrayList<Integer>();
            List<Integer> otherIdx = new ArrayList<Integer>();
            for (int i = 0; i < n; i++) {
                JSONObject m = msgs.optJSONObject(i);
                if (m != null && "system".equals(m.optString("role"))) systemIdx.add(i);
                else otherIdx.add(i);
            }

            boolean[] keep = new boolean[n];
            long usedBytes = baseBytes;
            int usedTokens = baseTokens;
            for (Integer i : systemIdx) {
                keep[i.intValue()] = true;
                usedBytes += bytes(msgs.optJSONObject(i.intValue()).toString());
                usedTokens += textTokens(msgs.optJSONObject(i.intValue()));
            }

            // Images and the latest user turn are mandatory regardless of keepRecent.
            boolean[] mandatory = new boolean[n];
            int latestUser = -1;
            for (int i = 0; i < n; i++) {
                JSONObject m = msgs.optJSONObject(i);
                if ("user".equals(m.optString("role"))) latestUser = i;
                mandatory[i] = hasImageContent(m.opt("content"));
            }
            if (latestUser >= 0) mandatory[latestUser] = true;
            for (int i = 0; i < n; i++) {
                JSONObject m = msgs.optJSONObject(i);
                if (!"assistant".equals(m.optString("role")) || m.optJSONArray("tool_calls") == null) continue;
                int end = i + 1;
                boolean protect = mandatory[i];
                while (end < n && "tool".equals(msgs.optJSONObject(end).optString("role"))) protect |= mandatory[end++];
                if (protect) for (int j = i; j < end; j++) mandatory[j] = true;
                i = end - 1;
            }
            // 最近 keepRecent 条优先保留
            int recentStart = Math.max(0, otherIdx.size() - Math.max(0, cfg.keepRecent));
            for (int k = recentStart; k < otherIdx.size(); k++) {
                int i = otherIdx.get(k).intValue();
                if (!keep[i]) {
                    keep[i] = true;
                    usedBytes += bytes(msgs.optJSONObject(i).toString());
                    usedTokens += textTokens(msgs.optJSONObject(i));
                }
            }

            // 从最近往更早的方向补入，放不下就丢
            for (int k = recentStart - 1; k >= 0; k--) {
                int i = otherIdx.get(k).intValue();
                JSONObject m = msgs.optJSONObject(i);
                if (m == null) continue;
                long mb = bytes(m.toString());
                int mt = textTokens(m);
                if (mandatory[i] || (usedBytes + mb <= byteBudget && usedTokens + mt <= tokenBudget)) {
                    keep[i] = true;
                    usedBytes += mb;
                    usedTokens += mt;
                } else {
                    r.dropped++;
                }
            }

            // Keep an assistant tool-call and its contiguous tool replies as one unit.
            // A boundary may land inside a parallel call; never silently strip calls or replies.
            for (int i = 0; i < n; i++) {
                JSONObject m = msgs.optJSONObject(i);
                if (!"assistant".equals(m.optString("role")) || m.optJSONArray("tool_calls") == null) continue;
                int end = i + 1;
                boolean any = keep[i];
                while (end < n && "tool".equals(msgs.optJSONObject(end).optString("role"))) {
                    any |= keep[end++];
                }
                if (any) for (int j = i; j < end; j++) {
                    if (!keep[j]) { keep[j] = true; r.dropped--; }
                }
                i = end - 1;
            }

            JSONArray kept = new JSONArray();
            for (int i = 0; i < n; i++) {
                if (keep[i]) kept.put(msgs.optJSONObject(i));
            }
            out.put("messages", kept);

            // 仍然超限：逐步截断最长的可截断文本内容
            int guard = 0;
            while (overBudget(out, byteBudget, tokenBudget) && guard < 30) {
                guard++;
                JSONArray cur = out.optJSONArray("messages");
                int longestIdx = -1;
                int longestLen = 0;
                JSONObject shrunk = null;
                for (int i = 0; i < cur.length(); i++) {
                    JSONObject m = cur.optJSONObject(i);
                    if (m == null || "system".equals(m.optString("role")) || protectedToolGroup(cur, i)) continue;
                    int len = truncatableTokens(m);
                    JSONObject candidate = truncateMessage(m, Math.max(16, truncatableTokens(m) / 2));
                    if (candidate != null && candidate.toString().length() < m.toString().length() && len > longestLen) {
                        longestLen = len;
                        longestIdx = i;
                        shrunk = candidate;
                    }
                }
                if (longestIdx < 0) break;
                cur.put(longestIdx, shrunk);
                out.put("messages", cur);
                r.truncated++;
            }

            // 兜底：继续丢弃最早的非 system 消息
            while (overBudget(out, byteBudget, tokenBudget)) {
                JSONArray cur = out.optJSONArray("messages");
                if (cur == null || cur.length() <= 1) break;
                int dropIdx = -1;
                for (int i = 0; i < cur.length() - 1; i++) {
                    JSONObject m = cur.optJSONObject(i);
                    if (m == null || "system".equals(m.optString("role")) || hasImageContent(m.opt("content")) || protectedToolGroup(cur, i)) continue;
                    int end = i + 1;
                    if ("assistant".equals(m.optString("role")) && m.optJSONArray("tool_calls") != null) {
                        while (end < cur.length() && "tool".equals(cur.optJSONObject(end).optString("role"))) end++;
                    }
                    boolean protectedGroup = false;
                    for (int j = i; j < end; j++) {
                        JSONObject member = cur.optJSONObject(j);
                        if (hasImageContent(member.opt("content"))) protectedGroup = true;
                        if ("user".equals(member.optString("role"))) {
                            boolean laterUser = false;
                            for (int k = j + 1; k < cur.length(); k++) if ("user".equals(cur.optJSONObject(k).optString("role"))) laterUser = true;
                            if (!laterUser) protectedGroup = true;
                        }
                    }
                    if (!protectedGroup && end < cur.length()) { dropIdx = i; break; }
                    i = end - 1;
                }
                if (dropIdx < 0) break;
                int dropEnd = dropIdx + 1;
                JSONObject first = cur.optJSONObject(dropIdx);
                if ("assistant".equals(first.optString("role")) && first.optJSONArray("tool_calls") != null) {
                    while (dropEnd < cur.length() && "tool".equals(cur.optJSONObject(dropEnd).optString("role"))) dropEnd++;
                    // Preserve the newest message even when it belongs to this group.
                    if (dropEnd == cur.length()) break;
                }
                JSONArray next = new JSONArray();
                for (int i = 0; i < cur.length(); i++) {
                    if (i < dropIdx || i >= dropEnd) next.put(cur.optJSONObject(i));
                }
                r.dropped += dropEnd - dropIdx;
                out.put("messages", next);
            }
        }

        r.payload = out;
        String finalStr = out.toString();
        r.finalBytes = bytes(finalStr);
        r.finalTokens = textTokens(out);
        updateImageStats(r, out);
        r.budgetExceeded = overBudget(out, byteBudget, tokenBudget);
        if (r.error == null && r.budgetExceeded) r.error = "budget_exceeded";
        r.changed = r.dropped > 0 || r.truncated > 0 || r.originalBytes != r.finalBytes
                || r.originalTokens != r.finalTokens || r.appliedMaxOutputTokens != null;
        return r;
    }

    private static boolean overBudget(JSONObject payload, long byteBudget, int tokenBudget) {
        String s = payload.toString();
        if (bytes(s) > byteBudget) return true;
        return textTokens(payload) > tokenBudget;
    }

    private static boolean protectedToolGroup(JSONArray messages, int index) {
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null || !"assistant".equals(m.optString("role")) || m.optJSONArray("tool_calls") == null) continue;
            int end = i + 1;
            boolean image = hasImageContent(m.opt("content"));
            while (end < messages.length() && "tool".equals(messages.optJSONObject(end).optString("role")))
                image |= hasImageContent(messages.optJSONObject(end++).opt("content"));
            if (index >= i && index < end) return image;
            i = end - 1;
        }
        return false;
    }

    // Only inspect protocol content positions; tool arguments are opaque JSON/text.
    private static boolean imageType(JSONObject p) {
        String t = p.optString("type", "");
        return "image_url".equals(t) || "image".equals(t) || "input_image".equals(t);
    }

    private static boolean hasImageContent(Object content) {
        if (content instanceof JSONArray) {
            JSONArray a = (JSONArray) content;
            for (int i = 0; i < a.length(); i++) if (hasImageContent(a.opt(i))) return true;
        } else if (content instanceof JSONObject) {
            JSONObject p = (JSONObject) content;
            if (imageType(p)) return true;
            if ("tool_result".equals(p.optString("type", ""))) return hasImageContent(p.opt("content"));
        }
        return false;
    }

    // The projection counts the JSON representation while replacing only recognized
    // image payload strings with empty strings. It never copies the image strings.
    private static class Projection {
        long cjk, other, imageBytes;
        int imageCount;
        final java.util.IdentityHashMap<JSONObject, java.util.Set<String>> masks =
                new java.util.IdentityHashMap<JSONObject, java.util.Set<String>>();
        void mark(JSONObject o, String key) {
            Object value = o.opt(key);
            if (!(value instanceof String)) return;
            java.util.Set<String> keys = masks.get(o);
            if (keys == null) { keys = new java.util.HashSet<String>(); masks.put(o, keys); }
            if (keys.add(key)) imageBytes = saturatedAdd(imageBytes, utf8Bytes((String) value));
        }
        void images(Object value) {
            if (value instanceof JSONArray) {
                JSONArray a = (JSONArray) value;
                for (int i = 0; i < a.length(); i++) images(a.opt(i));
            } else if (value instanceof JSONObject) {
                JSONObject p = (JSONObject) value;
                if (imageType(p)) {
                    if (imageCount < Integer.MAX_VALUE) imageCount++;
                    Object url = p.opt("image_url");
                    if (url instanceof String) mark(p, "image_url");
                    else if (url instanceof JSONObject) mark((JSONObject) url, "url");
                    JSONObject source = p.optJSONObject("source");
                    if (source != null) { mark(source, "data"); mark(source, "url"); }
                } else if ("tool_result".equals(p.optString("type", ""))) images(p.opt("content"));
            }
        }
        void count(String s) {
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) >= 0x2E80) cjk++; else other++;
            }
        }
        void json(Object value) {
            if (value instanceof JSONObject) {
                JSONObject o = (JSONObject) value;
                count("{"); boolean first = true;
                java.util.Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String key = it.next();
                    if (!first) count(","); first = false;
                    count(JSONObject.quote(key)); count(":");
                    java.util.Set<String> keys = masks.get(o);
                    if (keys != null && keys.contains(key)) count("\"\"");
                    else json(o.opt(key));
                }
                count("}");
            } else if (value instanceof JSONArray) {
                JSONArray a = (JSONArray) value; count("[");
                for (int i = 0; i < a.length(); i++) { if (i > 0) count(","); json(a.opt(i)); }
                count("]");
            } else if (value instanceof String) count(JSONObject.quote((String) value));
            else {
                // Android's public JSONObject API does not expose valueToString.
                // Serialize only the scalar through the public JSONArray API.
                String scalar = new JSONArray().put(value).toString();
                count(scalar.substring(1, scalar.length() - 1));
            }
        }
        int tokens() { return (int) Math.min(Integer.MAX_VALUE, cjk + (other + 3) / 4); }
    }
    private static long saturatedAdd(long a, long b) { return Long.MAX_VALUE - a < b ? Long.MAX_VALUE : a + b; }
    private static long utf8Bytes(String s) {
        long n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) n++;
            else if (c < 0x800) n += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) { n += 4; i++; }
            else if (Character.isSurrogate(c)) n++;
            else n += 3;
        }
        return n;
    }
    private static JSONObject shallowCopy(JSONObject source) {
        JSONObject copy = new JSONObject();
        java.util.Iterator<String> it = source.keys();
        while (it.hasNext()) { String key = it.next(); copy.put(key, source.opt(key)); }
        return copy;
    }
    private static Projection project(JSONObject payload) {
        Projection p = new Projection();
        JSONArray messages = payload.optJSONArray("messages");
        if (messages != null) for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m != null) p.images(m.opt("content"));
        }
        JSONArray input = payload.optJSONArray("input");
        if (input != null) for (int i = 0; i < input.length(); i++) {
            JSONObject item = input.optJSONObject(i);
            if (item != null) { if (imageType(item)) p.images(item); else p.images(item.opt("content")); }
        }
        if (payload.has("content")) p.images(payload.opt("content"));
        p.json(payload);
        return p;
    }
    private static int textTokens(JSONObject payload) { return project(payload).tokens(); }
    private static void updateImageStats(Result r, JSONObject payload) {
        Projection p = project(payload);
        r.imageCount = p.imageCount;
        r.imageBytes = p.imageBytes;
        r.imageTokenEstimateKnown = p.imageCount == 0;
        r.textTokenEstimate = p.tokens();
    }

    /** 给 max_tokens / max_completion_tokens 封顶（只改副本，不影响原 payload） */
    private static void applyOutputCap(JSONObject p, Result r, Config cfg) {
        if (cfg.maxOutputTokens <= 0) return;
        int cap = cfg.maxOutputTokens;
        boolean touched = false;
        if (p.has("max_tokens")) {
            int cur = p.optInt("max_tokens", cap);
            if (cur > cap) {
                p.put("max_tokens", cap);
                touched = true;
            }
        } else if (!p.has("max_completion_tokens")) {
            p.put("max_tokens", cap);
            touched = true;
        }
        if (p.has("max_completion_tokens") && p.optInt("max_completion_tokens", cap) > cap) {
            p.put("max_completion_tokens", cap);
            touched = true;
        }
        if (touched) r.appliedMaxOutputTokens = Integer.valueOf(cap);
    }

    /**
     * 修掉「tool 消息与 assistant.tool_calls 不成对」的裁剪结果。
     *
     * 上游对 tool_call_id 的配对是硬校验：留下 tool 却没有对应的 tool_calls（或反过来）
     * 会直接 400 "tool must be a response to a preceding message with tool_calls"。
     * 裁剪是按「条」做的，很容易把一半切掉，所以最后统一修一遍：
     *   - 没有父 tool_calls 的 tool 消息 → 丢弃
     *   - tool_calls 里没有任何回应的项 → 从 assistant 上摘掉；摘空了且没有正文 → 整条丢弃
     */
    static JSONArray sanitizeToolPairs(JSONArray msgs) {
        if (msgs == null) return new JSONArray();
        JSONArray out = new JSONArray();
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null || "tool".equals(m.optString("role"))) continue;
            JSONArray calls = m.optJSONArray("tool_calls");
            if (!"assistant".equals(m.optString("role")) || calls == null || calls.length() == 0) {
                out.put(m);
                continue;
            }
            // Only the immediately following tool run can answer this assistant.
            // Global ID membership incorrectly pairs replies across intervening turns.
            java.util.Map<String, JSONObject> replies = new java.util.LinkedHashMap<String, JSONObject>();
            int end = i + 1;
            while (end < msgs.length()) {
                JSONObject reply = msgs.optJSONObject(end);
                if (reply == null || !"tool".equals(reply.optString("role"))) break;
                String id = reply.optString("tool_call_id", "");
                if (!id.isEmpty() && !replies.containsKey(id)) replies.put(id, reply);
                end++;
            }
            JSONArray keptCalls = new JSONArray();
            List<JSONObject> keptReplies = new ArrayList<JSONObject>();
            for (int k = 0; k < calls.length(); k++) {
                JSONObject call = calls.optJSONObject(k);
                if (call == null) continue;
                JSONObject reply = replies.remove(call.optString("id", ""));
                if (reply != null) {
                    keptCalls.put(call);
                    keptReplies.add(reply);
                }
            }
            JSONObject copy = new JSONObject(m.toString());
            if (keptCalls.length() > 0) {
                copy.put("tool_calls", keptCalls);
                out.put(copy);
                for (JSONObject reply : keptReplies) out.put(reply);
            } else {
                copy.remove("tool_calls");
                Object content = copy.opt("content");
                boolean hasContent = content != null && !JSONObject.NULL.equals(content)
                        && !content.toString().trim().isEmpty()
                        && (!(content instanceof JSONArray) || ((JSONArray) content).length() > 0);
                if (hasContent) out.put(copy);
            }
            i = end - 1;
        }
        return out;
    }

    private static int truncatableTokens(JSONObject msg) {
        Object content = msg.opt("content");
        if (content instanceof String) return estimateTokens((String) content);
        int max = 0;
        if (content instanceof JSONArray) {
            JSONArray parts = (JSONArray) content;
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part != null && "text".equals(part.optString("type")))
                    max = Math.max(max, estimateTokens(part.optString("text", "")));
            }
        }
        return max;
    }

    private static JSONObject truncateMessage(JSONObject msg, int maxTokens) {
        try {
            JSONObject clone = shallowCopy(msg);
            Object content = clone.opt("content");
            if (content instanceof String) {
                String text = (String) content;
                if (estimateTokens(text) <= maxTokens) return null;
                clone.put("content", cutText(text, maxTokens) + TRUNCATE_MARKER);
                return clone;
            }
            if (content instanceof JSONArray) {
                JSONArray old = (JSONArray) content;
                JSONArray arr = new JSONArray();
                for (int i = 0; i < old.length(); i++) arr.put(old.opt(i));
                int best = -1;
                int bestLen = 0;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject part = arr.optJSONObject(i);
                    if (part == null || !"text".equals(part.optString("type"))) continue;
                    String t = part.optString("text", "");
                    int len = estimateTokens(t);
                    if (len > bestLen) {
                        bestLen = len;
                        best = i;
                    }
                }
                if (best < 0 || bestLen <= maxTokens) return null;
                JSONObject part = shallowCopy(arr.optJSONObject(best));
                String t = part.optString("text", "");
                part.put("text", cutText(t, maxTokens) + TRUNCATE_MARKER);
                arr.put(best, part);
                clone.put("content", arr);
                return clone;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 按 token 估算截取前缀，保证不切断代理对 */
    private static String cutText(String text, int maxTokens) {
        int keep = text.length();
        while (keep > 0 && estimateTokens(text.substring(0, keep)) > maxTokens) {
            keep = (int) Math.floor(keep * 0.85);
            if (keep < 16) break;
        }
        if (keep <= 0) return "";
        if (keep < text.length() && Character.isHighSurrogate(text.charAt(keep - 1))) keep--;
        return text.substring(0, keep);
    }

    public static String formatBytes(long n) {
        if (n <= 0) return "0 B";
        if (n >= 1048576) return String.format(java.util.Locale.US, "%.2f MB", n / 1048576.0);
        if (n >= 1024) return String.format(java.util.Locale.US, "%.1f KB", n / 1024.0);
        return n + " B";
    }
}
