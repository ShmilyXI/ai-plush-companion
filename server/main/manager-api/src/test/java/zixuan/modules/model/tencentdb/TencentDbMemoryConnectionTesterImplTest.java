package zixuan.modules.model.tencentdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.sun.net.httpserver.HttpServer;

import cn.hutool.json.JSONObject;
import zixuan.modules.companion.model.vo.CompanionModelTestVO;

class TencentDbMemoryConnectionTesterImplTest {
    private HttpServer core;

    @AfterEach
    void stopCore() {
        if (core != null) core.stop(0);
    }

    @Test
    void reportsAllThreeStagesInOrder() throws Exception {
        TencentDbMemoryModelProxyServiceImpl proxy = mock(TencentDbMemoryModelProxyServiceImpl.class);
        TencentDbMemoryRuntimeSettings runtime = runtime(startCore(200));
        when(proxy.chat(any(JSONObject.class), eq(runtime.modelSettings())))
                .thenReturn(response(200, "{\"choices\":[{}]}"));
        when(proxy.embeddings(any(JSONObject.class), eq(runtime.modelSettings())))
                .thenReturn(response(200, embedding(1024)));

        CompanionModelTestVO result = tester(proxy).test(runtime);

        assertEquals(true, result.isSuccess());
        assertEquals("MemoryCore 可用，记忆 LLM 可用，Embedding 可用", result.getMessage());
    }

    @Test
    void labelsMemoryCoreFailureWithoutCallingModels() throws Exception {
        TencentDbMemoryModelProxyServiceImpl proxy = mock(TencentDbMemoryModelProxyServiceImpl.class);

        CompanionModelTestVO result = tester(proxy).test(runtime(startCore(503)));

        assertEquals(false, result.isSuccess());
        assertEquals("MemoryCore 连接失败: HTTP 503", result.getMessage());
    }

    @Test
    void labelsLlmAndEmbeddingFailures() throws Exception {
        TencentDbMemoryModelProxyServiceImpl proxy = mock(TencentDbMemoryModelProxyServiceImpl.class);
        TencentDbMemoryRuntimeSettings runtime = runtime(startCore(200));
        when(proxy.chat(any(JSONObject.class), eq(runtime.modelSettings())))
                .thenReturn(response(502, "{\"error\":\"上游模型服务返回 HTTP 401\"}"));

        assertEquals("记忆 LLM 连接失败: HTTP 401", tester(proxy).test(runtime).getMessage());

        when(proxy.chat(any(JSONObject.class), eq(runtime.modelSettings())))
                .thenReturn(response(200, "{\"choices\":[{}]}"));
        when(proxy.embeddings(any(JSONObject.class), eq(runtime.modelSettings())))
                .thenReturn(response(200, embedding(1536)));

        assertEquals("Embedding 连接失败: 向量维度不符，期望 1024，实际 1536",
                tester(proxy).test(runtime).getMessage());
    }

    @Test
    void springCanConstructTheProductionTester() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(
                    TencentDbMemoryModelProxyServiceImpl.class,
                    () -> mock(TencentDbMemoryModelProxyServiceImpl.class));
            context.register(TencentDbMemoryConnectionTesterImpl.class);

            assertDoesNotThrow(context::refresh);
        }
    }

    private TencentDbMemoryConnectionTesterImpl tester(TencentDbMemoryModelProxyServiceImpl proxy) {
        return new TencentDbMemoryConnectionTesterImpl(proxy, HttpClient.newHttpClient());
    }

    private URI startCore(int status) throws Exception {
        core = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        core.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        core.start();
        return URI.create("http://127.0.0.1:" + core.getAddress().getPort());
    }

    private TencentDbMemoryRuntimeSettings runtime(URI coreUrl) {
        return new TencentDbMemoryRuntimeSettings(
                coreUrl,
                "core-secret",
                new TencentDbMemoryModelSettings(
                        URI.create("https://llm.example/v1"), "llm-secret", "memory-model",
                        URI.create("https://embedding.example/v1"), "embedding-secret", "embedding-model",
                        1024, true));
    }

    private TencentDbMemoryProxyResponse response(int status, String body) {
        return new TencentDbMemoryProxyResponse(
                status, "application/json", Map.of(),
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private String embedding(int dimensions) {
        StringBuilder vector = new StringBuilder();
        for (int index = 0; index < dimensions; index++) {
            if (index > 0) vector.append(',');
            vector.append('0');
        }
        return "{\"data\":[{\"embedding\":[" + vector + "]}]}";
    }
}
