package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Qoder 历史修复入口（AnthropicBridge.repairQoderHistory）的专属离线测试。
 *
 * 事实源：@magpie-community/opencode-qoder-auth 0.2.64 package/index.mjs 的 qoderMessages /
 * callID（:1156-1200、:1250-1285）。本测试只读这两处的语义，不联网、不碰账号、不起 Gradle。
 *
 * 三层验证：
 *  1. 修复入口本身：只补 id、不动别的东西、不改入参、形状不对就抛错。
 *  2. 真实链路：repairQoderHistory → QoderProtocol.apply（真适配器）→ 消息数组。
 *  3. 差分与环回（由仓库外的 node 脚本驱动，见下方两个模式）：
 *       --parity          按夹具打印输入与真实链路输出，供与真实插件逐字对账
 *       --loopback PORT   把真实链路的请求体经真实 TCP 送到严格校验对端，读回裁决
 *
 * 反向测试：严格规范化（normalizeOpenAiMessages）的既有行为一条都不许变，见
 * strictNormalizerKeepsItsOwnBehaviour()。
 */
public final class QoderHistoryRepairTest {

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--parity".equals(args[0])) { printParity(); return; }
        if (args.length == 1 && "--strict-parity".equals(args[0])) { printStrictParity(); return; }
        if (args.length == 2 && "--loopback".equals(args[0])) { loopback(Integer.parseInt(args[1])); return; }

