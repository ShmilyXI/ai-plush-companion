package zixuan.modules.model.tencentdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONObject;

class TencentDbMemoryModelProxyServiceImplTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shapesChatRequestAndOverridesModelAndAuthorization() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<JSONObject> request = new AtomicReference<>();
        URI baseUrl = startServer(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            path.set(exchange.getRequestURI().getPath());
            request.set(JSONUtil.parseObj(exchange.getRequestBody().readAllBytes()));
            byte[] body = "{\"id\":\"chat-1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        TencentDbMemoryModelProxyServiceImpl service = service();
        JSONObject input = JSONUtil.parseObj("""
                {"model":"attacker","messages":[{"role":"user","content":"hello"}],
                 "temperature":0.2,"max_tokens":100,"tools":[],"tool_choice":"auto",
                 "response_format":{"type":"json_object"},"stream":false,"stop":null,
                 "seed":7,"top_p":0.8,"frequency_penalty":0.1,"presence_penalty":0.2,
                 "base_url":"http://evil","api_key":"evil","evil":"field"}
                """);

        try (TencentDbMemoryProxyResponse response = service.chat(input, settings(baseUrl))) {
            assertEquals(200, response.status());
            assertEquals("application/json", response.contentType());
            assertEquals("{\"id\":\"chat-1\"}", new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
        }

        assertEquals("Bearer llm-secret", authorization.get());
        assertEquals("/v1/chat/completions", path.get());
        assertEquals("configured-memory-llm", request.get().getStr("model"));
        assertFalse(request.get().containsKey("base_url"));
        assertFalse(request.get().containsKey("api_key"));
        assertFalse(request.get().containsKey("evil"));
        assertTrue(request.get().containsKey("messages"));
        assertTrue(request.get().containsKey("response_format"));
    }

    @Test
    void shapesEmbeddingDimensionsAccordingToSavedSettings() throws Exception {
        AtomicReference<JSONObject> request = new AtomicReference<>();
        URI baseUrl = startServer(exchange -> {
            request.set(JSONUtil.parseObj(exchange.getRequestBody().readAllBytes()));
            byte[] body = "{\"data\":[{\"embedding\":[0.1,0.2]}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        TencentDbMemoryModelSettings settings = settings(baseUrl);

        try (TencentDbMemoryProxyResponse response = service().embeddings(
                JSONUtil.parseObj("{\"model\":\"bad\",\"input\":[\"hello\"],\"dimensions\":9,\"evil\":1}"),
                settings)) {
            assertEquals(200, response.status());
        }

        assertEquals("configured-embedding", request.get().getStr("model"));
        assertEquals(1024, request.get().getInt("dimensions"));
        assertFalse(request.get().containsKey("evil"));
    }

    @Test
    void removesInboundEmbeddingDimensionsWhenDisabled() throws Exception {
        AtomicReference<JSONObject> request = new AtomicReference<>();
        URI baseUrl = startServer(exchange -> {
            request.set(JSONUtil.parseObj(exchange.getRequestBody().readAllBytes()));
            byte[] body = "{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        TencentDbMemoryModelSettings enabled = settings(baseUrl);
        TencentDbMemoryModelSettings disabled = new TencentDbMemoryModelSettings(
                enabled.llmBaseUrl(), enabled.llmApiKey(), enabled.llmModel(),
                enabled.embeddingBaseUrl(), enabled.embeddingApiKey(), enabled.embeddingModel(),
                enabled.embeddingDimensions(), false);

        try (TencentDbMemoryProxyResponse ignored = service().embeddings(
                JSONUtil.parseObj("{\"input\":[\"hello\"],\"dimensions\":9}"), disabled)) {
            assertFalse(request.get().containsKey("dimensions"));
        }
    }

    @Test
    void exposesFirstSseChunkBeforeUpstreamFinishes() throws Exception {
        CountDownLatch firstChunkSent = new CountDownLatch(1);
        CountDownLatch releaseSecondChunk = new CountDownLatch(1);
        URI baseUrl = startServer(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("data: first\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            firstChunkSent.countDown();
            try {
                releaseSecondChunk.await(5, TimeUnit.SECONDS);
                exchange.getResponseBody().write("data: second\n\n".getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });

        try (TencentDbMemoryProxyResponse response = service().chat(
                JSONUtil.parseObj("{\"messages\":[],\"stream\":true}"), settings(baseUrl))) {
            assertTrue(firstChunkSent.await(1, TimeUnit.SECONDS));
            assertEquals("text/event-stream", response.contentType());
            assertEquals("data: first\n\n", new String(response.body().readNBytes(13), StandardCharsets.UTF_8));
            releaseSecondChunk.countDown();
            assertEquals("data: second\n\n", new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            releaseSecondChunk.countDown();
        }
    }

    @Test
    void rejectsMissingEmbeddingInputBeforeNetworkCall() {
        IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service().embeddings(new JSONObject(), settings(URI.create("https://example.com/v1"))));

        assertEquals("Embedding input 不能为空", error.getMessage());
    }

    @Test
    void convertsUpstreamFailureToSanitizedBadGateway() throws Exception {
        URI baseUrl = startServer(exchange -> {
            byte[] body = "secret upstream error".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        try (TencentDbMemoryProxyResponse response = service().chat(
                JSONUtil.parseObj("{\"messages\":[]}"), settings(baseUrl))) {
            assertEquals(502, response.status());
            String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(body.contains("401"));
            assertFalse(body.contains("secret upstream error"));
        }
    }

    @Test
    void readsFreshChatModelSettingsForEveryRequest() throws Exception {
        List<JSONObject> requests = new CopyOnWriteArrayList<>();
        URI baseUrl = startServer(exchange -> {
            requests.add(JSONUtil.parseObj(exchange.getRequestBody().readAllBytes()));
            byte[] body = "{\"id\":\"chat\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        TencentDbMemoryModelSettingsService settingsService = mock(TencentDbMemoryModelSettingsService.class);
        when(settingsService.requireEnabled())
                .thenReturn(settings(baseUrl, "memory-a", "embedding-a", 384))
                .thenReturn(settings(baseUrl, "memory-b", "embedding-b", 768));
        TencentDbMemoryModelProxyServiceImpl service = service(settingsService);

        try (TencentDbMemoryProxyResponse ignored = service.chat(JSONUtil.parseObj("{\"messages\":[]}"))) {
            assertEquals(200, ignored.status());
        }
        try (TencentDbMemoryProxyResponse ignored = service.chat(JSONUtil.parseObj("{\"messages\":[]}"))) {
            assertEquals(200, ignored.status());
        }

        assertEquals("memory-a", requests.get(0).getStr("model"));
        assertEquals("memory-b", requests.get(1).getStr("model"));
    }

    @Test
    void readsFreshEmbeddingModelAndDimensionsForEveryRequest() throws Exception {
        List<JSONObject> requests = new CopyOnWriteArrayList<>();
        URI baseUrl = startServer(exchange -> {
            requests.add(JSONUtil.parseObj(exchange.getRequestBody().readAllBytes()));
            byte[] body = "{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        TencentDbMemoryModelSettingsService settingsService = mock(TencentDbMemoryModelSettingsService.class);
        when(settingsService.requireEnabled())
                .thenReturn(settings(baseUrl, "memory-a", "embedding-a", 384))
                .thenReturn(settings(baseUrl, "memory-b", "embedding-b", 768));
        TencentDbMemoryModelProxyServiceImpl service = service(settingsService);

        try (TencentDbMemoryProxyResponse ignored = service.embeddings(
                JSONUtil.parseObj("{\"input\":[\"hello\"]}"))) {
            assertEquals(200, ignored.status());
        }
        try (TencentDbMemoryProxyResponse ignored = service.embeddings(
                JSONUtil.parseObj("{\"input\":[\"hello\"]}"))) {
            assertEquals(200, ignored.status());
        }

        assertEquals("embedding-a", requests.get(0).getStr("model"));
        assertEquals(384, requests.get(0).getInt("dimensions"));
        assertEquals("embedding-b", requests.get(1).getStr("model"));
        assertEquals(768, requests.get(1).getInt("dimensions"));
    }

    private TencentDbMemoryModelProxyServiceImpl service() {
        return service(mock(TencentDbMemoryModelSettingsService.class));
    }

    private TencentDbMemoryModelProxyServiceImpl service(TencentDbMemoryModelSettingsService settingsService) {
        return new TencentDbMemoryModelProxyServiceImpl(
                settingsService, new TencentDbMemoryUpstreamClient(HttpClient.newHttpClient()));
    }

    private TencentDbMemoryModelSettings settings(URI baseUrl) {
        return settings(baseUrl, "configured-memory-llm", "configured-embedding", 1024);
    }

    private TencentDbMemoryModelSettings settings(URI baseUrl, String llmModel, String embeddingModel,
            int embeddingDimensions) {
        return new TencentDbMemoryModelSettings(
                baseUrl, "llm-secret", llmModel,
                baseUrl, "embedding-secret", embeddingModel, embeddingDimensions, true);
    }

    private URI startServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", handler);
        server.createContext("/v1/embeddings", handler);
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }
}
