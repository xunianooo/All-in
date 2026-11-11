package com.gaeul.allin;

import java.io.IOException;
import java.net.HttpURLConnection;

/** Provider-local seam for GatewayServer; never changes transport behavior for other providers. */
public final class WorkBuddyNativeGatewayTransport {
    public interface ConnectionFactory { HttpURLConnection open(String url) throws IOException; }
    private WorkBuddyNativeGatewayTransport() {}
    public static WorkBuddyNativeTransport.Response execute(WorkBuddyNativeTransport.Request request,
            ConnectionFactory factory) throws IOException {
        return WorkBuddyNativeTransport.execute(request, factory::open);
    }
    public static HttpURLConnection connection(WorkBuddyNativeTransport.Response response) {
        return response==null?null:response.connection();
    }
}
