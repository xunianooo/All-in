package com.gaeul.allin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

public final class NativeAnthropicStreamOfflineTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static String e(String type, String body) { return "event: " + type + "\ndata: {\"type\":\"" + type + "\"" + body + "}\n\n"; }
    static final String START = e("message_start", ",\"message\":{\"content\":[]}");
    static final String END = e("message_delta", ",\"delta\":{\"stop_reason\":\"end_turn\"}") + e("message_stop", "");
    static String block(int i, String json) { return e("content_block_start", ",\"index\":" + i + ",\"content_block\":" + json); }
    static String delta(int i, String json) { return e("content_block_delta", ",\"index\":" + i + ",\"delta\":" + json); }
    static String stop(int i) { return e("content_block_stop", ",\"index\":" + i); }
    static InputStream input(String s) { return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)); }
    static String read(InputStream in) throws Exception { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[257]; int n; while ((n = in.read(b)) != -1) out.write(b,0,n); return new String(out.toByteArray(), StandardCharsets.UTF_8); }
    static NativeAnthropicStream stream(String s, PrivacyMasker.Session session) { return new NativeAnthropicStream(input(s), session, null, null); }
    public static void main(String[] args) throws Exception {
        String signed = START + block(0,"{\"type\":\"thinking\",\"thinking\":\"\",\"extra\":17}") + delta(0,"{\"type\":\"thinking_delta\",\"thinking\":\"\"}") + delta(0,"{\"type\":\"signature_delta\",\"signature\":\"abc\"}") + delta(0,"{\"type\":\"signature_delta\",\"signature\":\"def\"}") + stop(0) + block(1,"{\"type\":\"redacted_thinking\",\"data\":\"opaque\"}") + stop(1) + END;
        check(read(stream(signed,null)).equals(signed), "signed frames unchanged");
        check(read(stream(signed,new PrivacyMasker.Session())).equals(signed), "empty session unchanged");
        String mixed = START + block(0,"{\"type\":\"text\",\"text\":\"\",\"unknown\":true}") + delta(0,"{\"type\":\"text_delta\",\"text\":\"hi\",\"future\":42}") + stop(0) + block(1,"{\"type\":\"tool_use\",\"id\":\"x\",\"name\":\"f\",\"input\":{}}") + delta(1,"{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}") + stop(1) + END;
        check(read(stream(mixed,null)).equals(mixed), "mixed frames unchanged");
        String crlf = ": comment\r\nid: 7\r\nretry: 42\r\nevent: future\r\ndata: not-json\r\n\r\n" + "event: message_start\r\ndata: {\"type\":\"message_start\",\r\ndata: \"message\":{\"content\":[]}}\r\n\r\n" + END;
        check(read(stream(crlf,null)).equals(crlf), "CRLF multiline unknown");
        String allCrLf = (START + END).replace("\n", "\r\n");
        InputStream singleBulk = new ByteArrayInputStream(allCrLf.getBytes(StandardCharsets.UTF_8)) {
            @Override public synchronized int read(byte[] b, int off, int len) { return super.read(b, off, Math.min(len, 1)); }
            @Override public synchronized int read() { throw new AssertionError("single byte upstream read"); }
        };
        check(read(new NativeAnthropicStream(singleBulk, null, null, null)).equals(allCrLf), "CRLF split across reads including final message_stop");
        InputStream fragmented = new FilterInputStream(input(mixed)) { @Override public int read(byte[] b,int o,int l) throws IOException { return super.read(b,o,Math.min(1,l)); } };
        check(read(new NativeAnthropicStream(fragmented,null,null,null)).equals(mixed), "fragmented input");
        String upstreamError = e("error", ",\"error\":{\"type\":\"overloaded_error\",\"message\":\"original\"}");
        check(read(stream(upstreamError,null)).equals(upstreamError), "upstream error once");
        for (String bad : new String[]{"", START, START + e("message_stop", ""), START + block(0,"{\"type\":\"text\",\"text\":\"\"}") + END, e("message_start", ",\"message\":{\"content\":[{}]}")}) {
            String result = read(stream(bad,null)); check(result.contains("event: error") && !result.contains("event: message_stop"), "invalid stream fails");
        }
        for(String reason:new String[]{"unknown","","Model_Context_Window_Exceeded","model_context_window_exceeded "}) {
            String badReason=START+e("message_delta",",\"delta\":{\"stop_reason\":\""+reason+"\"}")+e("message_stop","");
            String result=read(stream(badReason,null));
            check(result.contains("event: error")&&!result.contains("event: message_stop"),"invalid terminal reason rejected");
        }
        String paused=START+e("message_delta",",\"delta\":{\"stop_reason\":\"pause_turn\"}")+e("message_stop","");
        check(read(stream(paused,null)).equals(paused),"native pause_turn preserved");
        String window=START+e("message_delta",",\"delta\":{\"stop_reason\":\"model_context_window_exceeded\"}")+e("message_stop","");
        check(read(stream(window,null)).equals(window),"native context window reason preserved");
        String openWindow=START+block(0,"{\"type\":\"text\",\"text\":\"\"}")+e("message_delta",",\"delta\":{\"stop_reason\":\"model_context_window_exceeded\"}")+e("message_stop","");
        check(read(stream(openWindow,null)).contains("event: error"),"context window reason with open block");
        PrivacyMasker.Session s = new PrivacyMasker.Session(); s.placeholderToReal.put("{{MASK_X}}", "restored");
        String masked = START + block(0,"{\"type\":\"text\",\"text\":\"\"}") + delta(0,"{\"type\":\"text_delta\",\"text\":\"{{MASK_\",\"extra\":1}") + delta(0,"{\"type\":\"text_delta\",\"text\":\"X}}\"}") + stop(0) + END;
        String restored = read(stream(masked,s)); check(restored.contains("restored") && restored.contains("\"extra\":1") && !restored.contains("event: error"), "split placeholder");
        String isolated = START + block(0,"{\"type\":\"text\",\"text\":\"\"}") + delta(0,"{\"type\":\"text_delta\",\"text\":\"{{MASK_\"}") + block(1,"{\"type\":\"text\",\"text\":\"\"}") + delta(1,"{\"type\":\"text_delta\",\"text\":\"X}}\"}") + stop(0) + stop(1) + END;
        check(!read(stream(isolated,s)).contains("restored"), "block isolation");
        String unknown = "event: future\ndata: not-json\n\n";
        String pendingUnknown = START + block(0,"{\"type\":\"text\",\"text\":\"\"}") + delta(0,"{\"type\":\"text_delta\",\"text\":\"{{MASK_\"}") + unknown + delta(0,"{\"type\":\"text_delta\",\"text\":\"X}}\"}") + stop(0) + END;
        String rejectedUnknown = read(stream(pendingUnknown,s));
        check(rejectedUnknown.contains("event: error") && !rejectedUnknown.contains("event: message_stop") && !rejectedUnknown.contains(unknown), "unknown event cannot cross pending text");
        check(read(stream(pendingUnknown,new PrivacyMasker.Session())).equals(pendingUnknown), "unknown event passes with empty mapping");
        check(read(stream(unknown + START + END, s)).equals(unknown + START + END), "unknown event before message start");
        String protectedMixed = START + block(0,"{\"type\":\"thinking\",\"thinking\":\"{{MASK_X}}\"}") + delta(0,"{\"type\":\"thinking_delta\",\"thinking\":\"{{MASK_X}}\"}") + delta(0,"{\"type\":\"signature_delta\",\"signature\":\"{{MASK_X}}\"}") + stop(0) + block(1,"{\"type\":\"redacted_thinking\",\"data\":\"{{MASK_X}}\"}") + stop(1) + block(2,"{\"type\":\"text\",\"text\":\"{{MASK_X}}\"}") + stop(2) + block(3,"{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"f\",\"input\":{\"nested\":[\"{{MASK_X}}\"],\"x\":3},\"unknown\":\"{{MASK_X}}\"}") + stop(3) + END;
        String protectedResult = read(stream(protectedMixed,s));
        check(protectedResult.contains("\"thinking\":\"{{MASK_X}}\"") && protectedResult.contains("\"signature\":\"{{MASK_X}}\"") && protectedResult.contains("\"data\":\"{{MASK_X}}\"") && protectedResult.contains("restored") && protectedResult.contains("\"unknown\":\"{{MASK_X}}\"") && !protectedResult.contains("event: error"), "protected and unmasked coexist");
        String longSignature = new String(new char[20000]).replace('\0','q');
        String sigStream = START + block(0,"{\"type\":\"thinking\",\"thinking\":\"\"}") + delta(0,"{\"type\":\"signature_delta\",\"signature\":\"" + longSignature + "\"}") + stop(0) + END;
        check(read(stream(sigStream,s)).equals(sigStream), "long signature fragmented");
        AtomicInteger bulk = new AtomicInteger();
        InputStream counted = new ByteArrayInputStream(mixed.getBytes(StandardCharsets.UTF_8)) { @Override public synchronized int read(byte[] b, int o, int l) { bulk.incrementAndGet(); return super.read(b,o,Math.min(l,31)); } @Override public synchronized int read() { throw new AssertionError("single byte upstream read"); } };
        check(read(new NativeAnthropicStream(counted,null,null,null)).equals(mixed) && bulk.get() > 1, "bulk reads");
        String badReason = START + block(0,"{\"type\":\"text\",\"text\":\"\"}") + END;
        check(read(stream(badReason,null)).contains("event: error"), "stop reason with open block");
        String badSignature = START + block(0,"{\"type\":\"text\",\"text\":\"\"}") + delta(0,"{\"type\":\"signature_delta\",\"signature\":\"s\"}");
        check(read(stream(badSignature,null)).contains("event: error"), "signature requires thinking");
        AtomicInteger closes = new AtomicInteger(), completes = new AtomicInteger();
        NativeAnthropicStream cancelled = new NativeAnthropicStream(input(mixed),null,new NativeAnthropicStream.Listener() { public void onEvent(JSONObject e) {} public void onComplete(boolean success) { check(!success,"cancel fails"); completes.incrementAndGet(); } },closes::incrementAndGet);
        cancelled.read(); cancelled.close(); cancelled.close(); check(closes.get()==1 && completes.get()==1,"close once");
        AtomicInteger exceptionalComplete = new AtomicInteger();
        NativeAnthropicStream exceptional = new NativeAnthropicStream(input(mixed), null, new NativeAnthropicStream.Listener() {
            public void onEvent(JSONObject event) { }
            public void onComplete(boolean success) { check(!success, "exceptional close fails"); exceptionalComplete.incrementAndGet(); }
        }, () -> { throw new IllegalStateException("close action"); });
        try { exceptional.close(); throw new AssertionError("close action exception expected"); } catch (IllegalStateException expected) { }
        exceptional.close(); check(exceptionalComplete.get() == 1, "exceptional close completes once");
        AtomicInteger deliveryCount = new AtomicInteger();
        AtomicInteger deliverySuccess = new AtomicInteger();
        NativeAnthropicStream.Listener deliveryListener = new NativeAnthropicStream.Listener() {
            public void onEvent(JSONObject event) { }
            public void onComplete(boolean success) { deliveryCount.incrementAndGet(); if (success) deliverySuccess.incrementAndGet(); }
        };
        NativeAnthropicStream deliveryFailure = new NativeAnthropicStream(input(START + END), null, deliveryListener, null);
        deliveryFailure.requireDeliveryConfirmation(); read(deliveryFailure);
        check(deliveryCount.get() == 0, "read waits for delivery");
        deliveryFailure.onWriteComplete(false); deliveryFailure.close();
        check(deliveryCount.get() == 1 && deliverySuccess.get() == 0, "delivery failure completes false");
        deliveryCount.set(0); deliverySuccess.set(0);
        NativeAnthropicStream delivered = new NativeAnthropicStream(input(START + END), null, deliveryListener, null);
        delivered.requireDeliveryConfirmation(); read(delivered);
        check(deliveryCount.get() == 0, "success awaits confirmation");
        delivered.onWriteComplete(true); delivered.onWriteComplete(true); delivered.close();
        check(deliveryCount.get() == 1 && deliverySuccess.get() == 1, "delivery success once");
        deliveryCount.set(0); deliverySuccess.set(0);
        NativeAnthropicStream earlyClose = new NativeAnthropicStream(input(START + END), null, deliveryListener, null);
        earlyClose.requireDeliveryConfirmation(); earlyClose.read(); earlyClose.close(); earlyClose.onWriteComplete(true);
        check(deliveryCount.get() == 1 && deliverySuccess.get() == 0, "incomplete close fails");
        deliveryCount.set(0); deliverySuccess.set(0);
        NativeAnthropicStream autoClosed = new NativeAnthropicStream(input(START + END), null, deliveryListener, null);
        autoClosed.requireDeliveryConfirmation(); read(autoClosed); autoClosed.close();
        check(deliveryCount.get() == 0, "NanoHTTPD close waits for final flush confirmation");
        autoClosed.onWriteComplete(true);
        check(deliveryCount.get() == 1 && deliverySuccess.get() == 1, "auto close then successful flush");
        deliveryCount.set(0); deliverySuccess.set(0);
        NativeAnthropicStream incomplete = new NativeAnthropicStream(input(START), null, deliveryListener, null);
        incomplete.requireDeliveryConfirmation(); incomplete.onWriteComplete(true);
        check(deliveryCount.get() == 0, "confirmation alone cannot complete stream");
        incomplete.close(); check(deliveryCount.get() == 1 && deliverySuccess.get() == 0, "incomplete stream fails");
        InputStream first = new InputStream() { byte[] a=START.getBytes(StandardCharsets.UTF_8); int p; public int read() throws IOException { if (p==a.length) throw new IOException("read ahead"); return a[p++]; } };
        byte[] buf = new byte[4096]; NativeAnthropicStream immediate = new NativeAnthropicStream(first,null,null,null); check(immediate.read(buf)==START.getBytes(StandardCharsets.UTF_8).length,"first frame immediate");
        String huge = "data: " + new String(new char[4*1024*1024]).replace('\0','x') + "\n\n";
        check(read(stream(huge,null)).contains("event: error"), "frame limit");
    }
}
