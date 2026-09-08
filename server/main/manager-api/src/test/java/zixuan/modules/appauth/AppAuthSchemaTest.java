package zixuan.modules.appauth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import zixuan.modules.appauth.entity.AppUserTokenEntity;

class AppAuthSchemaTest {
    @Test
    void userTableGainsUniquePhoneColumnWithBackfillForLegacyPhoneUsernames() throws Exception {
        Path schema = Path.of("src/main/resources/db/changelog/202609060900.sql");
        String sql = Files.readString(schema);

        assertTrue(sql.contains("ADD COLUMN phone"));
        assertTrue(sql.contains("UNIQUE KEY uk_phone (phone)"));
        // 历史"手机号即用户名"的账号必须回填 phone，才能继续用手机号登录
        assertTrue(sql.contains("SET phone = username"));
        assertTrue(sql.contains("REGEXP"));
    }

    @Test
    void tokenTableStoresHashesOnlyWithUniqueIndexes() throws Exception {
        Path schema = Path.of("src/main/resources/db/changelog/202609060900.sql");
        String sql = Files.readString(schema);

        assertTrue(sql.contains("CREATE TABLE app_user_token"));
        assertTrue(sql.contains("token_hash"));
        assertTrue(sql.contains("refresh_hash"));
        assertTrue(sql.contains("UNIQUE KEY uk_token_hash (token_hash)"));
        assertTrue(sql.contains("UNIQUE KEY uk_refresh_hash (refresh_hash)"));
        assertFalse(sql.toLowerCase().contains("token_plain"));
        assertFalse(Arrays.stream(AppUserTokenEntity.class.getDeclaredFields())
                .map(Field::getName).anyMatch(name -> name.equalsIgnoreCase("token") || name.equalsIgnoreCase("plaintext")));
    }

    @Test
    void rollbackDropsTokenTableAndPhoneColumn() throws Exception {
        Path rollback = Path.of("src/main/resources/db/changelog/202609060900-rollback.sql");
        String sql = Files.readString(rollback);

        assertTrue(sql.contains("DROP TABLE IF EXISTS app_user_token"));
        assertTrue(sql.contains("DROP COLUMN phone"));
    }
}
