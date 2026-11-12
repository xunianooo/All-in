package com.gaeul.allin;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 访问本机网关（127.0.0.1:3425）的统一通道。
 *
 * 为什么不用 HttpURLConnection：
 * 本机（Android 16）上系统判定 cleartext 全禁（NetworkSecurityPolicy 恒为 false，
 * manifest 的 usesCleartextTraffic 也被忽略），任何 http:// 请求都会抛
 * "Cleartext HTTP traffic to 127.0.0.1 not permitted"。
 * 原先 LoginActivity 里 11 处 HttpURLConnection 全部因此失败，
 * 表现为"点登录就提示连接网关失败"，所有网页登录通道都进不去。
 *
 * raw socket 不受该策略约束，也不需要 CORS / 代理。
 */
final class LocalBridge {

    static final String HOST = "127.0.0.1";
    static final int PORT = 3425;

    private LocalBridge() {
    }

    /** 网关当前是否在监听 */
    static boolean isGatewayUp() {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(HOST, PORT), 400);
            return true;
        } catch (Throwable e) {
            return false;
        } finally {
            closeQuietly(s);
        }
    }

    static String get(String pathAndQuery) {
        return request("GET", pathAndQuery, null, 15000);
    }

    static String post(String pathAndQuery, String jsonBody) {
        return request("POST", pathAndQuery, jsonBody, 20000);
    }

    /**
     * 发一个请求并返回响应正文（不解析状态码）。
     * 失败返回 null —— 调用方原本的 try/catch 语义保持不变。
     */
    static final class Result {
        final int status; final String body,transportError;
        Result(int status,String body,String error){this.status=status;this.body=body;this.transportError=error;}
        static Result failure(String kind){return new Result(0,"",kind);}
        boolean accountSaved() {
            if (status < 200 || status >= 300 || !transportError.isEmpty()) return false;
            try {
                com.gaeul.allin.json.JSONTokener parser = new com.gaeul.allin.json.JSONTokener(body);
                Object parsed = parser.nextValue();
                if (!(parsed instanceof com.gaeul.allin.json.JSONObject) || parser.nextClean() != 0) return false;
                com.gaeul.allin.json.JSONObject value = (com.gaeul.allin.json.JSONObject) parsed;
                Object id = value.opt("id");
                if (!Boolean.TRUE.equals(value.opt("ok")) || !(id instanceof String)
                        || ((String) id).trim().isEmpty() || ((String) id).length() > 512) return false;
                for (char c : ((String) id).toCharArray()) if (c < 32 || c == 127) return false;
                return true;
            } catch (Exception bad) { return false; }
        }
        String accountFailureMessage() {
            // Never echo an authentication response, token, or transport exception text into the UI.
            if (!transportError.isEmpty()) return "未收到完整保存确认，请检查网关连接后重试";
            if (status == 401 || status == 403) return "本地管理身份校验失败，请返回首页后重新登录";
            if (status == 409) return "账号已存在或正在处理，请检查账号池后重试";
            if (status == 400 || status == 422) return "凭据无效或信息不完整，请重新完成官方授权";
            if (status >= 500) return "网关保存失败，账号尚未确认入池，请重试";
            return "未获得有效入池确认，请检查账号池后重试";
        }
    }
    static Result postResult(String path,String body){return requestResult("POST",path,body,20000);}
    static String request(String method,String path,String body,int timeoutMs){
        Result r=requestResult(method,path,body,timeoutMs);return r.transportError.isEmpty()?r.body:null;
    }
    static Result requestResult(String method, String pathAndQuery, String body, int timeoutMs) {
        Socket socket = null;
        try {
            String m = (method == null || method.isEmpty()) ? "GET" : method.toUpperCase(java.util.Locale.ROOT);
            String path = (pathAndQuery == null || pathAndQuery.isEmpty()) ? "/" : pathAndQuery;
            if (path.charAt(0) != '/') path = "/" + path;
            byte[] payload = (body == null) ? new byte[0] : body.getBytes("UTF-8");

            socket = new Socket();
            socket.connect(new InetSocketAddress(HOST, PORT), 4000);
            socket.setSoTimeout(Math.max(1000, timeoutMs));

            StringBuilder head = new StringBuilder();
            head.append(m).append(' ').append(path).append(" HTTP/1.1\r\n");
            head.append("Host: ").append(HOST).append(':').append(PORT).append("\r\n");
            head.append("Origin: null\r\n");
            head.append("Connection: close\r\n");
            head.append("X-All-In-Bridge: 1\r\n");

            // 带上网关口令，等价于原来 openAuthed 里的 Authorization
            String management = GatewayManagementCredential.get();
            if (!management.isEmpty()) head.append("X-All-In-Management: ").append(management).append("\r\n");
            String key = GatewayAuthKeyHolder.get();
            if (key != null && !key.isEmpty()) {
                head.append("Authorization: Bearer ").append(key).append("\r\n");
            }
            if (payload.length > 0) {
                head.append("Content-Type: application/json; charset=utf-8\r\n");
            }
            head.append("Content-Length: ").append(payload.length).append("\r\n\r\n");

            OutputStream os = socket.getOutputStream();
            os.write(head.toString().getBytes("UTF-8"));
            if (payload.length > 0) os.write(payload);
            os.flush();

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            InputStream is = socket.getInputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = is.read(tmp)) > 0) {
                if(buf.size()+n>8*1024*1024)return Result.failure("response_too_large");
                buf.write(tmp,0,n);
            }
            byte[] all = buf.toByteArray();

            // 拆 header / body（按 ISO-8859-1 处理，避免多字节字符破坏偏移）
            String raw = new String(all, "ISO-8859-1");
            int split = raw.indexOf("\r\n\r\n");
            if(split<0)return Result.failure("invalid_response");
            String headerPart = raw.substring(0, split);
            byte[] bodyBytes = new byte[all.length - (split + 4)];
            System.arraycopy(all, split + 4, bodyBytes, 0, bodyBytes.length);

            String crlf=new String(new char[]{13,10});
            String[] statusLine=headerPart.split(crlf,2)[0].split(" ",3);
            if(statusLine.length<2 || !statusLine[0].matches("HTTP/1[.][01]"))return Result.failure("invalid_status");
            int status=Integer.parseInt(statusLine[1]);
            if(status<100 || status>599)return Result.failure("invalid_status");
            if(headerPart.toLowerCase(java.util.Locale.ROOT).contains("transfer-encoding: chunked"))bodyBytes=dechunk(bodyBytes);
            else {
                long declared=-1;
                for(String line:headerPart.split(crlf))if(line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")){
                    if(declared>=0)return Result.failure("invalid_response");declared=Long.parseLong(line.substring(15).trim());
                    if(declared<0 || declared!=bodyBytes.length)return Result.failure("incomplete_response");
                }
            }
            return new Result(status,new String(bodyBytes,"UTF-8"),"");
        } catch (Throwable e) {
            return Result.failure(e.getClass().getSimpleName());
        } finally {
            closeQuietly(socket);
        }
    }

    /**
     * 流式请求：边收边回调，不缓冲整包。
     *
     * 面板在 WebView 里只能走这座桥（明文 HTTP 被系统禁掉），而桥原来是
     * 「收完再一次性返回」，于是面板里任何 SSE 都变成「生成完才出现」。
     * 这里把 body 增量为行回调出去。
     */
    interface StreamCallback {
        boolean onLine(String line);

        void onDone(int status, String error);
    }

    static void stream(String method, String pathAndQuery, String body, int timeoutMs, StreamCallback cb) {
        Socket socket = null;
        int status = -1;
        try {
            String m = (method == null || method.isEmpty()) ? "GET" : method.toUpperCase();
            String path = (pathAndQuery == null || pathAndQuery.isEmpty()) ? "/" : pathAndQuery;
            if (path.charAt(0) != '/') path = "/" + path;
            byte[] payload = (body == null) ? new byte[0] : body.getBytes("UTF-8");

            socket = new Socket();
            socket.connect(new InetSocketAddress(HOST, PORT), 4000);
            socket.setSoTimeout(Math.max(1000, timeoutMs));

            StringBuilder head = new StringBuilder();
            head.append(m).append(' ').append(path).append(" HTTP/1.1\r\n");
            head.append("Host: ").append(HOST).append(':').append(PORT).append("\r\n");
            head.append("Origin: null\r\n");
            head.append("Connection: close\r\n");
            head.append("X-All-In-Bridge: 1\r\n");
            head.append("Accept: text/event-stream\r\n");
            String management = GatewayManagementCredential.get();
            if (!management.isEmpty()) head.append("X-All-In-Management: ").append(management).append("\r\n");
            String key = GatewayAuthKeyHolder.get();
            if (key != null && !key.isEmpty()) {
                head.append("Authorization: Bearer ").append(key).append("\r\n");
            }
            if (payload.length > 0) {
                head.append("Content-Type: application/json; charset=utf-8\r\n");
            }
            head.append("Content-Length: ").append(payload.length).append("\r\n\r\n");

            OutputStream os = socket.getOutputStream();
            os.write(head.toString().getBytes("UTF-8"));
            if (payload.length > 0) os.write(payload);
            os.flush();

            InputStream raw = socket.getInputStream();

            // ---- 读响应头（逐字节，避免吃掉 body 首块）
            java.io.ByteArrayOutputStream hb = new java.io.ByteArrayOutputStream();
            int a = -1, b2 = -1, c2 = -1, d;
            while ((d = raw.read()) != -1) {
                hb.write(d);
                if (a == '\r' && b2 == '\n' && c2 == '\r' && d == '\n') break;
                a = b2; b2 = c2; c2 = d;
            }
            String headerPart = new String(hb.toByteArray(), "ISO-8859-1");
            String lower = headerPart.toLowerCase();
            try {
                int sp = headerPart.indexOf(' ');
                if (sp > 0) status = Integer.parseInt(headerPart.substring(sp + 1, sp + 4).trim());
            } catch (Throwable ignored) {
            }

            InputStream bodyStream = lower.contains("transfer-encoding: chunked")
                    ? new ChunkedIn(raw) : raw;

            // ---- 按行回调
            java.io.ByteArrayOutputStream lineBuf = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = bodyStream.read(buf)) > 0) {
                for (int i = 0; i < n; i++) {
                    byte by = buf[i];
                    if (by == '\n') {
                        String line = new String(lineBuf.toByteArray(), "UTF-8");
                        lineBuf.reset();
                        if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                        if (!cb.onLine(line)) {
                            cb.onDone(status, null);
                            return;
                        }
                    } else {
                        lineBuf.write(by);
                    }
                }
            }
            if (lineBuf.size() > 0) {
                String line = new String(lineBuf.toByteArray(), "UTF-8");
                if (!line.isEmpty()) cb.onLine(line);
            }
            cb.onDone(status, null);
        } catch (Throwable e) {
            try {
                cb.onDone(status, e.getClass().getSimpleName() + ": " + e.getMessage());
            } catch (Throwable ignored) {
            }
        } finally {
            closeQuietly(socket);
        }
    }

    /** 增量解 Transfer-Encoding: chunked（只往下游交数据，不含 chunk 头） */
    static final class ChunkedIn extends InputStream {
        private final InputStream in;
        private long remaining = 0;
        private boolean eof = false;

        ChunkedIn(InputStream in) {
            this.in = in;
        }

        private String readSizeLine() throws java.io.IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n') break;
                if (c != '\r') sb.append((char) c);
            }
            return sb.toString().trim();
        }

        private void consumeCrLf() throws java.io.IOException {
            in.mark(2);
            int c1 = in.read();
            if (c1 == '\r') {
                int c2 = in.read();
                if (c2 != '\n') {
                    if (c2 != -1) in.reset();
                }
            } else if (c1 != '\n' && c1 != -1) {
                in.reset();
            }
        }

        private boolean nextChunk() throws java.io.IOException {
            if (eof) return false;
            String s = readSizeLine();
            int semi = s.indexOf(';');
            if (semi >= 0) s = s.substring(0, semi);
            long size;
            try {
                size = Long.parseLong(s.trim(), 16);
            } catch (Throwable e) {
                eof = true;
                return false;
            }
            if (size <= 0) {
                eof = true;
                return false;
            }
            remaining = size;
            return true;
        }

        @Override
        public int read() throws java.io.IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n <= 0 ? -1 : (b[0] & 0xff);
        }

        @Override
        public int read(byte[] b, int off, int len) throws java.io.IOException {
            if (len == 0) return 0;
            while (remaining <= 0) {
                if (!nextChunk()) return -1;
            }
            int want = (int) Math.min(len, remaining);
            int n = in.read(b, off, want);
            if (n < 0) {
                eof = true;
                return -1;
            }
            remaining -= n;
            if (remaining == 0) consumeCrLf();
            return n;
        }

        @Override
        public void close() throws java.io.IOException {
            in.close();
        }
    }

    private static byte[] dechunk(byte[] data) throws java.io.IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();int pos=0;
        while(pos<data.length){
            int nl=indexOfCrLf(data,pos);if(nl<0)throw new java.io.IOException("incomplete chunk");
            String text=new String(data,pos,nl-pos,"US-ASCII").trim();int semi=text.indexOf(';');if(semi>=0)text=text.substring(0,semi);
            int size;try{size=Integer.parseInt(text,16);}catch(Exception bad){throw new java.io.IOException("invalid chunk");}
            if(size<0)throw new java.io.IOException("invalid chunk");pos=nl+2;
            if(size==0){if(indexOfCrLf(data,pos)<0)throw new java.io.IOException("incomplete trailer");return out.toByteArray();}
            if(size>data.length-pos-2 || data[pos+size]!=13 || data[pos+size+1]!=10)throw new java.io.IOException("incomplete chunk");
            out.write(data,pos,size);pos+=size+2;
        }
        throw new java.io.IOException("missing terminal chunk");
    }

    private static int indexOfCrLf(byte[] data, int from) {
        for (int i = from; i + 1 < data.length; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n') return i;
        }
        return -1;
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
