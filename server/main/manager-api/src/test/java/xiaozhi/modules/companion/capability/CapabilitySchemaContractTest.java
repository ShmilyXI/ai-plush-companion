package xiaozhi.modules.companion.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.baomidou.mybatisplus.annotation.TableName;

import liquibase.change.core.SQLFileChange;
import liquibase.changelog.ChangeLogParameters;
import liquibase.parser.core.yaml.YamlChangeLogParser;
import liquibase.resource.ClassLoaderResourceAccessor;
import xiaozhi.modules.companion.capability.entity.CapabilityEntity;
import xiaozhi.modules.companion.capability.entity.CapabilitySecretEntity;
import xiaozhi.modules.companion.capability.entity.CapabilityVersionEntity;
import xiaozhi.modules.companion.capability.entity.DeviceSkillMappingEntity;
import xiaozhi.modules.companion.capability.entity.DeviceToolSnapshotEntity;
import xiaozhi.modules.companion.capability.entity.McpServerEntity;
import xiaozhi.modules.companion.capability.entity.McpToolSnapshotEntity;
import xiaozhi.modules.companion.capability.entity.PluginDefinitionEntity;
import xiaozhi.modules.companion.capability.entity.SkillDefinitionEntity;
import xiaozhi.modules.companion.capability.entity.SkillToolMappingEntity;
import xiaozhi.modules.companion.capability.entity.SkillTriggerEntity;

class CapabilitySchemaContractTest {

    @Test
    void migrationDefinesCapabilityCatalogAndDeviceBindings() throws Exception {
        String sql = resource("/db/changelog/202608161100.sql");
        List<String> tables = List.of(
                "ai_capability",
                "ai_capability_version",
                "ai_skill_definition",
                "ai_skill_trigger",
                "ai_skill_tool_mapping",
                "ai_device_skill_mapping",
                "ai_plugin_definition",
                "ai_mcp_server",
                "ai_mcp_tool_snapshot",
                "ai_device_tool_snapshot",
                "ai_capability_secret");

        tables.forEach(table -> assertTrue(sql.contains("CREATE TABLE `" + table + "`"), table));
        assertTrue(sql.contains("UNIQUE KEY `uk_capability_version` (`capability_id`,`version_no`)"));
        assertTrue(sql.contains("UNIQUE KEY `uk_device_skill` (`device_id`,`skill_id`)"));
        assertTrue(sql.contains("CONSTRAINT `fk_device_skill_device` FOREIGN KEY (`device_id`) REFERENCES `ai_device` (`id`)"));
        assertTrue(sql.contains("CONSTRAINT `fk_device_tool_device` FOREIGN KEY (`device_id`) REFERENCES `ai_device` (`id`)"));
        assertTrue(sql.contains("`secret_ciphertext` longtext NOT NULL"));
        assertTrue(!sql.contains("`secret_plaintext`"));
        assertTrue(!sql.contains("`secret_value`"));
    }

    @Test
    void rollbackDropsDependentTablesBeforeCatalogRoots() throws Exception {
        String rollback = resource("/db/changelog/202608161100-rollback.sql");

        assertBefore(rollback, "DROP TABLE IF EXISTS `ai_device_tool_snapshot`", "DROP TABLE IF EXISTS `ai_capability`");
        assertBefore(rollback, "DROP TABLE IF EXISTS `ai_device_skill_mapping`", "DROP TABLE IF EXISTS `ai_skill_definition`");
        assertBefore(rollback, "DROP TABLE IF EXISTS `ai_skill_tool_mapping`", "DROP TABLE IF EXISTS `ai_capability`");
        assertBefore(rollback, "DROP TABLE IF EXISTS `ai_capability_secret`", "DROP TABLE IF EXISTS `ai_capability`");
        assertTrue(rollback.trim().endsWith("DROP TABLE IF EXISTS `ai_capability`;"));
    }

    @Test
    void liquibaseRegistersForwardAndRollbackResources() throws Exception {
        try (var accessor = new ClassLoaderResourceAccessor()) {
            var changeLog = new YamlChangeLogParser().parse("db/changelog/db.changelog-master.yaml",
                    new ChangeLogParameters(), accessor);
            var changeSet = changeLog.getChangeSets().stream()
                    .filter(candidate -> "202608161100".equals(candidate.getId()))
                    .findFirst()
                    .orElseThrow();

            assertEquals(1, changeSet.getChanges().size());
            var forward = assertInstanceOf(SQLFileChange.class, changeSet.getChanges().get(0));
            assertEquals("classpath:db/changelog/202608161100.sql", forward.getPath());
            assertEquals(1, changeSet.getRollback().getChanges().size());
            var reverse = assertInstanceOf(SQLFileChange.class, changeSet.getRollback().getChanges().get(0));
            assertEquals("classpath:db/changelog/202608161100-rollback.sql", reverse.getPath());
        }
    }

    @Test
    void entitiesMapToCapabilityTables() {
        assertTable(CapabilityEntity.class, "ai_capability");
        assertTable(CapabilityVersionEntity.class, "ai_capability_version");
        assertTable(SkillDefinitionEntity.class, "ai_skill_definition");
        assertTable(SkillTriggerEntity.class, "ai_skill_trigger");
        assertTable(SkillToolMappingEntity.class, "ai_skill_tool_mapping");
        assertTable(DeviceSkillMappingEntity.class, "ai_device_skill_mapping");
        assertTable(PluginDefinitionEntity.class, "ai_plugin_definition");
        assertTable(McpServerEntity.class, "ai_mcp_server");
        assertTable(McpToolSnapshotEntity.class, "ai_mcp_tool_snapshot");
        assertTable(DeviceToolSnapshotEntity.class, "ai_device_tool_snapshot");
        assertTable(CapabilitySecretEntity.class, "ai_capability_secret");
    }

    private static void assertTable(Class<?> type, String expected) {
        assertEquals(expected, type.getAnnotation(TableName.class).value());
    }

    private static void assertBefore(String text, String first, String second) {
        assertTrue(text.indexOf(first) >= 0, first);
        assertTrue(text.indexOf(second) >= 0, second);
        assertTrue(text.indexOf(first) < text.indexOf(second), first + " must precede " + second);
    }

    private static String resource(String path) throws IOException {
        try (var stream = CapabilitySchemaContractTest.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("Missing resource " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
