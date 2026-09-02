package xiaozhi.modules.companion.entity;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ProactiveModeSchemaContractTest {
    @Test
    void modeMigrationAddsTurnBasedDefaultAndDefinesRollback() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/changelog/202608290900.sql"));
        String rollback = Files.readString(Path.of("src/main/resources/db/changelog/202608290900-rollback.sql"));
        String master = Files.readString(Path.of("src/main/resources/db/changelog/db.changelog-master.yaml"));

        assertTrue(sql.contains(
                "ADD COLUMN `companion_mode` varchar(32) NOT NULL DEFAULT 'turn_based'"));
        assertTrue(sql.contains("turn_based/proactive"));
        assertTrue(rollback.contains("DROP COLUMN `companion_mode`"));
        assertTrue(master.contains("path: classpath:db/changelog/202608290900.sql"));
        assertTrue(master.contains("path: classpath:db/changelog/202608290900-rollback.sql"));
    }
}
