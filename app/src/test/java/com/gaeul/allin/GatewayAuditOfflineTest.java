package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class GatewayAuditOfflineTest {
    public static void main(String[] args) throws Exception {
        try (ResponsesGatewayOfflineTest.Mock mock = new ResponsesGatewayOfflineTest.Mock("{\"error\":{\"message\":\"bad input\"}}", false, 422)) {
            GatewayServer g = ResponsesGatewayOfflineTest.gateway(mock.server.getLocalPort(), "responses");
            NanoHTTPD.Response r = g.serve(ResponsesGatewayOfflineTest.session("{\"model\":\"mock/m\",\"input\":\"hello\"}", "secret"));
            ResponsesGatewayOfflineTest.check(r.getStatus().getRequestStatus() == 422, "native Responses must preserve upstream HTTP 422, got " + r.getStatus().getRequestStatus());
        }
        String terminal = "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n";
        try (ResponsesGatewayOfflineTest.Mock mock = new ResponsesGatewayOfflineTest.Mock(terminal, true)) {
            GatewayServer g = ResponsesGatewayOfflineTest.gateway(mock.server.getLocalPort(), "responses");
            NanoHTTPD.Response r = g.serve(ResponsesGatewayOfflineTest.session("{\"model\":\"mock/m\",\"input\":\"hello\",\"stream\":true}", "secret"));
            java.lang.reflect.Field f = GatewayServer.class.getDeclaredField("U_ROW"); f.setAccessible(true);
            ResponsesGatewayOfflineTest.check(((ThreadLocal<?>) f.get(null)).get() == null, "stream handoff retains usage ThreadLocal on request thread");
            ResponsesGatewayOfflineTest.read(r.getData()); r.getData().close();
        }
        System.out.println("GatewayAuditOfflineTest passed");
    }
}
