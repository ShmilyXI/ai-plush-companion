package xiaozhi.modules.companion.wakeword.service;

import java.nio.file.Path;

public interface WakeWordAssetStore {
    String store(String deviceId, long version, byte[] content, String expectedSha256);

    Path resolve(String storedPath);
}
