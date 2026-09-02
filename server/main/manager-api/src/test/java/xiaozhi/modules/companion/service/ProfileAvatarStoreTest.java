package xiaozhi.modules.companion.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import xiaozhi.modules.companion.service.impl.FileProfileAvatarStore;

class ProfileAvatarStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void storesAndLoadsAnAvatarByOwnerAndContentChecksum() throws Exception {
        byte[] content = "avatar-bytes".getBytes(StandardCharsets.UTF_8);
        String checksum = java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content));
        FileProfileAvatarStore store = new FileProfileAvatarStore(temporaryDirectory);

        store.save("profile-a", checksum, "image/png", content);
        var loaded = store.load("profile-a", checksum).orElseThrow();

        assertArrayEquals(content, loaded.content());
        assertEquals("image/png", loaded.contentType());
        assertTrue(loaded.path().startsWith(temporaryDirectory));
    }

    @Test
    void rejectsExecutableSignaturesEvenWhenTheMimeTypeClaimsAnImage() throws Exception {
        byte[] content = new byte[] {'M', 'Z', 0, 0};
        String checksum = java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content));
        FileProfileAvatarStore store = new FileProfileAvatarStore(temporaryDirectory);

        assertThrows(RuntimeException.class,
                () -> store.save("profile-a", checksum, "image/png", content));
    }
}
