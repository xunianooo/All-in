package com.gaeul.allin;

import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import com.gaeul.allin.json.*;

/** Converts Qoder's SSE envelopes to OpenAI chat-completion SSE. */
public final class QoderStream {
    private QoderStream() {}
    private static final int FRAME_LIMIT = 1024 * 1024;
    private static final int TOOL_LIMIT = 1024 * 1024;
    // Live output is incremental and has no total cap; aggregation and non-business runs are bounded.
    private static final int AGGREGATE_LIMIT = 8 * 1024 * 1024;

    public interface Observer {
        default void sent() {}
        default void completed() {}
        void usage(JSONObject usage);
    }

    public static final class Failure extends IOException {
        public final int status, code, retryAfter;
        /** The max_tokens ceiling a range refusal named, 0 when the refusal named none. */
        public final int maxTokensCap;
        public final boolean sentOrUnknown;

        public Failure(int status, int code, int retryAfter, boolean sentOrUnknown, String message) {
            this(status, code, retryAfter, 0, sentOrUnknown, message);
        }

        public Failure(int status, int code, int retryAfter, int maxTokensCap,
                boolean sentOrUnknown, String message) {
            super(message);
            this.status = status;
            this.code = code;
            this.retryAfter = retryAfter;
            this.maxTokensCap = Math.max(0, maxTokensCap);
            this.sentOrUnknown = sentOrUnknown;
        }
    }

    private static Failure failure(int status, String body, boolean sent) throws IOException {
        try {
            JSONObject f = QoderFailurePolicy.failure(status, body);
            // A range refusal names the ceiling; keep it so the caller can ask once more at it
            // instead of throwing a history away or guessing a number.
            return new Failure(f.optInt("status", 502), f.optInt("qoder_code", 0),
                    f.optInt("retry_after", 0), QoderFailurePolicy.rangeCap(body), sent,
                    f.optString("error", "Qoder request failed"));
        } catch (Exception ex) {
            throw new IOException("Invalid Qoder failure");
        }
    }

    public static Stream open(QoderAuth.SignIn si, JSONObject body, JSONObject original,
                              String model, QoderAuth.ConnectionFactory factory,
                              RequestContext context) throws Exception {
        return open(si, body, original, model, factory, context, null);
    }

