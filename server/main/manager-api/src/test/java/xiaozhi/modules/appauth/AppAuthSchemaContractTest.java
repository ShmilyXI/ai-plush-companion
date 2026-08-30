package xiaozhi.modules.appauth;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class AppAuthSchemaContractTest {

    @Test
    void appAuthMigrationDefinesUniqueContactsAndOneTimeChallenges() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/changelog/202608291000.sql"),
                StandardCharsets.UTF_8);

        assertTrue(sql.contains("UNIQUE KEY uk_app_contact_value (channel, normalized_value)"));
        assertTrue(sql.contains("UNIQUE KEY uk_app_contact_user_channel (user_id, channel)"));
        assertTrue(sql.contains("consumed_at DATETIME"));
        assertTrue(sql.contains("revoked_at DATETIME"));
    }
}
