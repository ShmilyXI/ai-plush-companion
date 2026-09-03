package xiaozhi.modules.companion.wakeword;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import cn.hutool.crypto.digest.DigestUtil;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.companion.wakeword.service.impl.FileWakeWordAssetStore;

class FileWakeWordAssetStoreTest {
    @TempDir
    Path root;

    @Test
    void storesTheSameImmutableAssetAtTheSameManagedPath() throws Exception {
        FileWakeWordAssetStore store = new FileWakeWordAssetStore(root);
        byte[] content = new byte[] { 1, 2, 3 };
        String sha256 = DigestUtil.sha256Hex(content);

        String first = store.store("device-1", 7, content, sha256);
        String second = store.store("device-1", 7, content, sha256);

        assertEquals(first, second);
        assertEquals("device-1-7-" + sha256 + ".bin", Path.of(first).getFileName().toString());
        assertTrue(store.resolve(first).startsWith(root.toRealPath()));
        assertArrayEquals(content, Files.readAllBytes(store.resolve(first)));
    }

    @Test
    void storesAssetForMacAddressDeviceId() throws Exception {
        FileWakeWordAssetStore store = new FileWakeWordAssetStore(root);
        byte[] content = new byte[] { 4, 5, 6 };
        String sha256 = DigestUtil.sha256Hex(content);

        String stored = store.store("9c:13:9e:8a:14:a4", 1, content, sha256);

        assertEquals("9c:13:9e:8a:14:a4-1-" + sha256 + ".bin",
                Path.of(stored).getFileName().toString());
        assertArrayEquals(content, Files.readAllBytes(store.resolve(stored)));
    }

    @Test
    void resolvesManagedAssetAfterTheRuntimeRootMoves() throws Exception {
        FileWakeWordAssetStore store = new FileWakeWordAssetStore(root);
        byte[] content = new byte[] { 7, 8, 9 };
        String sha256 = DigestUtil.sha256Hex(content);
        String stored = store.store("device-1", 7, content, sha256);
        String legacyPath = Path.of("/workspace", "uploadfile", "wake-word",
                Path.of(stored).getFileName().toString()).toString();

        Path resolved = store.resolve(legacyPath);

        assertEquals(Path.of(stored).toRealPath(), resolved);
        assertArrayEquals(content, Files.readAllBytes(resolved));
    }

    @Test
    void rejectsHashMismatchAndPathTraversal() {
        FileWakeWordAssetStore store = new FileWakeWordAssetStore(root);
        byte[] original = new byte[] { 1, 2, 3 };
        String originalHash = DigestUtil.sha256Hex(original);

        String stored = store.store("device-1", 7, original, originalHash);

        assertThrows(RenException.class,
                () -> store.store("device-1", 7, new byte[] { 9 }, originalHash));
        assertThrows(RenException.class,
                () -> store.store("../device", 7, original, originalHash));
        assertThrows(RenException.class,
                () -> store.resolve(root.resolve("../outside.bin").toString()));
        assertThrows(RenException.class,
                () -> store.resolve(Path.of("/other", "uploadfile", "wake-word",
                        Path.of(stored).getFileName().toString()).toString()));
    }
}
