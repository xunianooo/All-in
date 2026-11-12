package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 网关中间件（Middleware）。
 *
 * 参考 magpie-community/plugins 的 gateway middleware 设计，做成配置驱动、零依赖的内置实现。
 * 共同语义与 magpie 一致：**钩子抛错就原样放行**，绝不因为中间件出问题而让请求失败。
 *
 * 之前 Android 侧只有一个存配置的 /api/middleware 空壳，
 * README 声称对齐的 model-map / param-override / system-prompt 其实一行都没生效 —— 这里补齐。
 *
 *  1. model-map      —— 请求里的模型别名映射到真实模型，回复仍保留客户端请求的名字
 *  2. param-override —— 强制/覆盖/删除请求参数（统一 temperature、限制 max_tokens）
 *  3. system-prompt  —— 注入统一的 system 提示词
 *  4. think-tags     —— <think>…</think> 与 reasoning_content 互转（两个方向）
 *  5. word-guard     —— 敏感词：拒绝或打码，同时作用于用户输入与模型回复
 */
final class Middleware {

    private Middleware() {}

    // ===================== JSON 路径工具 =====================

    private static Object getPath(JSONObject o, String path) {
        if (o == null || path == null || path.isEmpty()) return null;
        String[] keys = path.split("\\.");
        Object cur = o;
        for (String k : keys) {
            if (!(cur instanceof JSONObject)) return null;
            JSONObject jo = (JSONObject) cur;
            if (!jo.has(k)) return null;
            cur = jo.opt(k);
            if (cur == JSONObject.NULL) return null;
        }
        return cur;
    }

    private static void setPath(JSONObject o, String path, Object value) throws Exception {
        String[] keys = path.split("\\.");
        JSONObject cur = o;
        for (int i = 0; i < keys.length - 1; i++) {
            JSONObject next = cur.optJSONObject(keys[i]);
            if (next == null) {
                next = new JSONObject();
                cur.put(keys[i], next);
            }
            cur = next;
        }
        cur.put(keys[keys.length - 1], value);
    }

    private static void deletePath(JSONObject o, String path) {
        if (o == null || path == null || path.isEmpty()) return;
        String[] keys = path.split("\\.");
        JSONObject cur = o;
        for (int i = 0; i < keys.length - 1; i++) {
            JSONObject next = cur.optJSONObject(keys[i]);
            if (next == null) return;
            cur = next;
        }
        cur.remove(keys[keys.length - 1]);
    }

    private static JSONObject cfg(JSONObject middleware) {
        return middleware == null ? new JSONObject() : middleware;
    }

    // ===================== 1. model-map =====================

    /**
     * 别名映射。group/ 开头的引用不映射（那是路由组，不是别名）。
     * @return 映射后的模型名；没命中就返回原值
     */
    static String mapModel(String model, String providerId, JSONObject middleware) {
        if (model == null || model.isEmpty()) return model;
        if (model.startsWith("group/")) return model;
        try {
            JSONObject mw = cfg(middleware);
            JSONObject global = mw.optJSONObject("modelMap");
            if (global != null && global.has(model)) {
                String t = global.optString(model, model);
                return t.isEmpty() ? model : t;
            }
            JSONObject perProv = mw.optJSONObject("modelMapByProvider");
            if (perProv != null && providerId != null) {
                JSONObject one = perProv.optJSONObject(providerId);
                if (one != null && one.has(model)) {
                    String t = one.optString(model, model);
                    return t.isEmpty() ? model : t;
                }
            }
        } catch (Throwable ignored) {
        }
        return model;
    }

    // ===================== 2. param-override =====================

