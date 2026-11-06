package com.gaeul.allin;

import java.util.Map;
import java.util.TimeZone;

public final class ClientFingerprintOfflineTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        Map<String, String> copilot = CopilotAuth.clientHeaders();
        check(CopilotAuth.UA.equals(copilot.get("User-Agent")), "Copilot UA");
        check(CopilotAuth.EDITOR_VERSION.equals(copilot.get("Editor-Version")), "Copilot editor");
        check(CopilotAuth.PLUGIN_VERSION.equals(copilot.get("Editor-Plugin-Version")), "Copilot plugin");
        TimeZone ny = TimeZone.getTimeZone("America/New_York");
        check(MiniMaxAuth.timezoneOffsetSeconds(ny, 1768478400000L) == -18000, "NY winter");
        check(MiniMaxAuth.timezoneOffsetSeconds(ny, 1784116800000L) == -14400, "NY summer");
        check(MiniMaxAuth.timezoneOffsetSeconds(TimeZone.getTimeZone("Asia/Kolkata"), 1784116800000L) == 19800, "half-hour offset");
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
            check("19800".equals(MiniMaxAuth.chatHeaders("test-session").get("X-Mavis-Timezone-Offset")), "actual header");
        } finally { TimeZone.setDefault(previous); }
        System.out.println("ClientFingerprintOfflineTest OK");
    }
}
