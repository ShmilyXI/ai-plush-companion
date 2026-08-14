package xiaozhi.modules.model.tencentdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import cn.hutool.json.JSONObject;

class TencentDbMemoryModelProxyControllerTest {
    @Test
    void preservesJsonAndStreamingContentTypes() {
        TencentDbMemoryModelProxyService service = mock(TencentDbMemoryModelProxyService.class);
        when(service.chat(org.mockito.ArgumentMatchers.any())).thenReturn(response("text/event-stream"));
        when(service.embeddings(org.mockito.ArgumentMatchers.any())).thenReturn(response("application/json"));
        TencentDbMemoryModelProxyController controller = new TencentDbMemoryModelProxyController(service);

        var chat = controller.chat(new JSONObject());
        var embeddings = controller.embeddings(new JSONObject());

        assertEquals(200, chat.getStatusCode().value());
        assertEquals(MediaType.TEXT_EVENT_STREAM, chat.getHeaders().getContentType());
        assertEquals(MediaType.APPLICATION_JSON, embeddings.getHeaders().getContentType());
    }

    private TencentDbMemoryProxyResponse response(String contentType) {
        return new TencentDbMemoryProxyResponse(
                200,
                contentType,
                Map.of(),
                new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
    }
}
