package xiaozhi.modules.companion.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import xiaozhi.modules.companion.model.service.impl.CompanionModelConnectionTesterImpl;
import xiaozhi.modules.companion.model.vo.CompanionModelTestVO;

class CompanionModelConnectionTesterImplTest {

    @Test
    void openAiCompatibleTestRequestsModelsWithBearerCredential() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            CompanionModelTestVO result = new CompanionModelConnectionTesterImpl().test("openai", Map.of(
                    "base_url", baseUrl,
                    "api_key", "test-secret"));

            assertTrue(result.isSuccess());
            assertEquals("/v1/models", path.get());
            assertEquals("Bearer test-secret", authorization.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unsupportedProviderReturnsSafeMessageWithoutMakingARequest() {
        CompanionModelTestVO result = new CompanionModelConnectionTesterImpl().test("silero", Map.of(
                "base_url", "https://private.example/secret-path",
                "api_key", "secret-value"));

        assertFalse(result.isSuccess());
        assertEquals("当前供应器暂不支持自动测试", result.getMessage());
        assertFalse(result.toString().contains("secret-value"));
        assertFalse(result.toString().contains("private.example"));
    }

    @Test
    void httpFailureDoesNotExposeResponseBodyOrCredential() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            byte[] body = "credential=server-secret".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            CompanionModelTestVO result = new CompanionModelConnectionTesterImpl().test("openai", Map.of(
                    "base_url", baseUrl,
                    "api_key", "client-secret"));

            assertFalse(result.isSuccess());
            assertEquals("服务返回 HTTP 500", result.getMessage());
            assertFalse(result.toString().contains("server-secret"));
            assertFalse(result.toString().contains("client-secret"));
        } finally {
            server.stop(0);
        }
    }
}
