package xiaozhi.modules.model.tencentdb;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public record TencentDbMemoryProxyResponse(
        int status,
        String contentType,
        Map<String, String> headers,
        InputStream body) implements AutoCloseable {
    @Override
    public void close() throws IOException {
        body.close();
    }
}
