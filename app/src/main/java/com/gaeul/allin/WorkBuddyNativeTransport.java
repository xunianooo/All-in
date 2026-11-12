package com.gaeul.allin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.gaeul.allin.json.JSONObject;
import com.gaeul.allin.json.JSONArray;

/**
 * Standalone HTTP transport for WorkBuddy China Claude Messages.
 * It owns request preparation, fixed-origin headers, exactly one POST, and optional SSE aggregation;
 * gateway routing and accounting remain the caller's responsibility.
 *
 * A resend after WorkBuddy's signature refusal (magpie's withoutSignedThinking) is deliberately NOT
 * performed here. The caller's BeforeDispatch hook is the gateway's single outbound admission: it
 * takes the rate-limit ticket, registers the connection for cancellation and clamps the timeouts.
 * One hook invocation can only serve one connection, so a second POST from this class would either
 * re-run admission (a second ticket, a double markSent, a leaked limiter slot) or leave the retry
 * connection out of the cancellation registry and out of rate pacing. Both are unsafe, so the
 * recovery needs the gateway to split admission from per-connection registration first.
 */
public final class WorkBuddyNativeTransport {
    public interface ConnectionFactory { HttpURLConnection open(String url) throws IOException; }
    /** Runs after fixed headers/body setup and immediately before opening the request stream. */
    public interface BeforeDispatch { void run(HttpURLConnection connection) throws IOException; }

    public static final class Request {
        public final String provider, model, credential, session;
        public final JSONObject account, body, modelMetadata;
        public final boolean hideDsh, clientWantsStream;
        public final int connectTimeoutMillis, readTimeoutMillis;
        public final WorkBuddyHarnessPolicy.Report dshReport;
        public final Map<String,String> protocolHeaders;
        /**
         * Compatibility values for this exact destination, resolved by the caller from the
         * request-scoped rule snapshot before the first send. They are static client fingerprint
         * values only; see {@link #applyRuleHeaders}.
         */
        public final Map<String,String> ruleHeaders;
        public final BeforeDispatch beforeDispatch;

        public Request(String provider, String model, String credential, JSONObject account,
                String session, JSONObject body, JSONObject modelMetadata, boolean hideDsh,
                boolean clientWantsStream, int connectTimeoutMillis, int readTimeoutMillis,
                WorkBuddyHarnessPolicy.Report dshReport) {
            this(provider,model,credential,account,session,body,modelMetadata,hideDsh,clientWantsStream,
                    connectTimeoutMillis,readTimeoutMillis,dshReport,Collections.<String,String>emptyMap(),null);
        }
        public Request(String provider, String model, String credential, JSONObject account,
                String session, JSONObject body, JSONObject modelMetadata, boolean hideDsh,
                boolean clientWantsStream, int connectTimeoutMillis, int readTimeoutMillis,
                WorkBuddyHarnessPolicy.Report dshReport, Map<String,String> protocolHeaders,
                BeforeDispatch beforeDispatch) {
            this(provider,model,credential,account,session,body,modelMetadata,hideDsh,clientWantsStream,
                    connectTimeoutMillis,readTimeoutMillis,dshReport,protocolHeaders,
                    Collections.<String,String>emptyMap(),beforeDispatch);
        }
        public Request(String provider, String model, String credential, JSONObject account,
                String session, JSONObject body, JSONObject modelMetadata, boolean hideDsh,
                boolean clientWantsStream, int connectTimeoutMillis, int readTimeoutMillis,
                WorkBuddyHarnessPolicy.Report dshReport, Map<String,String> protocolHeaders,
                Map<String,String> ruleHeaders, BeforeDispatch beforeDispatch) {
            this.provider=provider; this.model=model; this.credential=credential; this.account=account;
            this.session=session; this.body=body; this.modelMetadata=modelMetadata;
            this.hideDsh=hideDsh; this.clientWantsStream=clientWantsStream;
            this.connectTimeoutMillis=connectTimeoutMillis; this.readTimeoutMillis=readTimeoutMillis;
            this.dshReport=dshReport;
            this.protocolHeaders=protocolHeaders==null?Collections.<String,String>emptyMap():new LinkedHashMap<>(protocolHeaders);
            this.ruleHeaders=ruleHeaders==null?Collections.<String,String>emptyMap():new LinkedHashMap<>(ruleHeaders);
            this.beforeDispatch=beforeDispatch;
        }
    }

