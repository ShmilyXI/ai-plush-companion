package xiaozhi.modules.model.tencentdb;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import xiaozhi.modules.companion.model.vo.CompanionModelTestVO;

class OpenAiEmbeddingConnectionTesterTest {

    @Test
    void testsOpenAiCompatibleEmbeddingWithConfiguredDimensions() throws Exception {
        TencentDbMemoryUpstreamClient upstream = mock(TencentDbMemoryUpstreamClient.class);
        TrackingInputStream responseBody = body("{\"data\":[{\"embedding\":[0.1,0.2]}]}");
        HttpResponse<InputStream> response = response(200, responseBody);
        when(upstream.post(any(), eq("secret"), any(), any())).thenReturn(response);
        OpenAiEmbeddingConnectionTester tester = new OpenAiEmbeddingConnectionTester(upstream);

        CompanionModelTestVO result = tester.test(config());

        assertTrue(result.isSuccess());
        ArgumentCaptor<byte[]> requestBody = ArgumentCaptor.forClass(byte[].class);
        verify(upstream).post(eq(URI.create("https://embedding.example/v1/embeddings")), eq("secret"),
                requestBody.capture(), any());
        JSONObject payload = JSONUtil.parseObj(new String(requestBody.getValue(), UTF_8));
        assertEquals("embedding-3", payload.getStr("model"));
        assertEquals("连接测试", payload.getStr("input"));
        assertEquals(1024, payload.getInt("dimensions"));
        assertTrue(responseBody.closed);
    }

    @Test
    void omitsDimensionsWhenProviderDoesNotSupportThem() throws Exception {
        TencentDbMemoryUpstreamClient upstream = mock(TencentDbMemoryUpstreamClient.class);
        HttpResponse<InputStream> response = response(200, body("{\"data\":[{\"embedding\":[0.1]}]}"));
        when(upstream.post(any(), eq("secret"), any(), any())).thenReturn(response);
        OpenAiEmbeddingConnectionTester tester = new OpenAiEmbeddingConnectionTester(upstream);

        tester.test(config().set("send_dimensions", false));

        ArgumentCaptor<byte[]> requestBody = ArgumentCaptor.forClass(byte[].class);
        verify(upstream).post(any(), eq("secret"), requestBody.capture(), any());
        assertFalse(JSONUtil.parseObj(new String(requestBody.getValue(), UTF_8)).containsKey("dimensions"));
    }

    @Test
    void sanitizesUpstreamFailuresAndInvalidConfiguration() throws Exception {
        TencentDbMemoryUpstreamClient upstream = mock(TencentDbMemoryUpstreamClient.class);
        HttpResponse<InputStream> response = response(401, body("{\"error\":\"credential leaked\"}"));
        when(upstream.post(any(), eq("secret"), any(), any())).thenReturn(response);
        OpenAiEmbeddingConnectionTester tester = new OpenAiEmbeddingConnectionTester(upstream);

        CompanionModelTestVO upstreamFailure = tester.test(config());
        CompanionModelTestVO invalid = tester.test(config().set("api_key", ""));

        assertFalse(upstreamFailure.isSuccess());
        assertEquals("Embedding 连接失败: HTTP 401", upstreamFailure.getMessage());
        assertFalse(upstreamFailure.getMessage().contains("credential leaked"));
        assertEquals("Embedding 模型配置不完整", invalid.getMessage());
    }

    private JSONObject config() {
        return new JSONObject()
                .set("type", "openai")
                .set("base_url", "https://embedding.example/v1")
                .set("api_key", "secret")
                .set("model_name", "embedding-3")
                .set("dimensions", 1024)
                .set("send_dimensions", true);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<InputStream> response(int status, InputStream body) {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    private TrackingInputStream body(String value) {
        return new TrackingInputStream(value.getBytes(UTF_8));
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        private TrackingInputStream(byte[] value) {
            super(value);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
