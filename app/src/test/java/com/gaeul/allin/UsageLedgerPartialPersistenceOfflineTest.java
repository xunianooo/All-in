package com.gaeul.allin;

import java.io.File;
import java.nio.file.Files;
import org.json.JSONObject;

public final class UsageLedgerPartialPersistenceOfflineTest {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("partial-ledger-").toFile();
        UsageLedger.init(dir);
        UsageLedger.Row row = new UsageLedger.Row();
        row.requestId = "partial-fixture"; row.status = 200;
        row.usageSource = "partial"; row.inputKnown = true; row.outputKnown = true;
        row.in = 30; row.out = 16;
        row.cacheSource = "partial"; row.cacheRead = 10; row.cacheWrite = 4;
        row.reasoningSource = "partial"; row.reasoning = 7;
        row.cacheInputTokens = 123; // Partial data must not establish a hit denominator.
        UsageLedger.record(row);
        UsageLedger.init(dir);
        JSONObject summary = UsageLedger.summary(1, "model");
        JSONObject recent = summary.getJSONArray("recent").getJSONObject(0);
        JSONObject totals = summary.getJSONObject("totals");
        check("partial".equals(recent.getString("cacheSource")), "cache source");
        check("partial".equals(recent.getString("reasoningSource")), "reasoning source");
        check(recent.getInt("cacheRead") == 10 && recent.isNull("cacheInputTokens"), "cache value and denominator");
        check(totals.getLong("cacheRead") == 10 && totals.getLong("cacheWrite") == 4 && totals.getLong("reasoning") == 7, "reloaded partial totals");
        check(totals.getLong("cacheKnown") == 0 && totals.getLong("cacheKnownInputTokens") == 0 && totals.getLong("reasoningKnown") == 0, "partial is not known");
        String csv = UsageLedger.csv(1);
        check(csv.contains(",30,16,10,4,7,"), "partial csv values");
        System.out.println("UsageLedgerPartialPersistenceOfflineTest passed");
    }
}