    /** Any post-dispatch failure is terminal for this logical request and must not be replayed. */
    public static class NoReplayException extends IOException {
        private final boolean outcomeUnknown;
        public NoReplayException(String message, Throwable cause, boolean outcomeUnknown) {
            super(message, cause); this.outcomeUnknown=outcomeUnknown;
        }
        public final boolean mayHaveReachedUpstream() { return true; }
        public final boolean outcomeUnknown() { return outcomeUnknown; }
    }
    /** Payload or local credentials failed validation before the request was opened. */
    public static final class InvalidRequestException extends IOException {
        public InvalidRequestException(String message,Throwable cause){super(message,cause);}
    }
    /** Connection setup failed before a request body could be sent. */
    public static final class ConnectException extends IOException {
        public ConnectException(String message,Throwable cause){super(message,cause);}
    }
    /** A write or status-read failure means the provider may have completed the request. */
    public static final class OutcomeUnknownException extends NoReplayException {
        public OutcomeUnknownException(String message, Throwable cause) { super(message,cause,true); }
    }
    /** A known successful HTTP response violated the expected native SSE contract. */
    public static final class ResponseProtocolException extends NoReplayException {
        public final int status;
        public ResponseProtocolException(String message, int status, Throwable cause) {
            super(message,cause,false); this.status=status;
        }
    }

    public static final class Response implements AutoCloseable {
        private final int status;
        private final Map<String,String> headers;
        private final InputStream body;
        private final HttpURLConnection connection;
        private final boolean streaming;
        private boolean consumed, closed;
        Response(int status, Map<String,String> headers, InputStream body,
                HttpURLConnection connection, boolean streaming) {
            this.status=status; this.headers=Collections.unmodifiableMap(new LinkedHashMap<>(headers));
            this.body=body; this.connection=connection; this.streaming=streaming;
        }
        public int statusCode() { return status; }
        public Map<String,String> headers() { return headers; }
        public boolean clientWantsStream() { return streaming; }
        /** Connection remains owned by the response until the caller closes or hands off the body. */
        public HttpURLConnection connection() { return connection; }
        /** Raw Anthropic SSE body for streaming clients; caller must close this response. */
        public InputStream stream() throws IOException {
            if(status<200 || status>=300)throw new IllegalStateException("cannot stream an unsuccessful HTTP response");
            if (!streaming) throw new IllegalStateException("response requested as non-streaming");
            if (consumed) throw new IllegalStateException("response body already consumed");
            consumed=true;
            if (body==null) { close(); throw new NoReplayException("upstream response body is empty",null,false); }
            return new java.io.FilterInputStream(body) {
                @Override public int read() throws IOException {
                    try { return super.read(); }
                    catch(IOException failure) { throw new NoReplayException("WorkBuddy SSE read failed after response headers",failure,false); }
                }
                @Override public int read(byte[] b,int off,int len) throws IOException {
                    try { return super.read(b,off,len); }
                    catch(IOException failure) { throw new NoReplayException("WorkBuddy SSE read failed after response headers",failure,false); }
                }
                @Override public void close() throws IOException { Response.this.close(); }
            };
        }
        /** Reads a bounded HTTP error body for the caller's existing gateway error mapper. */
        public String readErrorBody() throws IOException {
            if(status<400)throw new IllegalStateException("response is not an HTTP error");
            if(consumed)throw new IllegalStateException("response body already consumed");
            consumed=true;
            if(body==null){close();return "";}
            try {
                ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buffer=new byte[8192]; int n;
                while((n=body.read(buffer))!=-1) { if(out.size()+n>1024*1024)throw new IOException("WorkBuddy error response too large"); out.write(buffer,0,n); }
                return new String(out.toByteArray(),StandardCharsets.UTF_8);
            } catch(IOException failure) {
                throw new NoReplayException("failed while reading a known WorkBuddy HTTP error response",failure,false);
            } finally { close(); }
        }
        /** Aggregates the upstream native SSE into one Anthropic Messages JSON object. */
        public JSONObject aggregate() throws IOException {
            if (status<200 || status>=300) throw new IllegalStateException("cannot aggregate an unsuccessful HTTP response");
            if (streaming) throw new IllegalStateException("streaming response cannot be aggregated");
            if (consumed) throw new IllegalStateException("response body already consumed");
            consumed=true;
            if (body==null) { close(); throw new NoReplayException("upstream response body is empty",null,false); }
            try { return WorkBuddyNativeAggregator.aggregate(body); }
            catch(IOException malformed) { throw new NoReplayException("WorkBuddy response was received but SSE aggregation failed",malformed,false); }
            finally { close(); }
        }
        @Override public void close() throws IOException {
            if (closed) return; closed=true;
            IOException failure=null;
            if(body!=null)try{body.close();}catch(IOException e){failure=e;}
            connection.disconnect();
            if(failure!=null)throw new NoReplayException("WorkBuddy response cleanup failed after dispatch",failure,false);
        }
    }

