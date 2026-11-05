package fi.iki.elonen;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** Delegates framing unchanged; releases admission only after the complete HTTP send exits. */
public final class AllInTrackedResponse extends NanoHTTPD.Response {
    private final NanoHTTPD.Response delegate;
    public interface DeliveryObserver { void headers(); void body(); void failed(); }
    private final DeliveryObserver observer;
    private final Runnable completed;
    private final AtomicBoolean released = new AtomicBoolean();
    public AllInTrackedResponse(NanoHTTPD.Response delegate, Runnable completed) {
        this(delegate,completed,null);
    }
    public AllInTrackedResponse(NanoHTTPD.Response delegate,Runnable completed,DeliveryObserver observer) {
        super(delegate.getStatus(), delegate.getMimeType(), null, 0);
        this.observer=observer;
        this.delegate = delegate;
        this.completed = completed;
    }
    private void release() { if (released.compareAndSet(false, true)) completed.run(); }
    /** Optional body notification after HTTP framing and the final flush. */
    public interface WriteCompletion { default void onWriteStart() {} void onWriteComplete(boolean success); }
    @Override protected void send(final OutputStream output) {
        final boolean[] writeFailed = {false};
        boolean returned = false;
        OutputStream checked = new OutputStream() {
            int headerMatch; boolean headersDone;
            private void observed(byte[] bytes,int off,int len) {
                if(observer==null) return;
                for(int i=off;i<off+len;i++) {
                    if(headersDone) { observer.body(); return; }
                    int b=bytes[i]&255;
                    headerMatch=(headerMatch==0 && b==13)?1:(headerMatch==1 && b==10)?2:(headerMatch==2 && b==13)?3:(headerMatch==3 && b==10)?4:0;
                    if(headerMatch==4) { headersDone=true; observer.headers(); }
                }
            }
            @Override public void write(int b) throws IOException {
                try { output.write(b); observed(new byte[]{(byte)b},0,1); } catch (IOException e) { writeFailed[0] = true; if(observer!=null) observer.failed(); throw e; }
            }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                try { output.write(b, off, len); observed(b,off,len); } catch (IOException e) { writeFailed[0] = true; if(observer!=null) observer.failed(); throw e; }
            }
            @Override public void flush() throws IOException {
                try { output.flush(); } catch (IOException e) { writeFailed[0] = true; if(observer!=null) observer.failed(); throw e; }
            }
        };
        try {
            InputStream body = delegate.getData();
            if (body instanceof WriteCompletion) ((WriteCompletion) body).onWriteStart();
            delegate.send(checked); returned = true;
        }
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
