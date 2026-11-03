package com.magpie.pocket;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

public final class GatewayProtocolOfflineTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static InputStream input(String s) { return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)); }
    static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[256]; int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
    static InputStream nativeStream(InputStream in) throws Exception {
        Class<?> c = Class.forName("com.magpie.pocket.GatewayServer$AnthropicSseToOpenAI");
        Constructor<?> ctor = c.getDeclaredConstructors()[0]; ctor.setAccessible(true);
        return (InputStream) ctor.newInstance(in, new ZCodeAnthropic.StreamState("test"));
    }
    static String evt(String json) { return "data: " + json + "\n\n"; }
    static InputStream fails() { return new InputStream() { public int read() throws IOException { throw new IOException("bad"); } }; }
    public static void main(String[] args) throws Exception {
        PrivacyMasker.Session session = new PrivacyMasker.Session();
        session.placeholderToReal.put("{{MASK_SECRET_ab}}", "value");
        GatewayServer.StreamUnmask u = new GatewayServer.StreamUnmask(session);
        check((u.feed("before {") + u.feed("{MASK_SECRET_") + u.feed("ab}} after") + u.flush()).equals("before value after"), "split placeholder");
        check((u.feed("ordinary {") + u.flush()).equals("ordinary {"), "trailing prefix");
        String literal = "{{" + new String(new char[10000]).replace('\0', 'x');
        check((u.feed(literal) + u.flush()).equals(literal), "bounded ordinary braces");
        GatewayServer.OpenAiUnmask unmask = new GatewayServer.OpenAiUnmask(session);
        JSONObject one = new JSONObject("{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\"}},{\"index\":1,\"function\":{\"arguments\":\"{\"}}]}}]}");
        unmask.apply(one);
        JSONObject two = new JSONObject("{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{MASK_SECRET_ab}}\"}},{\"index\":1,\"function\":{\"arguments\":\"x\"}}]},\"finish_reason\":\"tool_calls\"}]}");
        unmask.apply(two);
        JSONArray calls = two.getJSONArray("choices").getJSONObject(0).getJSONObject("delta").getJSONArray("tool_calls");
        check(calls.getJSONObject(0).getJSONObject("function").getString("arguments").equals("value"), "tool zero");
        check(calls.getJSONObject(1).getJSONObject("function").getString("arguments").equals("{x"), "tool one isolation");
        JSONObject held = new JSONObject("{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":2,\"function\":{\"arguments\":\"{\"}}]}}]}");
        unmask.apply(held);
        JSONObject fin = new JSONObject("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");
        unmask.apply(fin);
        check(fin.toString().contains("arguments"), "tool tail flush");
        String open = evt("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"},\"finish_reason\":null}]}");
        String finish = evt("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}");
        String success = read(GatewayServer.openAiToAnthropicStream(input(open+finish), "test", session, null));
        check(success.contains("message_stop") && !success.contains("event: error"), "finish and EOF");
        for (InputStream bad : new InputStream[]{input(""), input(open), input(open + evt("[DONE]")), fails()}) {
            String result = read(GatewayServer.openAiToAnthropicStream(bad, "test", session, null));
            check(result.contains("event: error") && !result.contains("message_stop"), "openai error event");
        }
        String nativeOk = evt("{\"type\":\"message_start\"}") + evt("{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"hello\"}}") + evt("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}") + evt("{\"type\":\"message_stop\"}");
        check(read(nativeStream(input(nativeOk))).contains("[DONE]"), "native message_stop");
        String maskedNative = evt("{\"type\":\"message_start\"}")
                + evt("{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"{\"}}")
                + evt("{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"{MASK_SECRET_ab}}\"}}")
                + evt("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}")
                + evt("{\"type\":\"message_stop\"}");
        String roundtrip = read(GatewayServer.openAiToAnthropicStream(nativeStream(input(maskedNative)), "test", session, null));
        check(roundtrip.contains("value") && roundtrip.contains("message_stop"), "native to anthropic restores split placeholder");
        String truncated = read(GatewayServer.openAiToAnthropicStream(nativeStream(input(maskedNative.substring(0, maskedNative.lastIndexOf("data:")))), "test", session, null));
        check(truncated.contains("event: error") && !truncated.contains("event: message_stop"), "native truncation propagated");
        for (InputStream bad : new InputStream[]{input(""), input(evt("[DONE]")), input(evt("{\"type\":\"error\",\"error\":{\"message\":\"oops\"}}")), fails()}) {
            String result = read(nativeStream(bad));
            check(result.contains("\"error\"") && !result.contains("data: [DONE]"), "native error event");
        }
        JSONObject raw = new JSONObject("{\"model\":\"x\",\"max_tokens\":128,\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"call\",\"name\":\"f\",\"input\":{}}]},{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"call\",\"is_error\":true,\"content\":\"{{MASK_SECRET_ab}}\"}]}]}");
        JSONObject base = AnthropicBridge.requestToOpenAI(raw);
        JSONObject current = new JSONObject(base.toString());
        GatewayServer.normalizeCoreMessages(current);
        JSONObject passthrough = GatewayServer.nativeAnthropicRequest(raw, base, current, "upstream", false);
        check(passthrough != null && passthrough.toString().contains("is_error") && passthrough.toString().contains("{{MASK_SECRET_ab}}"), "native masked error retained");
        current.put("max_tokens", 64);
        check(GatewayServer.nativeAnthropicRequest(raw, base, current, "upstream", false).getInt("max_tokens") == 64, "output cap retained");
        current.put("temperature", 0.5);
        check(GatewayServer.nativeAnthropicRequest(raw, base, current, "upstream", false) == null, "middleware change disables native");
        current = new JSONObject(base.toString());
        current.put("messages", new JSONArray());
        check(GatewayServer.nativeAnthropicRequest(raw, base, current, "upstream", false) == null, "guard mutation disables native");
        check(GatewayServer.validateRawMessages(new JSONArray("[null]")) != null, "invalid raw message rejected");
        JSONObject missing = new JSONObject("{\"messages\":[{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"x\",\"type\":\"function\",\"function\":{\"name\":\"f\",\"arguments\":\"{}\"}}]}]}");
        GatewayServer.normalizeCoreMessages(missing);
        check(missing.getJSONArray("messages").length() == 2, "direct OpenAI tool pairing");
        JSONObject nativeImage = new JSONObject("{\"model\":\"x\",\"max_tokens\":128,\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"img\",\"name\":\"f\",\"input\":{}}]},{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"img\",\"is_error\":true,\"content\":[{\"type\":\"text\",\"text\":\"broken\"},{\"type\":\"image\",\"source\":{\"type\":\"url\",\"url\":\"https://example.invalid/a.png\"}}]}]}]}");
        JSONObject imageBase = AnthropicBridge.requestToOpenAI(nativeImage);
        JSONObject imageCurrent = new JSONObject(imageBase.toString()); imageCurrent.put("temperature", 0.3);
        JSONObject imageFallback = ZCodeAnthropic.toAnthropic(imageCurrent);
        ContextGuard.Config disabled = new ContextGuard.Config(); disabled.enabled = false;
        JSONObject recovered = GatewayServer.restoreNativeToolResults(nativeImage, imageBase, imageCurrent, imageFallback, disabled);
        check(GatewayServer.jsonEquivalent(recovered.getJSONArray("messages"), nativeImage.getJSONArray("messages")) && recovered.optDouble("temperature") == 0.3, "image attribution and middleware preserved");
        JSONObject changed = new JSONObject(imageCurrent.toString());
        JSONArray changedMessages = changed.getJSONArray("messages");
        for (int k = 0; k < changedMessages.length(); k++) {
            JSONObject m = changedMessages.getJSONObject(k);
            if ("tool".equals(m.optString("role"))) m.put("content", "[truncated]");
        }
        JSONObject changedFallback = ZCodeAnthropic.toAnthropic(changed);
        JSONObject changedRestored = GatewayServer.restoreNativeToolResults(nativeImage, imageBase, changed, changedFallback, disabled);
        check(GatewayServer.jsonEquivalent(changedFallback, changedRestored), "do not resurrect truncated content or image " + changedFallback + " != " + changedRestored);
        ContextGuard.Config tiny = new ContextGuard.Config(); tiny.enabled = true; tiny.maxBodyBytes = 1;
        check(GatewayServer.jsonEquivalent(imageFallback, GatewayServer.restoreNativeToolResults(nativeImage, imageBase, imageCurrent, imageFallback, tiny)), "guard rejects expanded restored body");
        JSONObject textChanged = new JSONObject(base.toString());
        textChanged.put("temperature", 0.3);
        textChanged.getJSONArray("messages").put(new JSONObject().put("role", "user").put("content", "new message"));
        JSONObject partial = GatewayServer.restoreNativeToolResults(raw, base, textChanged, ZCodeAnthropic.toAnthropic(textChanged), disabled);
        check(partial.toString().contains("is_error") && partial.toString().contains("{{MASK_SECRET_ab}}"), "unchanged matched text result restored after message change");
        System.out.println("GatewayProtocolOfflineTest OK");
    }
}
