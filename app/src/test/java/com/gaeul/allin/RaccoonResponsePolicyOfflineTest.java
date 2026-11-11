package com.gaeul.allin;

import org.json.JSONObject;

/** Offline contract checks for the Raccoon response shaping the plugin performs. */
public final class RaccoonResponsePolicyOfflineTest {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        signInState();
        jsonModelRewrite();
        sseLineRewrite();
        System.out.println("RaccoonResponsePolicyOfflineTest passed");
    }

    private static void signInState() {
        check("expired".equals(RaccoonResponsePolicy.signInState(401, false, false)), "401 is expired");
        check("expired".equals(RaccoonResponsePolicy.signInState(401, true, false)), "401 stays expired after a renewal");
        check("renewed".equals(RaccoonResponsePolicy.signInState(200, true, true)), "rotated token reports renewed");
        check("kept".equals(RaccoonResponsePolicy.signInState(200, false, true)), "plain success is kept");
        check("kept".equals(RaccoonResponsePolicy.signInState(500, false, false)), "a failed call that never touched the token is kept");
        check("kept".equals(RaccoonResponsePolicy.signInState(200, true, false)), "a rotated token on a failed call is not 'renewed'");
        check("X-Magpie-Sign-In".equals(RaccoonResponsePolicy.SIGN_IN_HEADER), "header name matches the plugin");
    }

    private static void jsonModelRewrite() {
        String body = "{\"id\":\"x\",\"model\":\"upstream-name\",\"choices\":[]}";
        JSONObject rewritten = new JSONObject(RaccoonResponsePolicy.rewriteJson(body, "raccoon/asked"));
        check("raccoon/asked".equals(rewritten.getString("model")), "requested model replaces the upstream one");
        check("x".equals(rewritten.getString("id")) && rewritten.getJSONArray("choices").length() == 0,
                "the rest of the body is untouched");
        check(body.equals(RaccoonResponsePolicy.rewriteJson(body, "")), "no requested model leaves the body alone");
        check(body.equals(RaccoonResponsePolicy.rewriteJson(body, null)), "null requested model leaves the body alone");
        String noModel = "{\"id\":\"x\",\"choices\":[]}";
        check(noModel.equals(RaccoonResponsePolicy.rewriteJson(noModel, "raccoon/asked")),
                "a body without a model key is passed through byte for byte");
        String broken = "{\"model\":";
        check(broken.equals(RaccoonResponsePolicy.rewriteJson(broken, "raccoon/asked")),
                "unparseable JSON is passed through, not dropped");
        check("".equals(RaccoonResponsePolicy.rewriteJson("", "raccoon/asked")), "empty body stays empty");
    }

    private static void sseLineRewrite() {
        String frame = "data: {\"id\":\"c1\",\"model\":\"upstream-name\",\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}";
        JSONObject rewritten = new JSONObject(RaccoonResponsePolicy.rewriteSseLine(frame, "raccoon/asked").substring(6));
        check("raccoon/asked".equals(rewritten.getString("model")), "streamed frame carries the requested model");
        check("hi".equals(rewritten.getJSONArray("choices").getJSONObject(0).getJSONObject("delta").getString("content")),
                "streamed delta survives the rewrite");
        String same = "data: {\"model\":\"raccoon/asked\",\"choices\":[]}";
        check(same.equals(RaccoonResponsePolicy.rewriteSseLine(same, "raccoon/asked")), "an already-correct frame is untouched");
        check("data: [DONE]".equals(RaccoonResponsePolicy.rewriteSseLine("data: [DONE]", "raccoon/asked")), "[DONE] is untouched");
        check("event: message".equals(RaccoonResponsePolicy.rewriteSseLine("event: message", "raccoon/asked")), "non-data lines are untouched");
        check("".equals(RaccoonResponsePolicy.rewriteSseLine("", "raccoon/asked")), "blank lines are untouched");
        check("data: not-json".equals(RaccoonResponsePolicy.rewriteSseLine("data: not-json", "raccoon/asked")),
                "an unparseable frame is passed through, not dropped");
        check("data: {\"choices\":[]}".equals(RaccoonResponsePolicy.rewriteSseLine("data: {\"choices\":[]}", "raccoon/asked")),
                "a frame without a model key is untouched");
        check("data: {\"model\":\"upstream\"}".equals(RaccoonResponsePolicy.rewriteSseLine("data: {\"model\":\"upstream\"}", "")),
                "no requested model means no rewrite");
    }
}
