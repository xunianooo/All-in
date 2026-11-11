package com.gaeul.allin;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * End-to-end contracts for WorkBuddy's filtered refusal on every streaming output path.
 *
 * <p>The upstream always answers with the vendor's real shape: ordinary chat SSE whose text is the
 * safety filter's one fixed sentence, ended by {@code finish_reason=content_filter}. What each
 * client protocol receives is asserted separately — Chat, Responses (projected after the shared
 * stream) and Anthropic Messages (translated from the same chat frames) — plus the native Messages
 * relay, which must stay byte for byte.
 */
public final class WorkBuddyFilterRefusalGatewayHttpTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    static final String SAYS = "Sorry, I can't respond to this question.";
    /** Exactly what copilot.tencent.com sends: the sentence wrapped in newlines. */
    static final String PADDED = "\n" + SAYS + "\n";

    static String chunk(JSONObject delta, String finish, JSONObject usage) {
        JSONObject choice = new JSONObject().put("index", 0).put("delta", delta)
                .put("finish_reason", finish == null ? JSONObject.NULL : finish);
        JSONObject body = new JSONObject().put("id", "chatcmpl-fixture")
                .put("object", "chat.completion.chunk")
                .put("choices", new JSONArray().put(choice));
        if (usage != null) body.put("usage", usage);
        return "data: " + body.toString() + "\n\n";
    }

    static JSONObject usage(int prompt, int completion) {
        return new JSONObject().put("prompt_tokens", prompt).put("completion_tokens", completion)
                .put("total_tokens", prompt + completion);
    }

    /** The vendor's refusal: role, one text frame, then the finish frame carrying real usage. */
    static String refusalSse() {
        return chunk(new JSONObject().put("role", "assistant"), null, null)
                + chunk(new JSONObject().put("content", PADDED), null, null)
                + chunk(new JSONObject(), "content_filter", usage(12, 0))
                + "data: [DONE]\n\n";
    }

    /** An ordinary answer, streamed in two frames. */
    static String answerSse() {
        return chunk(new JSONObject().put("role", "assistant"), null, null)
                + chunk(new JSONObject().put("content", "Here is "), null, null)
                + chunk(new JSONObject().put("content", "the plan: ship it."), null, null)
                + chunk(new JSONObject(), "stop", usage(12, 9))
                + "data: [DONE]\n\n";
    }

    static String textSse(String text, String finish) {
        return chunk(new JSONObject().put("role", "assistant"), null, null)
                + chunk(new JSONObject().put("content", text), null, null)
                + chunk(new JSONObject(), finish, usage(12, 4))
                + "data: [DONE]\n\n";
    }

    static JSONObject provider(String id, String model) {
        return VendorPolicyGatewayHttpTest.provider(id, "openai",
                "workbuddy".equals(id) ? "https://copilot.tencent.com/v2" : "https://www.workbuddy.ai/v2", model);
    }

    static VendorPolicyGatewayHttpTest.Result run(String id, String model, String path, JSONObject request,
                                                  String sse, List<String> upstreamPaths) throws Exception {
        try (VendorPolicyGatewayHttpTest.Upstream upstream = new VendorPolicyGatewayHttpTest.Upstream(
                (sent, count) -> new VendorPolicyGatewayHttpTest.Reply(200, "text/event-stream", sse));
             VendorPolicyGatewayHttpTest.Gateway gateway = new VendorPolicyGatewayHttpTest.Gateway(
                     VendorPolicyGatewayHttpTest.config(provider(id, model)), upstream, false)) {
            VendorPolicyGatewayHttpTest.Result result = VendorPolicyGatewayHttpTest.post(gateway, path, request);
            for (VendorPolicyGatewayHttpTest.Request sent : upstream.requests) upstreamPaths.add(sent.path);
            return result;
        }
    }

    static JSONObject chatRequest(String id, String model) {
        return VendorPolicyGatewayHttpTest.body(id + "/" + model, true);
    }

    static JSONObject responsesRequest(String id, String model) {
        return new JSONObject().put("model", id + "/" + model).put("stream", true)
                .put("max_output_tokens", 1024).put("input", "hello");
    }

    static JSONObject messagesRequest(String id, String model) {
        return new JSONObject().put("model", id + "/" + model).put("max_tokens", 1024).put("stream", true)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hello")));
    }

    static String seen(String body) {
        String shown = body.length() > 700 ? body.substring(0, 700) : body;
        return " <" + shown.replace("\n", "\\n") + ">";
    }

    // ------------------------------------------------------------------ Chat clients

    static void chatRefusal() throws Exception {
        for (String id : new String[]{"workbuddy", "workbuddy-ai"}) {
            List<String> paths = new ArrayList<String>();
            VendorPolicyGatewayHttpTest.Result result = run(id, "deepseek-v4.1-flash", "/v1/chat/completions",
                    chatRequest(id, "deepseek-v4.1-flash"), refusalSse(), paths);
            check(result.status == 200, "chat refusal status " + result.status + seen(result.body));
            check(!result.body.contains(SAYS), "chat client must not read the refusal as text:" + seen(result.body));
            check(!result.body.contains("Sorry"), "no fragment of the sentence survives:" + seen(result.body));
            check(result.body.contains("\"content_filter\""), "the finish is kept:" + seen(result.body));
            check(result.body.contains("\"prompt_tokens\":12") && result.body.contains("\"completion_tokens\":0"),
                    "the vendor's real usage is kept:" + seen(result.body));
            check(result.body.contains("[DONE]"), "the stream still ends properly:" + seen(result.body));
            check(paths.size() == 1 && "/v2/chat/completions".equals(paths.get(0)),
                    "one chat request to the vendor endpoint: " + paths);
        }
    }

    static void chatAnswer() throws Exception {
        for (String id : new String[]{"workbuddy", "workbuddy-ai"}) {
            List<String> paths = new ArrayList<String>();
            VendorPolicyGatewayHttpTest.Result result = run(id, "deepseek-v4.1-flash", "/v1/chat/completions",
                    chatRequest(id, "deepseek-v4.1-flash"), answerSse(), paths);
            check(result.status == 200, "chat answer status " + result.status + seen(result.body));
            check(result.body.contains("Here is ") && result.body.contains("the plan: ship it."),
                    "an ordinary answer streams through untouched:" + seen(result.body));
            check(result.body.contains("\"stop\""), "its finish is kept:" + seen(result.body));
        }
    }

    /**
     * Precision: the sentence only counts as a refusal when the finish says content_filter. The same
     * words ending with stop are that reply's real answer, and text that only begins like the
     * sentence is released at its first differing character.
     */
    static void chatPrecision() throws Exception {
        List<String> paths = new ArrayList<String>();
        VendorPolicyGatewayHttpTest.Result sameWords = run("workbuddy", "deepseek-v4.1-flash",
                "/v1/chat/completions", chatRequest("workbuddy", "deepseek-v4.1-flash"),
                textSse(SAYS, "stop"), paths);
        check(sameWords.status == 200, "stop case status " + sameWords.status + seen(sameWords.body));
        check(sameWords.body.contains(SAYS), "the same words with stop are a real answer:" + seen(sameWords.body));

        List<String> divergentPaths = new ArrayList<String>();
        String diverges = SAYS + " But here is what I can say.";
        VendorPolicyGatewayHttpTest.Result divergent = run("workbuddy", "deepseek-v4.1-flash",
                "/v1/chat/completions", chatRequest("workbuddy", "deepseek-v4.1-flash"),
                textSse(diverges, "content_filter"), divergentPaths);
        check(divergent.status == 200, "divergent status " + divergent.status + seen(divergent.body));
        check(divergent.body.contains("But here is what I can say."),
                "text that goes past the sentence is not a refusal:" + seen(divergent.body));
    }

    // ------------------------------------------------------------------ Responses clients

    static void responsesRefusal() throws Exception {
        List<String> paths = new ArrayList<String>();
        VendorPolicyGatewayHttpTest.Result result = run("workbuddy", "deepseek-v4.1-flash", "/v1/responses",
                responsesRequest("workbuddy", "deepseek-v4.1-flash"), refusalSse(), paths);
        check(result.status == 200, "responses refusal status " + result.status + seen(result.body));
        check(!result.body.contains(SAYS), "responses client must not read the refusal as text:" + seen(result.body));
        check(result.body.contains("response.incomplete"), "a filtered reply is incomplete:" + seen(result.body));
        check(result.body.contains("\"reason\":\"content_filter\""),
                "the reason says why:" + seen(result.body));
        check(!result.body.contains("output_text.delta"),
                "no text item is opened for a refusal:" + seen(result.body));
    }

    static void responsesAnswer() throws Exception {
        List<String> paths = new ArrayList<String>();
        VendorPolicyGatewayHttpTest.Result result = run("workbuddy", "deepseek-v4.1-flash", "/v1/responses",
                responsesRequest("workbuddy", "deepseek-v4.1-flash"), answerSse(), paths);
        check(result.status == 200, "responses answer status " + result.status + seen(result.body));
        check(result.body.contains("response.completed"), "an ordinary answer completes:" + seen(result.body));
        check(result.body.contains("Here is the plan: ship it."), "its text arrives:" + seen(result.body));
    }

    // ------------------------------------------------------------------ Anthropic translation

    static void anthropicRefusal() throws Exception {
        List<String> paths = new ArrayList<String>();
        VendorPolicyGatewayHttpTest.Result result = run("workbuddy", "deepseek-v4.1-flash", "/v1/messages",
                messagesRequest("workbuddy", "deepseek-v4.1-flash"), refusalSse(), paths);
        check(result.status == 200, "messages refusal status " + result.status + seen(result.body));
        check(!result.body.contains(SAYS), "a Messages client must not read the refusal as text:" + seen(result.body));
        check(result.body.contains("event: message_start"), "the Messages stream starts:" + seen(result.body));
        check(result.body.contains("\"stop_reason\":\"refusal\""),
                "content_filter becomes the refusal stop reason:" + seen(result.body));
        check(result.body.contains("event: message_stop"), "the Messages stream ends properly:" + seen(result.body));
        check(!result.body.contains("event: error"), "a filtered reply is not a protocol failure:" + seen(result.body));
        check(result.body.contains("\"output_tokens\":0"), "usage is carried into the Messages events:" + seen(result.body));
        check(paths.size() == 1 && "/v2/chat/completions".equals(paths.get(0)),
                "the translated path still uses the chat endpoint: " + paths);
    }

    static void anthropicAnswer() throws Exception {
        List<String> paths = new ArrayList<String>();
        VendorPolicyGatewayHttpTest.Result result = run("workbuddy", "deepseek-v4.1-flash", "/v1/messages",
                messagesRequest("workbuddy", "deepseek-v4.1-flash"), answerSse(), paths);
        check(result.status == 200, "messages answer status " + result.status + seen(result.body));
        check(result.body.contains("text_delta"), "a text block is opened:" + seen(result.body));
        check(result.body.contains("Here is ") && result.body.contains("the plan: ship it."),
                "its text arrives whole:" + seen(result.body));
        check(result.body.contains("\"stop_reason\":\"end_turn\""), "its stop reason is translated:" + seen(result.body));
    }

    // ------------------------------------------------------------------ native Messages is untouched

    /**
     * The domestic Claude Messages relay is a different upstream API and a different event shape;
     * upstream applies `unsaid` to non-native exchanges only. The same sentence must therefore reach
     * a native client unchanged, and only one native request may be sent.
     */
    static void nativeUnaffected() throws Exception {
        String nativeSse =
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_n\",\"type\":\"message\","
                + "\"role\":\"assistant\",\"model\":\"claude-opus-5.5\",\"content\":[],\"usage\":{\"input_tokens\":9}}}\n\n"
                + "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\","
                + "\"text\":\"Sorry, I can't respond to this question.\"}}\n\n"
                + "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                + "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"refusal\",\"stop_sequence\":null},"
                + "\"usage\":{\"output_tokens\":11}}\n\n"
                + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
        List<String> paths = new ArrayList<String>();
        VendorPolicyGatewayHttpTest.Result result = run("workbuddy", "claude-opus-5.5", "/v1/messages",
                messagesRequest("workbuddy", "claude-opus-5.5"), nativeSse, paths);
        check(result.status == 200, "native status " + result.status + seen(result.body));
        check(paths.size() == 1 && "/v1/messages".equals(paths.get(0)),
                "still one native Messages request: " + paths);
        check(result.body.contains(SAYS),
                "the native relay keeps the vendor's text byte for byte:" + seen(result.body));
        check(result.body.contains("\"stop_reason\":\"refusal\""), "and its own stop reason:" + seen(result.body));
    }

    /** A non-streaming client already had equivalent suppression; it must not change or regress. */
    static void nonStreamUnchanged() throws Exception {
        String aggregated = "{\"id\":\"x\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"\\nSorry, I can't respond to this question.\\n\"},"
                + "\"finish_reason\":\"content_filter\"}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":0,\"total_tokens\":12}}";
        try (VendorPolicyGatewayHttpTest.Upstream upstream = new VendorPolicyGatewayHttpTest.Upstream(
                (sent, count) -> new VendorPolicyGatewayHttpTest.Reply(200, "text/event-stream", refusalSse()));
             VendorPolicyGatewayHttpTest.Gateway gateway = new VendorPolicyGatewayHttpTest.Gateway(
                     VendorPolicyGatewayHttpTest.config(provider("workbuddy", "deepseek-v4.1-flash")), upstream, false)) {
            JSONObject request = VendorPolicyGatewayHttpTest.body("workbuddy/deepseek-v4.1-flash", false);
            VendorPolicyGatewayHttpTest.Result result = VendorPolicyGatewayHttpTest.post(gateway,
                    "/v1/chat/completions", request);
            check(result.status == 200, "non-stream status " + result.status + seen(result.body));
            JSONObject choice = new JSONObject(result.body).getJSONArray("choices").getJSONObject(0);
            check(choice.getJSONObject("message").optString("content", "").isEmpty(),
                    "the aggregated reply is a refusal, not an apology:" + seen(result.body));
            check("content_filter".equals(choice.optString("finish_reason")),
                    "and keeps the finish:" + seen(result.body));
            check(!result.body.contains(SAYS), "with no sentence left:" + seen(result.body));
        }
    }

    public static void main(String[] args) throws Exception {
        chatRefusal();
        chatAnswer();
        chatPrecision();
        responsesRefusal();
        responsesAnswer();
        anthropicRefusal();
        anthropicAnswer();
        nativeUnaffected();
        nonStreamUnchanged();
        System.out.println("WorkBuddyFilterRefusalGatewayHttpTest passed");
    }
}
