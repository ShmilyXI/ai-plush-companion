package xiaozhi.modules.model.tencentdb;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.stereotype.Component;

@Component
public class TencentDbMemoryUpstreamClient {
    private final HttpClient client;

    public TencentDbMemoryUpstreamClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    TencentDbMemoryUpstreamClient(HttpClient client) {
        this.client = client;
    }

    public HttpResponse<InputStream> post(URI target, String apiKey, byte[] json, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }
}