    private WorkBuddyNativeTransport() {}

    public static Response execute(Request request) throws IOException {
        return execute(request, new ConnectionFactory() {
            @Override public HttpURLConnection open(String url) throws IOException {
                return (HttpURLConnection)new URL(url).openConnection();
            }
        });
    }

    /** Injectable connection factory keeps all transport behavior testable without network access. */
    public static Response execute(Request request, ConnectionFactory factory) throws IOException {
        if(request==null || factory==null)throw new IllegalArgumentException("request and connection factory are required");
        if(request.body==null)throw new IllegalArgumentException("request body is required");
        if(!WorkBuddyNativePolicy.useNative(request.provider,request.model))
            throw new IllegalArgumentException("request is not a domestic WorkBuddy Claude model");
        final JSONObject outbound=prepareOutbound(request);
        // Exactly one POST per logical request. See the class comment: a signature-refusal resend
        // cannot be made safe from inside the transport while BeforeDispatch owns the single
        // outbound admission.
        return attempt(request,factory,outbound);
    }

    /** Model selection, DSH and catalog defaults, applied once before the single POST. */
    private static JSONObject prepareOutbound(Request request) throws IOException {
        try {
            JSONObject input=new JSONObject(request.body.toString());
            input.put("model",request.model);
            JSONObject prepared=new JSONObject(input.toString());
            if(request.hideDsh) {
                WorkBuddyHarnessPolicy.Report report=WorkBuddyHarnessPolicy.apply(prepared);
                JSONArray before=input.optJSONArray("messages"), after=prepared.optJSONArray("messages");
                for(int i=0;before!=null && i<before.length();i++) {
                    JSONObject message=before.optJSONObject(i);
                    if(message!=null && protectedMessage(message)
                            && !message.toString().equals(after.optJSONObject(i).toString()))
                        throw new IllegalArgumentException("DSH rewrite would modify protected history");
                }
                if(request.dshReport!=null) { request.dshReport.messages=report.messages; request.dshReport.descriptions=report.descriptions; }
            }
            JSONObject outbound=WorkBuddyNativePolicy.prepare(prepared,false,null,request.modelMetadata);
            outbound.put("model",request.model);
            return outbound;
        } catch(Exception invalid) { throw new InvalidRequestException("invalid WorkBuddy native request",invalid); }
    }

