package xiaozhi.modules.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import cn.hutool.json.JSONUtil;

class TencentDbMemoryMigrationContractTest {
    private static final Path CHANGELOG = Path.of("src/main/resources/db/changelog");

    @Test
    void migrationRegistersTencentDbMemoryWithExactEditableFields() throws Exception {
        String forward = Files.readString(CHANGELOG.resolve("202608141000.sql"));
        String rollback = Files.readString(CHANGELOG.resolve("202608141000-rollback.sql"));
        String master = Files.readString(CHANGELOG.resolve("db.changelog-master.yaml"));

        assertTrue(forward.contains("SYSTEM_Memory_tencentdb"));
        assertTrue(forward.contains("Memory_tencentdb"));
        assertTrue(forward.contains("'Memory', 'tencentdb'"));
        assertTrue(forward.contains("\"type\":\"tencentdb\""));
        assertTrue(forward.contains("\"key\":\"memory_core_api_key\""));
        assertTrue(forward.contains("\"key\":\"llm_api_key\""));
        assertTrue(forward.contains("\"key\":\"embedding_api_key\""));
        assertTrue(forward.contains("\"type\":\"password\""));
        assertTrue(forward.contains("\"key\":\"embedding_send_dimensions\""));
        assertTrue(rollback.indexOf("Memory_tencentdb") < rollback.indexOf("SYSTEM_Memory_tencentdb"));
        assertTrue(master.contains("id: 202608141000"));

        var matcher = Pattern.compile("'SYSTEM_Memory_tencentdb'.*?'(\\[\\{.*?}\\])'", Pattern.DOTALL)
                .matcher(forward);
        assertTrue(matcher.find());
        var fields = JSONUtil.parseArray(matcher.group(1));
        assertEquals(List.of(
                "memory_core_url",
                "memory_core_api_key",
                "llm_base_url",
                "llm_api_key",
                "llm_model",
                "embedding_base_url",
                "embedding_api_key",
                "embedding_model",
                "embedding_dimensions",
                "embedding_send_dimensions"),
                fields.stream().map(value -> JSONUtil.parseObj(value).getStr("key")).toList());
    }

    @Test
    void migrationRegistersManagedEmbeddingAndMemoryReferences() throws Exception {
        String forward = Files.readString(CHANGELOG.resolve("202608160100.sql"));
        String rollback = Files.readString(CHANGELOG.resolve("202608160100-rollback.sql"));
        String master = Files.readString(CHANGELOG.resolve("db.changelog-master.yaml"));

        assertTrue(forward.contains("SYSTEM_Embedding_openai"));
        assertTrue(forward.contains("Embedding_openai"));
        assertTrue(forward.contains("'Embedding', 'openai'"));
        assertTrue(forward.contains("\"key\":\"llm_model_id\""));
        assertTrue(forward.contains("\"key\":\"embedding_model_id\""));
        assertTrue(forward.contains("JSON_SET"));
        assertTrue(rollback.indexOf("Embedding_openai") < rollback.indexOf("SYSTEM_Embedding_openai"));
        assertTrue(master.contains("id: 202608160100"));
    }
}
