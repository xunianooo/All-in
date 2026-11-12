package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public final class ZCodeToolSearchOfflineTest {
    private static void ok(boolean b) { if (!b) throw new AssertionError(); }
    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[3]; int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
    public static void main(String[] args) throws Exception {
        String original = "{\"tools\":[{\"name\":\"ToolSearch\",\"defer_loading\":true},{\"name\":\"Bash\",\"defer_loading\":true},{\"name\":\"DeferredToolPlaceholder\"}],\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"signature\":\"ToolSearch\",\"thinking\":\"ToolSearch\"},{\"type\":\"tool_use\",\"name\":\"ToolSearch\",\"id\":\"ToolSearch\",\"input\":{\"name\":\"ToolSearch\"}},{\"type\":\"tool_reference\",\"tool_name\":\"ToolSearch\"}]},{\"role\":\"user\",\"content\":[{\"type\":\"tool_use\",\"name\":\"ToolSearch\"}]}],\"tool_choice\":{\"type\":\"tool\",\"name\":\"ToolSearch\"}}";
        JSONObject input = new JSONObject(original); String before = input.toString();
        ZCodeToolSearchPolicy.Result r = ZCodeToolSearchPolicy.transform(input); ok(r.renamed && before.equals(input.toString()));
        JSONObject b = r.body(); JSONArray tools = b.getJSONArray("tools"); ok(tools.length() == 2 && tools.getJSONObject(0).getString("name").equals("ToolSearchTool") && !tools.getJSONObject(1).has("defer_loading"));
        JSONArray content = b.getJSONArray("messages").getJSONObject(0).getJSONArray("content");
        ok(content.getJSONObject(0).getString("signature").equals("ToolSearch"));
        ok(content.getJSONObject(1).getString("name").equals("ToolSearchTool") && content.getJSONObject(1).getString("id").equals("ToolSearch") && content.getJSONObject(1).getJSONObject("input").getString("name").equals("ToolSearch"));
        ok(content.getJSONObject(2).getString("tool_name").equals("ToolSearch"));
        ok(b.getJSONObject("tool_choice").getString("name").equals("ToolSearchTool"));
        ok(b.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("name").equals("ToolSearch"));
        b.put("tools", new JSONArray()); ok(r.body().getJSONArray("tools").length() == 2);
        JSONObject conflict = new JSONObject(original); conflict.getJSONArray("tools").put(new JSONObject().put("name", "ToolSearchTool"));
        ok(!ZCodeToolSearchPolicy.transform(conflict).renamed && ZCodeToolSearchPolicy.transform(conflict).body().toString().equals(conflict.toString()));
        ok(!ZCodeToolSearchPolicy.transform(new JSONObject()).renamed);
        JSONObject response = new JSONObject("{\"content\":[{\"type\":\"tool_use\",\"name\":\"ToolSearchTool\",\"input\":{\"name\":\"ToolSearchTool\"}},{\"type\":\"thinking\",\"signature\":\"ToolSearchTool\"}]}");
        JSONObject restored = r.restoreJson(response); ok(restored.getJSONArray("content").getJSONObject(0).getString("name").equals("ToolSearch"));
        ok(response.getJSONArray("content").getJSONObject(0).getString("name").equals("ToolSearchTool"));
        ok(restored.getJSONArray("content").getJSONObject(0).getJSONObject("input").getString("name").equals("ToolSearchTool"));
        String sse = ": 注释\r\nevent: content_block_start\r\ndata: {\"content_block\":\r\ndata: {\"type\":\"tool_use\",\"name\":\"ToolSearchTool\",\"input\":{\"x\":\"中文\"}}}\r\n\r\ndata: {\"type\":\"text\",\"text\":\"ToolSearchTool\"}\n\n";
        byte[] encoded = sse.getBytes(StandardCharsets.UTF_8);
        InputStream split = new ByteArrayInputStream(encoded) { @Override public synchronized int read(byte[] a,int o,int l) { return super.read(a,o,Math.min(1,l)); } };
        InputStream stream = r.restoreStream(split, true); ok(stream.available() == 0);
        String out = read(stream); ok(out.contains("\"name\":\"ToolSearch\"") && out.contains("中文") && out.endsWith("data: {\"type\":\"text\",\"text\":\"ToolSearchTool\"}\n\n") && out.startsWith(": 注释\r\nevent: content_block_start\r\n"));
        stream.close(); try { stream.read(); throw new AssertionError(); } catch (IOException expected) { }
        InputStream untouched = new ByteArrayInputStream(encoded); ok(r.restoreStream(untouched, false) == untouched);
        ok(ZCodeToolSearchPolicy.restoreJson(response, false) == response);
        String cr = "data: {\"content_block\":{\"type\":\"tool_use\",\"name\":\"ToolSearchTool\"}}\r\rdata: [DONE]\r\r";
        ok(read(r.restoreStream(new ByteArrayInputStream(cr.getBytes(StandardCharsets.UTF_8)), true)).contains("\"name\":\"ToolSearch\""));
        byte[] first = "data: {\"type\":\"message_start\"}\n\n".getBytes(StandardCharsets.UTF_8);
        InputStream early = new InputStream() { int i; public int read() throws IOException { if (i == first.length) throw new IOException("read beyond first event"); return first[i++] & 255; } };
        InputStream incremental = r.restoreStream(early, true);
        byte[] received = new byte[first.length]; int count = 0; while (count < received.length) { int n = incremental.read(received, count, received.length-count); ok(n > 0); count += n; }
        ok(java.util.Arrays.equals(first, received));
        String[] invalid = {"data: {\"content_block\":{\"type\":\"tool_use\",\"name\":\"ToolSearchTool\"\n\n", "data: {\"type\":\"content_block_start\",\"content_block\":{\"type\":\"tool_use\"}}\n\n"};
        for (String bad : invalid) { InputStream is = r.restoreStream(new ByteArrayInputStream(bad.getBytes(StandardCharsets.UTF_8)), true); try { is.read(); throw new AssertionError("malformed event accepted"); } catch (IOException expected) {} try { is.read(); throw new AssertionError("failure not sticky"); } catch (IOException expected) {} }
        byte[] huge = new byte[1024 * 1024 + 1]; java.util.Arrays.fill(huge, (byte)'x');
        InputStream tooBig = r.restoreStream(new ByteArrayInputStream(huge), true);
        try { tooBig.read(); throw new AssertionError("oversize event accepted"); } catch (IOException expected) {}
        try { tooBig.read(); throw new AssertionError("oversize failure not sticky"); } catch (IOException expected) {}
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try { Future<?>[] fs = new Future<?>[30]; for (int i=0;i<fs.length;i++) fs[i] = pool.submit(() -> { try { ok(ZCodeToolSearchPolicy.transform(new JSONObject(original)).renamed); ok(!ZCodeToolSearchPolicy.transform(new JSONObject()).renamed); } catch (Exception e) { throw new RuntimeException(e); } }); for (Future<?> f:fs) f.get(); } finally { pool.shutdownNow(); }
        System.out.println("ZCodeToolSearchOfflineTest passed");
    }
}
