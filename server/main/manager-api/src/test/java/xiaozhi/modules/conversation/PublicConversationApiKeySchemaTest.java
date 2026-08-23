package xiaozhi.modules.conversation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import xiaozhi.modules.conversation.entity.PublicConversationApiKeyEntity;

class PublicConversationApiKeySchemaTest {
    @Test
    void schemaStoresHashAndLifecycleMetadataWithoutPlaintextKey() throws Exception {
        Path schema = Path.of("src/main/resources/db/changelog/202608240100.sql");
        String sql = Files.readString(schema);

        assertTrue(sql.contains("key_hash"));
        assertTrue(sql.contains("key_prefix"));
        assertTrue(sql.contains("scopes_json"));
        assertTrue(sql.contains("agent_ids_json"));
        assertTrue(sql.contains("expires_at"));
        assertTrue(sql.contains("revoked"));
        assertFalse(sql.toLowerCase().contains("plaintext"));
        assertFalse(Arrays.stream(PublicConversationApiKeyEntity.class.getDeclaredFields())
                .map(Field::getName).anyMatch(name -> name.equalsIgnoreCase("key") || name.equalsIgnoreCase("plainKey")));
    }
}