    /** One physical POST; the caller's BeforeDispatch hook owns outbound admission for it. */
    private static Response attempt(Request request, ConnectionFactory factory, JSONObject outbound)
            throws IOException {
        Map<String,String> auth=WorkBuddyPolicy.nativeHeaders(request.provider,WorkBuddyNativePolicy.URL,
                request.credential,request.account,request.session,outbound);
        if(auth.isEmpty())throw new IOException("WorkBuddy native endpoint or credentials rejected locally");
        final HttpURLConnection connection;
        try { connection=factory.open(WorkBuddyNativePolicy.URL); }
        catch(IOException openFailure) { throw new ConnectException("WorkBuddy native connection could not be opened",openFailure); }
        if(connection==null)throw new IOException("connection factory returned null");
        boolean outputOpened=false;
        boolean statusKnown=false;
        boolean beforeDispatchStarted=false;
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            if(request.connectTimeoutMillis>0)connection.setConnectTimeout(request.connectTimeoutMillis);
            if(request.readTimeoutMillis>0)connection.setReadTimeout(request.readTimeoutMillis);
            connection.setRequestProperty("Content-Type","application/json");
            for(Map.Entry<String,String> h:auth.entrySet())connection.setRequestProperty(h.getKey(),h.getValue());
            // Compatibility values restyle the static fingerprint only; identity, conversation and
            // the client's own Anthropic protocol headers are already set and stay untouched.
            applyRuleHeaders(connection,request.ruleHeaders);
            for(Map.Entry<String,String> h:request.protocolHeaders.entrySet()) {
                if(h.getValue()==null || h.getValue().length()>2048
                        || h.getValue().indexOf('\r')>=0 || h.getValue().indexOf('\n')>=0)
                    throw new IOException("unsupported Anthropic protocol header");
                if("anthropic-beta".equalsIgnoreCase(h.getKey())) connection.setRequestProperty("anthropic-beta",h.getValue());
                else if("anthropic-version".equalsIgnoreCase(h.getKey()) && "2023-06-01".equals(h.getValue()))
                    connection.setRequestProperty("anthropic-version",h.getValue());
                else throw new IOException("unsupported Anthropic protocol header");
            }
            byte[] bytes=outbound.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            if(request.beforeDispatch!=null) {
                beforeDispatchStarted=true;
                request.beforeDispatch.run(connection);
            }
            final OutputStream out;
            try { outputOpened=true; out=connection.getOutputStream(); }
            catch(IOException failure) { throw new OutcomeUnknownException("WorkBuddy request outcome unknown while opening request body",failure); }
            try {
                out.write(bytes); out.flush(); out.close();
            } catch(IOException failure) {
                try { out.close(); } catch(IOException ignored) { }
                throw new OutcomeUnknownException("WorkBuddy request outcome unknown while sending request body",failure);
            }
            final int status;
            try { status=connection.getResponseCode(); statusKnown=true; }
            catch(IOException failure) { throw new OutcomeUnknownException("WorkBuddy request outcome unknown while reading response status",failure); }
            InputStream responseBody;
            try { responseBody=status>=400?connection.getErrorStream():connection.getInputStream(); }
            catch(IOException failure) {
                responseBody=status>=300?connection.getErrorStream():null;
                if(status>=200 && status<300 && responseBody==null)
                    throw new OutcomeUnknownException("WorkBuddy request was accepted but its response body could not be opened",failure);
            }
            if(status>=200 && status<300) {
                String contentType=connection.getContentType();
                if(contentType==null || !contentType.toLowerCase(java.util.Locale.ROOT).contains("text/event-stream")) {
                    if(responseBody!=null)try{responseBody.close();}catch(IOException ignored){}
                    throw new ResponseProtocolException("WorkBuddy returned success without a native SSE body",status,null);
                }
            }
            return new Response(status,copyHeaders(connection),responseBody,connection,request.clientWantsStream);
        } catch(OutcomeUnknownException unknown) {
            connection.disconnect(); throw unknown;
        } catch(IOException failure) {
            connection.disconnect();
            if(beforeDispatchStarted && !outputOpened)throw failure;
            if(outputOpened && !statusKnown)throw new OutcomeUnknownException("WorkBuddy request outcome unknown after request dispatch began",failure);
            if(!outputOpened)throw new ConnectException("WorkBuddy native connection setup failed",failure);
            throw failure;
        } catch(RuntimeException failure) {
            connection.disconnect(); throw failure;
        }
    }

    /**
     * The only names an engine 2 rule may set on this route: exactly the static client
     * fingerprint this transport already sends from {@code WorkBuddyPolicy.nativeHeaders}. A rule
     * may restyle that fingerprint and nothing else. Credential ({@code Authorization},
     * {@code X-API-Key}), account ({@code X-User-Id}, {@code X-Domain}), conversation/turn
     * identity ({@code X-Conversation-*}, {@code X-Request-ID}), framing ({@code Content-Type},
     * {@code Content-Encoding}, {@code Content-Length}) and the client's own Anthropic protocol
     * headers ({@code anthropic-version}, {@code anthropic-beta}) are outside the set, so a rule
     * cannot rename the caller, replace a secret, break SSE, or change the Messages contract even
     * if a snapshot were ever wider than the parser allows.
     *
     * <p>This set and {@code ProviderQuirks.NATIVE_HEADERS} are one contract read twice: the parser
     * refuses a name this filter would drop, and this filter drops a name the parser would refuse,
     * so neither side can grow alone. The {@code X-Stainless-*} entries are literals of the
     * installed client build in {@code WorkBuddyPolicy.endpointHeaders} — including the retry count
     * and timeout it announces — and the transport encoding is still not among them.
     */
    private static final Set<String> RULE_HEADERS = Collections.unmodifiableSet(new LinkedHashSet<>(
            Arrays.asList("user-agent", "x-requested-with", "x-product", "x-ide-type", "x-ide-name",
                    "x-ide-version", "x-agent-intent", "x-agent-purpose", "x-agent-type",
                    "x-agent-runtime", "accept",
                    "x-stainless-lang", "x-stainless-package-version", "x-stainless-os",
                    "x-stainless-arch", "x-stainless-runtime", "x-stainless-runtime-version",
                    "x-stainless-retry-count", "x-stainless-timeout")));

    /** Applied after identity headers, so only the allowlisted static values can be replaced. */
    private static void applyRuleHeaders(HttpURLConnection connection, Map<String,String> headers) {
        if (headers == null || headers.isEmpty()) return;
        for (Map.Entry<String,String> h : headers.entrySet()) {
            String name = h.getKey() == null ? "" : h.getKey().trim();
            String value = h.getValue();
            if (!RULE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) continue;
            if (value == null || value.isEmpty() || value.length() > 256 || !printableAscii(value)) continue;
            connection.setRequestProperty(name, value);
        }
    }

    /** Same value space the rules document itself allows: visible ASCII, no controls, no folds. */
    private static boolean printableAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7e) return false;
        }
        return true;
    }

    private static boolean protectedMessage(JSONObject message) {
        if(message==null)return false;
        if(message.has("signature"))return true;
        com.gaeul.allin.json.JSONArray blocks=message.optJSONArray("content");
        for(int i=0;blocks!=null && i<blocks.length();i++) {
            JSONObject block=blocks.optJSONObject(i);
            if(block!=null && (block.has("signature") || "redacted_thinking".equals(block.optString("type"))))return true;
        }
        return false;
    }
    private static Map<String,String> copyHeaders(HttpURLConnection connection) {
        Map<String,String> out=new LinkedHashMap<>();
        Map<String,java.util.List<String>> source=connection.getHeaderFields();
        if(source==null)return out;
        for(Map.Entry<String,java.util.List<String>> e:source.entrySet()) {
            if(e.getKey()==null || e.getValue()==null || e.getValue().isEmpty())continue;
            out.put(e.getKey(),join(e.getValue()));
        }
        return out;
    }
    private static String join(java.util.List<String> values) {
        StringBuilder out=new StringBuilder(); for(String v:values){if(out.length()>0)out.append(", ");out.append(v);} return out.toString();
    }
}
