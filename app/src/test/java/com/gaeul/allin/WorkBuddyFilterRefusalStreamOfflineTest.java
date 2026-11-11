package com.gaeul.allin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/**
 * Offline contract checks for the WorkBuddy filter-refusal stream, against the plugin's
 * {@code unsaid}: only the filter's own sentence, ended by {@code finish_reason
 * content_filter}, is taken out; the finish and usage frames stay; any other reply's text
 * goes out as it comes.
 */
final class WorkBuddyFilterRefusalStreamOfflineTest {

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static String head() {
        return "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}\n\n";
    }

    private static String text(String value) {
        return "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":"
                + JSONObject.quote(value) + "}}]}\n\n";
    }

    private static String frame(String deltaJson, String finish, String extra) {
        return "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":"
                + deltaJson + ",\"finish_reason\":" + (finish == null ? "null" : JSONObject.quote(finish))
                + "}]" + (extra == null ? "" : "," + extra) + "}\n\n";
    }

    private static final String USAGE = "\"usage\":{\"prompt_tokens\":31,\"completion_tokens\":0,\"total_tokens\":31}";

    private static InputStream wire(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    /** An upstream that hands over {@code step} bytes at a time, so frames split at any byte. */
    private static InputStream drip(final String text, final int step) {
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return new InputStream() {
            private int at;
            @Override public int read() {
                return at < bytes.length ? bytes[at++] & 255 : -1;
            }
            @Override public int read(byte[] b, int off, int len) {
                if (len == 0) return 0;
                if (at >= bytes.length) return -1;
                int n = Math.min(Math.min(step, len), bytes.length - at);
                System.arraycopy(bytes, at, b, off, n);
                at += n;
                return n;
            }
        };
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream source = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[256];
            int n;
            while ((n = source.read(buffer, 0, buffer.length)) >= 0) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String filtered(InputStream in) throws IOException {
        return readAll(new WorkBuddyFilterRefusalStream(in));
    }

    /** Each read() result, so a test can tell what went out before the stream ended. */
    private static List<String> reads(InputStream in, int size) throws IOException {
        List<String> out = new ArrayList<String>();
        try (InputStream source = new WorkBuddyFilterRefusalStream(in)) {
            byte[] buffer = new byte[size];
            int n;
            while ((n = source.read(buffer, 0, buffer.length)) >= 0) {
                if (n > 0) out.add(new String(buffer, 0, n, StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static List<JSONObject> frames(String output) throws Exception {
        List<JSONObject> out = new ArrayList<JSONObject>();
        for (String block : output.split("\n\n")) {
            String line = block.trim();
            if (line.isEmpty() || !line.startsWith("data:")) continue;
            String payload = line.substring(5).trim();
            if ("[DONE]".equals(payload)) continue;
            out.add(new JSONObject(payload));
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        refusalIsSuppressed();
        splitAtEveryByte();
        ordinaryTextGoesOutAtOnce();
        divergenceReleasesWhatWasHeld();
        otherTextUnderContentFilterIsKept();
        filterSentenceWithOtherFinishIsKept();
        toolCallsReleaseImmediately();
        endWithoutFinishReleases();
        crlfFraming();
        curlyApostropheAndPadding();
        noSpaceAfterDataColon();
        nonTextFramesPassImmediately();
        heldBufferBoundOpens();
        emptyAndCommentFrames();
        singleByteReadWorks();
        System.out.println("WorkBuddyFilterRefusalStreamOfflineTest passed");
    }

    /** The filter's sentence, split over frames, is taken out; finish, usage and [DONE] stay. */
    private static void refusalIsSuppressed() throws Exception {
        String upstream = head()
                + text("Sorry, I can")
                + text("'t respond to this question.")
                + frame("{\"content\":\"\"}", "content_filter", USAGE)
                + "data: [DONE]\n\n";
        String out = filtered(wire(upstream));
        check(!out.contains("Sorry") && !out.contains("respond to this question"),
                "the filter sentence must not reach the client: " + out);
        check(out.contains("\"role\":\"assistant\""), "the role frame is kept");
        check(out.contains("\"prompt_tokens\":31") && out.contains("\"total_tokens\":31"),
                "the usage frame is kept as it came: " + out);
        check(out.contains("\"finish_reason\":\"content_filter\""), "the finish is kept: " + out);
        check(out.trim().endsWith("data: [DONE]"), "the terminal frame is kept: " + out);
        List<JSONObject> kept = frames(out);
        check(kept.size() == 2, "role + finish frames only (the [DONE] is not a JSON frame): "
                + kept.size() + " -> " + out);
        JSONObject finish = kept.get(1);
        check("content_filter".equals(finish.getJSONArray("choices").getJSONObject(0).optString("finish_reason")),
                "the kept finish still says content_filter");
        check(finish.getJSONArray("choices").getJSONObject(0).getJSONObject("delta").length() == 0,
                "the refused text is gone from the finish frame's delta: " + finish);
    }

    /** Frames split at arbitrary byte offsets, one byte at a time, still suppress. */
    private static void splitAtEveryByte() throws Exception {
        String upstream = head()
                + text("Sorry, I can")
                + text("\u2019t respond to this question.")
                + frame("{}", "content_filter", USAGE)
                + "data: [DONE]\n\n";
        for (int step : new int[]{1, 2, 3, 5, 17}) {
            String out = filtered(drip(upstream, step));
            check(!out.contains("Sorry") && !out.contains("respond"),
                    "a reply split every " + step + " byte(s) must still be suppressed: " + out);
            check(out.contains("content_filter") && out.contains("[DONE]"),
                    "the terminal frames survive the " + step + "-byte split: " + out);
        }
    }

    /** A reply that is not the filter's is released with its first text frame, not at EOF. */
    private static void ordinaryTextGoesOutAtOnce() throws Exception {
        String upstream = head() + text("Hello there") + frame("{}", "stop", null) + "data: [DONE]\n\n";
        List<String> reads = reads(wire(upstream), 256);
        check(reads.size() >= 2, "each frame is its own read: " + reads.size());
        check(reads.get(0).contains("\"role\":\"assistant\""), "the role frame arrives first: " + reads.get(0));
        check(reads.get(1).contains("Hello there"),
                "the first text frame is released immediately, not held to the end: " + reads.get(1));
        for (String read : reads) check(!read.isEmpty(), "no empty read in the middle of a live reply");
    }

    /** Text that only begins like the filter is let through at its first diverging word. */
    private static void divergenceReleasesWhatWasHeld() throws Exception {
        String upstream = head()
                + text("Sorry, I can")
                + text(" help you with that.")
                + frame("{}", "stop", null)
                + "data: [DONE]\n\n";
        String out = filtered(wire(upstream));
        check(out.contains("Sorry, I can") && out.contains(" help you with that."),
                "a diverging reply keeps its whole text: " + out);
        check(out.indexOf("Sorry, I can") < out.indexOf(" help you with that."),
                "the held frame comes out before the one that ended the doubt");
        check(!out.contains("content_filter"), "a stop reply is not a refusal");
    }

    /** Under content_filter, text that is not the filter's sentence stays. */
    private static void otherTextUnderContentFilterIsKept() throws Exception {
        String upstream = head() + text("Here is the answer.") + frame("{}", "content_filter", null) + "data: [DONE]\n\n";
        String out = filtered(wire(upstream));
        check(out.contains("Here is the answer."), "only the filter's own text is dropped: " + out);
        check(out.contains("content_filter"), "the finish still travels");
    }

    /** The filter's sentence ended by another finish is what the model said: it stays. */
    private static void filterSentenceWithOtherFinishIsKept() throws Exception {
        String upstream = head()
                + text(WorkBuddyFilterRefusalStream.FILTER_SAYS)
                + frame("{}", "stop", null)
                + "data: [DONE]\n\n";
        String out = filtered(wire(upstream));
        check(out.contains("Sorry, I can't respond to this question."),
                "without content_filter the sentence is the answer: " + out);
    }

    /** A tool call is proof the reply is not the refusal, even with the filter's text held. */
    private static void toolCallsReleaseImmediately() throws Exception {
        String upstream = head()
                + text("Sorry, I can")
                + frame("{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}]}", null, null)
                + frame("{}", "tool_calls", null)
                + "data: [DONE]\n\n";
        String out = filtered(wire(upstream));
        check(out.contains("Sorry, I can"), "the held text comes back out: " + out);
        check(out.contains("call_1"), "the tool call travels: " + out);
        check(out.indexOf("Sorry, I can") < out.indexOf("call_1"), "held text precedes the call it was waiting for");
    }

    /** Upstream ends while the answer is still in doubt: what was held goes out as it came. */
    private static void endWithoutFinishReleases() throws Exception {
        String upstream = head() + text("Sorry, I can");
        String out = filtered(wire(upstream));
        check(out.contains("Sorry, I can"), "an end with no finish is not proof of a refusal: " + out);
        check(!out.contains("content_filter"), "nothing invented at the end");
    }

    /** CRLF framing is read the same way. */
    private static void crlfFraming() throws Exception {
        String upstream = head().replace("\n\n", "\r\n\r\n")
                + text("Sorry, I can't respond to this question.").replace("\n\n", "\r\n\r\n")
                + frame("{}", "content_filter", USAGE).replace("\n\n", "\r\n\r\n")
                + "data: [DONE]\r\n\r\n";
        String out = filtered(wire(upstream));
        check(!out.contains("Sorry"), "CRLF framing must not defeat the check: " + out);
        check(out.contains("content_filter") && out.contains("[DONE]"), "CRLF terminal frames survive: " + out);
    }

    /** The curly apostrophe copilot.tencent.com can send, and the newlines around the sentence. */
    private static void curlyApostropheAndPadding() throws Exception {
        String curly = head()
                + text("Sorry, I can\u2019t respond to this question.")
                + frame("{}", "content_filter", null)
                + "data: [DONE]\n\n";
        check(!filtered(wire(curly)).contains("respond to this question"),
                "a curly apostrophe is the same sentence");
        String padded = head()
                + text("\nSorry, I can't respond to this question.\n")
                + frame("{}", "content_filter", null)
                + "data: [DONE]\n\n";
        check(!filtered(wire(padded)).contains("respond to this question"),
                "the newlines the upstream wraps around the sentence are ignored");
        check(WorkBuddyFilterRefusalStream.filterSaid("\n Sorry, I can't \n"),
                "whitespace on either side still reads as the filter's");
        check(!WorkBuddyFilterRefusalStream.filterSaid("Sorry, I can't respond to this question. And more."),
                "text that goes on past the sentence is not the filter's");
    }

    /** A data line with no space after the colon is still read. */
    private static void noSpaceAfterDataColon() throws Exception {
        String upstream = "data:{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}\n\n"
                + "data:{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Sorry, I can't respond to this question.\"}}]}\n\n"
                + "data:{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"content_filter\"}],"
                + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":0,\"total_tokens\":7}}\n\n"
                + "data: [DONE]\n\n";
        String out = filtered(wire(upstream));
        check(!out.contains("Sorry"), "a frame without a space after data: is still understood: " + out);
        check(out.contains("\"prompt_tokens\":7") && out.contains("content_filter"),
                "its usage and finish survive: " + out);
    }

    /** Reasoning, usage and choice-less frames never wait behind the filter check. */
    private static void nonTextFramesPassImmediately() throws Exception {
        String usageOnly = "data: {\"id\":\"c1\",\"choices\":[],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":1,\"total_tokens\":6}}\n\n";
        String reasoning = frame("{\"reasoning_content\":\"thinking\"}", null, null);
        String upstream = head() + reasoning + usageOnly + text("Sorry, I can't respond to this question.")
                + frame("{}", "content_filter", null) + "data: [DONE]\n\n";
        List<String> reads = reads(wire(upstream), 4096);
        check(!reads.isEmpty() && reads.get(0).contains("\"role\":\"assistant\""),
                "the role frame comes first: " + reads);
        String reasoningRead = readWith(reads, "thinking");
        String usageRead = readWith(reads, "\"prompt_tokens\":5");
        check(reasoningRead != null && !reasoningRead.contains("content_filter"),
                "reasoning is released as it comes, not held to the finish: " + reads);
        check(usageRead != null && !usageRead.contains("content_filter"),
                "a standalone usage frame is released as it comes: " + reads);
        String all = String.join("", reads);
        check(all.indexOf("thinking") < all.indexOf("content_filter"), "reasoning precedes the finish");
        check(all.contains("\"prompt_tokens\":5"), "a usage frame on its own travels: " + all);
        check(!all.contains("Sorry"), "the refusal is still suppressed when other frames came first: " + all);
        check(all.contains("\"prompt_tokens\":5") && all.contains("\"total_tokens\":6"),
                "the standalone usage frame is kept whole: " + all);
    }

    /** The first read that carries {@code marker}, or null. */
    private static String readWith(List<String> reads, String marker) {
        for (String read : reads) if (read.contains(marker)) return read;
        return null;
    }

    /** A pathological hold opens the gate instead of growing without bound. */
    private static void heldBufferBoundOpens() throws Exception {
        StringBuilder upstream = new StringBuilder(head()).append(text("Sorry, I can"));
        for (int i = 0; i < WorkBuddyFilterRefusalStream.MAX_HELD_EVENTS + 8; i++) {
            upstream.append("data: {\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{}}],\"seq\":").append(i).append("}\n\n");
        }
        upstream.append(frame("{}", "content_filter", null)).append("data: [DONE]\n\n");
        String out = filtered(wire(upstream.toString()));
        check(out.contains("Sorry, I can"),
                "past the hold bound the stream is let through rather than buffered for ever: " + out.length());
        check(out.contains("[DONE]"), "and it still ends properly");
    }

    /** Empty events and comment lines are not mistaken for content. */
    private static void emptyAndCommentFrames() throws Exception {
        String upstream = ": keep-alive\n\n" + head() + "\n\n"
                + text("Sorry, I can't respond to this question.")
                + frame("{}", "content_filter", null)
                + "data: [DONE]\n\n";
        String out = filtered(wire(upstream));
        check(out.contains(": keep-alive"), "a comment line is passed through: " + out);
        check(!out.contains("Sorry"), "the refusal is still suppressed: " + out);
        String notJson = ": ping\n\ndata: not-json\n\n" + head();
        check(filtered(wire(notJson)).contains("not-json"), "an unparseable data line is passed through");
    }

    /** The single-byte read() path works the same as the array one. */
    private static void singleByteReadWorks() throws Exception {
        String upstream = head() + text("Sorry, I can't respond to this question.")
                + frame("{}", "content_filter", null) + "data: [DONE]\n\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new WorkBuddyFilterRefusalStream(wire(upstream))) {
            int b;
            while ((b = in.read()) >= 0) out.write(b);
        }
        String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
        check(!text.contains("Sorry") && text.contains("[DONE]"), "single-byte reads suppress too: " + text);
    }
}
