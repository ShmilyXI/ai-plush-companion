package zixuan.modules.companion.wakeword;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import cn.hutool.crypto.digest.DigestUtil;
import zixuan.modules.companion.wakeword.controller.WakeWordAssetDownloadController;
import zixuan.modules.companion.wakeword.dao.DeviceWakeWordDao;
import zixuan.modules.companion.wakeword.entity.DeviceWakeWordEntity;
import zixuan.modules.companion.wakeword.service.WakeWordAssetStore;

class WakeWordAssetDownloadControllerTest {
    @TempDir
    Path root;

    @Test
    void returnsOnlyAHashVerifiedCandidateForAnOpaqueToken() throws Exception {
        byte[] content = new byte[] { 1, 2, 3 };
        Path file = root.resolve("candidate.bin");
        Files.write(file, content);
        DeviceWakeWordEntity row = new DeviceWakeWordEntity();
        row.setCandidatePath(file.toString());
        row.setCandidateSha256(DigestUtil.sha256Hex(content));
        row.setCandidateSize(3L);
        DeviceWakeWordDao dao = mock(DeviceWakeWordDao.class);
        WakeWordAssetStore store = mock(WakeWordAssetStore.class);
        when(dao.selectByCandidateToken("token")).thenReturn(row);
        when(store.resolve(file.toString())).thenReturn(file);

        var response = new WakeWordAssetDownloadController(dao, store).download("token");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(3, response.getHeaders().getContentLength());
        assertEquals("private, max-age=300, immutable", response.getHeaders().getCacheControl());
    }

    @Test
    void unknownTokenReturnsNotFound() {
        DeviceWakeWordDao dao = mock(DeviceWakeWordDao.class);
        var response = new WakeWordAssetDownloadController(dao, mock(WakeWordAssetStore.class)).download("missing");

        assertEquals(404, response.getStatusCode().value());
    }
}
