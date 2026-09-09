package com.xjjk.knowledge.document.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PaddleOcrHttpClientTest {

    @Test
    void sendsStableContractAndMapsBlocksWithoutLoggingImagePayload() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/ocr", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("{\"requestId\":\"req-1\",\"rotation\":90,\"blocks\":["
                    + "{\"text\":\"退款规则\",\"confidence\":0.98,"
                    + "\"box\":[[0,0],[10,0],[10,10],[0,10]]}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            OcrProperties properties = new OcrProperties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setLowConfidenceThreshold(0.85);
            PaddleOcrHttpClient client = new PaddleOcrHttpClient(properties);

            OcrResult result = client.recognize("req-1", "ch", new byte[] {1, 2, 3});

            assertThat(requestBody.get()).contains("\"requestId\":\"req-1\"")
                    .contains("\"language\":\"ch\"")
                    .contains("\"imageBase64\":\"AQID\"");
            assertThat(result.rotation()).isEqualTo(90);
            assertThat(result.blocks()).singleElement().satisfies(block -> {
                assertThat(block.text()).isEqualTo("退款规则");
                assertThat(block.confidence()).isEqualTo(0.98);
                assertThat(block.lowConfidence()).isFalse();
            });
        } finally {
            server.stop(0);
        }
    }
}
