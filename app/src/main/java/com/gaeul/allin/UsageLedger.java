package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.TreeMap;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * 用量账本：每一次调用一行，追加写、不掉数据，汇总口径对齐 yetone/magpie
 * 的 internal/usage（Totals / Row）。
 *
 * 口径（照抄上游，别自己拍脑袋）：
 *   - Tokens      = Input + Output（不含缓存流量）
 *   - 缓存命中率   = 已知缓存的 CacheRead / 相应的 Input token；未知覆盖单独报告
 *   - 速度        = DecodeOut / DecodeMs，解码窗口取「首字 → 结束」这段时间
 *                   窗口 < 100ms 或折算 > 10000 tok/s 视为「一次性吐完」，不计速度（但 TTFT 仍计）
 *   - 失败        = status >= 400 或 err 非空
 *   - 重试        = tries > 1（第一次失败、后面成功的那次，算成功）
 *
 * 落盘格式：filesDir/usage/ledger-YYYYMM.jsonl，一行一条，只追加从不重写。
 */
public class UsageLedger {

    private static final Object LOCK = new Object();
    private static final String DIR = "usage";
    /** 内存里保留的天数（更早的只留在文件里） */
    private static final int MEMORY_DAYS = 90;
    /** 单个账本文件超过这个大小就不再往里写，开新月份文件兜底 */
    private static final long MAX_FILE_BYTES = 32L * 1024 * 1024;

    private static File baseDir;
    private static File currentFile;
    private static OutputStreamWriter writer;
    private static String currentMonth = "";
    private static final Object REBUILD_LOCK = new Object();
    private static Map<String, HeatCounts> heatDays = new LinkedHashMap<String, HeatCounts>();
    // Timestamp precision from the indexed local day onward supports clock rollback.
    private static final TreeMap<Long, HeatCounts> future = new TreeMap<Long, HeatCounts>();
    private static TimeZone heatZone;
    private static long firstTimestamp = Long.MAX_VALUE;
    private static long invalidLines, readErrors, persistenceErrors, uncertainWrites;
    private static long precisionStart;
    private static boolean initializing;
    // Package-private fault hooks for deterministic offline tests.
    interface RollbackHook { boolean rollback(File file, long length) throws Exception; }
    static RollbackHook rollbackHook;
    static Runnable rebuildSnapshotHook;
    private static List<Row> rebuildDelta;
    private static final List<Row> unpersisted = new ArrayList<Row>();
    private static long generation;

    private static final class HeatCounts {
        long input, output, requests, failed, attempts, attemptFailed;
        void add(Row r) {
            if (r.isTerminal()) { requests++; if (r.failed()) failed++; }
            if (r.isAttempt()) { attempts++; if (r.failed()) attemptFailed++; }
            if (r.countTokens()) { input += r.countedInput(); output += r.countedOutput(); }
        }
        void add(HeatCounts c) { input += c.input; output += c.output; requests += c.requests; failed += c.failed; attempts += c.attempts; attemptFailed += c.attemptFailed; }
        void subtract(HeatCounts c) { input -= c.input; output -= c.output; requests -= c.requests; failed -= c.failed; attempts -= c.attempts; attemptFailed -= c.attemptFailed; }
        JSONObject json() { JSONObject o = new JSONObject(); o.put("tokens", input + output); o.put("input", input); o.put("output", output); o.put("requests", requests); o.put("failed", failed); o.put("attempts", attempts); o.put("attemptFailed", attemptFailed); return o; }
    }
    private static void addHeat(Map<String, HeatCounts> index, Row r, TimeZone zone) {
        String key = dayKey(r.t, zone);
        HeatCounts c = index.get(key);
        if (c == null) { c = new HeatCounts(); index.put(key, c); }
        c.add(r);
    }
    private static void addFuture(TreeMap<Long, HeatCounts> index, Row r) {
        HeatCounts c = index.get(r.t);
        if (c == null) { c = new HeatCounts(); index.put(r.t, c); }
        c.add(r);
    }


    /** 内存索引：按天分桶，天 -> 行列表 */
    private static final LinkedHashMap<String, List<Row>> DAYS = new LinkedHashMap<String, List<Row>>();

    private UsageLedger() {
    }

    // ---------------------------------------------------------------- 一行