    public static Stream open(QoderAuth.SignIn si, JSONObject body, JSONObject original,
                              String model, QoderAuth.ConnectionFactory factory,
                              RequestContext context, Observer observer) throws Exception {
        HttpURLConnection conn = null;
        InputStream source = null;
        boolean sent = false, attached = false;
        try {
            if (context != null) context.check();
            String wire = QoderAuth.encodeBody(body.toString());
            JSONObject config = body.optJSONObject("model_config");
            Map<String, String> headers = QoderAuth.chatHeaders(QoderAuth.CHAT_URL, si, wire,
                    config == null ? "" : config.optString("key", ""),
                    config == null ? "" : config.optString("source", ""));
            conn = factory != null ? factory.open(QoderAuth.CHAT_URL)
                    : context != null && context.network != null
                    ? context.network.openHttp(QoderAuth.CHAT_URL) : NetworkPolicy.openHttp(QoderAuth.CHAT_URL);
            if (context != null) { context.attach(conn); attached = true; context.check(); }
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(Math.min(15000, timeout(context)));
            conn.setReadTimeout(timeout(context));
            byte[] bytes = wire.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            conn.setRequestProperty("Content-Type", "application/json");
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                conn.setRequestProperty(entry.getKey(), entry.getValue());
            }
            if (context != null) context.check();
            sent = true;
            if (observer != null) observer.sent();
            try (OutputStream out = conn.getOutputStream()) {
                if (context != null) context.check();
                out.write(bytes);
                out.flush();
                if (context != null) context.check();
            }
            if (context != null) context.check();
            conn.setReadTimeout(timeout(context));
            int status = conn.getResponseCode();
            source = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
            if (source != null) source = contextInput(source, conn, context);
            String encoding = conn.getContentEncoding();
            source = decodeEncoding(source, encoding);
            if (status < 200 || status >= 300)
                throw failure(status, readError(source, declaredBytes(conn, encoding)), true);
            verifyMime(conn.getContentType());
            if (source == null) throw new IOException("Missing Qoder response stream");
            Stream stream = new Stream(conn, source, original, model, context, observer);
            stream.prime();
            // Ownership transfers only after priming succeeds.
            conn = null;
            source = null;
            attached = false;
            return stream;
        } catch (Exception ex) {
            if (source != null) try { source.close(); } catch (IOException ignored) {}
            if (conn != null) {
                if (attached && context != null) context.detach(conn);
                conn.disconnect();
            }
            if (ex instanceof Failure) throw ex;
            throw new Failure(502, 0, 0, sent, sent ? "Qoder transport or stream failed" : "Qoder request could not be started");
        }
    }

    private static int timeout(RequestContext context) {
        return context == null ? 300000 : (int) Math.max(1, Math.min(300000L, context.remainingMillis()));
    }

    private static InputStream contextInput(InputStream source, HttpURLConnection conn, RequestContext context) {
        if (context == null) return source;
        return new FilterInputStream(source) {
            private void before() throws IOException { context.check(); conn.setReadTimeout(timeout(context)); }
            @Override public int read() throws IOException { before(); int n = in.read(); context.check(); return n; }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                before(); int n = in.read(b, off, len); context.check(); return n;
            }
        };
    }

    private static InputStream decodeEncoding(InputStream source, String encoding) throws IOException {
        if (encoding == null || encoding.trim().isEmpty() || encoding.trim().equalsIgnoreCase("identity")) return source;
        if (encoding.trim().equalsIgnoreCase("gzip") && source != null) return new GZIPInputStream(source);
        throw new IOException("Unsupported Qoder content encoding");
    }

    private static void verifyMime(String mime) throws IOException {
        if (mime == null || !mime.split(";", 2)[0].trim().equalsIgnoreCase("text/event-stream"))
            throw new IOException("Invalid Qoder response content type");
    }

    /**
     * The number of bytes the answered refusal promised, or -1 when this transport cannot check it:
     * an answer with no declared length (chunked), or one it decodes (the promise is about the wire,
     * the bytes read are the decoded ones). A partial answer is not Qoder's verdict at all: the
     * plugin reads it with {@code (await res.text())} (index.mjs:3651), which rejects when the body
     * ends early, and that rejection is the 502 at index.mjs:3701 — never a status read off half of
     * the text. No platform is relied on to say so: the JDK's HttpURLConnection reads a short
     * fixed-length body to EOF with no error at all (measured on 17: 24 of 51 bytes, then EOF), and
     * Android's throws (measured on 16). Checking the promise by size reads nothing twice and sends
     * nothing again: the request was answered, it is simply not an answer Qoder finished.
     */
    private static long declaredBytes(HttpURLConnection conn, String encoding) {
        if (encoding != null) {
            String e = encoding.trim();
            if (!e.isEmpty() && !e.equalsIgnoreCase("identity")) return -1;
        }
        return conn.getContentLength();
    }

    /** The refused answer's own words, bounded; a body short of its declared length is a failure. */
    private static String readError(InputStream in, long declared) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        while (out.size() < QoderFailurePolicy.MAX_ERROR_BYTES) {
            int n = in.read(buf, 0, Math.min(buf.length, QoderFailurePolicy.MAX_ERROR_BYTES - out.size()));
            if (n <= 0) break;
            out.write(buf, 0, n);
        }
        // Only a promise this read meant to take whole is checked; a longer one is bounded here on purpose.
        if (declared >= 0 && declared <= QoderFailurePolicy.MAX_ERROR_BYTES && out.size() < declared)
            throw new IOException("Qoder refusal body ended early");
        return out.toString("UTF-8");
    }

    private static JSONObject object(String text) throws IOException {
        try {
            if (text.getBytes(StandardCharsets.UTF_8).length > FRAME_LIMIT)
                throw new IOException("Qoder JSON object too large");
            new BoundedStrictJson(text).run();
            JSONTokener tokener = new JSONTokener(text);
            Object value = tokener.nextValue();
            if (!(value instanceof JSONObject) || tokener.nextClean() != 0) throw new IOException("Expected JSON object");
            return (JSONObject) value;
        } catch (JSONException | IllegalArgumentException ex) {
            throw new IOException("Invalid JSON object");
        }
    }

    private static final class Call {
        String id = "", name = "";
        final StringBuilder args = new StringBuilder();
    }

    public static final class Stream extends InputStream {
        public final HttpURLConnection connection;
        public final InputStream input;
        public final int status = 200;
        public volatile JSONObject usage;
        private final Reader reader;
        private final RequestContext context;
        private final Observer observer;
        private final JSONObject original;
        private final String model, id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "");
        private final long created = System.currentTimeMillis() / 1000;
        private final TreeMap<Integer, Call> calls = new TreeMap<>();
        private final List<Call> textCalls = new ArrayList<>();
        private final QoderTextTools textTools;
        private final boolean allowTextTools;
        private boolean textFlushed;
        private byte[] pending = new byte[0];
        private int offset, held = -1, frameSize, skipped, toolSize;
        private boolean terminal, closed, business, finishSeen;
        private String finish = "";
        private IOException failed;

        Stream(HttpURLConnection conn, InputStream source, JSONObject original, String model,
               RequestContext context, Observer observer) {
            this.connection = conn;
            this.input = this;
            this.original = original;
            this.model = model == null ? "" : model;
            this.context = context;
            this.observer = observer;
            this.textTools = new QoderTextTools(original == null ? null : original.optJSONArray("tools"));
            this.allowTextTools = original == null || !"none".equals(original.opt("tool_choice"));
            this.reader = new InputStreamReader(source, StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT));
        }

        private void check() throws IOException {
            if (failed != null) throw failed;
            if (closed) throw new IOException("Qoder stream closed");
            if (context != null) context.check();
        }

        private int next() throws IOException {
            if (held >= 0) { int c = held; held = -1; return c; }
            return reader.read();
        }

        private String line() throws IOException {
            StringBuilder line = new StringBuilder();
            while (true) {
                if ((frameSize & 4095) == 0) check();
                int c = next();
                if (c < 0) return line.length() == 0 ? null : line.toString();
                // Count UTF-8 bytes conservatively (a surrogate pair counts as six).
                frameSize += c <= 0x7f ? 1 : c <= 0x7ff ? 2 : 3;
                if (frameSize > FRAME_LIMIT) throw new IOException("Qoder SSE frame too large");
                if (c == '\n') return line.toString();
                if (c == '\r') {
                    int n = next();
                    if (n >= 0 && n != '\n') held = n;
                    else if (n == '\n' && ++frameSize > FRAME_LIMIT) throw new IOException("Qoder SSE frame too large");
                    return line.toString();
                }
                line.append((char) c);
            }
        }

        private JSONObject chunk(JSONObject delta, String reason) throws JSONException {
            JSONObject choice = new JSONObject().put("index", 0).put("delta", delta)
                    .put("finish_reason", reason == null ? JSONObject.NULL : reason);
            return new JSONObject().put("id", id).put("object", "chat.completion.chunk")
                    .put("created", created).put("model", model).put("choices", new JSONArray().put(choice));
        }

        private void emit(ByteArrayOutputStream out, JSONObject data) throws IOException {
            byte[] bytes = ("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8);
            if (out.size() + bytes.length > AGGREGATE_LIMIT) throw new IOException("Qoder output too large");
            out.write(bytes);
        }

        private static String stringField(JSONObject obj, String key) throws IOException {
            Object value = obj.opt(key);
            if (value == null || value == JSONObject.NULL) return "";
            if (!(value instanceof String)) throw new IOException("Invalid Qoder field type");
            return (String) value;
        }

        private void collectTools(Object value) throws IOException {
            if (!(value instanceof JSONArray)) throw new IOException("Invalid Qoder tool list");
            JSONArray list = (JSONArray) value;
            if (list.length() > 0 && !textCalls.isEmpty()) throw new IOException("Mixed Qoder tool encodings");
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) throw new IOException("Invalid Qoder tool call");
                Object rawIndex = item.opt("index");
                // Plugin defaults an absent index to zero; this is unambiguous only for a single call.
                if (rawIndex == null && list.length() == 1 && (calls.isEmpty() || (calls.size() == 1 && calls.containsKey(0)))) rawIndex = 0;
                if (!(rawIndex instanceof Number) || ((Number) rawIndex).doubleValue() != ((Number) rawIndex).intValue()
                        || ((Number) rawIndex).intValue() < 0 || ((Number) rawIndex).intValue() > 1024)
                    throw new IOException("Invalid Qoder tool index");
                int index = ((Number) rawIndex).intValue();
                Call call = calls.get(index);
                if (call == null) {
                    if (calls.size() >= 128) throw new IOException("Too many Qoder tools");
                    call = new Call(); calls.put(index, call);
                }
                String id = stringField(item, "id"), type = stringField(item, "type");
                if (!type.isEmpty() && !type.equals("function")) throw new IOException("Invalid Qoder tool type");
                if (!id.isEmpty()) {
                    if (!call.id.isEmpty() && !call.id.equals(id)) throw new IOException("Qoder tool id changed");
                    call.id = id;
                }
                Object function = item.opt("function");
                if (function != null && function != JSONObject.NULL && !(function instanceof JSONObject))
                    throw new IOException("Invalid Qoder function");
                if (function instanceof JSONObject) {
                    JSONObject f = (JSONObject) function;
                    String name = stringField(f, "name"), args = stringField(f, "arguments");
                    if (!name.isEmpty()) {
                        if (!call.name.isEmpty() && !call.name.equals(name)) throw new IOException("Qoder tool name changed");
                        call.name = name;
                    }
                    toolSize += args.getBytes(StandardCharsets.UTF_8).length;
                    if (toolSize > TOOL_LIMIT) throw new IOException("Qoder tools too large");
                    call.args.append(args);
                }
            }
        }

        private void textPieces(List<QoderTextTools.Piece> pieces, ByteArrayOutputStream out) throws Exception {
            for (QoderTextTools.Piece piece : pieces) {
                if (piece.text != null && !piece.text.isEmpty()) {
                    emit(out, chunk(new JSONObject().put("content", piece.text), null));
                    business = true;
                }
                if (piece.call != null) {
                    if (!calls.isEmpty()) throw new IOException("Mixed Qoder tool encodings");
                    if (textCalls.size() >= 128) throw new IOException("Too many Qoder tools");
                    Call call = new Call();
                    call.id = "call_" + UUID.randomUUID().toString().replace("-", "");
                    call.name = stringField(piece.call, "name");
                    String args = stringField(piece.call, "args");
                    toolSize += args.getBytes(StandardCharsets.UTF_8).length;
                    if (toolSize > TOOL_LIMIT) throw new IOException("Qoder tools too large");
                    call.args.append(args);
                    textCalls.add(call);
                }
            }
        }

        private void flushText(ByteArrayOutputStream out) throws Exception {
            if (!textFlushed) {
                textFlushed = true;
                if (allowTextTools) textPieces(textTools.flush(), out);
            }
        }

        private boolean declared(String name) {
            JSONArray tools = original == null ? null : original.optJSONArray("tools");
            if (tools == null) return false;
            for (int i = 0; i < tools.length(); i++) {
                JSONObject item = tools.optJSONObject(i), fn = item == null ? null : item.optJSONObject("function");
                if (fn != null && name.equals(fn.optString("name"))) return true;
            }
            return false;
        }

        private void checkToolChoice(JSONArray result) throws IOException {
            Object choice = original == null ? null : original.opt("tool_choice");
            if ("none".equals(choice) && result.length() > 0) throw new IOException("Qoder violated tool_choice none");
            if ("required".equals(choice) && result.length() == 0) throw new IOException("Qoder omitted required tool");
            /* A selected tool is carried as "the tools are available", so the only place its
               intent can still be held is here: a reply that calls something else fails
               closed instead of passing for the answer the caller asked for. */
            String selected = "";
            if (choice instanceof JSONObject) {
                JSONObject select = (JSONObject) choice;
                JSONObject fn = select.optJSONObject("function");
                selected = fn == null ? select.optString("name", "") : fn.optString("name", "");
            } else if (choice instanceof String && !"auto".equals(choice) && !"none".equals(choice)
                    && !"required".equals(choice) && !"any".equals(choice)) {
                selected = ((String) choice).trim();
            }
            if (!selected.isEmpty()) {
                if (result.length() == 0) throw new IOException("Qoder omitted selected tool");
                for (int i = 0; i < result.length(); i++) {
                    JSONObject call = result.optJSONObject(i), f = call == null ? null : call.optJSONObject("function");
                    if (f == null || !selected.equals(f.optString("name"))) throw new IOException("Qoder violated selected tool");
                }
            }
            if(original!=null&&Boolean.FALSE.equals(original.opt("parallel_tool_calls"))&&result.length()>1)throw new IOException("Qoder returned parallel tools when disabled");
        }

        private void finish(ByteArrayOutputStream out) throws Exception {
            if (!finishSeen) throw new IOException("Qoder stream lacks finish reason");
            flushText(out);
            JSONArray result = new JSONArray();
            Set<String> ids = new HashSet<>();
            int index = 0;
            List<Call> allCalls = new ArrayList<>(calls.values());
            allCalls.addAll(textCalls);
            for (Call call : allCalls) {
                if (call.id.trim().isEmpty() || !ids.add(call.id) || call.name.trim().isEmpty() || !declared(call.name))
                    throw new IOException("Invalid Qoder tool identity or undeclared name");
                try { new BoundedStrictJson(call.args.toString()).run(); }
                catch (IllegalArgumentException ex) { throw new IOException("Invalid Qoder tool arguments"); }
                object(call.args.toString());
                result.put(new JSONObject().put("index", index++).put("id", call.id).put("type", "function")
                        .put("function", new JSONObject().put("name", call.name).put("arguments", call.args.toString())));
            }
            checkToolChoice(result);
            if (original!=null && "required".equals(original.opt("tool_choice")) && result.length()==0) throw new IOException("Qoder omitted required tool");
            if (finish.equals("tool_calls") && result.length() == 0) throw new IOException("Missing Qoder tool calls");
            if (result.length() > 0 && finish.equals("length")) throw new IOException("Qoder tool call truncated");
            if (!finish.equals("stop") && !finish.equals("length") && !finish.equals("tool_calls") && !finish.equals("content_filter")) finish="stop";
            if (result.length() > 0) { emit(out, chunk(new JSONObject().put("tool_calls", result), null)); business = true; }
            if (!business) throw new IOException("Empty Qoder response");
            String reason = result.length() > 0 ? "tool_calls" : finish;
            JSONObject last = chunk(new JSONObject(), reason);
            if (usage != null) last.put("usage", usage);
            emit(out, last);
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            terminal = true;
            if(observer!=null) observer.completed();
        }

        /** Merge successive usage snapshots without inventing or adding token counts. */
        private static JSONObject normalizeUsage(JSONObject raw) throws IOException, JSONException {
            int prompt=count(raw.opt("prompt_tokens")), completion=count(raw.opt("completion_tokens"));
            JSONObject out=new JSONObject().put("prompt_tokens",prompt).put("completion_tokens",completion).put("total_tokens",prompt+completion);
            JSONObject pd=raw.optJSONObject("prompt_tokens_details"), cd=raw.optJSONObject("completion_tokens_details"); if(pd==null)pd=raw.optJSONObject("details");
            int cached=pd==null?0:count(pd.opt("cached_tokens")), reasoning=cd==null?0:count(cd.opt("reasoning_tokens"));
            if(cached>0)out.put("prompt_tokens_details",new JSONObject().put("cached_tokens",cached));
            if(reasoning>0)out.put("completion_tokens_details",new JSONObject().put("reasoning_tokens",reasoning));
            return out;
        }
        private static int count(Object raw) throws IOException {
            if(raw==null||raw==JSONObject.NULL)return 0;
            if(!(raw instanceof Number))throw new IOException("Invalid Qoder usage count");
            double n=((Number)raw).doubleValue(); if(!Double.isFinite(n)||n<0||n>Integer.MAX_VALUE||n!=Math.rint(n))throw new IOException("Invalid Qoder usage count");
            return (int)n;
        }


        private void acceptChunk(JSONObject data, ByteArrayOutputStream out) throws Exception {
            JSONArray choices = data.optJSONArray("choices");
            if (choices == null && (data.has("error") || (data.has("code") && data.has("message"))))
                throw failure(502, data.toString(), true);
            JSONObject u = data.optJSONObject("usage");
            if (data.has("usage") && !data.isNull("usage") && u == null) throw new IOException("Invalid Qoder usage");
            if (u != null) {
                usage = normalizeUsage(u);
                if (observer != null) observer.usage(usage);
            }
            if (choices == null) {
                if (u == null) throw new IOException("Unknown Qoder event");
                return;
            }
            if (choices.length() == 0) return;
            if (choices.length() != 1) throw new IOException("Multiple Qoder choices unsupported");
            JSONObject choice = choices.optJSONObject(0);
            if (choice == null) throw new IOException("Invalid Qoder choice");
            Object choiceIndex = choice.opt("index");
            if (choiceIndex != null && choiceIndex != JSONObject.NULL
                    && (!(choiceIndex instanceof Number) || ((Number) choiceIndex).doubleValue() != 0))
                throw new IOException("Invalid Qoder choice index");
            String reason = stringField(choice, "finish_reason");
            JSONObject delta = choice.optJSONObject("delta");
            if (choice.has("delta") && !choice.isNull("delta") && delta == null) throw new IOException("Invalid Qoder delta");
            if (delta != null) {
                JSONObject output = new JSONObject();
                String reasoning = stringField(delta, "reasoning_content");
                String content = stringField(delta, "content");
                if ((!reasoning.isEmpty() || !content.isEmpty()) && finishSeen)
                    throw new IOException("Qoder data after finish");
                if (!reasoning.isEmpty()) {
                    output.put("reasoning_content", reasoning);
                    business = true;
                    emit(out, chunk(output, null));
                }
                if (!content.isEmpty()) {
                    if (allowTextTools) textPieces(textTools.feed(content), out);
                    else { emit(out, chunk(new JSONObject().put("content", content), null)); business = true; }
                }
                if (delta.has("tool_calls") && !delta.isNull("tool_calls")) {
                    if (finishSeen) throw new IOException("Qoder tools after finish");
                    collectTools(delta.opt("tool_calls"));
                }
            }
            if (!reason.isEmpty()) {
                if (finishSeen && !finish.equals(reason)) throw new IOException("Conflicting Qoder finish reason");
                finish = reason; finishSeen = true;
            }
        }

        private void acceptEnvelope(String text, ByteArrayOutputStream out) throws Exception {
            if (text.equals("[DONE]")) { finish(out); return; }
            JSONObject env = object(text);
            Object status = env.opt("statusCodeValue"), body = env.opt("body");
            if (status != null && status != JSONObject.NULL) {
                int code;
                try { code = Integer.parseInt(status.toString()); }
                catch (Exception ex) { throw new IOException("Invalid Qoder status"); }
                if (code != 200) throw failure(code, body instanceof String ? (String) body : text, true);
            }
            if (!(body instanceof String)) throw new IOException("Invalid Qoder envelope");
            String payload = ((String) body).trim();
            if (payload.equals("[DONE]")) finish(out);
            else acceptChunk(object(payload), out);
        }

        private void fill() throws IOException {
            try {
                while (!terminal && offset == pending.length) {
                    check();
                    frameSize = 0;
                    StringBuilder data = new StringBuilder();
                    boolean hasData = false, complete = false, sawLine = false;
                    while (true) {
                        String line = line();
                        if (line == null) break;
                        sawLine = true;
                        if (line.isEmpty()) { complete = true; break; }
                        if (line.charAt(0) == ':') continue;
                        int colon = line.indexOf(':');
                        String key = colon < 0 ? line : line.substring(0, colon);
                        String value = colon < 0 ? "" : line.substring(colon + 1);
                        if (value.startsWith(" ")) value = value.substring(1);
                        if (key.equals("data")) {
                            if (hasData) data.append('\n');
                            data.append(value); hasData = true;
                        }
                    }
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    if (!complete) {
                        // A fully framed finish followed by clean EOF is a valid terminal; partial frames never are.
                        if (!sawLine && finishSeen) finish(out);
                        else throw new IOException("Qoder stream truncated");
                    } else if (hasData) acceptEnvelope(data.toString().trim(), out);
                    pending = out.toByteArray(); offset = 0;
                    if (pending.length == 0) {
                        skipped += frameSize;
                        if (skipped > AGGREGATE_LIMIT) throw new IOException("Qoder stream has no business events");
                    } else skipped = 0;
                }
            } catch (IOException ex) {
                failed = ex; throw ex;
            } catch (Exception ex) {
                failed = new IOException("Invalid Qoder stream"); throw failed;
            }
        }

        public void prime() throws IOException { fill(); }
        @Override public int read() throws IOException {
            byte[] one = new byte[1]; int n = read(one, 0, 1); return n < 0 ? -1 : one[0] & 255;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (b == null) throw new NullPointerException();
            if (off < 0 || len < 0 || off > b.length - len) throw new IndexOutOfBoundsException();
            if (len == 0) return 0;
            try { check(); fill(); } catch (IOException ex) { if (failed == null) failed = ex; throw failed; }
            if (offset == pending.length) return -1;
            int n = Math.min(len, pending.length - offset);
            System.arraycopy(pending, offset, b, off, n); offset += n; return n;
        }
        @Override public void close() throws IOException {
            if (!closed) {
                closed = true;
                try { reader.close(); }
                finally { if (context != null) context.detach(connection); connection.disconnect(); }
            }
        }
        public JSONObject aggregate() throws Exception { return QoderStream.aggregate(this); }
    }

    public static JSONObject aggregate(Stream stream) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder text = new StringBuilder(), reasoning = new StringBuilder();
        JSONArray calls = new JSONArray();
        JSONObject first = null;
        String finish = null, line;
        int total = 0;
        while ((line = reader.readLine()) != null) {
            total += line.getBytes(StandardCharsets.UTF_8).length + 1;
            if (total > AGGREGATE_LIMIT) throw new IOException("Qoder aggregate too large");
            if (!line.startsWith("data: ") || line.equals("data: [DONE]")) continue;
            JSONObject chunk = object(line.substring(6));
            if (first == null) first = chunk;
            JSONObject choice = chunk.getJSONArray("choices").getJSONObject(0), delta = choice.getJSONObject("delta");
            text.append(delta.optString("content", ""));
            reasoning.append(delta.optString("reasoning_content", ""));
            JSONArray tc = delta.optJSONArray("tool_calls");
            if (tc != null) for (int i = 0; i < tc.length(); i++) {
                JSONObject call = tc.getJSONObject(i); call.remove("index"); calls.put(call);
            }
            if (!choice.isNull("finish_reason")) finish = choice.getString("finish_reason");
        }
        if (first == null || finish == null) throw new IOException("Incomplete Qoder response");
        JSONObject message = new JSONObject().put("role", "assistant")
                .put("content", text.length() == 0 ? JSONObject.NULL : text.toString());
        if (reasoning.length() > 0) message.put("reasoning_content", reasoning.toString());
        if (calls.length() > 0) message.put("tool_calls", calls);
        JSONObject result = new JSONObject().put("id", first.get("id")).put("object", "chat.completion")
                .put("created", first.get("created")).put("model", first.get("model"))
                .put("choices", new JSONArray().put(new JSONObject().put("index", 0).put("message", message).put("finish_reason", finish)));
        if (stream.usage != null) result.put("usage", stream.usage);
        return result;
    }
}
