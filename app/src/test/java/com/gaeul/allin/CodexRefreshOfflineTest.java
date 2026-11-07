package com.gaeul.allin;

public final class CodexRefreshOfflineTest {
    static void check(boolean b) { if (!b) throw new AssertionError(); }
    static CodexAuth.SignInInfo info(String a, String r) {
        CodexAuth.SignInInfo i=new CodexAuth.SignInInfo(); i.accessToken=a; i.refreshToken=r; return i;
    }
    public static void main(String[] args) {
        CodexAuth.SignInInfo old=info("old", "old-refresh"), candidate=info("new", "new-refresh");
        old.refreshCommit=(before,next)-> { check(before.accessToken.equals("old")); check(next.accessToken.equals("new")); throw new Exception("synthetic"); };
        try { CodexAuth.applyCommittedRefresh(old,candidate); throw new AssertionError(); }
        catch (CodexAuth.RefreshCommitFailure expected) { check(old.accessToken.equals("old") && old.refreshToken.equals("old-refresh")); }
        old.refreshCommit=(before,next)->check(old.accessToken.equals("old"));
        CodexAuth.applyCommittedRefresh(old,candidate);
        check(old.accessToken.equals("new") && old.refreshToken.equals("new-refresh"));
        check(CodexAuth.shouldReuseCached("old-access", "new-access", "unauthorized_recovery"));
        check(!CodexAuth.shouldReuseCached("new-access", "new-access", "unauthorized_recovery"));
        check(CodexAuth.shouldReuseCached("new-access", "new-access", "proactive_expiry"));
        check(CodexAuth.shouldReuseCached("old-access", "new-access", "proactive_expiry"));
        System.out.println("CodexRefreshOfflineTest passed");
    }
}
