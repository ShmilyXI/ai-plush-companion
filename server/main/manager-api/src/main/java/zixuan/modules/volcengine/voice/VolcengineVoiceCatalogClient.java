package zixuan.modules.volcengine.voice;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zixuan.common.exception.RenException;

@Component
@RequiredArgsConstructor
public class VolcengineVoiceCatalogClient {
    private final HttpClient volcengineVoiceHttpClient;

    public Response execute(SignedVolcengineRequest signed) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(signed.uri())
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(signed.body()));
        signed.headers().forEach(builder::header);
        try {
            HttpResponse<String> response = volcengineVoiceHttpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RenException("火山引擎音色列表请求中断", e);
        } catch (Exception e) {
            throw new RenException("火山引擎音色列表请求失败", e);
        }
    }

    public record Response(int statusCode, String body) {
    }
}
