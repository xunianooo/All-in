package fi.iki.elonen;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Validates framing before NanoHTTPD can collapse duplicate header fields. */
public final class AllInRawHeaderInputStream extends InputStream {
    private static final int MAX_HEADER = 8192;
    private final InputStream source;
    private final OutputStream response;
    private byte[] header;
    private int headerPosition;
    private long bodyRemaining;
    private boolean closed;

    public AllInRawHeaderInputStream(InputStream source, OutputStream response) {
        this.source = source;
        this.response = response;
    }

    @Override public int read() throws IOException {
        byte[] one = new byte[1];
        int count = read(one, 0, 1);
        return count < 0 ? -1 : one[0] & 255;
    }

    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        if (bytes == null) throw new NullPointerException();
        if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
        if (length == 0) return 0;
        if (closed) return -1;
        if (header != null && headerPosition < header.length) {
            int count = Math.min(length, header.length - headerPosition);
            System.arraycopy(header, headerPosition, bytes, offset, count);
            headerPosition += count;
            return count;
        }
        if (bodyRemaining > 0) {
            int count = source.read(bytes, offset, (int) Math.min((long) length, bodyRemaining));
            if (count < 0) { closed = true; return -1; }
            bodyRemaining -= count;
            return count;
        }
        if (!loadHeader()) return -1;
        return read(bytes, offset, length);
    }

    private boolean loadHeader() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int state = 0;
        while (true) {
            int c = source.read();
            if (c < 0) {
                if (buffer.size() != 0) reject(400);
                closed = true;
                return false;
            }
            buffer.write(c);
            if (buffer.size() > MAX_HEADER) reject(400);
            if (state == 0) state = c == '\r' ? 1 : 0;
            else if (state == 1) state = c == '\n' ? 2 : -1;
            else if (state == 2) state = c == '\r' ? 3 : 0;
            else if (state == 3) { if (c == '\n') break; state = -1; }
            if (state == -1 || (c == '\n' && state != 2)) reject(400);
        }
        byte[] candidate = buffer.toByteArray();
        String text = new String(candidate, StandardCharsets.ISO_8859_1);
        String[] lines = text.substring(0, text.length() - 4).split("\\r\\n", -1);
        if (lines.length == 0 || !validRequestLine(lines[0])) reject(400);
        boolean hasLength = false, hasTransfer = false;
        String transfer = null;
        long parsedLength = 0;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon <= 0) reject(400);
            for (int j = 0; j < colon; j++) if (!token(line.charAt(j))) reject(400);
            for (int j = colon + 1; j < line.length(); j++) {
                char ch = line.charAt(j);
                if ((ch < 32 && ch != '\t') || ch == 127) reject(400);
            }
            String name = line.substring(0, colon);
            String value = line.substring(colon + 1).trim();
            if (name.equalsIgnoreCase("content-length")) {
                if (hasLength || value.isEmpty()) reject(400);
                hasLength = true;
                for (int j = 0; j < value.length(); j++) {
                    char c = value.charAt(j);
                    if (c < '0' || c > '9' || parsedLength > (Long.MAX_VALUE - (c - '0')) / 10) reject(400);
                    parsedLength = parsedLength * 10 + c - '0';
                }
            } else if (name.equalsIgnoreCase("transfer-encoding")) {
                if (hasTransfer) reject(400);
                hasTransfer = true;
                transfer = value;
            }
        }
        if (hasTransfer && hasLength) reject(400);
        if (hasTransfer) reject("chunked".equalsIgnoreCase(transfer) ? 501 : 400);
        header = candidate;
        headerPosition = 0;
        bodyRemaining = parsedLength;
        return true;
    }

    private static boolean validRequestLine(String line) {
        int first = line.indexOf(' '), second = first < 0 ? -1 : line.indexOf(' ', first + 1);
        if (first <= 0 || second <= first + 1 || line.indexOf(' ', second + 1) >= 0) return false;
        for (int i=0; i<first; i++) if (!token(line.charAt(i))) return false;
        for (int i=first+1; i<second; i++) {
            char c=line.charAt(i); if (c<=32 || c>=127) return false;
        }
        String version=line.substring(second+1);
        return "HTTP/1.1".equals(version) || "HTTP/1.0".equals(version);
    }

    private static boolean token(char c) {
        return c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
                || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
    }

    private void reject(int status) throws IOException {
        closed = true;
        String reason = status == 501 ? "Not Implemented" : "Bad Request";
        byte[] message = ("HTTP/1.1 " + status + " " + reason + "\r\nConnection: close\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        response.write(message);
        response.flush();
        throw new IOException("Invalid HTTP request framing");
    }

    @Override public int available() { return 0; }
    @Override public void close() throws IOException { closed = true; source.close(); }
}
