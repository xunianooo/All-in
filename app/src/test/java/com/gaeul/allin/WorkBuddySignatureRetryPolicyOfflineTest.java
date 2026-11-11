package com.gaeul.allin;

import org.json.JSONObject;

/** Retry eligibility, safety and one-retry budget tests for WorkBuddy signature refusals. */
public final class WorkBuddySignatureRetryPolicyOfflineTest {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static final String ERROR="{\"error\":{\"internal_code\":11159,\"message\":\"Invalid signature in thinking block\"}}";
    public static void main(String[] args) throws Exception {
        JSONObject clean=new JSONObject("{\"model\":\"claude-opus-5.5\",\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"\"},{\"type\":\"text\",\"text\":\"continue\"}]},{\"role\":\"user\",\"content\":\"next\"}]}");
        String before=clean.toString();
        WorkBuddySignatureRetryPolicy.Session retry=WorkBuddyChatGatewayAdapter.newSignatureRetrySession(null,true);
        WorkBuddySignatureRetryPolicy.Decision candidate=retry.decide(clean,400,ERROR,false);
        check(candidate.retry&&candidate.reason==null&&candidate.body!=null,"enabled explicit refusal has a safe one-retry candidate");
        check(candidate.body.getJSONArray("messages").getJSONObject(0).getJSONArray("content").length()==1,"only empty unsigned thinking removed");
        check(before.equals(clean.toString()),"source history remains immutable");
        check(retry.retryCandidateIssued(),"session spends its one retry budget when issuing candidate");
        check(retry.decide(candidate.body,400,ERROR,false).reason==WorkBuddySignatureRetryPolicy.StopReason.ALREADY_RETRIED,"session never issues a second retry");
        check(WorkBuddyChatGatewayAdapter.newSignatureRetrySession(null,false).decide(clean,400,ERROR,false).reason==WorkBuddySignatureRetryPolicy.StopReason.RETRY_DISABLED,"retry requires explicit enablement");
        check(WorkBuddyChatGatewayAdapter.newSignatureRetrySession(null,true).decide(clean,400,ERROR,true).reason==WorkBuddySignatureRetryPolicy.StopReason.OUTCOME_UNKNOWN,"unknown execution never retries");
        check(WorkBuddySignatureRetryPolicy.decide(clean,503,ERROR,true,false,false,false).reason==WorkBuddySignatureRetryPolicy.StopReason.HTTP_STATUS_NOT_RETRYABLE,"only definitive 400 qualifies");
        check(WorkBuddySignatureRetryPolicy.decide(clean,400,"ordinary bad request",true,false,false,false).reason==WorkBuddySignatureRetryPolicy.StopReason.NOT_SIGNATURE_REJECTION,"unrelated rejection is terminal");

        JSONObject signed=new JSONObject("{\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"opaque\",\"signature\":\"signed-blob\"},{\"type\":\"text\",\"text\":\"answer\"}]}]}");
        String signedBefore=signed.toString();WorkBuddySignatureRetryPolicy.Decision denied=WorkBuddySignatureRetryPolicy.decide(signed,400,ERROR,true,false,false,false);
        check(!denied.retry&&denied.reason==WorkBuddySignatureRetryPolicy.StopReason.PROTECTED_HISTORY&&signedBefore.equals(signed.toString()),"signature-protected history stays unchanged and refused");
        JSONObject nonempty=new JSONObject("{\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"reasoning is context\"}]}]}");
        check(WorkBuddySignatureRetryPolicy.decide(nonempty,400,ERROR,true,false,false,false).reason==WorkBuddySignatureRetryPolicy.StopReason.RETRY_WOULD_BE_LOSSY,"non-empty thinking history is not dropped");
        JSONObject emptyNoThinking=new JSONObject("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}");
        check(WorkBuddySignatureRetryPolicy.decide(emptyNoThinking,400,ERROR,true,false,false,false).reason==WorkBuddySignatureRetryPolicy.StopReason.RETRY_WOULD_BE_LOSSY,"no-op replay is refused");
        JSONObject redacted=new JSONObject("{\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"redacted_thinking\",\"data\":\"opaque\"}]}]}");
        check(WorkBuddySignatureRetryPolicy.decide(redacted,400,ERROR,true,false,false,false).reason==WorkBuddySignatureRetryPolicy.StopReason.PROTECTED_HISTORY,"redacted history is protected");

        JSONObject enterprise=new JSONObject().put("enterpriseId","team-1");
        check(WorkBuddyChatGatewayAdapter.isEnterpriseAccount(enterprise),"adapter recognizes enterprise account");
        WorkBuddySignatureRetryPolicy.Decision enterpriseDenied=WorkBuddyChatGatewayAdapter.newSignatureRetrySession(enterprise,true).decide(clean,400,ERROR,false);
        check(!enterpriseDenied.retry&&enterpriseDenied.reason==WorkBuddySignatureRetryPolicy.StopReason.ENTERPRISE_SKIPPED,"enterprise retry is skipped");
        JSONObject personal=new JSONObject().put("uid","personal");
        check(WorkBuddyChatGatewayAdapter.newSignatureRetrySession(personal,true).decide(clean,400,ERROR,false).retry,"personal account reaches enabled policy");
        System.out.println("WorkBuddySignatureRetryPolicyOfflineTest OK");
    }
}
