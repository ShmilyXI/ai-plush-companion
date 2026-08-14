package xiaozhi.modules.model.tencentdb;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.hutool.json.JSONObject;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/tencentdb-memory-model/v1")
public class TencentDbMemoryModelProxyController {
    private final TencentDbMemoryModelProxyService proxyService;

    @PostMapping("/chat/completions")
    public void chat(@RequestBody JSONObject request, HttpServletResponse response) throws IOException {
        write(proxyService.chat(request), response);
    }

    @PostMapping("/embeddings")
    public void embeddings(@RequestBody JSONObject request, HttpServletResponse response) throws IOException {
        write(proxyService.embeddings(request), response);
    }

    private void write(TencentDbMemoryProxyResponse proxyResponse, HttpServletResponse response) throws IOException {
        response.setStatus(proxyResponse.status());
        response.setContentType(proxyResponse.contentType());
        proxyResponse.headers().forEach(response::setHeader);
        try (proxyResponse;
                InputStream input = proxyResponse.body();
                OutputStream output = response.getOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    output.write(buffer, 0, read);
                    output.flush();
                }
            }
        }
    }
}
