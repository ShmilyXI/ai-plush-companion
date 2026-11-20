package zixuan.modules.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import zixuan.common.exception.RenException;

class ModelChannelUpstreamClientImplTest {
    private final ModelChannelUpstreamClientImpl client = new ModelChannelUpstreamClientImpl();
    private HttpServer server;
    private String origin;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void openAiCompatibleListIsFetchedWithBearerKeyAndSorted() {
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/v1/models", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, """
                    {"object":"list","data":[{"id":"deepseek-reasoner"},{"id":"deepseek-chat"},
                     {"id":"deepseek-chat"},{"id":"  "},{"object":"model"}]}
                    """);
        });

        List<String> models = client.listModels("openai", origin + "/v1/", "sk-test-secret");

        assertEquals(List.of("deepseek-chat", "deepseek-reasoner"), models);
        assertEquals("Bearer sk-test-secret", authorization.get());
    }

    @Test
    void ollamaUsesTheNativeTagListWithoutAuthorization() {
        AtomicReference<String> authorization = new AtomicReference<>("unset");
        server.createContext("/api/tags", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"models\":[{\"name\":\"qwen2.5:7b\",\"model\":\"qwen2.5:7b\"}]}");
        });

        assertEquals(List.of("qwen2.5:7b"), client.listModels("ollama", origin + "/v1", "ignored"));
        assertNull(authorization.get());
    }

    @Test
    void failuresBecomeReadableMessagesThatNeverContainTheKey() {
        server.createContext("/denied/models", exchange -> respond(exchange, 401, "{\"error\":\"sk-test-secret\"}"));
        server.createContext("/missing/models", exchange -> respond(exchange, 404, "not found"));
        server.createContext("/broken/models", exchange -> respond(exchange, 200, "<html>oops</html>"));
        server.createContext("/odd/models", exchange -> respond(exchange, 200, "{\"result\":[]}"));

        RenException denied = assertThrows(RenException.class,
                () -> client.listModels("openai", origin + "/denied", "sk-test-secret"));
        assertEquals("密钥无效或没有权限（HTTP 401）", denied.getMsg());
        RenException missing = assertThrows(RenException.class,
                () -> client.listModels("openai", origin + "/missing", "sk-test-secret"));
        assertEquals("这个地址不支持拉取模型列表（HTTP 404），可以手动添加模型", missing.getMsg());
        for (String path : List.of("/broken", "/odd")) {
            RenException invalid = assertThrows(RenException.class,
                    () -> client.listModels("openai", origin + path, "sk-test-secret"));
            assertEquals("服务返回的模型列表格式无法识别", invalid.getMsg());
        }
        for (RenException exception : List.of(denied, missing)) {
            assertFalse(exception.getMsg().contains("sk-test-secret"));
        }
    }

    @Test
    void unreachableAddressAndMalformedKeyAreReportedSafely() {
        server.stop(0);
        RenException unreachable = assertThrows(RenException.class,
                () -> client.listModels("openai", origin + "/v1", "sk-test-secret"));
        assertEquals("连接失败，请检查接口地址", unreachable.getMsg());

        RenException malformed = assertThrows(RenException.class,
                () -> client.listModels("openai", origin + "/v1", "sk-test\nInjected: 1"));
        assertEquals("接口地址或密钥格式无效", malformed.getMsg());
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
