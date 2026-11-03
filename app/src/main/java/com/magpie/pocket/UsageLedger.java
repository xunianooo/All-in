package com.magpie.pocket;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
 *   - 缓存命中率   = CacheRead / (Input + CacheRead + CacheWrite − CacheUnknown)
 *                   「只对上游真的报了缓存 token 的调用计算」，一个都没报就没有命中率
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
        public int hour = -1;            // 本地小时 0-23，用于时段热力图

        public boolean failed() {
            return status >= 400 || (err != null && !err.isEmpty());
        }

        public int tokens() {
            return in + out;
        }
    }

    // ---------------------------------------------------------------- 汇总

    public static class Totals {
        public int input, output, cacheRead, cacheWrite, reasoning;
        public int requests, failed, retried;
        public int timed;          // 有 TTFT 的成功调用数
        public long ttftSum;
        public long msSum;
        public long decodeMs;
        public int decodeOut;
        public int cacheUnknown;

        void add(Row r) {
            requests++;
            msSum += r.ms;
            if (r.failed()) failed++;
            if (r.tries > 1) retried++;
            if (r.failed()) return;          // 失败的调用不计 token（上游没给）
            input += r.in;
            output += r.out;
            cacheRead += r.cacheRead;
            cacheWrite += r.cacheWrite;
            reasoning += r.reasoning;
            if (r.cacheUnknown) cacheUnknown++;
            if (r.ttft >= 0) {
                timed++;
                ttftSum += r.ttft;
            }
            if (r.decMs >= 100) {
                double sp = r.decOut / (r.decMs / 1000.0);
                if (sp <= 10000) {
                    decodeMs += r.decMs;
                    decodeOut += r.decOut;
                }
            }
        }

        public int tokens() {
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
            int p = input + cacheRead + cacheWrite - cacheUnknown;
            if (p <= 0) return new double[]{0, 0};
            return new double[]{(double) cacheRead / p, 1};
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
        synchronized (LOCK) {
            baseDir = new File(filesDir, DIR);
            if (!baseDir.exists()) baseDir.mkdirs();
            load();
            openWriter();
        }
    }

    private static String dayKey(long t) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        return f.format(new Date(t));
    }

    private static String monthKey(long t) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMM", Locale.US);
        return f.format(new Date(t));
    }

    private static void openWriter() {
        try {
            if (writer != null) {
                try {
                    writer.flush();
                    writer.close();
                } catch (Throwable ignored) {
                }
            }
            String m = monthKey(System.currentTimeMillis());
            currentMonth = m;
            currentFile = new File(baseDir, "ledger-" + m + ".jsonl");
            writer = new OutputStreamWriter(new FileOutputStream(currentFile, true), "UTF-8");
        } catch (Throwable e) {
            writer = null;
        }
    }

    /** 启动时把最近 MEMORY_DAYS 天的账本读进内存 */
    private static void load() {
        DAYS.clear();
        long cutoff = System.currentTimeMillis() - MEMORY_DAYS * 86400000L;
        File[] files = baseDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.getName().startsWith("ledger-") || !f.getName().endsWith(".jsonl")) continue;
            BufferedReader br = null;
            try {
                br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    Row r = parse(line);
                    if (r == null || r.t < cutoff) continue;
                    bucket(r.t).add(r);
                }
            } catch (Throwable ignored) {
            } finally {
                try {
                    if (br != null) br.close();
                } catch (Throwable ignored) {
                }
            }
        }
        // 按天排序，方便出图
        List<String> keys = new ArrayList<String>(DAYS.keySet());
        Collections.sort(keys);
        LinkedHashMap<String, List<Row>> sorted = new LinkedHashMap<String, List<Row>>();
        for (String k : keys) sorted.put(k, DAYS.get(k));
        DAYS.clear();
        DAYS.putAll(sorted);
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
        if (r.t <= 0) r.t = System.currentTimeMillis();
        if (r.hour < 0) {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTimeInMillis(r.t);
            r.hour = c.get(java.util.Calendar.HOUR_OF_DAY);
        }
        synchronized (LOCK) {
            bucket(r.t).add(r);
            try {
                String m = monthKey(r.t);
                if (writer == null || !m.equals(currentMonth)
                        || (currentFile != null && currentFile.length() > MAX_FILE_BYTES)) {
                    openWriter();
                }
                if (writer != null) {
                    writer.write(toJsonLine(r));
                    writer.write("\n");
                    writer.flush();
                }
            } catch (Throwable ignored) {
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
            o.put("t", r.t);
            o.put("prov", r.prov == null ? "" : r.prov);
            o.put("model", r.model == null ? "" : r.model);
            if (r.req != null && !r.req.isEmpty()) o.put("req", r.req);
            o.put("in", r.in);
            o.put("out", r.out);
            o.put("cr", r.cacheRead);
            o.put("cw", r.cacheWrite);
            if (r.reasoning > 0) o.put("reas", r.reasoning);
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
        long now = System.currentTimeMillis();
        long from = now - (long) days * 86400000L;
        Totals total = new Totals();
        Map<String, Totals> by = new LinkedHashMap<String, Totals>();
        Map<String, Totals> byDay = new LinkedHashMap<String, Totals>();
        Map<String, Totals> byProv = new LinkedHashMap<String, Totals>();
        Map<String, Totals> bySession = new LinkedHashMap<String, Totals>();
        final int[] hourTokens = new int[24];
        final int[] hourReq = new int[24];
        Map<String, Integer> errCount = new LinkedHashMap<String, Integer>();
        int rows = 0;

        synchronized (LOCK) {
            for (Map.Entry<String, List<Row>> e : DAYS.entrySet()) {
                for (Row r : e.getValue()) {
                    if (r.t < from) continue;
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
                    Totals d = byDay.get(e.getKey());
                    if (d == null) {
                        d = new Totals();
                        byDay.put(e.getKey(), d);
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
                        hourTokens[r.hour] += r.tokens();
                        hourReq[r.hour]++;
                    }
                    if (r.failed()) {
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
                    return b.getValue().tokens() - a.getValue().tokens();
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
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            for (int i = days - 1; i >= 0; i--) {
                long t = now - (long) i * 86400000L;
                String k = f.format(new Date(t));
                Totals d = byDay.get(k);
                JSONObject o = new JSONObject();
                o.put("day", k);
                if (d == null) {
                    o.put("tokens", 0);
                    o.put("input", 0);
                    o.put("output", 0);
                    o.put("requests", 0);
                    o.put("failed", 0);
                } else {
                    JSONObject dj = d.toJson();
                    o.put("tokens", dj.optInt("tokens", 0));
                    o.put("input", dj.optInt("input", 0));
                    o.put("output", dj.optInt("output", 0));
                    o.put("requests", dj.optInt("requests", 0));
                    o.put("failed", dj.optInt("failed", 0));
                }
                daily.put(o);
            }
            out.put("daily", daily);

            JSONArray recent = new JSONArray();
            List<Row> all = new ArrayList<Row>();
            synchronized (LOCK) {
                for (List<Row> l : DAYS.values()) {
                    for (Row r : l) if (r.t >= from) all.add(r);
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
                o.put("prov", r.prov);
                o.put("model", r.model);
                o.put("in", r.in);
                o.put("out", r.out);
                o.put("cacheRead", r.cacheRead);
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
                    return b.getValue().tokens() - a.getValue().tokens();
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

    /** 导出 CSV（给「导出 CSV」用） */
    /** 窗口内总量：[请求数, token, 失败数]；给 /api/quota 的 totals 用（计数器重启会归零） */
    public static long[] windowTotals(int days) {
        long from = System.currentTimeMillis() - (long) days * 86400000L;
        long req = 0, tok = 0, fail = 0;
        synchronized (LOCK) {
            for (List<Row> l : DAYS.values()) {
                for (Row r : l) {
                    if (r.t < from) continue;
                    req++;
                    if (r.failed()) fail++;
                    else tok += r.tokens();
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
                    a[0]++;
                    if (!r.failed()) a[1] += r.tokens();
                    else a[2]++;                       // 复用第 3 格记失败数
                    if (r.ms > 0) a[3] += r.ms;
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
                    req[r.hour]++;
                    if (!r.failed()) tok[r.hour] += r.tokens();
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
            o.put("statusCode", r.status);
            o.put("model", r.model == null || r.model.isEmpty() ? r.req : r.model);
            o.put("provider", r.prov);
            o.put("totalTokens", r.tokens());
            o.put("durationMs", r.ms);
            if (r.err != null && !r.err.isEmpty()) o.put("error", r.err);
            out.put(o);
        }
        return out;
    }

    public static String csv(int days) {
        long from = System.currentTimeMillis() - (long) days * 86400000L;
        StringBuilder sb = new StringBuilder();
        sb.append("time,provider,model,requested,input,output,cache_read,cache_write,reasoning,ttft_ms,decode_ms,ms,status,tries,error\n");
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
                    .append(r.in).append(',')
                    .append(r.out).append(',')
                    .append(r.cacheRead).append(',')
                    .append(r.cacheWrite).append(',')
                    .append(r.reasoning).append(',')
                    .append(r.ttft < 0 ? "" : r.ttft).append(',')
                    .append(r.decMs < 0 ? "" : r.decMs).append(',')
                    .append(r.ms).append(',')
                    .append(r.status).append(',')
                    .append(r.tries).append(',')
                    .append(csvEsc(r.err)).append('\n');
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