    public static class Row {
        public long t;              // 毫秒时间戳
        public String prov = "";    // 供应商 id
        public String model = "";   // 实际应答的模型
        public String req = "";     // 客户端请求的模型（可能是 group/ 或别名）
        public int in, out, cacheRead, cacheWrite, reasoning;
        transient int compatBaseIn, compatBaseOut, compatBaseCacheRead, compatBaseCacheWrite, compatBaseReasoning;
        // A bit is set when the current physical send reported that dimension.
        transient int compatUsageSends, compatCurrentMask, compatCompleteMask = 31;
        transient boolean compatCacheUnknown, compatCacheEstimated, compatReasoningEstimated;
        void beginCompatUsageSend() {
            if (compatUsageSends > 0) compatCompleteMask &= compatCurrentMask;
            compatUsageSends++;
            compatCurrentMask = 0;
            compatBaseIn=in; compatBaseOut=out; compatBaseCacheRead=cacheRead;
            compatBaseCacheWrite=cacheWrite; compatBaseReasoning=reasoning;
            if (inputKnown || outputKnown) usageSource = "estimated".equals(usageSource) ? "estimated" : "partial";
            if (compatUsageSends > 1) {
                if (!cacheSource.isEmpty() && !"unknown".equals(cacheSource)) cacheSource = "partial";
                if (!reasoningSource.isEmpty() && !"unknown".equals(reasoningSource)) reasoningSource = "partial";
                cacheInputTokens = -1;
            }
        }
        boolean compatComplete(int mask) {
            return compatUsageSends == 0 || ((compatCompleteMask & compatCurrentMask & mask) == mask);
        }
        public boolean cacheUnknown;
        public long ttft = -1;      // 首字延迟 ms，-1 未知
        public long decMs = -1;     // 解码窗口 ms
        public int decOut;          // 窗口内产出 token
        public long ms;             // 整请求耗时
        public int status;
        public String err = "";
        public int tries = 1;
        public String ep = "";
        public String session = "";      // 会话标识（客户端 header 或 Anthropic metadata.user_id）
        // Empty requestId denotes an immutable legacy row. New attempts use ordinal 1+;
        // ordinal 0 is a terminal-only marker and never counts as an attempt.
        public String requestId = "", credentialId = "", accountId = "";
        public int attempt = 1;
        public boolean terminal = true;
        // New rows: known, estimated or unknown. Empty source is treated as unknown.
        public String usageSource = "", cacheSource = "", reasoningSource = "";
        public boolean inputKnown, outputKnown;
        // Protocol-normalized cache hit denominator; -1 means unavailable.
        public long cacheInputTokens = -1;
        // In-flight protocol state: Anthropic input excludes cache counters.
        public transient boolean cacheInputExcludesCache;
        public transient RecentUsageScheduler.Lease recentUsageLease;
        /** Bounded transport metadata, never prompt/header/credential material. */
        public long outboundAt, queuedMs;
        public String failureKind="", upstreamErrorCode="", upstreamRetryAfter="";
        public RequestDiagnostics.Snapshot diagnostics;
        transient RequestDiagnostics.Observation diagnosticObservation;
        public boolean isLegacy() { return requestId == null || requestId.isEmpty(); }
        public boolean isTerminal() { return isLegacy() || terminal; }
        public boolean isAttempt() { return isLegacy() || attempt > 0; }
        public String effectiveUsageSource() {
            if (isLegacy()) return "legacy";
            if ("known".equals(usageSource) || "estimated".equals(usageSource) || "partial".equals(usageSource)) return usageSource;
            return "unknown";
        }
        // A known source without flags is the pre-dimension compatibility form.
        public boolean effectiveInputKnown() {
            return isLegacy() || "known".equals(usageSource) ||
                    (("partial".equals(usageSource) || "estimated".equals(usageSource)) && inputKnown);
        }
        public boolean effectiveOutputKnown() {
            return isLegacy() || "known".equals(usageSource) ||
                    (("partial".equals(usageSource) || "estimated".equals(usageSource)) && outputKnown);
        }
        public boolean countTokens() { return isLegacy() ? !failed() : isAttempt() && (effectiveInputKnown() || effectiveOutputKnown()); }
        public long countedInput() { return countTokens() && effectiveInputKnown() ? in : 0; }
        public long countedOutput() { return countTokens() && effectiveOutputKnown() ? out : 0; }
        public long countedTokens() { return countedInput() + countedOutput(); }
        public boolean cacheKnown() { return isLegacy() ? !cacheUnknown : !cacheUnknown && "known".equals(cacheSource); }
        public int hour = -1;            // 本地小时 0-23，用于时段热力图

        public boolean failed() {
            return status >= 400 || (err != null && !err.isEmpty());
        }

        public long tokens() {
            return (long) in + out;
        }
    }

    // ---------------------------------------------------------------- 汇总

    public static class Totals {
        public long input, output, cacheRead, cacheWrite, reasoning;
        public long requests, failed, retried;
        public long timed;          // 有 TTFT 的成功调用数
        public long ttftSum;
        public long msSum;
        public long decodeMs;
        public long decodeOut;
        public long cacheUnknown, cacheKnownInput, cacheUnknownInput;
        public long attempts, attemptFailed, usageKnown, usageUnknown, usageEstimated, usagePartial, usageLegacy, inputKnownCount, outputKnownCount;
        public long cacheKnown, cacheEstimated, reasoningKnown, reasoningUnknown, reasoningEstimated, cacheKnownRead;

        void add(Row r) {
            if (r.isTerminal()) {
                requests++; msSum += r.ms;
                if (r.failed()) failed++;
                if (r.tries > 1) retried++;
                if (!r.failed() && r.ttft >= 0) { timed++; ttftSum += r.ttft; }
                if (!r.failed() && r.decMs >= 100) {
                    double sp = r.decOut / (r.decMs / 1000.0);
                    if (sp <= 10000) { decodeMs += r.decMs; decodeOut += r.decOut; }
                }
            }
            if (!r.isAttempt()) return;
            attempts++; if (r.failed()) attemptFailed++;
            String source = r.effectiveUsageSource();
            if ("legacy".equals(source)) usageLegacy++;
            else if ("unknown".equals(source)) usageUnknown++;
            else if ("estimated".equals(source)) usageEstimated++;
            else if ("partial".equals(source)) usagePartial++;
            else usageKnown++;
            if (!r.isLegacy() && r.effectiveInputKnown()) inputKnownCount++;
            if (!r.isLegacy() && r.effectiveOutputKnown()) outputKnownCount++;
            // A normalized denominator can be known independently of the input dimension.
            // An unknown usage source cannot establish a true denominator.
            boolean cacheDenominatorKnown = !"unknown".equals(source) &&
                    (r.isLegacy() ? !r.failed() : r.cacheInputTokens >= 0 || r.effectiveInputKnown());
            if (r.cacheKnown() && cacheDenominatorKnown) {
                cacheKnown++;
                cacheKnownInput += r.isLegacy() || r.cacheInputTokens < 0 ? r.countedInput() : r.cacheInputTokens;
                cacheKnownRead += r.cacheRead;
            } else {
                if ("estimated".equals(r.cacheSource)) cacheEstimated++; else cacheUnknown++;
                cacheUnknownInput += r.countedInput();
            }
            if (!r.countTokens()) { reasoningUnknown++; return; }
            input += r.countedInput(); output += r.countedOutput();
            cacheRead += r.cacheRead; cacheWrite += r.cacheWrite; reasoning += r.reasoning;
            if (r.isLegacy() || "known".equals(r.reasoningSource)) reasoningKnown++;
            else if ("estimated".equals(r.reasoningSource)) reasoningEstimated++;
            else reasoningUnknown++;
        }

        public long tokens() {
            return input + output;
        }

        public long meanTtft() {
            return timed == 0 ? 0 : ttftSum / timed;
        }

        public double speed() {
            return decodeMs <= 0 ? 0 : decodeOut / (decodeMs / 1000.0);
        }

