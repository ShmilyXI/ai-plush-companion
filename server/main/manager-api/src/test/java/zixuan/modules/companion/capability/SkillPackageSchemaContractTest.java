package zixuan.modules.companion.capability;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.baomidou.mybatisplus.annotation.TableName;

import zixuan.modules.companion.capability.entity.SkillPackageEntity;

class SkillPackageSchemaContractTest {

    @Test
    void migrationCreatesImmutableSkillPackageMetadata() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/changelog/202608181500.sql"));

        assertTrue(sql.contains("CREATE TABLE `ai_skill_package`"));
        assertTrue(sql.contains("UNIQUE KEY `uk_skill_package_version` (`capability_id`,`version_no`)"));
        assertTrue(sql.contains("`package_sha256` char(64) NOT NULL"));
        assertTrue(sql.contains("`storage_key` varchar(500) NOT NULL"));
    }

    @Test
    void entityMapsToSkillPackageTable() {
        assertTrue(SkillPackageEntity.class.isAnnotationPresent(TableName.class));
        assertTrue("ai_skill_package".equals(SkillPackageEntity.class.getAnnotation(TableName.class).value()));
    }
}
