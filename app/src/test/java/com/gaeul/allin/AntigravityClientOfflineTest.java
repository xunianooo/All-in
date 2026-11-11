package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Offline protocol contract checks for the Antigravity envelope and SSE bridge. */
public final class AntigravityClientOfflineTest {
    public static void main(String[] args) throws Exception {
        imagePartsSurviveEnvelope();
        imageFormsSurviveUserAndToolEnvelope();
        imageRepliesFailClosed();
        hostileImageTypeIsNotEchoed();
        signaturesRoundTripCompletionAndStream();
        namedToolChoiceAndHistoricalPairing();
        thinkingBudgetMatchesMagpie();
        magpieTurnPairingAndGenerationConfig();
        incompleteAndErrorStreamsFailClosed();
        finishReasonsMatchMagpie();
        System.out.println("AntigravityClientOfflineTest: PASS");
    }

    private static void imagePartsSurviveEnvelope() {
        JSONObject payload = new JSONObject();
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "user").put("content", new JSONArray()
                .put(new JSONObject().put("type", "text").put("text", "inspect"))
                .put(new JSONObject().put("type", "image_url").put("image_url",
                        new JSONObject().put("url", "data:image/png;base64,AAE=")))));
        payload.put("messages", messages);
        JSONObject request = AntigravityClient.buildEnvelope(payload, "gemini-3.1-pro", "project")
                .getJSONObject("request");
        JSONArray parts = request.getJSONArray("contents").getJSONObject(0).getJSONArray("parts");
        check("inspect".equals(parts.getJSONObject(0).optString("text")), "text content missing");
        JSONObject inline = parts.getJSONObject(1).getJSONObject("inlineData");
        check("image/png".equals(inline.optString("mimeType")), "image MIME missing");
        check("AAE=".equals(inline.optString("data")), "image data missing");
        JSONObject toolPayload = new JSONObject().put("messages", new JSONArray()
                .put(new JSONObject().put("role", "user").put("content", "inspect"))
                .put(new JSONObject().put("role", "assistant").put("tool_calls", new JSONArray().put(
                        new JSONObject().put("id", "c1").put("function", new JSONObject()
                                .put("name", "read_image").put("arguments", "{}")))))
                .put(new JSONObject().put("role", "tool").put("tool_call_id", "c1")
                        .put("content", new JSONArray().put(new JSONObject().put("type", "image_url")
                                .put("image_url", new JSONObject().put("url", "data:image/jpeg;base64,BBI="))))));
        JSONArray turns = AntigravityClient.buildEnvelope(toolPayload, "gemini-3.1-pro", "project")
                .getJSONObject("request").getJSONArray("contents");
        JSONObject toolImage = turns.getJSONObject(2).getJSONArray("parts").getJSONObject(1).getJSONObject("inlineData");
        check("image/jpeg".equals(toolImage.optString("mimeType")) && "BBI=".equals(toolImage.optString("data")),
                "tool result image missing or out of order");
    }

    private static void imageFormsSurviveUserAndToolEnvelope() {
        JSONArray blocks = new JSONArray()
                .put(new JSONObject().put("type", "text").put("text", "before"))
                .put(new JSONObject().put("type", "image_url").put("image_url", "data:image/png;base64,ONE="))
                .put(new JSONObject().put("type", "input_image").put("image_url", "data:image/jpeg;base64,TWO="))
                .put(new JSONObject().put("type", "image").put("url", "https://example.test/three.png"))
                .put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", "data:image/webp;base64,FOUR=")))
                .put(new JSONObject().put("type", "text").put("text", "after"));
        for (String role : new String[]{"user", "tool"}) {
            JSONObject payload = new JSONObject().put("messages", new JSONArray()
                    .put(new JSONObject().put("role", role).put("name", "read_image").put("content", blocks)));
            String original = payload.toString();
            JSONArray parts = AntigravityClient.buildEnvelope(payload, "gemini-3.1-pro", "project")
                    .getJSONObject("request").getJSONArray("contents").getJSONObject(0).getJSONArray("parts");
            check(original.equals(payload.toString()), "conversion mutated source images");
            int start = 1;
            check(parts.length() == ("tool".equals(role) ? 5 : 6), "image forms disappeared: " + role);
            if ("tool".equals(role)) check("beforeafter".equals(parts.getJSONObject(0)
                    .getJSONObject("functionResponse").getJSONObject("response").getString("result")), "tool text missing");
            check("ONE=".equals(parts.getJSONObject(start).getJSONObject("inlineData").getString("data")), "string image_url lost");
            check("TWO=".equals(parts.getJSONObject(start + 1).getJSONObject("inlineData").getString("data")), "input_image lost");
            check("https://example.test/three.png".equals(parts.getJSONObject(start + 2).getJSONObject("fileData").getString("fileUri")), "remote image lost");
            check("FOUR=".equals(parts.getJSONObject(start + 3).getJSONObject("inlineData").getString("data")), "object image_url lost");
        }
    }

    /** Magpie gives a reply's inlineData an image event of its own; an OpenAI answer carries text
     *  only, so the bridge says the picture cannot be delivered instead of answering with nothing. */
    private static void imageRepliesFailClosed() throws Exception {
        String picture = "{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"AAE=\"}}";
        String imageOnly = replyOf(picture, "STOP");
        String output = readAll(AntigravityClient.toOpenAiStream(bytes(imageOnly), "gemini-image"));
        check(output.contains("image/png") && output.contains("antigravity_stream_error"),
                "a reply carrying only a picture was not reported: " + output);
        check(!output.contains("data: [DONE]") && !output.contains("\"finish_reason\":\"stop\""),
                "a reply carrying a picture ended as a successful, empty answer");
        boolean failed = false;
        try { AntigravityClient.toOpenAiCompletion(bytes(imageOnly), "gemini-image"); }
        catch (Exception expected) { failed = expected.getMessage().contains("image/png"); }
        check(failed, "non-stream decoder must not answer a picture with an empty completion");

        // A picture after text: the text goes out, then why it cannot be the whole answer.
        String withText = replyOf("{\"text\":\"here is the chart\"}," + picture, "STOP");
        output = readAll(AntigravityClient.toOpenAiStream(bytes(withText), "gemini-image"));
        check(output.contains("here is the chart"), "text before a picture was lost");
        check(output.contains("antigravity_stream_error") && !output.contains("data: [DONE]"),
                "text before a picture must not end the turn as success");

        // A tool call is told as it comes, and the picture still leaves the turn unwhole.
        String withCall = replyOf("{\"functionCall\":{\"id\":\"c1\",\"name\":\"lookup\",\"args\":{}}},"
                + picture, "STOP");
        output = readAll(AntigravityClient.toOpenAiStream(bytes(withCall), "gemini-image"));
        check(output.contains("\"tool_calls\"") && output.contains("antigravity_stream_error")
                        && !output.contains("data: [DONE]"),
                "a picture beside a tool call must not pass as a clean stop");

        // Magpie keeps a thought's draft images with its reasoning, and an empty part is nothing.
        String drafts = replyOf("{\"text\":\"thinking\",\"thought\":true},"
                + "{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"AAE=\"},\"thought\":true},"
                + "{\"text\":\"done\"}", "STOP");
        JSONObject message = AntigravityClient.toOpenAiCompletion(bytes(drafts), "gemini-3.1-pro")
                .getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        check("done".equals(message.optString("content")), "a draft image must not change the answer");
        check("thinking".equals(message.optString("reasoning_content")), "draft reasoning was lost");
        output = readAll(AntigravityClient.toOpenAiStream(bytes(drafts), "gemini-3.1-pro"));
        check(output.contains("data: [DONE]") && !output.contains("antigravity_stream_error"),
                "a draft image must not fail the turn");
        String emptyPart = replyOf("{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"\"}},"
                + "{\"text\":\"plain\"}", "STOP");
        check("plain".equals(AntigravityClient.toOpenAiCompletion(bytes(emptyPart), "gemini-3.1-pro")
                        .getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")),
                "an image part without data must stay nothing");

        // Truncation stays the error it was, picture or not.
        String cut = replyOf(picture, null);
        output = readAll(AntigravityClient.toOpenAiStream(bytes(cut), "gemini-image"));
        check(output.contains("antigravity_stream_error") && !output.contains("data: [DONE]"),
                "a picture in a truncated stream must not end as success");
        failed = false;
        try { AntigravityClient.decodeAll(bytes(cut)); } catch (Exception expected) { failed = true; }
        check(failed, "non-stream decoder must reject a truncated reply carrying a picture");
    }

    /** The picture's type is the upstream's word: the error says a picture came back, not
     *  whatever the upstream put in mimeType — no unbounded text, no newline, no [DONE]. */
    private static void hostileImageTypeIsNotEchoed() throws Exception {
        String longType = "image/" + repeat('A', 4000);
        String marker = repeat('A', 20);
        String message = streamError(readAll(AntigravityClient.toOpenAiStream(
                bytes(replyOf(picture(longType), "STOP")), "gemini-image")));
        check(!message.isEmpty(), "a picture with a long type was not reported");
        check(!message.contains(marker), "the upstream's long image type was echoed at its own length");
        check(message.length() < 300, "the reported image error grew with the upstream's type");
        check(message.contains("carries text only"), "the fixed wording of the image error was lost");

        // A type that would close the frame or forge a client's own words is not repeated at all.
        String hostileType = "image/png\"],\"x\":\"leak\",\n\"error\":{\"message\":\"forged";
        String hostile = "data: " + new JSONObject().put("response", new JSONObject().put("candidates",
                new JSONArray().put(new JSONObject()
                        .put("content", new JSONObject().put("parts", new JSONArray().put(new JSONObject()
                                .put("inlineData", new JSONObject().put("mimeType", hostileType).put("data", "AAE=")))))
                        .put("finishReason", "STOP")))) + "\n\n";
        String output = readAll(AntigravityClient.toOpenAiStream(bytes(hostile), "gemini-image"));
        check(!output.contains("forged") && !output.contains("leak") && !output.contains("\\u000a"),
                "the upstream's image type was spoken as this bridge's words: " + output);
        check(!output.contains("data: [DONE]"), "a forged type ended the stream as success");
        for (String line : output.split("\n")) {
            if (line.isEmpty()) continue;
            check(line.startsWith("data: "), "a type broke the stream frame: " + line);
        }
        message = streamError(output);
        check(message.contains("carries text only") && message.length() < 300,
                "a hostile image type was not answered with the fixed wording: " + message);
        check(message.contains("carries text only") && message.length() < 300,
                "a hostile image type was not answered with the fixed wording: " + message);

        // Only a media type the upstream could have gave back unchanged; anything else is unnamed.
        String htmlType = streamError(readAll(AntigravityClient.toOpenAiStream(
                bytes(replyOf(picture("text/html\"><script>"), "STOP")), "gemini-image")));
        check(!htmlType.contains("text/html") && !htmlType.contains("script"),
                "a non-image type was repeated in the error: " + htmlType);
        for (String typed : new String[]{"image/png", "image/jpeg", "IMAGE/WEBP", "image/svg+xml"}) {
            String said = streamError(readAll(AntigravityClient.toOpenAiStream(
                    bytes(replyOf(picture(typed), "STOP")), "gemini-image")));
            check(said.contains(typed), "a well-formed image type was not named: " + typed + " in " + said);
        }
        String unnamed = streamError(readAll(AntigravityClient.toOpenAiStream(
                bytes(replyOf(picture(""), "STOP")), "gemini-image")));
        check(unnamed.contains("returned an image in its reply") && unnamed.contains("carries text only"),
                "an image with no type must still be reported: " + unnamed);
        // The same bound holds for the picture a non-stream reply carries.
        boolean failed = false;
        try { AntigravityClient.toOpenAiCompletion(bytes(replyOf(picture(longType), "STOP")), "gemini-image"); }
        catch (Exception expected) { failed = expected.getMessage().length() < 300 && !expected.getMessage().contains(marker); }
        check(failed, "a non-stream reply echoed the upstream's long image type");
    }

    /** An inline image part of exactly this type. */
    private static String picture(String mimeType) {
        return "{\"inlineData\":{\"mimeType\":\"" + mimeType + "\",\"data\":\"AAE=\"}}";
    }

    /** The message of the error a stream ended with, or "" when it ended without one. */
    private static String streamError(String output) throws Exception {
        for (String line : output.split("\n")) {
            if (!line.startsWith("data: {")) continue;
            JSONObject error = new JSONObject(line.substring(6)).optJSONObject("error");
            if (error != null) return error.optString("message", "");
        }
        return "";
    }

    private static String repeat(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }

    /** A reply of one candidate holding exactly these parts, without finishReason when cut. */
    private static String replyOf(String parts, String finish) {
        return "data: {\"response\":{\"candidates\":[{\"content\":{\"parts\":[" + parts + "]}"
                + (finish == null ? "" : ",\"finishReason\":\"" + finish + "\"") + "}]}}\n\n";
    }

    private static String signedResponse() {
        JSONArray parts = new JSONArray()
                .put(new JSONObject().put("text", "thinking").put("thought", true))
                .put(new JSONObject().put("text", "answer"))
                .put(new JSONObject().put("text", "").put("thoughtSignature", "text-sig+/="))
                .put(new JSONObject().put("functionCall", new JSONObject().put("id", "call1").put("name", "read_image")
                        .put("args", new JSONObject().put("path", "one.png"))).put("thoughtSignature", "call-sig+/="))
                .put(new JSONObject().put("functionCall", new JSONObject().put("id", "call2").put("name", "read_image")
                        .put("args", new JSONObject().put("path", "two.png"))));
        return "data: " + new JSONObject().put("response", new JSONObject().put("candidates", new JSONArray()
                .put(new JSONObject().put("content", new JSONObject().put("parts", parts)).put("finishReason", "STOP")))) + "\n\n";
    }

    private static void signaturesRoundTripCompletionAndStream() throws Exception {
        JSONObject completion = AntigravityClient.toOpenAiCompletion(bytes(signedResponse()), "gemini-3.1-pro");
        JSONObject message = completion.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        assertSignedRoundTrip(message);
        JSONObject streamed = new JSONObject().put("role", "assistant").put("content", "").put("tool_calls", new JSONArray());
        String output = readAll(AntigravityClient.toOpenAiStream(bytes(signedResponse()), "gemini-3.1-pro"));
        check(output.contains("data: [DONE]"), "signed SSE did not complete");
        for (String line : output.split("\n")) {
            if (!line.startsWith("data: {") ) continue;
            JSONObject event = new JSONObject(line.substring(6));
            JSONArray choices = event.optJSONArray("choices");
            if (choices == null || choices.length() == 0) continue;
            JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
            if (delta == null) continue;
            if (delta.has("content")) streamed.put("content", streamed.getString("content") + delta.getString("content"));
            if (delta.has("extra_content")) streamed.put("extra_content", delta.getJSONObject("extra_content"));
            JSONArray calls = delta.optJSONArray("tool_calls");
            for (int i = 0; calls != null && i < calls.length(); i++) streamed.getJSONArray("tool_calls").put(calls.getJSONObject(i));
        }
        assertSignedRoundTrip(streamed);
        // Text-only, signature-only completion must also survive a second request.
        String signatureOnly = "data: {\"response\":{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"\",\"thoughtSignature\":\"empty-sig\"}]},\"finishReason\":\"STOP\"}]}}\n\n";
        JSONObject empty = AntigravityClient.toOpenAiCompletion(bytes(signatureOnly), "gemini-3.1-pro")
                .getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        JSONArray turns = AntigravityClient.buildEnvelope(new JSONObject().put("messages", new JSONArray()
                .put(new JSONObject().put("role", "user").put("content", "go")).put(empty)), "gemini-3.1-pro", "project")
                .getJSONObject("request").getJSONArray("contents");
        check("empty-sig".equals(turns.getJSONObject(1).getJSONArray("parts").getJSONObject(0).optString("thoughtSignature")), "empty signed part lost");
    }

    private static void assertSignedRoundTrip(JSONObject assistant) {
        check("call-sig+/=".equals(assistant.getJSONArray("tool_calls").getJSONObject(0)
                .getJSONObject("extra_content").getJSONObject("google").getString("thought_signature")), "outgoing tool signature missing");
        JSONObject payload = new JSONObject().put("messages", new JSONArray()
                .put(new JSONObject().put("role", "user").put("content", "inspect"))
                .put(assistant)
                .put(new JSONObject().put("role", "tool").put("tool_call_id", "call1").put("content", new JSONArray()
                        .put(new JSONObject().put("type", "image_url").put("image_url", "data:image/png;base64,ONE="))))
                .put(new JSONObject().put("role", "tool").put("tool_call_id", "call2").put("content", "done")));
        JSONArray turns = AntigravityClient.buildEnvelope(payload, "gemini-3.1-pro", "project")
                .getJSONObject("request").getJSONArray("contents");
        JSONArray parts = turns.getJSONObject(1).getJSONArray("parts");
        check("answer".equals(parts.getJSONObject(0).getString("text")), "answer changed");
        check("text-sig+/=".equals(parts.getJSONObject(0).getString("thoughtSignature")), "text signature not returned");
        check("call-sig+/=".equals(parts.getJSONObject(1).getString("thoughtSignature")), "call signature not returned");
        check(!parts.getJSONObject(2).has("thoughtSignature"), "unsigned parallel call acquired a signature");
        JSONArray result = turns.getJSONObject(2).getJSONArray("parts");
        check("read_image".equals(result.getJSONObject(0).getJSONObject("functionResponse").getString("name")), "tool ID pairing lost");
        check("ONE=".equals(result.getJSONObject(1).getJSONObject("inlineData").getString("data")), "tool image lost on next request");
    }

    private static void namedToolChoiceAndHistoricalPairing() {
        JSONObject payload = new JSONObject();
        JSONArray tools = new JSONArray().put(new JSONObject().put("type", "function")
                .put("function", new JSONObject().put("name", "lookup").put("parameters", new JSONObject())));
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "user").put("content", "find"))
                .put(new JSONObject().put("role", "assistant").put("tool_calls", new JSONArray().put(
                        new JSONObject().put("id", "call.bad/1").put("type", "function")
                                .put("function", new JSONObject().put("name", "lookup").put("arguments", "{}")))))
                .put(new JSONObject().put("role", "tool").put("tool_call_id", "call.bad/1").put("content", "ok"));
        payload.put("tools", tools).put("tool_choice", new JSONObject().put("type", "function")
                .put("function", new JSONObject().put("name", "lookup"))).put("messages", messages);
        JSONObject request = AntigravityClient.buildEnvelope(payload, "gemini-3.1-pro", "project")
                .getJSONObject("request");
        JSONObject cfg = request.getJSONObject("toolConfig").getJSONObject("functionCallingConfig");
        check("ANY".equals(cfg.optString("mode")), "named tool choice must be ANY");
        check("lookup".equals(cfg.getJSONArray("allowedFunctionNames").optString(0)), "named tool allowlist missing");
        JSONArray turns = request.getJSONArray("contents");
        JSONObject call = turns.getJSONObject(1).getJSONArray("parts").getJSONObject(0).getJSONObject("functionCall");
        check("call_bad_1".equals(call.optString("id")), "unsafe tool ID was not normalized");
        check("skip_thought_signature_validator".equals(turns.getJSONObject(1).getJSONArray("parts")
                .getJSONObject(0).optString("thoughtSignature")), "tool signature marker missing");
        JSONObject result = turns.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse");
        check("lookup".equals(result.optString("name")), "tool response name was not recovered from call ID");
        JSONObject signedPayload = new JSONObject().put("messages", new JSONArray()
                .put(new JSONObject().put("role", "user").put("content", "continue"))
                .put(new JSONObject().put("role", "assistant").put("tool_calls", new JSONArray().put(
                        new JSONObject().put("id", "signed1").put("extra_content", new JSONObject()
                                .put("google", new JSONObject().put("thought_signature", "sig123")))
                                .put("function", new JSONObject().put("name", "lookup").put("arguments", "{}"))))));
        JSONObject signedPart = AntigravityClient.buildEnvelope(signedPayload, "gemini-3.1-pro", "project")
                .getJSONObject("request").getJSONArray("contents").getJSONObject(1).getJSONArray("parts").getJSONObject(0);
        check("sig123".equals(signedPart.optString("thoughtSignature")), "incoming Google thought signature was not retained");
    }

    private static void thinkingBudgetMatchesMagpie() {
        JSONObject payload = new JSONObject().put("messages", new JSONArray().put(
                new JSONObject().put("role", "user").put("content", "think")))
                .put("reasoning", new JSONObject().put("effort", "low"));
        JSONObject req = AntigravityClient.buildEnvelope(payload, "gemini-4-argon", "project").getJSONObject("request");
        JSONObject thinking = req.getJSONObject("generationConfig").getJSONObject("thinkingConfig");
        check("low".equals(thinking.optString("thinkingLevel")), "nested reasoning effort was ignored");
        payload.put("reasoning_effort", "none");
        req = AntigravityClient.buildEnvelope(payload, "gemini-3.1-pro", "project").getJSONObject("request");
        thinking = req.getJSONObject("generationConfig").getJSONObject("thinkingConfig");
        check("low".equals(thinking.optString("thinkingLevel")), "reasoning off should map to a low supported level");
        payload.put("reasoning_effort", "low").remove("max_tokens");
        JSONObject claude = AntigravityClient.buildEnvelope(payload, "claude-thinking", "project").getJSONObject("request");
        JSONObject claudeGen = claude.getJSONObject("generationConfig");
        check(claudeGen.getJSONObject("thinkingConfig").optInt("thinkingBudget") == 4096,
                "Claude low effort must use Magpie's 4096 budget");
        check(claudeGen.optInt("maxOutputTokens") == 36096, "Claude default output budget mismatch");
        JSONObject defaultClaude = AntigravityClient.buildEnvelope(new JSONObject().put("messages", new JSONArray()
                .put(new JSONObject().put("role", "user").put("content", "think"))), "claude-thinking", "project")
                .getJSONObject("request").getJSONObject("generationConfig");
        check(defaultClaude.getJSONObject("thinkingConfig").optInt("thinkingBudget") == 10000,
                "Claude thinking model should use Magpie's default medium budget");
        payload.put("reasoning_effort", "max");
        claude = AntigravityClient.buildEnvelope(payload, "claude-thinking", "project").getJSONObject("request");
        check(claude.getJSONObject("generationConfig").getJSONObject("thinkingConfig").optInt("thinkingBudget") == 32000,
                "Claude max effort budget mismatch");
        payload.put("max_tokens", 800);
        claude = AntigravityClient.buildEnvelope(payload, "claude-thinking", "project").getJSONObject("request");
        check(!claude.optJSONObject("generationConfig").has("thinkingConfig"),
                "Claude thinking config must be omitted when no 1024 token minimum fits");
    }

    /** Magpie keeps a tool result turn apart from later user text, answers a call no
     *  result came back for, forwards the client's JSON schema, and never sends
     *  gpt-oss a thinkingConfig. */
    private static void magpieTurnPairingAndGenerationConfig() {
        JSONObject split = new JSONObject().put("messages", new JSONArray()
                .put(new JSONObject().put("role", "user").put("content", "read it"))
                .put(new JSONObject().put("role", "assistant").put("tool_calls", new JSONArray().put(
                        new JSONObject().put("id", "c1").put("function", new JSONObject()
                                .put("name", "read").put("arguments", "{}")))))
                .put(new JSONObject().put("role", "tool").put("tool_call_id", "c1").put("content", "body"))
                .put(new JSONObject().put("role", "user").put("content", "now summarize")));
        JSONArray turns = AntigravityClient.buildEnvelope(split, "gemini-3.1-pro", "project")
                .getJSONObject("request").getJSONArray("contents");
        check(turns.length() == 4, "tool result and user text must not share one user turn");
        check(turns.getJSONObject(2).getJSONArray("parts").length() == 1
                        && turns.getJSONObject(2).getJSONArray("parts").getJSONObject(0).has("functionResponse"),
                "tool result turn lost its response");
        check("now summarize".equals(turns.getJSONObject(3).getJSONArray("parts")
                .getJSONObject(0).optString("text")), "user text after a tool result was not split out");

        JSONObject unanswered = new JSONObject().put("messages", new JSONArray()
                .put(new JSONObject().put("role", "user").put("content", "read it"))
                .put(new JSONObject().put("role", "assistant").put("tool_calls", new JSONArray().put(
                        new JSONObject().put("id", "c1").put("function", new JSONObject()
                                .put("name", "read").put("arguments", "{}")))))
                .put(new JSONObject().put("role", "user").put("content", "never mind")));
        JSONArray paired = AntigravityClient.buildEnvelope(unanswered, "gemini-3.1-pro", "project")
                .getJSONObject("request").getJSONArray("contents");
        JSONObject synth = paired.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse");
        check("read".equals(synth.optString("name")) && "c1".equals(synth.optString("id")),
                "unanswered call was not answered before the next user turn");
        check(synth.getJSONObject("response").optString("result").contains("interrupted"),
                "interrupted tool note missing");
        check("never mind".equals(paired.getJSONObject(3).getJSONArray("parts").getJSONObject(0).optString("text")),
                "synthetic answer must not mix with the user text");

        JSONObject schema = new JSONObject().put("type", "object").put("properties", new JSONObject());
        JSONObject formatted = new JSONObject().put("messages", new JSONArray()
                .put(new JSONObject().put("role", "user").put("content", "answer")))
                .put("response_format", new JSONObject().put("type", "json_schema")
                        .put("json_schema", new JSONObject().put("name", "out").put("schema", schema)));
        JSONObject generation = AntigravityClient.buildEnvelope(formatted, "gemini-3.1-pro", "project")
                .getJSONObject("request").getJSONObject("generationConfig");
        check("application/json".equals(generation.optString("responseMimeType")), "json_schema MIME missing");
        check("object".equals(generation.optJSONObject("responseJsonSchema").optString("type")),
                "client JSON schema was not forwarded");

        JSONObject oss = new JSONObject().put("messages", new JSONArray()
                .put(new JSONObject().put("role", "user").put("content", "think")))
                .put("reasoning_effort", "high");
        check(AntigravityClient.buildEnvelope(oss, "gpt-oss-120b", "project")
                .getJSONObject("request").optJSONObject("generationConfig") == null,
                "gpt-oss must not receive a thinkingConfig");
    }

    private static void incompleteAndErrorStreamsFailClosed() throws Exception {
        String upstream = "data: {\"response\":{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"partial\"}]}}]}}\n\n";
        String output = readAll(AntigravityClient.toOpenAiStream(bytes(upstream), "gemini-3.1-pro"));
        check(output.contains("antigravity_stream_error"), "EOF without finishReason was not reported as error");
        check(!output.contains("data: [DONE]"), "incomplete stream was terminated as success");
        boolean failed = false;
        try {
            AntigravityClient.decodeAll(bytes(upstream));
        } catch (Exception expected) {
            failed = true;
        }
        check(failed, "non-stream decoder must reject EOF without finishReason");
        String providerError = "data: {\"type\":\"error\",\"error\":{\"message\":\"upstream rejected\"}}\n\n";
        output = readAll(AntigravityClient.toOpenAiStream(bytes(providerError), "gemini-3.1-pro"));
        check(output.contains("upstream rejected") && !output.contains("data: [DONE]"),
                "provider error must not be followed by success terminal");
        failed = false;
        try { AntigravityClient.decodeAll(bytes(providerError)); }
        catch (Exception expected) { failed = true; }
        check(failed, "non-stream decoder must reject provider error frames");
        failed = false;
        try { AntigravityClient.toOpenAiCompletion(bytes("data: {\"response\":{\"error\":{\"message\":\"wrapped failure\"}}}\n\n"), "gemini-3.1-pro"); }
        catch (Exception expected) { failed = expected.getMessage().contains("wrapped failure"); }
        check(failed, "wrapped response error must not be treated as an empty success");
    }

    private static void finishReasonsMatchMagpie() throws Exception {
        checkCompletionFinish("STOP", "stop");
        checkCompletionFinish("MAX_TOKENS", "length");
        checkCompletionFinish("SAFETY", "content_filter");
        checkCompletionFinish("BLOCKLIST", "content_filter");
        String tool = "{\"id\":\"call1\",\"name\":\"lookup\",\"args\":{}}";
        String response = "data: {\"response\":{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":"+tool+"}]},\"finishReason\":\"STOP\"}]}}\n\n";
        JSONObject completion = AntigravityClient.toOpenAiCompletion(bytes(response), "gemini-3.1-pro");
        check("tool_calls".equals(completion.getJSONArray("choices").getJSONObject(0).optString("finish_reason")),
                "STOP with function call must map to tool_calls");
        String limitedTool = "data: {\"response\":{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":"+tool+"}]},\"finishReason\":\"MAX_TOKENS\"}]}}\n\n";
        completion = AntigravityClient.toOpenAiCompletion(bytes(limitedTool), "gemini-3.1-pro");
        check("length".equals(completion.getJSONArray("choices").getJSONObject(0).optString("finish_reason")),
                "MAX_TOKENS must remain length when tool calls are present");
        boolean failed = false;
        String lateError = "data: {\"response\":{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"partial\"}]},\"finishReason\":\"STOP\"}]}}\n\n"
                + "data: {\"error\":{\"message\":\"late failure\"}}\n\n";
        try { AntigravityClient.toOpenAiCompletion(bytes(lateError), "gemini-3.1-pro"); }
        catch (Exception expected) { failed = expected.getMessage().contains("late failure"); }
        check(failed, "provider error after finish reason must remain an error");
    }

    private static void checkCompletionFinish(String upstreamFinish, String expected) throws Exception {
        String upstream = "data: {\"response\":{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"done\"}]},\"finishReason\":\""+upstreamFinish+"\"}]}}\n\n";
        JSONObject completion = AntigravityClient.toOpenAiCompletion(bytes(upstream), "gemini-3.1-pro");
        String actual = completion.getJSONArray("choices").getJSONObject(0).optString("finish_reason");
        check(expected.equals(actual), upstreamFinish + " mapped to " + actual + " instead of " + expected);
        String stream = readAll(AntigravityClient.toOpenAiStream(bytes(upstream), "gemini-3.1-pro"));
        check(stream.contains("data: [DONE]"), upstreamFinish + " valid finish reason did not terminate stream");
    }

    private static InputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String readAll(InputStream input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) >= 0) out.write(buffer, 0, count);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
