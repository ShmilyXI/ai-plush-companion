package xiaozhi.modules.companion.wakeword.controller;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import cn.hutool.crypto.digest.DigestUtil;
import xiaozhi.modules.companion.wakeword.dao.DeviceWakeWordDao;
import xiaozhi.modules.companion.wakeword.entity.DeviceWakeWordEntity;
import xiaozhi.modules.companion.wakeword.service.WakeWordAssetStore;

@RestController
public class WakeWordAssetDownloadController {
    private final DeviceWakeWordDao dao;
    private final WakeWordAssetStore store;

    public WakeWordAssetDownloadController(DeviceWakeWordDao dao, WakeWordAssetStore store) {
        this.dao = dao;
        this.store = store;
    }

    @GetMapping("/wake-word-assets/{token}")
    public ResponseEntity<Resource> download(@PathVariable String token) {
        try {
            DeviceWakeWordEntity row = dao.selectByCandidateToken(token);
            if (row == null || row.getCandidateSha256() == null || row.getCandidateSize() == null) {
                return ResponseEntity.notFound().build();
            }
            Path file = store.resolve(row.getCandidatePath());
            long size = Files.size(file);
            if (size != row.getCandidateSize()
                    || !DigestUtil.sha256Hex(Files.readAllBytes(file)).equals(row.getCandidateSha256())) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(size)
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300, immutable")
                    .body(new FileSystemResource(file));
        } catch (RuntimeException | java.io.IOException exception) {
            return ResponseEntity.notFound().build();
        }
    }
}
