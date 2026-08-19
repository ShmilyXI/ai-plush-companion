package xiaozhi.modules.companion.capability;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.companion.capability.packagefile.LocalSkillPackageStore;

class LocalSkillPackageStoreTest {

    @TempDir
    Path root;

    @Test
    void storesByRelativeManagedKeyAndDeletesAtomicallyWrittenPackage() {
        LocalSkillPackageStore store = new LocalSkillPackageStore(root);
        byte[] bytes = "package".getBytes();

        String key = store.put("skill-weather", 3, "a".repeat(64), bytes);

        assertFalse(Path.of(key).isAbsolute());
        assertTrue(key.endsWith("skill-weather/3/" + "a".repeat(64) + ".skill.zip"));
        assertArrayEquals(bytes, store.get(key));
        store.delete(key);
        assertThrows(RenException.class, () -> store.get(key));
    }

    @Test
    void rejectsIdentifiersAndKeysThatEscapeTheManagedRoot() {
        LocalSkillPackageStore store = new LocalSkillPackageStore(root);

        assertThrows(RenException.class, () -> store.put("../outside", 1, "a".repeat(64), new byte[] { 1 }));
        assertThrows(RenException.class, () -> store.get("../outside.skill.zip"));
    }
}
