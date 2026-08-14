package xiaozhi.modules.model.tencentdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

import cn.hutool.json.JSONObject;

class TencentDbMemoryModelProxyControllerTest {
    @Test
    void writesJsonAndStreamingResponsesWithoutAsyncDispatch() throws Exception {
        TencentDbMemoryModelProxyService service = mock(TencentDbMemoryModelProxyService.class);
        when(service.chat(org.mockito.ArgumentMatchers.any()))
                .thenReturn(response("text/event-stream", "data: first\n\n"));
        when(service.embeddings(org.mockito.ArgumentMatchers.any()))
                .thenReturn(response("application/json", "{\"data\":[]}"));
        TencentDbMemoryModelProxyController controller = new TencentDbMemoryModelProxyController(service);
        MockHttpServletResponse chat = new MockHttpServletResponse();
        MockHttpServletResponse embeddings = new MockHttpServletResponse();

        controller.chat(new JSONObject(), chat);
        controller.embeddings(new JSONObject(), embeddings);

        assertEquals(200, chat.getStatus());
        assertEquals(MediaType.TEXT_EVENT_STREAM_VALUE, chat.getContentType());
        assertEquals("data: first\n\n", chat.getContentAsString());
        assertEquals(200, embeddings.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_VALUE, embeddings.getContentType());
        assertEquals("{\"data\":[]}", embeddings.getContentAsString());
    }

    private TencentDbMemoryProxyResponse response(String contentType, String body) {
        return new TencentDbMemoryProxyResponse(
                200,
                contentType,
                Map.of(),
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }
}