        repairOnlyFillsMissingIds();
        repairNeverMutatesTheCaller();
        repairIsDeterministicAndIdempotent();
        idsAreThePluginsIds();
        repairKeepsImagesAndResultText();
        unrepairableShapesAreRefused();
        repairablePredicateIsShapeOnly();
        strictNormalizerKeepsItsOwnBehaviour();
        chainIsAcceptedByTheAdapter();
        chainInventsNoResultAndKeepsEveryWord();
        adapterStillRefusesObjectArguments();
        System.out.println("QoderHistoryRepairTest: all checks passed");
    }

    // ===================== 夹具 =====================
    // 每条都对应插件 qoderMessages 里的一条规则（index.mjs 行号见各处）。

    static final Map<String, String> FIXTURES = new LinkedHashMap<String, String>();
    static {
        // :1250-1262 调用没 id → 按 (下标, 序号, name, arguments) 算一个；:1255-1262 结果没 id → 回答最早
        // 还没被回答、也没被别的结果点名的那个调用。
        FIXTURES.put("lost-call-id-and-result",
                "[{\"role\":\"user\",\"content\":\"Q1 hello\"},"
                + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"x\\\":1}\"}}]},"
                + "{\"role\":\"tool\",\"content\":\"T1 lost result\"}]");
        // :1267-1275 本回合里被点名的调用算「已保留」，丢 id 的结果不能抢它的答案。
        FIXTURES.put("lost-before-kept",
                "[{\"role\":\"user\",\"content\":\"Q2 hello\"},"
                + "{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":["
                + "{\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"x\\\":1}\"}},"
                + "{\"id\":\"b\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"x\\\":2}\"}}]},"
                + "{\"role\":\"tool\",\"content\":\"T2 lost answers the unnamed call\"},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"b\",\"content\":\"T2 b result\"}]");
        // 空白 id 在插件里等同没有 id（String(c.id ?? "").trim() || callID(...)）。
        FIXTURES.put("blank-ids",
                "[{\"role\":\"user\",\"content\":\"Q3 hello\"},"
                + "{\"role\":\"assistant\",\"content\":null,\"note\":\"untouched\","
                + "\"tool_calls\":[{\"id\":\"   \",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}]},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"  \",\"content\":\"T3 blank id result\"}]");
        // :1255-1258 没有调用可回答的结果 → 变成本人回合里的文字，不丢。
        FIXTURES.put("orphan-result",
                "[{\"role\":\"user\",\"content\":\"Q4 hello\"},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"zzz\",\"content\":\"T4 orphan words\"},"
                + "{\"role\":\"user\",\"content\":\"Q4 next\"}]");
        // 同一调用被回答两次：第一次照常，第二次（连同文字）变成本人回合里的文字，不悄悄丢。
        FIXTURES.put("duplicate-answer",
                "[{\"role\":\"user\",\"content\":\"Q5 hello\"},"
                + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["
                + "{\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}},"
                + "{\"id\":\"b\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}]},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"a\",\"content\":\"T5 A\"},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"a\",\"content\":\"T5 duplicate A\"},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"b\",\"content\":\"T5 B\"}]");
        // 中断的回合：没人回答的调用就那样留着，绝不补一条「结果不可用」的假结果。
        FIXTURES.put("unanswered-call",
                "[{\"role\":\"user\",\"content\":\"Q6 hello\"},"
                + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["
                + "{\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}},"
                + "{\"id\":\"b\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}]},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"a\",\"content\":\"T6 A\"}]");
        // :1277-1285 工具结果的图片走到紧接着的用户回合里，工具回合只留文字和一句提示。
        FIXTURES.put("tool-result-images",
                "[{\"role\":\"user\",\"content\":\"Q7 hello\"},"
                + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"t1\",\"type\":\"function\",\"function\":{\"name\":\"shot\",\"arguments\":\"{}\"}}]},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"t1\",\"name\":\"shot\",\"content\":["
                + "{\"type\":\"text\",\"text\":\"T7 screenshot taken\"},"
                + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,IMG7\"}}]},"
                + "{\"role\":\"user\",\"content\":\"Q7 what is this\"}]");
        // 结果回来的顺序和调用顺序不一样：插件照客户端给的顺序发，绝不替它重排（今天的严格规范化
        // 会按调用顺序重排；Qoder 两种都收，但「客户端说的顺序」才是插件的行为）。
        FIXTURES.put("out-of-order-results",
                "[{\"role\":\"user\",\"content\":\"Q10 hello\"},"
                + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["
                + "{\"id\":\"a1\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}},"
                + "{\"id\":\"b1\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}]},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"b1\",\"content\":\"T10 B\"},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"a1\",\"content\":\"T10 A\"}]");
        // :1185-1200 早先 assistant 回合的思考按 reasoning_content 回去。
        FIXTURES.put("reasoning-content",
                "[{\"role\":\"user\",\"content\":\"Q8 hello\"},"
                + "{\"role\":\"assistant\",\"content\":\"looking\",\"reasoning_content\":\"R8 private thinking\","
                + "\"tool_calls\":[{\"id\":\"r1\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}]},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"r1\",\"content\":\"T8 result\"}]");
        // :1222 system/developer 回合不参与回合配对，但仍然是客户端数组里的下标（id 由它决定）。
        FIXTURES.put("system-and-developer-turns",
                "[{\"role\":\"system\",\"content\":\"S9 system prompt\"},"
                + "{\"role\":\"developer\",\"content\":\"S9 developer note\"},"
                + "{\"role\":\"user\",\"content\":\"Q9 hello\"},"
                + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}]},"
                + "{\"role\":\"tool\",\"content\":\"T9 lost result\"}]");
    }

    /** 客户端原样（不修复）就该被 Qoder 拒的历史：环回时的反向对照，证明校验不是走过场。 */
    static final Set<String> NEGATIVE = new HashSet<String>(Arrays.asList(
            "lost-call-id-and-result", "lost-before-kept", "blank-ids", "orphan-result", "duplicate-answer",
            "system-and-developer-turns"));

    /** 严格规范化会原样放行的干净历史：修复入口在这两条上必须是空操作，链路的输出应与它逐字相同，
     *  用来证明「对账」不是两边都没在检查。其余夹具恰恰是严格规范化会拒或会改写的那几类。 */
    static final Set<String> STRICT_UNCHANGED = new HashSet<String>(Arrays.asList(
            "tool-result-images", "reasoning-content"));

    static JSONArray fixture(String name) throws Exception { return new JSONArray(FIXTURES.get(name)); }

    // ===================== 1. 入口本身 =====================

    /** 修复只补 id：消息条数、角色顺序、其余字段逐字不变。 */
    private static void repairOnlyFillsMissingIds() throws Exception {
        for (Map.Entry<String, String> entry : FIXTURES.entrySet()) {
            JSONArray input = new JSONArray(entry.getValue());
            JSONArray out = AnthropicBridge.repairQoderHistory(input);
            check(out.length() == input.length(), entry.getKey() + ": repair changed the message count");
            for (int i = 0; i < input.length(); i++) {
                JSONObject before = input.getJSONObject(i), after = out.getJSONObject(i);
                check(before.optString("role").equals(after.optString("role")), entry.getKey() + ": role moved");
                JSONObject b = new JSONObject(before.toString()), a = new JSONObject(after.toString());
                stripIds(b);
                stripIds(a);
                check(b.toString().equals(a.toString()),
                        entry.getKey() + ": repair changed more than missing ids at message " + i);
            }
        }
    }

    private static void stripIds(JSONObject message) throws Exception {
        Object raw = message.opt("tool_calls");
        if (!(raw instanceof JSONArray)) return;
        JSONArray calls = (JSONArray) raw;
        for (int k = 0; k < calls.length(); k++) calls.getJSONObject(k).remove("id");
    }

    private static void repairNeverMutatesTheCaller() throws Exception {
        for (Map.Entry<String, String> entry : FIXTURES.entrySet()) {
            JSONArray input = new JSONArray(entry.getValue());
            String before = input.toString();
            AnthropicBridge.repairQoderHistory(input);
            check(before.equals(input.toString()), entry.getKey() + ": repair mutated the caller's array");
        }
    }

    /** 同一个历史每次补出来的 id 必须一样（插件 #290：请求之间不能变，否则提示缓存全废）。 */
    private static void repairIsDeterministicAndIdempotent() throws Exception {
        for (Map.Entry<String, String> entry : FIXTURES.entrySet()) {
            JSONArray once = AnthropicBridge.repairQoderHistory(fixture(entry.getKey()));
            JSONArray twice = AnthropicBridge.repairQoderHistory(fixture(entry.getKey()));
            check(once.toString().equals(twice.toString()), entry.getKey() + ": repair is not deterministic");
            JSONArray again = AnthropicBridge.repairQoderHistory(once);
            check(once.toString().equals(again.toString()), entry.getKey() + ": repair is not idempotent");
        }
    }

    /** 金值来自真实插件：node 里对同一份夹具跑 qoderMessages 得到的 call_6b41... 就是这个串。 */
    private static void idsAreThePluginsIds() throws Exception {
        JSONArray out = AnthropicBridge.repairQoderHistory(fixture("lost-call-id-and-result"));
        JSONObject call = out.getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0);
        check("call_6b41bb068d910234a4cc06aebc323ba5".equals(call.optString("id")),
                "id must be the plugin's callID for the same history: " + call.optString("id"));
        check("b".equals(AnthropicBridge.repairQoderHistory(fixture("lost-before-kept"))
                .getJSONObject(1).getJSONArray("tool_calls").getJSONObject(1).optString("id")),
                "an id the client did send must be left alone");
    }

    private static void repairKeepsImagesAndResultText() throws Exception {
        JSONObject repaired = AnthropicBridge.repairQoderHistory(fixture("tool-result-images")).getJSONObject(2);
        JSONArray content = repaired.getJSONArray("content");
        check("T7 screenshot taken".equals(content.getJSONObject(0).optString("text")), "tool text lost");
        check("data:image/png;base64,IMG7".equals(content.getJSONObject(1).getJSONObject("image_url").optString("url")),
                "tool image lost");
        check("shot".equals(repaired.optString("name")), "tool result name lost");
    }

    private static void unrepairableShapesAreRefused() throws Exception {
        String[] bad = {
            "[{\"content\":\"no role\"}]",
            "[{\"role\":\"   \"}]",
            "[{\"role\":\"user\",\"tool_calls\":{}}]",
            "[{\"role\":\"user\",\"tool_calls\":[42]}]",
            "[{\"role\":\"user\"},7]"
        };
        for (String json : bad) {
            try {
                AnthropicBridge.repairQoderHistory(new JSONArray(json));
                throw new AssertionError("repair accepted a malformed history: " + json);
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage() != null, "refusal must say what is wrong");
            }
        }
        try {
            AnthropicBridge.repairQoderHistory(null);
            throw new AssertionError("repair accepted a missing messages array");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("messages"), "refusal must name messages");
        }
    }

    /** 判据只看形状：能修的历史为 true，形状坏掉的为 false。不用原始历史校验当判据——它比插件严，
     *  见 AnthropicBridge.qoderHistoryRepairable 的说明。 */
    private static void repairablePredicateIsShapeOnly() throws Exception {
        for (String name : FIXTURES.keySet())
            check(AnthropicBridge.qoderHistoryRepairable(new JSONObject().put("messages", fixture(name))),
                    name + ": a history the adapter can take must be repairable");
        check(!AnthropicBridge.qoderHistoryRepairable(new JSONObject()), "missing messages must not be repairable");
        check(!AnthropicBridge.qoderHistoryRepairable(new JSONObject().put("messages", 7)),
                "a non-array messages must not be repairable");
        check(!AnthropicBridge.qoderHistoryRepairable(new JSONObject().put("messages",
                new JSONArray("[{\"role\":\"user\",\"tool_calls\":{}}]"))), "a broken tool_calls must not be repairable");
        check(!AnthropicBridge.qoderHistoryRepairable(null), "a null request must not be repairable");
    }

    // ===================== 2. 反向测试：严格路径一条不许变 =====================

    private static void strictNormalizerKeepsItsOwnBehaviour() throws Exception {
        // 丢了 id 的调用：严格规范化继续抛错（Qoder 之外的每个上游都靠这个拒绝）。
        try {
            AnthropicBridge.normalizeOpenAiMessages(fixture("lost-call-id-and-result"));
            throw new AssertionError("the strict normalizer must still refuse a call without an id");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("id"), "strict refusal must name the id: " + expected.getMessage());
        }
        // 没人回答的调用：严格规范化继续补那条「结果不可用」的凭据（Qoder 路径不许出现它）。
        JSONArray strict = AnthropicBridge.normalizeOpenAiMessages(new JSONArray(
                "[{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"f\",\"arguments\":\"{}\"}}]}]"));
        check(strict.length() == 2 && strict.getJSONObject(1).optString("content").contains("interrupted")
                        && "a".equals(strict.getJSONObject(1).optString("tool_call_id")),
                "the strict normalizer must keep answering an unanswered call with its interruption notice: " + strict);
        // 重复回答：严格规范化继续只留第一份（严格路径的既有取舍，这里只是把它固定住）。
        JSONArray dup = AnthropicBridge.normalizeOpenAiMessages(new JSONArray(
                "[{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"f\",\"arguments\":\"{}\"}}]},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"a\",\"content\":\"first\"},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"a\",\"content\":\"second\"}]"));
        check(dup.length() == 2 && !dup.toString().contains("second"),
                "the strict normalizer's duplicate handling must stay as it was: " + dup);
        // 干净的历史：严格规范化与修复入口都不改它（Qoder 路径在干净历史上与今天完全一致）。
        JSONArray clean = new JSONArray(
                "[{\"role\":\"user\",\"content\":\"hi\"},"
                + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"f\",\"arguments\":\"{}\"}}]},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"a\",\"content\":\"ok\"}]");
        check(AnthropicBridge.repairQoderHistory(clean).toString().equals(clean.toString()),
                "repair must be a no-op on a history that lost nothing");
        check(AnthropicBridge.normalizeOpenAiMessages(clean).toString().equals(clean.toString()),
                "the strict normalizer must be a no-op on the same clean history");
    }

    // ===================== 3. 真实链路（真适配器） =====================

    private static void chainIsAcceptedByTheAdapter() throws Exception {
        for (String name : FIXTURES.keySet()) {
            JSONArray chain = chain(name);
            check(chain.length() > 0, name + ": the adapter produced no messages");
            Set<String> seen = new HashSet<String>();
            List<String> pending = new ArrayList<String>();
            for (int i = 0; i < chain.length(); i++) {
                JSONObject m = chain.getJSONObject(i);
                String role = m.optString("role");
                JSONArray calls = m.optJSONArray("tool_calls");
                if ("assistant".equals(role) && calls != null) {
                    pending = new ArrayList<String>();
                    for (int k = 0; k < calls.length(); k++) {
                        String id = calls.getJSONObject(k).optString("id");
                        check(id != null && !id.trim().isEmpty(), name + ": a call went out without an id");
                        check(seen.add(id), name + ": duplicate call id " + id);
                        pending.add(id);
                    }
                } else if ("tool".equals(role)) {
                    String id = m.optString("tool_call_id");
                    check(!id.trim().isEmpty(), name + ": a tool turn went out without a tool_call_id");
                    check(pending.remove(id), name + ": a tool turn answers no preceding call: " + id);
                } else pending = new ArrayList<String>();
            }
        }
    }

    /** 禁止生成不存在的结果、禁止悄悄丢内容：客户端说的每个字、每张图都要在链路上。 */
    private static void chainInventsNoResultAndKeepsEveryWord() throws Exception {
        for (String name : FIXTURES.keySet()) {
            String chain = chain(name).toString();
            for (String word : words(fixture(name))) check(chain.contains(word), name + ": the chain lost " + word);
            check(!chain.contains("the turn was interrupted"),
                    name + ": the chain invented a result for an unanswered call");
            check(!chain.contains("is unavailable"),
                    name + ": the chain invented a result for an unanswered call");
        }
    }

    /** 夹具里的标记文字与图片（system/developer 回合由网关的 base system 单独携带，不在链路上）。 */
    private static List<String> words(JSONArray messages) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) continue;
            String role = message.optString("role");
            if ("system".equals(role) || "developer".equals(role)) continue;
            collect(message.opt("content"), out);
            if (message.opt("reasoning_content") instanceof String)
                out.add((String) message.opt("reasoning_content"));
        }
        return out;
    }

    private static void collect(Object value, List<String> out) {
        if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value;
            for (int i = 0; i < a.length(); i++) collect(a.opt(i), out);
            return;
        }
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject) value;
            for (java.util.Iterator<String> it = o.keys(); it.hasNext();) {
                String key = it.next();
                Object v = o.opt(key);
                if ("url".equals(key) && v instanceof String && ((String) v).startsWith("data:image")) out.add((String) v);
                else if (v instanceof String && marked((String) v)) out.add((String) v);
                else collect(v, out);
            }
        }
    }

    /** 只挑带 Q/T/S/R 前缀标记的文字，避免把 "" 之类的空串当证据。 */
    private static boolean marked(String text) {
        if (text == null) return false;
        String t = text.trim();
        return t.length() > 2 && Character.isDigit(t.charAt(1))
                && ("QTSR".indexOf(t.charAt(0)) >= 0);
    }

    /** 边界（记录在案，不在本批范围）：参数不是字符串时，插件 JSON.stringify，适配器 strictArgs 拒绝。 */
    private static void adapterStillRefusesObjectArguments() throws Exception {
        JSONArray args = new JSONArray("[{\"role\":\"user\",\"content\":\"Q0 hi\"},"
                + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"type\":\"function\","
                + "\"function\":{\"name\":\"read\",\"arguments\":{\"x\":1}}}]},"
                + "{\"role\":\"tool\",\"content\":\"T0 r\"}]");
        JSONArray repaired = AnthropicBridge.repairQoderHistory(args);
        check(AnthropicBridge.qoderHistoryRepairable(new JSONObject().put("messages", args)),
                "an object-valued arguments still has a repairable shape");
        try {
            QoderProtocol.apply(base(), new JSONObject().put("messages", repaired), "session", "account");
            throw new AssertionError("the adapter used to refuse non-string tool arguments; this boundary changed");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("arguments"), "refusal must name arguments: " + expected.getMessage());
        }
    }

    // ===================== 真实链路的公共部分 =====================

    static JSONObject base() throws Exception {
        return new JSONObject("{\"system\":[{\"type\":\"text\",\"text\":\"S-base system\"}],"
                + "\"parameters\":{\"enable_thinking\":false,\"max_tokens\":500},"
                + "\"business\":{\"id\":\"old\"},\"model_config\":{\"key\":\"model-test\"},"
                + "\"session_id\":\"old\",\"request_set_id\":\"old\"}");
    }

    /** repairQoderHistory → QoderProtocol.apply（真适配器），去掉网关自己的装饰：首条 system 回合、
     *  user 回合的 contents 孪生字段、末条 user 回合的 cache_control 标记。 */
    static JSONArray chain(String name) throws Exception {
        return chainOf(fixture(name));
    }

    static JSONArray chainOf(JSONArray messages) throws Exception {
        JSONArray repaired = AnthropicBridge.repairQoderHistory(messages);
        JSONObject body = QoderProtocol.apply(base(), new JSONObject().put("messages", repaired), "session", "account");
        JSONArray out = body.getJSONArray("messages");
        JSONArray chain = new JSONArray();
        for (int i = 1; i < out.length(); i++) {
            JSONObject copy = new JSONObject(out.get(i).toString());
            copy.remove("contents");
            Object content = copy.opt("content");
            if (content instanceof JSONArray)
                for (int k = 0; k < ((JSONArray) content).length(); k++) {
                    JSONObject block = ((JSONArray) content).optJSONObject(k);
                    if (block != null) block.remove("cache_control");
                }
            chain.put(copy);
        }
        return chain;
    }

    // ===================== 差分 / 环回入口 =====================

    /** 打印夹具真值：{"fixtures":[{name,input,chain}]}，给仓库外的 node 脚本与真实插件逐字对账。 */
    private static void printParity() throws Exception {
        JSONArray fixtures = new JSONArray();
        for (String name : FIXTURES.keySet())
            fixtures.put(new JSONObject().put("name", name)
                    .put("input", new JSONArray(FIXTURES.get(name)))
                    .put("chain", chain(name)));
        System.out.println(new JSONObject().put("fixtures", fixtures).toString());
    }

    /** 反面对照：同一份夹具走严格规范化（今天所有非 Qoder 上游的路）会怎样。
     *  refused 或与插件不一致 → 说明「修复入口」确实是差别所在，也说明上面那份对账不是走过场。 */
    private static void printStrictParity() throws Exception {
        JSONArray fixtures = new JSONArray();
        for (String name : FIXTURES.keySet()) {
            JSONObject entry = new JSONObject().put("name", name)
                    .put("bareRejected", NEGATIVE.contains(name))
                    .put("strictUnchanged", STRICT_UNCHANGED.contains(name));
            try {
                entry.put("chain", chainOf(AnthropicBridge.normalizeOpenAiMessages(fixture(name))));
            } catch (Exception refused) {
                entry.put("refused", String.valueOf(refused.getMessage()));
            }
            fixtures.put(entry);
        }
        System.out.println(new JSONObject().put("fixtures", fixtures).toString());
    }

    /** 真实环回：把真适配器产生的整份请求体经真实 TCP 送到严格校验对端，读回它的裁决。
     *  同一对端还要拒绝「客户端原样、没有修复」的那份历史，证明它不是一个照单全收的服务端。 */
    private static void loopback(int port) throws Exception {
        boolean ok = true;
        for (String name : FIXTURES.keySet()) {
            JSONObject body = new JSONObject().put("messages", withSystem(chain(name)));
            String verdict = send(port, new JSONObject().put("name", name).put("body", body));
            System.out.println("positive " + name + ": " + verdict);
            if (!"accepted".equals(verdict)) ok = false;
        }
        for (String name : FIXTURES.keySet()) {
            if (!NEGATIVE.contains(name)) continue;
            JSONObject body = new JSONObject().put("messages", fixture(name));
            String verdict = send(port, new JSONObject().put("name", "bare-" + name).put("body", body));
            System.out.println("negative " + name + ": " + verdict);
            if (verdict == null || verdict.startsWith("accepted")) ok = false;
        }
        if (!ok) throw new AssertionError("loopback verdict mismatch");
        System.out.println("QoderHistoryRepairTest loopback: ok");
    }

    private static JSONArray withSystem(JSONArray chain) throws Exception {
        JSONArray out = new JSONArray();
        out.put(new JSONObject().put("role", "system").put("content", base().getJSONArray("system")));
        for (int i = 0; i < chain.length(); i++) out.put(chain.get(i));
        return out;
    }

    private static String send(int port, JSONObject line) throws Exception {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(10000);
            OutputStream out = socket.getOutputStream();
            out.write((line.toString() + "\n").getBytes("UTF-8"));
            out.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            return reader.readLine();
        } finally {
            socket.close();
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