    /** @return 是否改过 payload */
    static boolean applyParamOverride(JSONObject payload, String providerId, JSONObject middleware) {
        if (payload == null) return false;
        try {
            JSONObject mw = cfg(middleware);
            JSONObject base = mw.optJSONObject("paramOverride");
            JSONObject perProv = null;
            JSONObject allProv = mw.optJSONObject("paramOverrideByProvider");
            if (allProv != null && providerId != null) perProv = allProv.optJSONObject(providerId);

            boolean touched = false;

            // set：供应商级覆盖全局
            JSONObject setAll = base == null ? null : base.optJSONObject("set");
            JSONObject setOne = perProv == null ? null : perProv.optJSONObject("set");
            touched |= applySet(setAll, payload);
            touched |= applySet(setOne, payload);

            // delete
            touched |= applyDelete(base == null ? null : base.optJSONArray("delete"), payload);
            touched |= applyDelete(perProv == null ? null : perProv.optJSONArray("delete"), payload);

            // maxTokensCap：供应商级优先
            Object cap = null;
            if (perProv != null && perProv.has("maxTokensCap")) cap = perProv.opt("maxTokensCap");
            else if (base != null && base.has("maxTokensCap")) cap = base.opt("maxTokensCap");
            if (cap != null) {
                double c = Double.parseDouble(String.valueOf(cap));
                String[] keys = {"max_tokens", "max_completion_tokens", "maxTokens"};
                for (String k : keys) {
                    Object cur = payload.opt(k);
                    if (cur instanceof Number) {
                        double v = ((Number) cur).doubleValue();
                        if (v > c) {
                            payload.put(k, (int) c);
                            touched = true;
                        }
                    }
                }
            }
            return touched;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean applySet(JSONObject set, JSONObject payload) {
        if (set == null || payload == null) return false;
        boolean touched = false;
        Iterator<String> it = set.keys();
        while (it.hasNext()) {
            String k = it.next();
            try {
                setPath(payload, k, set.opt(k));
                touched = true;
            } catch (Throwable ignored) {
            }
        }
        return touched;
    }

    private static boolean applyDelete(JSONArray del, JSONObject payload) {
        if (del == null || payload == null) return false;
        boolean touched = false;
        for (int i = 0; i < del.length(); i++) {
            String k = del.optString(i, "");
            if (k.isEmpty()) continue;
            deletePath(payload, k);
            touched = true;
        }
        return touched;
    }

    // ===================== 3. system-prompt =====================

    /** @return 是否注入过 */
    static boolean applySystemPrompt(JSONObject payload, String providerId, JSONObject middleware) {
        if (payload == null) return false;
        try {
            JSONObject mw = cfg(middleware);
            String prompt = mw.optString("systemPrompt", "");
            if (prompt == null || prompt.trim().isEmpty()) return false;

            JSONArray skip = mw.optJSONArray("systemPromptSkipProviders");
            for (int i = 0; skip != null && i < skip.length(); i++) {
                if (providerId != null && providerId.equals(skip.optString(i))) return false;
            }

            String mode = mw.optString("systemPromptMode", "prepend");
            JSONArray msgs = payload.optJSONArray("messages");
            if (msgs == null) {
                msgs = new JSONArray();
                payload.put("messages", msgs);
            }

            int sysIdx = -1;
            for (int i = 0; i < msgs.length(); i++) {
                JSONObject m = msgs.optJSONObject(i);
                if (m != null && "system".equals(m.optString("role"))) {
                    sysIdx = i;
                    break;
                }
            }

            if (sysIdx >= 0) {
                JSONObject cur = msgs.optJSONObject(sysIdx);
                Object c = cur.opt("content");
                String curText = (c instanceof String) ? (String) c : "";
                String next;
                if ("replace".equals(mode)) next = prompt;
                else if ("append".equals(mode)) next = curText.isEmpty() ? prompt : curText + "\n\n" + prompt;
                else next = curText.isEmpty() ? prompt : prompt + "\n\n" + curText;
                cur.put("content", next);
            } else {
                JSONObject sys = new JSONObject();
                sys.put("role", "system");
                sys.put("content", prompt);
                JSONArray out = new JSONArray();
                out.put(sys);
                for (int i = 0; i < msgs.length(); i++) out.put(msgs.opt(i));
                payload.put("messages", out);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ===================== 4. think-tags =====================

    private static final String THINK_OPEN = "<think>";
    private static final String THINK_CLOSE = "</think>";

    static boolean thinkTagsEnabled(JSONObject middleware) {
        try {
            JSONObject t = cfg(middleware).optJSONObject("thinkTags");
            return t != null && t.optBoolean("enabled", false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String thinkTagsMode(JSONObject middleware) {
        try {
            JSONObject t = cfg(middleware).optJSONObject("thinkTags");
            String m = t == null ? "strip" : t.optString("mode", "strip");
            return "toContent".equals(m) ? "toContent" : "strip";
        } catch (Throwable ignored) {
            return "strip";
        }
    }

    /**
     * 处理一条助手回复的文本。
     *
     * strip      ：把正文里的 &lt;think&gt;…&lt;/think&gt; 挪进 reasoning，正文只留其余部分
     * toContent  ：把 reasoning 包成 &lt;think&gt;…&lt;/think&gt; 塞到正文最前面
     *
     * @return {text, reasoning}
     */
    static String[] applyThinkTags(String text, String reasoning, JSONObject middleware) {
        String t = text == null ? "" : text;
        String r = reasoning == null ? "" : reasoning;
        if (!thinkTagsEnabled(middleware)) return new String[]{t, r};

        if ("toContent".equals(thinkTagsMode(middleware))) {
            if (r.trim().isEmpty()) return new String[]{t, r};
            StringBuilder sb = new StringBuilder();
            sb.append(THINK_OPEN).append("\n").append(r).append("\n").append(THINK_CLOSE);
            if (!t.isEmpty()) sb.append("\n").append(t);
            return new String[]{sb.toString(), ""};
        }

        // strip：抽取所有完整的 think 块
        StringBuilder kept = new StringBuilder();
        StringBuilder moved = new StringBuilder();
        int idx = 0;
        while (true) {
            int open = t.indexOf(THINK_OPEN, idx);
            if (open < 0) {
                kept.append(t, idx, t.length());
                break;
            }
            int close = t.indexOf(THINK_CLOSE, open + THINK_OPEN.length());
            if (close < 0) {
                // 没有闭合标签：整段留着，不要误吞正文
                kept.append(t, idx, t.length());
                break;
            }
            kept.append(t, idx, open);
            String inner = t.substring(open + THINK_OPEN.length(), close);
            if (moved.length() > 0) moved.append("\n");
            moved.append(inner.trim());
            idx = close + THINK_CLOSE.length();
        }
        String outText = kept.toString().trim();
        String outReason = moved.length() > 0 ? moved.toString() : r;
        if (moved.length() > 0 && !r.trim().isEmpty()) {
            // 正文里抽出来的和后端已经给的 reasoning 合并，避免丢
            outReason = r + "\n" + outReason;
        }
        return new String[]{outText, outReason};
    }

    /**
     * 流式版 strip：用一个短缓冲避免 &lt;think&gt; 被切在两个 delta 中间。
     * 只做 strip；toContent 在流式里没法逐块拼 reasoning，交给非流式路径。
     *
     * @param pending 跨块挂起的不完整文本（长度小于标签长度时才会挂起）
     */
    static final class ThinkStream {
        private final boolean active;
        private final boolean strip;
        private boolean inThink = false;
        private final StringBuilder hold = new StringBuilder();

        ThinkStream(JSONObject middleware) {
            this.active = thinkTagsEnabled(middleware);
            this.strip = active && !"toContent".equals(thinkTagsMode(middleware));
        }

        /** @return 过滤后应该写给客户端的 delta 文本（可能为空串） */
        String feed(String delta) {
            if (!active || !strip || delta == null || delta.isEmpty()) return delta == null ? "" : delta;
            hold.append(delta);
            StringBuilder out = new StringBuilder();
            while (true) {
                if (inThink) {
                    int close = hold.indexOf(THINK_CLOSE);
                    if (close < 0) {
                        // 还在 think 里，除了可能的半个闭合标签，其余全丢
                        trimHoldToTagPrefix(THINK_CLOSE);
                        return out.toString();
                    }
                    hold.delete(0, close + THINK_CLOSE.length());
                    inThink = false;
                    continue;
                }
                int open = hold.indexOf(THINK_OPEN);
                if (open < 0) {
                    // 没有开标签：把「可能是半个开标签」的尾巴留着，其余发出
                    int safe = hold.length();
                    String s = hold.toString();
                    for (int k = Math.min(THINK_OPEN.length() - 1, s.length()); k >= 1; k--) {
                        if (s.substring(s.length() - k).equals(THINK_OPEN.substring(0, k))) {
                            safe = s.length() - k;
                            break;
                        }
                    }
                    out.append(s, 0, safe);
                    hold.delete(0, safe);
                    return out.toString();
                }
                out.append(hold, 0, open);
                hold.delete(0, open + THINK_OPEN.length());
                inThink = true;
            }
        }

        private void trimHoldToTagPrefix(String tag) {
            int keep = 0;
            String s = hold.toString();
            for (int k = Math.min(tag.length() - 1, s.length()); k >= 1; k--) {
                if (s.substring(s.length() - k).equals(tag.substring(0, k))) {
                    keep = k;
                    break;
                }
            }
            if (s.length() > keep) hold.delete(0, s.length() - keep);
        }

        /** 流结束时把残留的发出去（避免吞字） */
        String flush() {
            if (!active || !strip) return "";
            String s = hold.toString();
            hold.setLength(0);
            return s;
        }
    }

    // ===================== 5. word-guard =====================

    static boolean wordGuardEnabled(JSONObject middleware) {
        try {
            JSONObject g = cfg(middleware).optJSONObject("wordGuard");
            return g != null && g.optBoolean("enabled", false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** @return 命中的第一个敏感词；没命中返回 null */
    static String hitWord(String text, JSONObject middleware) {
        if (text == null || text.isEmpty()) return null;
        try {
            JSONObject g = cfg(middleware).optJSONObject("wordGuard");
            if (g == null || !g.optBoolean("enabled", false)) return null;
            String lower = text.toLowerCase();

            JSONArray words = g.optJSONArray("words");
            for (int i = 0; words != null && i < words.length(); i++) {
                String w = words.optString(i, "").trim();
                if (w.isEmpty()) continue;
                if (lower.contains(w.toLowerCase())) return w;
            }
            // 正则：patterns 里每条形如 {"pattern": "...", "label": "..."}
            JSONArray pats = g.optJSONArray("patterns");
            for (int i = 0; pats != null && i < pats.length(); i++) {
                // 先看元素类型：optString 对 JSONObject 会返回整个 JSON 文本（非空），
                // 靠 isEmpty() 判断会漏掉对象形式，导致 {"pattern":..} 被当正则字面量编译。
                JSONObject po = pats.optJSONObject(i);
                String p;
                String label = "正则 #" + (i + 1);
                if (po != null) {
                    p = po.optString("pattern", "");
                    label = po.optString("label", label);
                } else {
                    p = pats.optString(i, "");
                }
                if (p == null || p.trim().isEmpty()) continue;
                try {
                    if (java.util.regex.Pattern.compile(p,
                            java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text).find()) {
                        return label;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 打码：把命中的词/正则替换掉 */
    static String maskWords(String text, JSONObject middleware) {
        if (text == null || text.isEmpty()) return text;
        try {
            JSONObject g = cfg(middleware).optJSONObject("wordGuard");
            if (g == null || !g.optBoolean("enabled", false)) return text;
            String with = g.optString("maskWith", "***");
            if (with.isEmpty()) with = "***";
            String out = text;

            JSONArray words = g.optJSONArray("words");
            for (int i = 0; words != null && i < words.length(); i++) {
                String w = words.optString(i, "").trim();
                if (w.isEmpty()) continue;
                out = out.replaceAll("(?i)" + java.util.regex.Pattern.quote(w),
                        java.util.regex.Matcher.quoteReplacement(with));
            }
            JSONArray pats = g.optJSONArray("patterns");
            for (int i = 0; pats != null && i < pats.length(); i++) {
                JSONObject po = pats.optJSONObject(i);
                String p = (po != null) ? po.optString("pattern", "") : pats.optString(i, "");
                if (p == null || p.trim().isEmpty()) continue;
                try {
                    out = out.replaceAll("(?i)" + p, java.util.regex.Matcher.quoteReplacement(with));
                } catch (Throwable ignored) {
                }
            }
            return out;
        } catch (Throwable t) {
            return text;
        }
    }

    /** 扫描一个 OpenAI 请求里所有用户可见的文本（system 与 user 消息） */
    static String requestText(JSONObject payload) {
        if (payload == null) return "";
        StringBuilder sb = new StringBuilder();
        JSONArray msgs = payload.optJSONArray("messages");
        for (int i = 0; msgs != null && i < msgs.length(); i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role", "");
            if (!"user".equals(role) && !"system".equals(role)) continue;
            Object c = m.opt("content");
            if (c instanceof String) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(c);
            } else if (c instanceof JSONArray) {
                JSONArray arr = (JSONArray) c;
                for (int k = 0; k < arr.length(); k++) {
                    JSONObject part = arr.optJSONObject(k);
                    if (part == null) continue;
                    String t = part.optString("text", "");
                    if (!t.isEmpty()) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(t);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** 打码一个请求里所有用户消息文本（system 与 user） */
    static void maskRequestInPlace(JSONObject payload, JSONObject middleware) {
        if (payload == null) return;
        try {
            JSONArray msgs = payload.optJSONArray("messages");
            for (int i = 0; msgs != null && i < msgs.length(); i++) {
                JSONObject m = msgs.optJSONObject(i);
                if (m == null) continue;
                String role = m.optString("role", "");
                if (!"user".equals(role) && !"system".equals(role)) continue;
                Object c = m.opt("content");
                if (c instanceof String) {
                    m.put("content", maskWords((String) c, middleware));
                } else if (c instanceof JSONArray) {
                    JSONArray arr = (JSONArray) c;
                    for (int k = 0; k < arr.length(); k++) {
                        JSONObject part = arr.optJSONObject(k);
                        if (part == null) continue;
                        if (part.has("text")) {
                            part.put("text", maskWords(part.optString("text", ""), middleware));
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** word-guard 是不是「拒绝」模式 */
    static boolean wordGuardBlocks(JSONObject middleware) {
        try {
            JSONObject g = cfg(middleware).optJSONObject("wordGuard");
            return g != null && "block".equals(g.optString("mode", "mask"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ===================== 编排 =====================

    /**
     * 请求侧一次性跑完。顺序有意如此：
     *  system-prompt 先加（让上下文完整）→ param-override 改参数（封顶后的 max_tokens
     *  要能被随后的上下文保护看到）→ word-guard 最后扫（用户原话与注入的 system 都覆盖到）。
     *
     * @return 实际生效的中间件名字列表
     */
    static List<String> runRequest(JSONObject payload, String providerId, JSONObject middleware) {
        List<String> applied = new ArrayList<String>();
        if (payload == null || middleware == null) return applied;

        if (applySystemPrompt(payload, providerId, middleware)) applied.add("system-prompt");
        if (applyParamOverride(payload, providerId, middleware)) applied.add("param-override");

        if (wordGuardEnabled(middleware)) {
            String hit = hitWord(requestText(payload), middleware);
            if (hit != null) {
                if (wordGuardBlocks(middleware)) {
                    // 调用方看到 applied 里有 "word-guard-block" 就直接拒绝
                    applied.add("word-guard-block:" + hit);
                } else {
                    maskRequestInPlace(payload, middleware);
                    applied.add("word-guard");
                }
            }
        }
        return applied;
    }
}
