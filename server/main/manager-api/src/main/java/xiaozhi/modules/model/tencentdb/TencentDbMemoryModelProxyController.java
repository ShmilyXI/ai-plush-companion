package xiaozhi.modules.model.tencentdb;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/tencentdb-memory-model/v1")
public class TencentDbMemoryModelProxyController {
    private final TencentDbMemoryModelProxyService proxyService;

    @PostMapping("/chat/completions")
    public ResponseEntity<StreamingResponseBody> chat(@RequestBody JSONObject request) {
        return response(proxyService.chat(request));
    }

    @PostMapping("/embeddings")
    public ResponseEntity<StreamingResponseBody> embeddings(@RequestBody JSONObject request) {
        return response(proxyService.embeddings(request));
    }

    private ResponseEntity<StreamingResponseBody> response(TencentDbMemoryProxyResponse proxyResponse) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(proxyResponse.contentType()));
        proxyResponse.headers().forEach(headers::set);
        StreamingResponseBody body = output -> {
            try (proxyResponse) {
                proxyResponse.body().transferTo(output);
                output.flush();
            }
        };
        return ResponseEntity.status(proxyResponse.status()).headers(headers).body(body);
    }
}
