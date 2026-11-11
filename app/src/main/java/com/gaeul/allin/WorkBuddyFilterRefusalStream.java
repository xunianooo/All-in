package com.gaeul.allin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * WorkBuddy 国内上游的安全过滤会在拒绝回复时，把一句话当作正文正常流出来，并给
 * {@code finish_reason=content_filter}（copilot.tencent.com 实测：{@code "\nSorry, I can't
 * respond to this question.\n"}，输出 token 计 0）。上游插件把这句话从流里摘掉、保留
 * finish 与 usage，好让宿主认出「这是拒绝」并去问路由组的下一位成员，而不是把一句道歉
 * 当成答案（plugins#540）。
 *
 * <p>本类只做这一件事，逐事件照抄插件 {@code unsaid} 的判定：
 * <ul>
 *   <li>只有「到目前为止的正文仍是那句话的前缀」时才把事件压住（hold）；出现正文之外的
 *       任何证据（工具调用、正文偏离前缀）立即放开，并把压住的按原序一起吐出。</li>
 *   <li>正文是那句话、且本事件带 {@code finish_reason} 时：仅当 finish 恰为
 *       {@code content_filter} 才把 {@code delta.content} 摘掉（保留 finish、usage、
 *       其他 delta 字段）；finish 是别的值则原样放行——那句话就是它真的回答的内容。</li>
 *   <li>正文为空的事件（role、reasoning、usage、心跳、注释）在还没压住任何正文时立即放行，
 *       所以普通回复的首字不会被延迟。</li>
 * </ul>
 *
 * <p>与上游一样，抑制只在「明确收到 content_filter」时发生；流在没有 finish 的情况下结束时，
 * 压住的事件原样吐出（插件 flush 的行为），不把截断当成拒绝。
 *
 * <p>两处有意的偏离，均以「不制造新的失败」为准：
 * <ul>
 *   <li>压住的缓冲有上限（{@value #MAX_HELD_EVENTS} 个事件 / {@value #MAX_HELD_CHARS} 字符）。
 *       上游没有上限；这里超限就放开并停止抑制，宁可漏一句拒绝文案，也不把内存吃光或
 *       把正常流判死。</li>
 *   <li>前缀比较用 Java 的 {@code trim()}（≤U+0020），JS 的 {@code trim()} 还包含
 *       {@code \u00a0}、{@code \ufeff} 等。实际载荷是换行包裹的句子，二者一致。</li>
 * </ul>
 *
 * <p>本类尚未接线：{@code GatewayServer} 的 WorkBuddy 流式分支需要在交给
 * {@code streamForwardResponse} 之前包一层（见 docs/workbuddy-filter-refusal-stream.md）。
 * 非流式的同一条抑制已由 {@code GatewayServer} 在聚合完成后单独实现。
 */
final class WorkBuddyFilterRefusalStream extends InputStream {

    /** 过滤拒绝时代替正文流出的那句话，与插件 FILTER_SAYS 一致。 */
    static final String FILTER_SAYS = "Sorry, I can't respond to this question.";

    static final int MAX_HELD_EVENTS = 1024;
    static final long MAX_HELD_CHARS = 4L * 1024 * 1024;
    private static final int MAX_EVENT_CHARS = 1024 * 1024;

    private final BufferedReader in;
    private byte[] buffer = new byte[0];
    private int offset;
    private boolean finished;
    /** 回复仍可能是那句拒绝文案；一旦为 false 就不再抑制。 */
    private boolean open = true;
    /** 到目前为止压住的正文（未 trim，比较时再 trim）。 */
    private final StringBuilder said = new StringBuilder();
    private final List<String> held = new ArrayList<String>();
    private long heldChars;
    private final List<String> pending = new ArrayList<String>();
    private boolean eofDrained;

    WorkBuddyFilterRefusalStream(InputStream source) {
        if (source == null) throw new IllegalArgumentException("upstream");
        in = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8));
    }

    /** 只对过滤拒绝返回 true：到目前为止的正文仍是那句话（忽略两侧空白、弯引号）。 */
    static boolean filterSaid(String text) {
        if (text == null) return false;
        String said = text.replace('\u2019', '\'').trim();
        return FILTER_SAYS.startsWith(said);
    }

    // ------------------------------------------------------------------ 事件流

    private void pump() throws IOException {
        while (offset >= buffer.length) {
            if (pending.isEmpty()) {
                if (eofDrained) { finished = true; return; }
                step();
                if (pending.isEmpty() && eofDrained) { finished = true; return; }
                if (pending.isEmpty()) continue;
            }
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < pending.size(); i++) out.append(pending.get(i)).append("\n\n");
            pending.clear();
            buffer = out.toString().getBytes(StandardCharsets.UTF_8);
            offset = 0;
        }
    }

    /** 读一个事件，决定它和可能压住的事件是否此刻放出。 */
    private void step() throws IOException {
        String event = readEvent();
        if (event == null) {
            // 上游结束而抑制还没定论：压住的原样吐出，不把截断当拒绝（插件 flush 的行为）。
            pending.addAll(held);
            held.clear();
            heldChars = 0;
            open = false;
            eofDrained = true;
            return;
        }
        pending.addAll(accept(event));
    }

    /** 一个完整 SSE 事件（不含结尾空行）；null 表示上游结束。 */
    private String readEvent() throws IOException {
        StringBuilder sb = null;
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isEmpty()) return sb == null ? "" : sb.toString();
            if (sb == null) sb = new StringBuilder(line.length() + 8);
            else sb.append('\n');
            sb.append(line);
            if (sb.length() > MAX_EVENT_CHARS) throw new IOException("WorkBuddy event exceeds size limit");
        }
        return sb == null ? null : sb.toString();
    }

    /** 一个事件的处理结果，顺序即输出顺序；空列表表示被压住。 */
    private List<String> accept(String event) {
        List<String> out = new ArrayList<String>(2);
        if (!open) { out.add(event); return out; }
        JSONObject body = chunkOf(event);
        JSONObject choice = body == null ? null : firstChoice(body);
        JSONObject delta = choice == null ? null : choice.optJSONObject("delta");

        String text = "";
        if (delta != null) {
            Object raw = delta.opt("content");
            if (raw instanceof String) text = (String) raw;
        }
        Object finishRaw = choice == null ? null : choice.opt("finish_reason");
        boolean hasFinish = finishRaw != null && finishRaw != JSONObject.NULL
                && !(finishRaw instanceof String && ((String) finishRaw).isEmpty());
        String finish = finishRaw instanceof String ? (String) finishRaw : "";

        boolean calls = false;
        if (delta != null) {
            Object tools = delta.opt("tool_calls");
            if (tools instanceof JSONArray) calls = ((JSONArray) tools).length() > 0;
            else calls = tools != null && tools != JSONObject.NULL;
        }

        // 工具调用，或正文已经偏离那句话：这是真答案，压住的一起放出去。
        if (calls || (!text.isEmpty() && !filterSaid(said.toString() + text))) {
            open = false;
            release(out);
            out.add(event);
            return out;
        }

        said.append(text);

        // 那句话 + content_filter：只摘正文，finish 与 usage 留着给宿主判断。
        if ("content_filter".equals(finish) && said.length() > 0) {
            open = false;
            for (int i = 0; i < held.size(); i++) {
                String without = without(held.get(i));
                if (!without.isEmpty()) out.add(without);
            }
            held.clear();
            heldChars = 0;
            String stripped = without(event);
            if (!stripped.isEmpty()) out.add(stripped);
            return out;
        }
        // 别的 finish：那句话就是它的回答，原样放行。
        if (hasFinish) {
            open = false;
            release(out);
            out.add(event);
            return out;
        }
        // 还没出现任何正文（role / reasoning / usage / 心跳）：立即放行，不拖慢普通回复。
        if (said.length() == 0) {
            out.add(event);
            return out;
        }
        held.add(event);
        heldChars += event.length();
        if (held.size() > MAX_HELD_EVENTS || heldChars > MAX_HELD_CHARS) {
            // 超限按「不是那句拒绝」处理：放开并停止抑制，绝不无限压住一个正常流。
            open = false;
            release(out);
        }
        return out;
    }

    private void release(List<String> out) {
        out.addAll(held);
        held.clear();
        heldChars = 0;
    }

    /**
     * 把事件里的过滤正文摘掉；事件因此再无内容时返回空串（调用方丢弃）。
     * 不带正文字符串的事件原样返回，所以 usage 与 finish 帧逐字节保留。
     */
    private static String without(String event) {
        JSONObject body = chunkOf(event);
        JSONObject choice = body == null ? null : firstChoice(body);
        JSONObject delta = choice == null ? null : choice.optJSONObject("delta");
        if (delta == null) return event;
        Object content = delta.opt("content");
        if (!(content instanceof String)) return event;
        delta.remove("content");
        boolean left = false;
        Iterator<String> keys = delta.keys();
        while (keys.hasNext()) {
            if (!"role".equals(keys.next())) { left = true; break; }
        }
        Object finish = choice.opt("finish_reason");
        boolean noFinish = finish == null || finish == JSONObject.NULL;
        Object usage = body.opt("usage");
        boolean noUsage = usage == null || usage == JSONObject.NULL;
        if (!left && noFinish && noUsage) return "";
        return "data: " + body.toString();
    }

    /** 事件里第一条 {@code data:} 行的 JSON 对象；不是数据帧、不是对象、是 [DONE] 时为 null。 */
    private static JSONObject chunkOf(String event) {
        if (event == null || event.isEmpty()) return null;
        int at = 0;
        while (at <= event.length()) {
            int end = event.indexOf('\n', at);
            if (end < 0) end = event.length();
            String line = event.substring(at, end);
            if (line.startsWith("data:")) {
                String payload = line.substring(5);
                if (payload.startsWith(" ")) payload = payload.substring(1);
                payload = payload.trim();
                if (payload.isEmpty() || "[DONE]".equals(payload)) return null;
                try {
                    return new JSONObject(payload);
                } catch (Exception notJson) {
                    return null;
                }
            }
            if (end >= event.length()) break;
            at = end + 1;
        }
        return null;
    }

    private static JSONObject firstChoice(JSONObject body) {
        JSONArray choices = body.optJSONArray("choices");
        if (choices == null || choices.length() == 0) return null;
        return choices.optJSONObject(0);
    }

    // ------------------------------------------------------------------ InputStream

    @Override
    public int read() throws IOException {
        if (!fill()) return -1;
        return buffer[offset++] & 255;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (b == null) throw new NullPointerException();
        if (off < 0 || len < 0 || off > b.length - len) throw new IndexOutOfBoundsException();
        if (len == 0) return 0;
        if (!fill()) return -1;
        int n = Math.min(len, buffer.length - offset);
        System.arraycopy(buffer, offset, b, off, n);
        offset += n;
        return n;
    }

    private boolean fill() throws IOException {
        pump();
        return offset < buffer.length;
    }

    @Override
    public void close() throws IOException {
        finished = true;
        in.close();
    }
}
