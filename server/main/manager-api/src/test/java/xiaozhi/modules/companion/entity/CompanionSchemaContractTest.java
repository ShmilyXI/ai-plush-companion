package xiaozhi.modules.companion.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.apache.ibatis.annotations.Select;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.annotation.TableName;

import liquibase.change.core.SQLFileChange;
import liquibase.changelog.ChangeLogParameters;
import liquibase.parser.core.yaml.YamlChangeLogParser;
import liquibase.resource.ClassLoaderResourceAccessor;

import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.agent.entity.AgentTemplateEntity;
import xiaozhi.modules.companion.vo.CompanionDeviceVO;
import xiaozhi.modules.device.dao.OtaDao;
import xiaozhi.modules.device.entity.DeviceEntity;

class CompanionSchemaContractTest {

    @Test
    void wakeWordMigrationInheritsDeviceTableCollationForForeignKeyCompatibility() throws Exception {
        String sql = resource("/db/changelog/202608132300.sql");

        assertTrue(sql.contains("FOREIGN KEY (`device_id`) REFERENCES `ai_device` (`id`)"));
        assertFalse(sql.toUpperCase().contains("COLLATE="));
    }

    @Test
    void deviceDebugLogMigrationAddsSwitchAndDefinesRollback() throws Exception {
        String sql = resource("/db/changelog/202608121900.sql");
        String rollbackSql = resource("/db/changelog/202608121900-rollback.sql");

        assertTrue(sql.contains("ADD COLUMN `debug_log_enabled` TINYINT NOT NULL DEFAULT 0"));
        assertTrue(rollbackSql.contains("DROP COLUMN `debug_log_enabled`"));
        try (var accessor = new ClassLoaderResourceAccessor()) {
            var changeLog = new YamlChangeLogParser().parse("db/changelog/db.changelog-master.yaml",
                    new ChangeLogParameters(), accessor);
            var changeSet = changeLog.getChangeSets().stream()
                    .filter(candidate -> "202608121900".equals(candidate.getId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(1, changeSet.getChanges().size());
            var forward = assertInstanceOf(SQLFileChange.class, changeSet.getChanges().get(0));
            assertEquals("classpath:db/changelog/202608121900.sql", forward.getPath());
            assertEquals(1, changeSet.getRollback().getChanges().size());
            var rollback = assertInstanceOf(SQLFileChange.class, changeSet.getRollback().getChanges().get(0));
            assertEquals("classpath:db/changelog/202608121900-rollback.sql", rollback.getPath());
        }
        assertFieldType(DeviceEntity.class, "debugLogEnabled", Integer.class);
        assertFieldType(CompanionDeviceVO.class, "debugLogEnabled", Boolean.class);
    }

    @Test
    void privateModelMigrationDefinesOwnershipAndUniqueBindings() throws Exception {
        String sql = resource("/db/changelog/202608050100.sql");
        String rollbackSql = resource("/db/changelog/202608050100-rollback.sql");
        String master = resource("/db/changelog/db.changelog-master.yaml");

        assertTrue(sql.contains("CREATE TABLE `ai_companion_private_model`"));
        assertTrue(sql.contains("`user_id` bigint NOT NULL"));
        assertTrue(sql.contains("`api_key_ciphertext` text"));
        assertTrue(sql.contains("KEY `idx_companion_private_model_owner_type` (`user_id`,`model_type`,`enabled`)"));
        assertTrue(sql.contains("CREATE TABLE `ai_companion_profile_model`"));
        assertTrue(sql.contains("UNIQUE KEY `uk_companion_profile_model_type` (`agent_id`,`model_type`)"));
        assertTrue(sql.contains("KEY `idx_companion_profile_model_resource` (`source_type`,`resource_id`)"));
        assertTrue(rollbackSql.indexOf("DROP TABLE IF EXISTS `ai_companion_profile_model`")
                < rollbackSql.indexOf("DROP TABLE IF EXISTS `ai_companion_private_model`"));
        assertTrue(master.contains("id: 202608050100"));
        assertTrue(master.contains("path: classpath:db/changelog/202608050100.sql"));
        assertTrue(master.contains("path: classpath:db/changelog/202608050100-rollback.sql"));
    }

    @Test
    void unifiedCatalogMigrationAddsTemplateAndSecretMapColumns() throws Exception {
        String sql = resource("/db/changelog/202608050200.sql");
        String rollbackSql = resource("/db/changelog/202608050200-rollback.sql");
        String master = resource("/db/changelog/db.changelog-master.yaml");

        assertTrue(sql.contains("ADD COLUMN `provider_template_id` varchar(32)"));
        assertTrue(sql.contains("ADD COLUMN `secret_config_ciphertext` text"));
        assertTrue(sql.contains("idx_companion_private_model_template"));
        assertTrue(rollbackSql.contains("DROP KEY `idx_companion_private_model_template`"));
        assertTrue(rollbackSql.contains("DROP COLUMN `secret_config_ciphertext`"));
        assertTrue(rollbackSql.contains("DROP COLUMN `provider_template_id`"));
        assertTrue(master.contains("id: 202608050200"));
        assertTrue(master.contains("path: classpath:db/changelog/202608050200.sql"));
        assertTrue(master.contains("path: classpath:db/changelog/202608050200-rollback.sql"));
    }

    @Test
    void modelVendorCredentialMigrationKeepsSystemSecretsAccountScopedAndEncrypted() throws Exception {
        String master = resource("/db/changelog/db.changelog-master.yaml");
        String sql = resource("/db/changelog/202608052330.sql");

        assertTrue(master.contains("202608052330.sql"));
        assertTrue(sql.contains("CREATE TABLE `ai_companion_model_preset_meta`"));
        assertTrue(sql.contains("CREATE TABLE `ai_companion_global_model_credential`"));
        assertTrue(sql.contains(
                "UNIQUE KEY `uk_companion_global_credential_user_model` (`user_id`,`global_model_id`)"));
        assertTrue(sql.contains("`secret_config_ciphertext` text"));
        assertFalse(sql.contains("`api_key` varchar"));
        assertTrue(sql.contains("ADD COLUMN `vendor_name` varchar(80)"));
        assertTrue(sql.contains("ADD COLUMN `protocol` varchar(50)"));
        assertTrue(sql.contains("ADD COLUMN `credential_required` tinyint"));
    }

    @Test
    void volcengineTtsMigrationPreservesLegacyV2RuntimeReferencesAndRegistersRollback() throws Exception {
        String sql = resource("/db/changelog/202608121200.sql");
        String rollbackSql = resource("/db/changelog/202608121200-rollback.sql");
        String master = resource("/db/changelog/db.changelog-master.yaml");

        assertFalse(sql.contains("UPDATE `ai_agent`"));
        assertFalse(sql.contains("UPDATE `ai_agent_template`"));
        assertFalse(sql.contains("UPDATE `ai_voice_clone`"));
        assertFalse(sql.contains("UPDATE `ai_tts_voice`"));
        assertFalse(sql.contains("UPDATE `ai_companion_profile_model`"));
        assertFalse(sql.contains("UPDATE `ai_companion_global_model_credential`"));
        assertFalse(sql.contains("TTS_HSDSTTS_V2"));
        assertTrue(sql.contains("'seed-tts-1.0'"));
        assertTrue(sql.contains("'seed-tts-2.0'"));
        assertTrue(sql.contains("access_key_id"));
        assertTrue(sql.contains("secret_access_key"));
        assertTrue(sql.contains("'$.ws_url', COALESCE("));
        assertTrue(sql.contains("NULLIF(JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.ws_url')), '')"));
        assertTrue(sql.contains("WHEN JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.resource_id')) = 'volc.service_type.10029'"));
        assertFalse(rollbackSql.contains("'$.resource_id'"));
        assertFalse(rollbackSql.contains("TTS_HSDSTTS_V2"));
        assertFalse(rollbackSql.contains("JSON_REMOVE"));
        assertFalse(rollbackSql.contains("$.access_key_id"));
        assertFalse(rollbackSql.contains("$.secret_access_key"));
        assertTrue(master.contains("id: 202608121200"));
        assertTrue(master.contains("path: classpath:db/changelog/202608121200.sql"));
        assertTrue(master.contains("path: classpath:db/changelog/202608121200-rollback.sql"));
    }

    @Test
    void agentAndDeviceExposeCompanionCapabilityColumns() throws Exception {
        assertFieldType(AgentEntity.class, "companionEnabled", Integer.class);
        assertFieldType(AgentEntity.class, "relationMode", String.class);
        assertFieldType(AgentEntity.class, "userAddress", String.class);
        assertFieldType(AgentEntity.class, "personality", String.class);
        assertFieldType(AgentEntity.class, "companionTemplateId", String.class);
        assertFieldType(AgentEntity.class, "companionCueConfig", String.class);
        assertFieldType(AgentEntity.class, "screenExpressionEnabled", Integer.class);
        assertFieldType(AgentEntity.class, "cameraPreferenceEnabled", Integer.class);
        assertEquals("friend", AgentEntity.DEFAULT_RELATION_MODE);
        assertFieldType(AgentTemplateEntity.class, "companionCueConfig", String.class);

        assertFieldType(DeviceEntity.class, "hasDisplay", Integer.class);
        assertFieldType(DeviceEntity.class, "hasCamera", Integer.class);
    }

    @Test
    void companionEntitiesMapToTheirTablesWithStableFieldTypes() throws Exception {
        assertTableName(CompanionPlanEntity.class, "ai_companion_plan");
        assertFieldType(CompanionPlanEntity.class, "id", String.class);
        assertFieldType(CompanionPlanEntity.class, "maxDevices", Integer.class);
        assertFieldType(CompanionPlanEntity.class, "longTermMemory", Integer.class);
        assertFieldType(CompanionPlanEntity.class, "createdAt", Date.class);

        assertTableName(CompanionSubscriptionEntity.class, "ai_companion_subscription");
        assertFieldType(CompanionSubscriptionEntity.class, "userId", Long.class);
        assertFieldType(CompanionSubscriptionEntity.class, "planId", String.class);
        assertFieldType(CompanionSubscriptionEntity.class, "startsAt", Date.class);
        assertEquals("active", CompanionSubscriptionEntity.STATUS_ACTIVE);

        assertTableName(CompanionAuditEntity.class, "ai_companion_audit");
        assertFieldType(CompanionAuditEntity.class, "operatorId", Long.class);
        assertFieldType(CompanionAuditEntity.class, "targetUserId", Long.class);
        assertFieldType(CompanionAuditEntity.class, "summary", String.class);
    }

    @Test
    void migrationPreservesSubscriptionHistoryAndUniquelyIndexesOnlyActiveRows() throws IOException {
        String sql = resource("/db/changelog/202607282300.sql");
        String planSeed = resource("/db/changelog/202607291130.sql");
        String planSeedRollback = resource("/db/changelog/202607291130-rollback.sql");
        String master = resource("/db/changelog/db.changelog-master.yaml");

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `ai_companion_plan`"));
        assertTrue(sql.contains("UNIQUE KEY `uk_companion_plan_code` (`plan_code`)"));
        assertTrue(planSeed.contains("('basic', 'basic', 'Basic', 1, 3, 1, 0, 1)"));
        assertTrue(planSeedRollback.contains("DELETE FROM `ai_companion_plan`"));
        assertFalse(planSeedRollback.contains("DELETE FROM `ai_companion_subscription`"));
        assertFalse(planSeedRollback.toUpperCase().contains("CREATE PROCEDURE"));
        assertFalse(planSeedRollback.toUpperCase().contains("TEMPORARY TABLE"));
        assertTrue(planSeedRollback.contains("SELECT `id` FROM `ai_companion_plan`"));
        assertTrue(planSeedRollback.toUpperCase().contains("FOR UPDATE"));
        assertTrue(planSeedRollback.contains("INSERT INTO `ai_companion_subscription`"));
        assertTrue(planSeedRollback.contains("FROM `ai_companion_subscription`"));
        assertTrue(planSeedRollback.contains("WHERE `plan_id` = 'basic'"));
        assertTrue(planSeedRollback.indexOf("SELECT `id` FROM `ai_companion_plan`")
                < planSeedRollback.indexOf("FROM `ai_companion_subscription`"));
        assertTrue(planSeedRollback.indexOf("INSERT INTO `ai_companion_subscription`")
                < planSeedRollback.indexOf("DELETE FROM `ai_companion_plan`"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `ai_companion_subscription`"));
        assertTrue(sql.contains("GENERATED ALWAYS AS (CASE WHEN `status` = 'active' THEN `user_id` ELSE NULL END) STORED"));
        assertTrue(sql.contains("UNIQUE KEY `uk_companion_subscription_active_user` (`active_user_id`)"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `ai_companion_audit`"));
        assertTrue(master.contains("id: 202607282300"));
        assertTrue(master.contains("path: classpath:db/changelog/202607282300.sql"));
        assertTrue(master.contains("path: classpath:db/changelog/202607291130.sql"));
        assertTrue(master.contains("path: classpath:db/changelog/202607291130-rollback.sql"));
        assertTrue(master.contains("path: classpath:db/changelog/202607291600.sql"));
        assertTrue(master.contains("path: classpath:db/changelog/202607291600-rollback.sql"));
        assertTrue(resource("/db/changelog/202607291600.sql")
                .contains("ADD COLUMN `companion_cue_config` TEXT"));
        assertTrue(resource("/db/changelog/202607291600.sql")
                .contains("WHERE `agent_code` = 'xiaozhi-companion'"));
        assertTrue(resource("/db/changelog/202607291600-rollback.sql")
                .contains("DROP COLUMN `companion_cue_config`"));
        String normalizedMac = resource("/db/changelog/202607291700.sql");
        String normalizedMacRollback = resource("/db/changelog/202607291700-rollback.sql");
        assertTrue(normalizedMac.contains("LOWER(REPLACE(REPLACE(TRIM(`mac_address`), ':', ''), '-', ''))"));
        assertTrue(normalizedMac.contains("UNIQUE KEY `uk_ai_device_normalized_mac` (`normalized_mac_address`)"));
        assertTrue(normalizedMacRollback.contains("DROP INDEX `uk_ai_device_normalized_mac`"));
        assertTrue(normalizedMacRollback.contains("DROP COLUMN `normalized_mac_address`"));
        assertTrue(master.contains("id: 202607291700"));
        assertTrue(master.contains("path: classpath:db/changelog/202607291700.sql"));
        assertTrue(master.contains("path: classpath:db/changelog/202607291700-rollback.sql"));
    }

    @Test
    void normalizedMacUniqueConstraintRejectsCaseAndSeparatorDuplicates() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:normalized_mac_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE ai_device (id VARCHAR(64) PRIMARY KEY, mac_address VARCHAR(64),"
                + " normalized_mac_address VARCHAR(64) GENERATED ALWAYS AS"
                + " (LOWER(REPLACE(REPLACE(TRIM(mac_address), ':', ''), '-', ''))),"
                + " CONSTRAINT uk_ai_device_normalized_mac UNIQUE(normalized_mac_address))");
        jdbc.update("INSERT INTO ai_device(id, mac_address) VALUES ('one', ' AA:BB:CC ')");

        assertThrows(DuplicateKeyException.class,
                () -> jdbc.update("INSERT INTO ai_device(id, mac_address) VALUES ('two', 'aa-bb-cc')"));
        assertEquals("aabbcc", jdbc.queryForObject(
                "SELECT normalized_mac_address FROM ai_device WHERE id='one'", String.class));
    }

    @Test
    void normalizedMacMigrationFailsWithoutDeletingExistingDuplicates() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:normalized_mac_upgrade_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE ai_device (id VARCHAR(64) PRIMARY KEY, mac_address VARCHAR(64),"
                + " normalized_mac_address VARCHAR(64) GENERATED ALWAYS AS"
                + " (LOWER(REPLACE(REPLACE(TRIM(mac_address), ':', ''), '-', ''))))");
        jdbc.update("INSERT INTO ai_device(id, mac_address) VALUES ('one', 'AA:BB:CC')");
        jdbc.update("INSERT INTO ai_device(id, mac_address) VALUES ('two', 'aa-bb-cc')");

        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.execute("ALTER TABLE ai_device ADD CONSTRAINT uk_ai_device_normalized_mac"
                        + " UNIQUE(normalized_mac_address)"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_device", Integer.class));
    }

    @Test
    void firmwareTypeMigrationDefinesNormalizedUniqueSlotAndRollback() throws Exception {
        String sql = resource("/db/changelog/202607292300.sql");
        String rollbackSql = resource("/db/changelog/202607292300-rollback.sql");
        String master = resource("/db/changelog/db.changelog-master.yaml");

        assertTrue(sql.contains("normalized_type_key"));
        assertTrue(sql.contains("LOWER"));
        assertTrue(sql.contains("TRIM"));
        assertTrue(sql.contains("__default__"));
        assertTrue(sql.contains("UNIQUE KEY `uk_ai_ota_normalized_type`"));
        assertTrue(rollbackSql.contains("DROP INDEX `uk_ai_ota_normalized_type`"));
        assertTrue(rollbackSql.contains("DROP COLUMN `normalized_type_key`"));
        assertTrue(master.contains("id: 202607292300"));
        assertTrue(master.contains("path: classpath:db/changelog/202607292300.sql"));
        assertTrue(master.contains("path: classpath:db/changelog/202607292300-rollback.sql"));
        try (var accessor = new ClassLoaderResourceAccessor()) {
            var changeLog = new YamlChangeLogParser().parse("db/changelog/db.changelog-master.yaml",
                    new ChangeLogParameters(), accessor);
            var changeSet = changeLog.getChangeSets().stream()
                    .filter(candidate -> "202607292300".equals(candidate.getId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(1, changeSet.getRollback().getChanges().size());
        }
    }

    @Test
    void normalizedFirmwareTypeUniqueConstraintCoversCaseWhitespaceAndDefaultSlot() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:normalized_ota_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE ai_ota (id VARCHAR(64) PRIMARY KEY, type VARCHAR(64),"
                + " normalized_type_key VARCHAR(64) GENERATED ALWAYS AS"
                + " (LOWER(CASE WHEN TRIM(COALESCE(type, '')) = '' THEN '__default__' ELSE TRIM(type) END)),"
                + " CONSTRAINT uk_ai_ota_normalized_type UNIQUE(normalized_type_key))");
        jdbc.update("INSERT INTO ai_ota(id, type) VALUES ('one', ' ESP32 ')");
        jdbc.update("INSERT INTO ai_ota(id, type) VALUES ('blank', '   ')");

        assertThrows(DuplicateKeyException.class,
                () -> jdbc.update("INSERT INTO ai_ota(id, type) VALUES ('two', 'esp32')"));
        assertThrows(DuplicateKeyException.class,
                () -> jdbc.update("INSERT INTO ai_ota(id, type) VALUES ('null-type', NULL)"));
        assertEquals("esp32", jdbc.queryForObject(
                "SELECT normalized_type_key FROM ai_ota WHERE id='one'", String.class));
        assertEquals("__default__", jdbc.queryForObject(
                "SELECT normalized_type_key FROM ai_ota WHERE id='blank'", String.class));
    }

    @Test
    void firmwareTypeUniqueUpgradeFailsWithoutDeletingHistoricalDuplicates() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:normalized_ota_upgrade_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE ai_ota (id VARCHAR(64) PRIMARY KEY, type VARCHAR(64),"
                + " normalized_type_key VARCHAR(64) GENERATED ALWAYS AS"
                + " (LOWER(CASE WHEN TRIM(COALESCE(type, '')) = '' THEN '__default__' ELSE TRIM(type) END)))");
        jdbc.update("INSERT INTO ai_ota(id, type) VALUES ('one', 'ESP32')");
        jdbc.update("INSERT INTO ai_ota(id, type) VALUES ('two', ' esp32 ')");

        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.execute("ALTER TABLE ai_ota ADD CONSTRAINT uk_ai_ota_normalized_type"
                        + " UNIQUE(normalized_type_key)"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_ota", Integer.class));
    }

    @Test
    void firmwareDaoExposesNormalizedTypeAndIdRowLocks() throws Exception {
        Method typeLock = OtaDao.class.getMethod("selectByNormalizedTypeForUpdate", String.class);
        Method idLock = OtaDao.class.getMethod("selectByIdForUpdate", String.class);
        Select typeSelect = typeLock.getAnnotation(Select.class);
        Select idSelect = idLock.getAnnotation(Select.class);

        assertTrue(String.join(" ", typeSelect.value()).contains("normalized_type_key"));
        assertTrue(String.join(" ", typeSelect.value()).toUpperCase().contains("FOR UPDATE"));
        assertTrue(String.join(" ", idSelect.value()).toUpperCase().contains("FOR UPDATE"));
    }

    @Test
    void migrationRegistersExecutableRollbackInMasterChangeSet() throws Exception {
        String sql = resource("/db/changelog/202607282300.sql");
        String rollbackSql = resource("/db/changelog/202607282300-rollback.sql");
        String master = resource("/db/changelog/db.changelog-master.yaml");

        assertTrue(master.contains("rollback:\n        - sqlFile:\n            encoding: utf8\n"
                + "            path: classpath:db/changelog/202607282300-rollback.sql"));
        try (var accessor = new ClassLoaderResourceAccessor()) {
            var changeLog = new YamlChangeLogParser().parse("db/changelog/db.changelog-master.yaml",
                    new ChangeLogParameters(), accessor);
            var changeSet = changeLog.getChangeSets().stream()
                    .filter(candidate -> "202607282300".equals(candidate.getId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(1, changeSet.getRollback().getChanges().size());
            var rollback = assertInstanceOf(SQLFileChange.class, changeSet.getRollback().getChanges().get(0));
            assertEquals("classpath:db/changelog/202607282300-rollback.sql", rollback.getPath());
        }
        assertTrue(rollbackSql.indexOf("DROP TABLE IF EXISTS `ai_companion_audit`")
                < rollbackSql.indexOf("DROP TABLE IF EXISTS `ai_companion_subscription`"));
        assertTrue(rollbackSql.indexOf("DROP TABLE IF EXISTS `ai_companion_subscription`")
                < rollbackSql.indexOf("DROP TABLE IF EXISTS `ai_companion_plan`"));
        assertTrue(rollbackSql.indexOf("DROP TABLE IF EXISTS `ai_companion_plan`")
                < rollbackSql.indexOf("ALTER TABLE `ai_device`"));
        assertTrue(rollbackSql.indexOf("ALTER TABLE `ai_device`")
                < rollbackSql.indexOf("ALTER TABLE `ai_agent`"));
        assertTrue(!sql.contains("-- rollback"));
    }

    @Test
    void basicSeedRollbackRefusesToDeleteReferencedBusinessData() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:basic_rollback_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createRollbackTables(jdbc);
        jdbc.update("INSERT INTO ai_companion_plan VALUES"
                + " ('basic','basic','Basic',1,3,1,0,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO ai_companion_subscription VALUES"
                + " ('subscription-1',7,'basic','active',CURRENT_TIMESTAMP,NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        String rollbackSql = resource("/db/changelog/202607291130-rollback.sql");

        assertThrows(DuplicateKeyException.class, () -> executeStatements(jdbc, rollbackSql));

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_companion_plan WHERE id='basic'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_companion_subscription WHERE plan_id='basic'", Integer.class));
    }

    @Test
    void basicSeedRollbackDeletesSeedWhenItHasNoReferences() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:basic_rollback_empty_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createRollbackTables(jdbc);
        jdbc.update("INSERT INTO ai_companion_plan VALUES"
                + " ('basic','basic','Basic',1,3,1,0,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");

        executeStatements(jdbc, resource("/db/changelog/202607291130-rollback.sql"));

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_companion_plan WHERE id='basic'", Integer.class));
    }

    @Test
    void basicSeedRollbackRefusesModifiedSeedWithoutReferences() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:basic_rollback_modified_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createRollbackTables(jdbc);
        jdbc.update("INSERT INTO ai_companion_plan VALUES"
                + " ('basic','basic','Customized Basic',2,4,1,0,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");

        assertThrows(DuplicateKeyException.class,
                () -> executeStatements(jdbc, resource("/db/changelog/202607291130-rollback.sql")));

        assertEquals("Customized Basic",
                jdbc.queryForObject("SELECT plan_name FROM ai_companion_plan WHERE id='basic'", String.class));
    }

    @Test
    void basicSeedRollbackLocksPlanBeforeReferenceCheckAndBlocksConcurrentGrant() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:basic_rollback_concurrent_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createRollbackTables(jdbc);
        jdbc.update("INSERT INTO ai_companion_plan VALUES"
                + " ('basic','basic','Basic',1,3,1,0,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        List<String> statements = statements(resource("/db/changelog/202607291130-rollback.sql"));
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        CountDownLatch referencesChecked = new CountDownLatch(1);
        CountDownLatch continueRollback = new CountDownLatch(1);
        CountDownLatch grantFinished = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try {
            var rollback = executor.submit(() -> transaction.executeWithoutResult(status -> {
                int referenceCheck = 0;
                for (; referenceCheck < statements.size(); referenceCheck++) {
                    jdbc.execute(statements.get(referenceCheck));
                    if (statements.get(referenceCheck).contains("FROM `ai_companion_subscription`")) {
                        break;
                    }
                }
                referencesChecked.countDown();
                await(continueRollback);
                for (int index = referenceCheck + 1; index < statements.size(); index++) {
                    jdbc.execute(statements.get(index));
                }
            }));
            assertTrue(referencesChecked.await(2, TimeUnit.SECONDS));

            var grant = executor.submit(() -> transaction.executeWithoutResult(status -> {
                List<String> plans = jdbc.queryForList(
                        "SELECT id FROM ai_companion_plan WHERE id='basic' FOR UPDATE", String.class);
                if (!plans.isEmpty()) {
                    jdbc.update("INSERT INTO ai_companion_subscription VALUES"
                            + " ('subscription-2',7,'basic','active',CURRENT_TIMESTAMP,NULL,"
                            + " CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
                }
                grantFinished.countDown();
            }));

            assertFalse(grantFinished.await(200, TimeUnit.MILLISECONDS));
            continueRollback.countDown();
            rollback.get(2, TimeUnit.SECONDS);
            grant.get(2, TimeUnit.SECONDS);
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_companion_plan", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_companion_subscription", Integer.class));
        } finally {
            continueRollback.countDown();
            executor.shutdownNow();
        }
    }

    private static void assertFieldType(Class<?> type, String fieldName, Class<?> expectedType) throws Exception {
        Field field = type.getDeclaredField(fieldName);
        assertEquals(expectedType, field.getType(), type.getSimpleName() + "." + fieldName);
    }

    private static void assertTableName(Class<?> type, String expectedTableName) {
        TableName annotation = type.getAnnotation(TableName.class);
        assertEquals(expectedTableName, annotation.value());
    }

    private static String resource(String path) throws IOException {
        try (var input = CompanionSchemaContractTest.class.getResourceAsStream(path)) {
            if (input == null) {
                return "";
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void executeStatements(JdbcTemplate jdbc, String sql) {
        statements(sql).forEach(jdbc::execute);
    }

    private static List<String> statements(String sql) {
        return java.util.Arrays.stream(sql.split(";"))
                .map(String::trim)
                .filter(statement -> !statement.isEmpty())
                .toList();
    }

    private static void createRollbackTables(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE ai_companion_plan (id VARCHAR(32) PRIMARY KEY, plan_code VARCHAR(32) UNIQUE NOT NULL,"
                + " plan_name VARCHAR(64) NOT NULL, max_devices INT NOT NULL, max_profiles INT NOT NULL,"
                + " long_term_memory TINYINT NOT NULL, advanced_voice TINYINT NOT NULL, status TINYINT NOT NULL,"
                + " created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE ai_companion_subscription (id VARCHAR(32) PRIMARY KEY, user_id BIGINT NOT NULL,"
                + " plan_id VARCHAR(32) NOT NULL, status VARCHAR(16) NOT NULL, starts_at TIMESTAMP NOT NULL,"
                + " expires_at TIMESTAMP NULL, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL)");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for rollback continuation");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
