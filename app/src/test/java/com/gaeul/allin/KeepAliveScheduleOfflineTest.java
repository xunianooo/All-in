package com.gaeul.allin;

public final class KeepAliveScheduleOfflineTest {
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
    public static void main(String[] args) {
        long start=1000,target=start+KeepAliveScheduler.INTERVAL_MS;
        check(KeepAliveScheduler.pendingRequestFresh("inexactOneShot",start,target,start+1,1),"In-flight alarm is not postponed");
        check(KeepAliveScheduler.pendingRequestFresh("inexactOneShot",start,target,target+1000,target+1000-start),"Ordinary inexact delivery delay is tolerated");
        long stale=target+2*KeepAliveScheduler.INTERVAL_MS+1;
        check(!KeepAliveScheduler.pendingRequestFresh("inexactOneShot",start,target,stale,stale-start),"Stale metadata cannot suppress rescheduling forever");
        check(!KeepAliveScheduler.pendingRequestFresh("inexactOneShot",start,target,500,500),"Reboot epoch invalidates old alarm");
        check(!KeepAliveScheduler.pendingRequestFresh("inexactOneShot",start,target,target,0),"Wall/elapsed mismatch invalidates old alarm");
        check(!KeepAliveScheduler.pendingRequestFresh("",start,target,target,target-start),"Cancelled metadata is not live");
        System.out.println("KeepAliveScheduleOfflineTest PASS checks=6");
    }
}
