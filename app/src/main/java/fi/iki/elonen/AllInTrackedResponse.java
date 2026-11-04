package fi.iki.elonen;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** Delegates framing unchanged; releases admission only after the complete HTTP send exits. */
public final class AllInTrackedResponse extends NanoHTTPD.Response {
    private final NanoHTTPD.Response delegate;
    private final Runnable completed;
    private final AtomicBoolean released = new AtomicBoolean();
    public AllInTrackedResponse(NanoHTTPD.Response delegate, Runnable completed) {
        super(delegate.getStatus(), delegate.getMimeType(), null, 0);
        this.delegate = delegate;
        this.completed = completed;
    }
    private void release() { if (released.compareAndSet(false, true)) completed.run(); }
    /** Optional body notification after HTTP framing and the final flush. */
    public interface WriteCompletion { void onWriteComplete(boolean success); }
    @Override protected void send(final OutputStream output) {
        final boolean[] writeFailed = {false};
        boolean returned = false;
        OutputStream checked = new OutputStream() {
            @Override public void write(int b) throws IOException {
                try { output.write(b); } catch (IOException e) { writeFailed[0] = true; throw e; }
            }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                try { output.write(b, off, len); } catch (IOException e) { writeFailed[0] = true; throw e; }
            }
            @Override public void flush() throws IOException {
                try { output.flush(); } catch (IOException e) { writeFailed[0] = true; throw e; }
            }
        };
        try { delegate.send(checked); returned = true; }
        finally {
            try {
                InputStream body = delegate.getData();
                if (body instanceof WriteCompletion) ((WriteCompletion) body).onWriteComplete(returned && !writeFailed[0]);
            } finally { try { delegate.close(); } catch (IOException ignored) { } finally { release(); } }
        }
    }
    @Override public void close() throws IOException {
        try {
            if (!released.get() && delegate.getData() instanceof WriteCompletion)
                ((WriteCompletion) delegate.getData()).onWriteComplete(false);
        } finally { try { delegate.close(); } finally { release(); } }
    }
    @Override public void addHeader(String name, String value) { delegate.addHeader(name, value); }
    @Override public void closeConnection(boolean close) { delegate.closeConnection(close); }
    @Override public boolean isCloseConnection() { return delegate.isCloseConnection(); }
    @Override public InputStream getData() { return delegate.getData(); }
    @Override public String getHeader(String name) { return delegate.getHeader(name); }
    @Override public String getMimeType() { return delegate.getMimeType(); }
    @Override public NanoHTTPD.Method getRequestMethod() { return delegate.getRequestMethod(); }
    @Override public IStatus getStatus() { return delegate.getStatus(); }
    @Override public void setGzipEncoding(boolean gzip) { delegate.setGzipEncoding(gzip); }
    @Override public void setKeepAlive(boolean keepAlive) { delegate.setKeepAlive(keepAlive); }
    @Override public void setChunkedTransfer(boolean chunked) { delegate.setChunkedTransfer(chunked); }
    @Override public void setData(InputStream data) { delegate.setData(data); }
    @Override public void setMimeType(String mime) { delegate.setMimeType(mime); }
    @Override public void setRequestMethod(NanoHTTPD.Method method) { delegate.setRequestMethod(method); }
    @Override public void setStatus(IStatus status) { delegate.setStatus(status); }
}