        /** @return 命中率 0..1，和一个「是否可算」的标志 */
        public double[] hitRate() {
            long p = cacheKnownInput;
            if (p <= 0) return new double[]{0, 0};
            return new double[]{(double) cacheKnownRead / p, 1};
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("input", input);
                o.put("output", output);
                o.put("cacheRead", cacheRead);
                o.put("cacheWrite", cacheWrite);
                o.put("reasoning", reasoning);
                o.put("tokens", tokens());
                o.put("requests", requests);
                o.put("failed", failed);
                o.put("retried", retried);
                o.put("attempts", attempts); o.put("attemptFailed", attemptFailed);
                o.put("usageKnown", usageKnown); o.put("usageUnknown", usageUnknown); o.put("usageEstimated", usageEstimated); o.put("usagePartial", usagePartial); o.put("usageLegacy", usageLegacy);
                o.put("inputKnown", inputKnownCount); o.put("outputKnown", outputKnownCount);
                o.put("cacheKnown", cacheKnown); o.put("cacheUnknown", cacheUnknown); o.put("cacheEstimated", cacheEstimated);
                o.put("cacheKnownInputTokens", cacheKnownInput); o.put("cacheUnknownInputTokens", cacheUnknownInput);
                o.put("cacheCoverage", attempts == 0 ? JSONObject.NULL : Math.round(cacheKnown * 1000.0 / attempts) / 10.0);
                o.put("usageCoverage", attempts == 0 ? JSONObject.NULL : Math.round(usageKnown * 1000.0 / attempts) / 10.0);
                o.put("inputCoverage", attempts == 0 ? JSONObject.NULL : Math.round(inputKnownCount * 1000.0 / attempts) / 10.0);
                o.put("outputCoverage", attempts == 0 ? JSONObject.NULL : Math.round(outputKnownCount * 1000.0 / attempts) / 10.0);
                o.put("reasoningKnown", reasoningKnown); o.put("reasoningUnknown", reasoningUnknown); o.put("reasoningEstimated", reasoningEstimated);
                o.put("timed", timed);
                o.put("meanTtft", meanTtft());
                o.put("avgMs", requests == 0 ? 0 : msSum / requests);
                o.put("speed", Math.round(speed() * 10) / 10.0);
                double[] hr = hitRate();
                o.put("hitRate", hr[1] > 0 ? Math.round(hr[0] * 1000) / 10.0 : JSONObject.NULL);
            } catch (Exception ignored) {
            }
            return o;
        }
    }

    // ---------------------------------------------------------------- 生命周期

    public static void init(File filesDir) {
        synchronized (REBUILD_LOCK) {
            synchronized (LOCK) {
                closeWriter();
                baseDir = new File(filesDir, DIR);
                DAYS.clear(); heatDays.clear(); future.clear(); unpersisted.clear(); firstTimestamp = Long.MAX_VALUE;
                invalidLines = 0; readErrors = 0; persistenceErrors = 0; uncertainWrites = 0;
                initializing = true;
                if (!baseDir.exists() && !baseDir.mkdirs()) persistenceErrors++;
                heatZone = null; generation++;
            }
            rebuild(TimeZone.getDefault(), true, System.currentTimeMillis());
            synchronized (LOCK) { openWriter(); }
        }
    }

    private static String dayKey(long t) { return dayKey(t, TimeZone.getDefault()); }
    private static String dayKey(long t, TimeZone zone) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(zone);
        return f.format(new Date(t));
    }

    private static void closeWriter() {
        if (writer != null) {
            try { writer.close(); } catch (Exception e) { persistenceErrors++; }
            writer = null;
        }
    }

    private static long midnight(long now, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone, Locale.US); c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static String monthKey(long t) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMM", Locale.US);
        return f.format(new Date(t));
    }

    private static void openWriter() {
        try {
            closeWriter();
            String m = monthKey(System.currentTimeMillis());
            currentMonth = m;
            currentFile = new File(baseDir, "ledger-" + m + ".jsonl");
            // A previous failed append may have left a partial JSON line. Preserve
            // it for the scanner to report, but never join a new row to it.
            if (currentFile.exists() && currentFile.length() > 0) {
                try (RandomAccessFile file = new RandomAccessFile(currentFile, "rw")) {
                    file.seek(file.length() - 1);
                    if (file.read() != '\n') { file.seek(file.length()); file.write('\n'); }
                }
            }
            writer = new OutputStreamWriter(new FileOutputStream(currentFile, true), "UTF-8");
        } catch (Throwable e) {
            writer = null;
            persistenceErrors++;
        }
    }

    // Snapshot file lengths under LOCK, then read only that prefix without holding LOCK.
    // Records written concurrently are replayed exactly once from rebuildDelta.
    private static void rebuild(TimeZone zone, boolean loadRows, long now) {
        Map<String, HeatCounts> next = new LinkedHashMap<String, HeatCounts>();
        TreeMap<Long, HeatCounts> nextFuture = new TreeMap<Long, HeatCounts>();
        List<Row> loaded = new ArrayList<Row>();
        File[] files; long[] lengths; List<Row> pending; long gen; long bad = 0, errors = 0, first = Long.MAX_VALUE;
        long cutoffPrecision = midnight(now, zone);
        synchronized (LOCK) {
            gen = generation;
            files = baseDir == null ? null : baseDir.listFiles();
            if (files == null) { files = new File[0]; errors++; }
            lengths = new long[files.length];
            for (int i = 0; i < files.length; i++) lengths[i] = files[i].length();
            pending = new ArrayList<Row>(unpersisted);
            rebuildDelta = new ArrayList<Row>();
            if (loadRows) { initializing = false; LOCK.notifyAll(); }
        }
        Runnable snapshotHook = rebuildSnapshotHook;
        if (snapshotHook != null) snapshotHook.run();
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (!f.getName().startsWith("ledger-") || !f.getName().endsWith(".jsonl")) continue;
            final long limit = lengths[i];
            try (InputStream raw = new FileInputStream(f);
                 InputStream bounded = new java.io.FilterInputStream(raw) {
                     long left = limit;
                     public int read() throws java.io.IOException { if (left <= 0) return -1; int x = super.read(); if (x >= 0) left--; return x; }
                     public int read(byte[] b, int off, int len) throws java.io.IOException { if (left <= 0) return -1; int n = super.read(b, off, (int)Math.min(left, len)); if (n > 0) left -= n; return n; }
                 };
                 BufferedReader br = new BufferedReader(new InputStreamReader(bounded, "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    Row r = parse(line.trim());
                    if (r == null || r.t <= 0) { bad++; continue; }
                    first = Math.min(first, r.t); addHeat(next, r, zone);
                    if (r.t >= cutoffPrecision) addFuture(nextFuture, r);
                    if (loadRows && r.t >= now - MEMORY_DAYS * 86400000L) loaded.add(r);
                }
            } catch (Exception e) { errors++; }
        }
        synchronized (LOCK) {
            if (gen != generation) return;
            for (Row r : pending) { addHeat(next, r, zone); first = Math.min(first, r.t); if (r.t >= cutoffPrecision) addFuture(nextFuture, r); }
            for (Row r : rebuildDelta) {
                addHeat(next, r, zone); first = Math.min(first, r.t);
                if (r.t >= cutoffPrecision) addFuture(nextFuture, r);
            }
            heatDays = next; future.clear(); future.putAll(nextFuture); heatZone = (TimeZone)zone.clone(); precisionStart = cutoffPrecision;
            firstTimestamp = first; invalidLines = bad; readErrors = errors; rebuildDelta = null;
            if (loadRows) for (Row r : loaded) bucket(r.t).add(r);
        }
    }

    private static void ensureZone(TimeZone zone, long now) {
        synchronized (LOCK) { if (heatZone != null && heatZone.equals(zone) && now >= precisionStart) return; }
        rebuild(zone, false, now);
    }

    private static List<Row> bucket(long t) {
        String k = dayKey(t);
        List<Row> l = DAYS.get(k);
        if (l == null) {
            l = new ArrayList<Row>();
            DAYS.put(k, l);
        }
        return l;
    }

    // ---------------------------------------------------------------- 写入

    public static void record(Row r) {
        if (r == null) return;
        RequestDiagnostics.freeze(r);
        if (r.recentUsageLease != null) r.recentUsageLease.complete(r);
        if (r.t <= 0) r.t = System.currentTimeMillis();
        if (r.hour < 0) {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTimeInMillis(r.t);
            r.hour = c.get(java.util.Calendar.HOUR_OF_DAY);
        }
        synchronized (LOCK) {
            while (initializing) {
                try { LOCK.wait(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
            boolean known = true;
            long before = -1;
            try {
                String m = monthKey(r.t);
                if (writer == null || !m.equals(currentMonth)
                        || (currentFile != null && currentFile.length() > MAX_FILE_BYTES)) openWriter();
                if (writer == null) { persistenceErrors++; unpersisted.add(r); }
                else {
                    before = currentFile.length();
                    writer.write(toJsonLine(r)); writer.write("\n"); writer.flush();
                }
            } catch (Throwable e) {
                persistenceErrors++;
                closeWriter();
                boolean rolledBack = false;
                if (before >= 0 && currentFile != null) {
                    try {
                        if (rollbackHook != null) rolledBack = rollbackHook.rollback(currentFile, before);
                        else try (RandomAccessFile file = new RandomAccessFile(currentFile, "rw")) {
                            file.setLength(before); rolledBack = file.length() == before;
                        }
                    } catch (Exception ignored) { }
                }
                if (rolledBack || before < 0) unpersisted.add(r);
                else { uncertainWrites++; known = false; }
            }
            if (known) {
                bucket(r.t).add(r);
                if (heatZone != null) addHeat(heatDays, r, heatZone);
                firstTimestamp = Math.min(firstTimestamp, r.t);
                if (r.t >= precisionStart) addFuture(future, r);
                if (rebuildDelta != null) rebuildDelta.add(r);
            }
        }
        // 顺带把老的实时计数器续上，老的 /api/quota 不至于变空
        try {
            StatsHooks.onRow(r);
        } catch (Throwable ignored) {
        }
    }

    /** 让 GatewayServer 把老的 totalRequests/totalTokens 计数器同步上（可选） */
    public interface Hooks {
        void onRow(Row r);
    }

    private static volatile Hooks hooks;

    public static void setHooks(Hooks h) {
        hooks = h;
    }

    private static final class StatsHooks {
        static void onRow(Row r) {
            Hooks h = hooks;
            if (h != null) h.onRow(r);
        }
    }

    private static String toJsonLine(Row r) {
        JSONObject o = new JSONObject();
        try {
            if(r.diagnostics!=null) o.put("diagnostics",r.diagnostics.json());
            if (!r.isLegacy()) {
                o.put("requestId", r.requestId); o.put("attempt", r.attempt); o.put("terminal", r.terminal);
                if (r.credentialId != null && !r.credentialId.isEmpty()) o.put("credentialId", r.credentialId);
                if (r.accountId != null && !r.accountId.isEmpty()) o.put("accountId", r.accountId);
                o.put("usageSource", r.effectiveUsageSource());
                o.put("outboundAt",r.outboundAt); o.put("queuedMs",r.queuedMs);
                o.put("failureKind",r.failureKind); o.put("upstreamErrorCode",r.upstreamErrorCode); o.put("upstreamRetryAfter",r.upstreamRetryAfter);
                o.put("inputKnown", r.inputKnown); o.put("outputKnown", r.outputKnown);
                if (r.cacheInputTokens >= 0 && !"partial".equals(r.cacheSource)) o.put("cacheInputTokens", r.cacheInputTokens);
                o.put("cacheSource", r.cacheSource == null || r.cacheSource.isEmpty() ? "unknown" : r.cacheSource);
                o.put("reasoningSource", r.reasoningSource == null || r.reasoningSource.isEmpty() ? "unknown" : r.reasoningSource);
            }
            o.put("t", r.t);
            o.put("prov", r.prov == null ? "" : r.prov);
            o.put("model", r.model == null ? "" : r.model);
            if (r.req != null && !r.req.isEmpty()) o.put("req", r.req);
            if (r.effectiveInputKnown()) o.put("in", r.in);
            if (r.effectiveOutputKnown()) o.put("out", r.out);
            if (r.isLegacy() || "known".equals(r.cacheSource) || "estimated".equals(r.cacheSource) || "partial".equals(r.cacheSource)) {
                o.put("cr", r.cacheRead); o.put("cw", r.cacheWrite);
            }
            if ((r.isLegacy() && r.reasoning > 0) || (!r.isLegacy() && ("known".equals(r.reasoningSource) || "estimated".equals(r.reasoningSource) || "partial".equals(r.reasoningSource)))) o.put("reas", r.reasoning);
            if (r.cacheUnknown) o.put("cu", true);
            if (r.ttft >= 0) o.put("ttft", r.ttft);
            if (r.decMs >= 0) {
                o.put("decMs", r.decMs);
                o.put("decOut", r.decOut);
            }
            o.put("ms", r.ms);
            o.put("st", r.status);
            if (r.err != null && !r.err.isEmpty()) o.put("err", shorten(r.err, 200));
            if (r.tries > 1) o.put("tries", r.tries);
            if (r.ep != null && !r.ep.isEmpty()) o.put("ep", r.ep);
            if (r.session != null && !r.session.isEmpty()) o.put("sess", shorten(r.session, 80));
            if (r.hour >= 0) o.put("h", r.hour);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    private static Row parse(String line) {
        try {
            JSONObject o = new JSONObject(line);
            Row r = new Row();
            r.diagnostics=RequestDiagnostics.Snapshot.parse(o.optJSONObject("diagnostics"));
            r.requestId = o.optString("requestId", "");
            r.credentialId = o.optString("credentialId", "");
            r.accountId = o.optString("accountId", "");
            r.outboundAt=o.optLong("outboundAt",0); r.queuedMs=o.optLong("queuedMs",0);
            r.failureKind=o.optString("failureKind",""); r.upstreamErrorCode=o.optString("upstreamErrorCode",""); r.upstreamRetryAfter=o.optString("upstreamRetryAfter","");
            r.attempt = o.optInt("attempt", 1); r.terminal = o.optBoolean("terminal", true);
            r.inputKnown = o.optBoolean("inputKnown", false); r.outputKnown = o.optBoolean("outputKnown", false);
            r.cacheInputTokens = o.optLong("cacheInputTokens", -1);
            r.usageSource = o.optString("usageSource", ""); r.cacheSource = o.optString("cacheSource", ""); r.reasoningSource = o.optString("reasoningSource", "");
            if ("partial".equals(r.cacheSource)) r.cacheInputTokens = -1;
            r.t = o.optLong("t", 0);
            r.prov = o.optString("prov", "");
            r.model = o.optString("model", "");
            r.req = o.optString("req", "");
            r.in = o.optInt("in", 0);
            r.out = o.optInt("out", 0);
            r.cacheRead = o.optInt("cr", 0);
            r.cacheWrite = o.optInt("cw", 0);
            r.reasoning = o.optInt("reas", 0);
            r.cacheUnknown = o.optBoolean("cu", false);
            r.ttft = o.has("ttft") ? o.optLong("ttft", -1) : -1;
            r.decMs = o.has("decMs") ? o.optLong("decMs", -1) : -1;
            r.decOut = o.optInt("decOut", 0);
            r.ms = o.optLong("ms", 0);
            r.status = o.optInt("st", 0);
            r.err = o.optString("err", "");
            r.tries = o.optInt("tries", 1);
            r.ep = o.optString("ep", "");
            r.session = o.optString("sess", "");
            r.hour = o.has("h") ? o.optInt("h", -1) : -1;
            return r;
        } catch (Throwable e) {
            return null;
        }
    }

    private static String shorten(String s, int n) {
        String t = s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > n ? t.substring(0, n) + "…" : t;
    }

    // ---------------------------------------------------------------- 查询

    /**
     * @param days  统计最近多少天（含今天）
     * @param group 按什么维度拆分：model / provider
     */
    public static JSONObject summary(int days, String group) {
        return summary(days, group, System.currentTimeMillis(), TimeZone.getDefault());
    }

    static JSONObject summary(int days, String group, long now, TimeZone zone) {
        Calendar start = Calendar.getInstance(zone, Locale.US);
        start.setTimeInMillis(now);
        start.set(Calendar.HOUR_OF_DAY, 0); start.set(Calendar.MINUTE, 0); start.set(Calendar.SECOND, 0); start.set(Calendar.MILLISECOND, 0);
        start.add(Calendar.DAY_OF_MONTH, 1 - days);
        long from = start.getTimeInMillis();
        Totals total = new Totals();
        Map<String, Totals> by = new LinkedHashMap<String, Totals>();
        Map<String, Totals> byDay = new LinkedHashMap<String, Totals>();
        Map<String, Totals> byProv = new LinkedHashMap<String, Totals>();
        Map<String, Totals> bySession = new LinkedHashMap<String, Totals>();
        final long[] hourTokens = new long[24];
        final int[] hourReq = new int[24];
        Map<String, Integer> errCount = new LinkedHashMap<String, Integer>();
        int rows = 0;

        synchronized (LOCK) {
            for (Map.Entry<String, List<Row>> e : DAYS.entrySet()) {
                for (Row r : e.getValue()) {
                    if (r.t < from || r.t > now) continue;
                    rows++;
                    total.add(r);
                    String key = "model".equals(group)
                            ? (r.model == null || r.model.isEmpty() ? "未知" : r.model)
                            : (r.prov == null || r.prov.isEmpty() ? "未知" : r.prov);
                    Totals t = by.get(key);
                    if (t == null) {
                        t = new Totals();
                        by.put(key, t);
                    }
                    t.add(r);
                    String day = dayKey(r.t, zone);
                    Totals d = byDay.get(day);
                    if (d == null) {
                        d = new Totals();
                        byDay.put(day, d);
                    }
                    d.add(r);
                    String pk = r.prov == null || r.prov.isEmpty() ? "未知" : r.prov;
                    Totals pt = byProv.get(pk);
                    if (pt == null) {
                        pt = new Totals();
                        byProv.put(pk, pt);
                    }
                    pt.add(r);

                    String sk = r.session == null || r.session.isEmpty() ? "（未标记会话）" : r.session;
                    Totals st = bySession.get(sk);
                    if (st == null) {
                        st = new Totals();
                        bySession.put(sk, st);
                    }
                    st.add(r);

                    if (r.hour >= 0 && r.hour < 24) {
                        if (r.countTokens()) hourTokens[r.hour] += r.countedTokens();
                        if (r.isTerminal()) hourReq[r.hour]++;
                    }
                    if (r.isAttempt() && r.failed()) {
                        String why = r.status >= 400 ? ("HTTP " + r.status) : "连接失败";
                        if (r.err != null && !r.err.isEmpty()) {
                            String et = r.err;
                            if (et.length() > 60) et = et.substring(0, 60) + "…";
                            why = why + " · " + et;
                        }
                        Integer c = errCount.get(why);
                        errCount.put(why, c == null ? 1 : c + 1);
                    }
                }
            }
        }

        JSONObject out = new JSONObject();
        try {
            out.put("ok", true);
            out.put("days", days);
            out.put("group", group);
            out.put("rows", rows);
            out.put("totals", total.toJson());

            JSONArray groups = new JSONArray();
            List<Map.Entry<String, Totals>> list = new ArrayList<Map.Entry<String, Totals>>(by.entrySet());
            Collections.sort(list, new Comparator<Map.Entry<String, Totals>>() {
                @Override
                public int compare(Map.Entry<String, Totals> a, Map.Entry<String, Totals> b) {
                    return Long.compare(b.getValue().tokens(), a.getValue().tokens());
                }
            });
            for (Map.Entry<String, Totals> e : list) {
                JSONObject g = e.getValue().toJson();
                g.put("name", e.getKey());
                groups.put(g);
            }
            out.put("groups", groups);

            JSONArray providers = new JSONArray();
            for (Map.Entry<String, Totals> e : byProv.entrySet()) {
                JSONObject g = e.getValue().toJson();
                g.put("name", e.getKey());
                providers.put(g);
            }
            out.put("providers", providers);

            // 补齐区间内的每一天，柱状图不会因为某天没请求而错位
            JSONArray daily = new JSONArray();
            Calendar dayCursor = (Calendar)start.clone();
            for (int i = 0; i < days; i++, dayCursor.add(Calendar.DAY_OF_MONTH, 1)) {
                String k = dayKey(dayCursor.getTimeInMillis(), zone);
                Totals d = byDay.get(k);
                JSONObject o = new JSONObject();
                o.put("day", k);
                if (d == null) {
                    o.put("tokens", 0);
                    o.put("input", 0);
                    o.put("output", 0);
                    o.put("requests", 0);
                    o.put("failed", 0); o.put("attempts", 0); o.put("attemptFailed", 0);
                } else {
                    JSONObject dj = d.toJson();
                    o.put("tokens", dj.optLong("tokens", 0));
                    o.put("input", dj.optLong("input", 0));
                    o.put("output", dj.optLong("output", 0));
                    o.put("requests", dj.optLong("requests", 0));
                    o.put("failed", dj.optLong("failed", 0)); o.put("attempts", dj.optLong("attempts", 0)); o.put("attemptFailed", dj.optLong("attemptFailed", 0));
                }
                daily.put(o);
            }
            out.put("daily", daily);

            JSONArray recent = new JSONArray();
            List<Row> all = new ArrayList<Row>();
            synchronized (LOCK) {
                for (List<Row> l : DAYS.values()) {
                    for (Row r : l) if (r.t >= from && r.t <= now) all.add(r);
                }
            }
            Collections.sort(all, new Comparator<Row>() {
                @Override
                public int compare(Row a, Row b) {
                    return Long.compare(b.t, a.t);
                }
            });
            for (int i = 0; i < all.size() && i < 50; i++) {
                Row r = all.get(i);
                JSONObject o = new JSONObject();
                o.put("t", r.t);
                o.put("diagnostics",RequestDiagnostics.json(r));
                o.put("prov", r.prov);
                o.put("requestId", r.requestId); o.put("attempt", r.attempt); o.put("terminal", r.isTerminal());
                o.put("credentialId", r.credentialId); o.put("usageSource", r.effectiveUsageSource());
                o.put("accountId", r.accountId);
                o.put("outboundAt",r.outboundAt); o.put("queuedMs",r.queuedMs); o.put("failureKind",r.failureKind); o.put("upstreamErrorCode",r.upstreamErrorCode); o.put("upstreamRetryAfter",r.upstreamRetryAfter);
                o.put("cacheSource", r.cacheSource); o.put("reasoningSource", r.reasoningSource);
                o.put("cacheInputTokens", r.cacheInputTokens >= 0 && !"partial".equals(r.cacheSource) ? r.cacheInputTokens : JSONObject.NULL);
                o.put("model", r.model);
                o.put("in", r.effectiveInputKnown() ? r.in : JSONObject.NULL);
                o.put("out", r.effectiveOutputKnown() ? r.out : JSONObject.NULL);
                o.put("inputKnown", r.effectiveInputKnown()); o.put("outputKnown", r.effectiveOutputKnown());
                o.put("cacheRead", !r.isLegacy() && !"known".equals(r.cacheSource) && !"estimated".equals(r.cacheSource) && !"partial".equals(r.cacheSource) ? JSONObject.NULL : r.cacheRead);
                o.put("ms", r.ms);
                o.put("status", r.status);
                if (!r.err.isEmpty()) o.put("err", r.err);
                if (r.ttft >= 0) o.put("ttft", r.ttft);
                if (r.tries > 1) o.put("tries", r.tries);
                recent.put(o);
            }
            out.put("recent", recent);

            // 会话维度（按 token 排序取前 12）
            JSONArray sessArr = new JSONArray();
            List<Map.Entry<String, Totals>> sl = new ArrayList<Map.Entry<String, Totals>>(bySession.entrySet());
            Collections.sort(sl, new Comparator<Map.Entry<String, Totals>>() {
                @Override
                public int compare(Map.Entry<String, Totals> a, Map.Entry<String, Totals> b) {
                    return Long.compare(b.getValue().tokens(), a.getValue().tokens());
                }
            });
            for (int i = 0; i < sl.size() && i < 12; i++) {
                JSONObject o = sl.get(i).getValue().toJson();
                o.put("name", sl.get(i).getKey());
                sessArr.put(o);
            }
            out.put("sessions", sessArr);
            out.put("sessionCount", sl.size());

            // 时段分布：0-23 点各多少 token / 请求
            JSONArray hourly = new JSONArray();
            for (int h = 0; h < 24; h++) {
                JSONObject o = new JSONObject();
                o.put("hour", h);
                o.put("tokens", hourTokens[h]);
                o.put("requests", hourReq[h]);
                hourly.put(o);
            }
            out.put("hourly", hourly);

            // 失败原因 Top
            JSONArray errs = new JSONArray();
            List<Map.Entry<String, Integer>> el = new ArrayList<Map.Entry<String, Integer>>(errCount.entrySet());
            Collections.sort(el, new Comparator<Map.Entry<String, Integer>>() {
                @Override
                public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                    return b.getValue() - a.getValue();
                }
            });
            for (int i = 0; i < el.size() && i < 8; i++) {
                JSONObject o = new JSONObject();
                o.put("reason", el.get(i).getKey());
                o.put("count", el.get(i).getValue());
                errs.put(o);
            }
            out.put("errors", errs);
        } catch (Exception ignored) {
        }
        return out;
    }

    public static JSONObject heatmap() { return heatmapInternal(null, TimeZone.getDefault()); }

    static JSONObject heatmap(long now, TimeZone zone) { return heatmapInternal(now, zone); }

    private static JSONObject heatmapInternal(Long fixedNow, TimeZone zone) {
        synchronized (REBUILD_LOCK) {
            for (;;) {
                long observed;
                synchronized (LOCK) { observed = fixedNow == null ? System.currentTimeMillis() : fixedNow; }
                ensureZone(zone, observed);
                synchronized (LOCK) {
                    long now = fixedNow == null ? System.currentTimeMillis() : fixedNow;
                    if (now < precisionStart) continue;
                    long today = midnight(now, zone);
                    if (today > precisionStart) { future.headMap(today, false).clear(); precisionStart = today; }
                    return heatmapLocked(now, zone);
                }
            }
        }
    }

    // Caller holds LOCK, so the clock snapshot and included records are consistent.
    private static JSONObject heatmapLocked(long now, TimeZone zone) {
        Calendar start = Calendar.getInstance(zone, Locale.US);
        start.setTimeInMillis(now);
        start.set(Calendar.HOUR_OF_DAY, 0); start.set(Calendar.MINUTE, 0); start.set(Calendar.SECOND, 0); start.set(Calendar.MILLISECOND, 0);
        start.add(Calendar.DAY_OF_MONTH, -((start.get(Calendar.DAY_OF_WEEK) + 5) % 7) - 52 * 7);
        String end = dayKey(now, zone);
        JSONArray days = new JSONArray(); HeatCounts totals = new HeatCounts();
        Map<String, HeatCounts> exclusions = new LinkedHashMap<String, HeatCounts>();
        for (Map.Entry<Long, HeatCounts> e : future.tailMap(now, false).entrySet()) {
            String date = dayKey(e.getKey(), zone); HeatCounts count = exclusions.get(date);
            if (count == null) { count = new HeatCounts(); exclusions.put(date, count); }
            count.add(e.getValue());
        }
        for (Calendar c = (Calendar)start.clone(); ; c.add(Calendar.DAY_OF_MONTH, 1)) {
            String date = dayKey(c.getTimeInMillis(), zone);
            if (date.compareTo(end) > 0) break;
            HeatCounts count = new HeatCounts(); HeatCounts original = heatDays.get(date);
            if (original != null) count.add(original);
            HeatCounts excluded = exclusions.get(date); if (excluded != null) count.subtract(excluded);
            totals.add(count); JSONObject day = count.json(); day.put("date", date); days.put(day);
        }
        JSONObject out = new JSONObject(); out.put("ok", true); out.put("timezone", zone.getID());
        out.put("start", dayKey(start.getTimeInMillis(), zone)); out.put("end", end); out.put("weeks", 53);
        out.put("days", days); out.put("totals", totals.json());
        out.put("firstRecordedDate", firstTimestamp > now ? JSONObject.NULL : dayKey(firstTimestamp, zone));
        out.put("invalidLines", invalidLines); out.put("readErrors", readErrors); out.put("persistenceErrors", persistenceErrors);
        out.put("uncertainWrites", uncertainWrites); out.put("generatedAt", now); return out;
    }

    /** 导出 CSV（给「导出 CSV」用） */
    /** 窗口内总量：[请求数, token, 失败数]；给 /api/quota 的 totals 用（计数器重启会归零） */
    public static long[] windowTotals(int days) {
        long from = System.currentTimeMillis() - (long) days * 86400000L;
        long req = 0, tok = 0, fail = 0;
        synchronized (LOCK) {
            for (List<Row> l : DAYS.values()) {
                for (Row r : l) {
                    if (r.t < from) continue;
                    if (r.isTerminal()) { req++; if (r.failed()) fail++; }
                    if (r.countTokens()) tok += r.countedTokens();
                }
            }
        }
        return new long[]{req, tok, fail};
    }

    /** /api/quota 的供应商维度：请求数、token、成功率、平均延迟（按天窗口聚合） */
    public static JSONArray providerStats(int days) {
        long from = System.currentTimeMillis() - (long) days * 86400000L;
        Map<String, long[]> agg = new LinkedHashMap<String, long[]>();
        synchronized (LOCK) {
            for (List<Row> l : DAYS.values()) {
                for (Row r : l) {
                    if (r.t < from) continue;
                    String k = r.prov == null || r.prov.isEmpty() ? "未知" : r.prov;
                    long[] a = agg.get(k);
                    if (a == null) {
                        a = new long[]{0L, 0L, 0L, 0L};   // 请求 / token / 成功 / 累计耗时
                        agg.put(k, a);
                    }
                    if (r.isTerminal()) {
                        a[0]++; if (r.failed()) a[2]++;
                        if (r.ms > 0) a[3] += r.ms;
                    }
                    if (r.countTokens()) a[1] += r.countedTokens();
                }
            }
        }
        JSONArray out = new JSONArray();
        for (Map.Entry<String, long[]> e : agg.entrySet()) {
            long req = e.getValue()[0];
            long fail = e.getValue()[2];
            long ok = req - fail;
            JSONObject o = new JSONObject();
            o.put("providerId", e.getKey());
            o.put("requests", req);
            o.put("tokens", e.getValue()[1]);
            o.put("failures", fail);
            o.put("successRate", req == 0 ? 100 : Math.round(ok * 1000.0 / req) / 10.0);
            o.put("avgLatencyMs", req == 0 ? 0 : Math.round((double) e.getValue()[3] / req));
            out.put(o);
        }
        return out;
    }

    /** /api/quota 的时段分布：0-23 点各自的 token 与请求数 */
    public static JSONArray hourlyStats(int days) {
        long from = System.currentTimeMillis() - (long) days * 86400000L;
        final long[] tok = new long[24];
        final long[] req = new long[24];
        synchronized (LOCK) {
            for (List<Row> l : DAYS.values()) {
                for (Row r : l) {
                    if (r.t < from) continue;
                    if (r.hour < 0 || r.hour > 23) continue;
                    if (r.isTerminal()) req[r.hour]++;
                    if (r.countTokens()) tok[r.hour] += r.countedTokens();
                }
            }
        }
        JSONArray out = new JSONArray();
        for (int h = 0; h < 24; h++) {
            JSONObject o = new JSONObject();
            o.put("hour", h);
            o.put("tokens", tok[h]);
            o.put("requests", req[h]);
            out.put(o);
        }
        return out;
    }

    /** /api/sessions：最近 N 条请求（面板「最近请求」用） */
    public static JSONArray recentRequests(int limit) {
        List<Row> all = new ArrayList<Row>();
        synchronized (LOCK) {
            for (List<Row> l : DAYS.values()) {
                all.addAll(l);
            }
        }
        Collections.sort(all, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                return Long.compare(b.t, a.t);
            }
        });
        JSONArray out = new JSONArray();
        for (int i = 0; i < all.size() && i < limit; i++) {
            Row r = all.get(i);
            JSONObject o = new JSONObject();
            o.put("timestamp", r.t);
            o.put("diagnostics",RequestDiagnostics.json(r));
            o.put("statusCode", r.status);
            o.put("model", r.model == null || r.model.isEmpty() ? r.req : r.model);
            o.put("provider", r.prov);
            o.put("accountId", r.accountId);
            o.put("totalTokens", r.countedTokens());
            o.put("requestId", r.requestId); o.put("attempt", r.attempt); o.put("terminal", r.isTerminal());
            o.put("usageSource", r.effectiveUsageSource());
            o.put("durationMs", r.ms);
            if (r.err != null && !r.err.isEmpty()) o.put("error", r.err);
            out.put(o);
        }
        return out;
    }

    public static String csv(int days) {
        long from = System.currentTimeMillis() - (long) days * 86400000L;
        StringBuilder sb = new StringBuilder();
        sb.append("time,provider,model,requested,input,output,cache_read,cache_write,reasoning,ttft_ms,decode_ms,ms,status,tries,error,request_id,credential_id,attempt,terminal,usage_source,cache_source,reasoning_source,counted_input,counted_output,request_count,attempt_count,attempt_failed\n");
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        List<Row> all = new ArrayList<Row>();
        synchronized (LOCK) {
            for (List<Row> l : DAYS.values()) {
                for (Row r : l) if (r.t >= from) all.add(r);
            }
        }
        Collections.sort(all, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                return Long.compare(a.t, b.t);
            }
        });
        for (Row r : all) {
            sb.append(f.format(new Date(r.t))).append(',')
                    .append(csvEsc(r.prov)).append(',')
                    .append(csvEsc(r.model)).append(',')
                    .append(csvEsc(r.req)).append(',')
                    .append(!r.effectiveInputKnown() ? "" : String.valueOf(r.in)).append(',')
                    .append(!r.effectiveOutputKnown() ? "" : String.valueOf(r.out)).append(',')
                    .append(!r.isLegacy() && !"known".equals(r.cacheSource) && !"estimated".equals(r.cacheSource) && !"partial".equals(r.cacheSource) ? "" : String.valueOf(r.cacheRead)).append(',')
                    .append(!r.isLegacy() && !"known".equals(r.cacheSource) && !"estimated".equals(r.cacheSource) && !"partial".equals(r.cacheSource) ? "" : String.valueOf(r.cacheWrite)).append(',')
                    .append(!r.isLegacy() && !"known".equals(r.reasoningSource) && !"estimated".equals(r.reasoningSource) && !"partial".equals(r.reasoningSource) ? "" : String.valueOf(r.reasoning)).append(',')
                    .append(r.ttft < 0 ? "" : r.ttft).append(',')
                    .append(r.decMs < 0 ? "" : r.decMs).append(',')
                    .append(r.ms).append(',')
                    .append(r.status).append(',')
                    .append(r.tries).append(',')
                    .append(csvEsc(r.err)).append(',')
                    .append(csvEsc(r.requestId)).append(',').append(csvEsc(r.credentialId)).append(',')
                    .append(r.attempt).append(',').append(r.isTerminal()).append(',')
                    .append(csvEsc(r.effectiveUsageSource())).append(',').append(csvEsc(r.cacheSource)).append(',').append(csvEsc(r.reasoningSource)).append(',')
                    .append(!r.effectiveInputKnown() ? "" : String.valueOf(r.countedInput())).append(',')
                    .append(!r.effectiveOutputKnown() ? "" : String.valueOf(r.countedOutput())).append(',')
                    .append(r.isTerminal() ? 1 : 0).append(',').append(r.isAttempt() ? 1 : 0).append(',').append(r.isAttempt() && r.failed() ? 1 : 0).append('\n');
        }
        return sb.toString();
    }

    private static String csvEsc(String s) {
        if (s == null) return "";
        String t = s.replace("\"", "\"\"");
        if (t.indexOf(',') >= 0 || t.indexOf('"') >= 0 || t.indexOf('\n') >= 0) {
            return "\"" + t + "\"";
        }
        return t;
    }
}
