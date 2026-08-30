package xiaozhi.modules.companion;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ProfileConversationSchemaContractTest {
    @Test
    void profileMigrationIsIdempotentAndConversationTurnsAreDeduplicated() throws IOException {
        String profileSql = Files.readString(Path.of("src/main/resources/db/changelog/202608291015.sql"));
        String conversationSql = Files.readString(Path.of("src/main/resources/db/changelog/202608291030.sql"));
        assertTrue(profileSql.contains("memory_enabled"));
        assertTrue(profileSql.contains("consumer_deleted_at"));
        assertTrue(conversationSql.contains("UNIQUE KEY uk_companion_conversation_turn (conversation_id, turn_id)"));
        assertTrue(conversationSql.contains("user_text TEXT"));
        assertTrue(!conversationSql.contains("audio_blob"));
    }
}
