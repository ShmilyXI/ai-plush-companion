package zixuan.modules.companion.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import zixuan.common.exception.RenException;
import zixuan.modules.companion.capability.packagefile.SkillPackageParser;

class SkillPackageParserTest {
    private final SkillPackageParser parser = new SkillPackageParser();

    @ParameterizedTest
    @ValueSource(strings = { "../secret.txt", "/tmp/secret.txt", "scripts/run.py", "bin/tool", "assets/archive.zip",
            "assets/run.php" })
    void rejectsUnsafeEntries(String name) {
        Map<String, String> files = validFiles();
        files.put(name, "payload");
        byte[] archive = zip(files);

        assertThrows(RenException.class, () -> parser.parse(archive));
    }

    @Test
    void rejectsArchivePastExpandedLimit() {
        byte[] archive = zip(Map.of("SKILL.md", "x".repeat(31 * 1024 * 1024)));

        assertThrows(RenException.class, () -> parser.parse(archive));
    }

    @Test
    void parsesCanonicalPackageWithoutExtractingIt() {
        Map<String, String> files = validFiles();
        files.put("assets/readme.txt", "asset");

        var document = parser.parse(zip(files));

        assertEquals("skill-weather", document.manifest().get("id"));
        assertEquals("# Weather\nUse the registered weather tool.\n", document.markdown());
        assertEquals("asset", new String(document.assets().get("assets/readme.txt"), StandardCharsets.UTF_8));
    }

    private static Map<String, String> validFiles() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("skill.yaml", "schemaVersion: 1\nid: skill-weather\nname: Weather\nversion: 1\n");
        files.put("SKILL.md", "# Weather\nUse the registered weather tool.\n");
        return files;
    }

    static byte[] zip(Map<String, String> files) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                for (var file : files.entrySet()) {
                    zip.putNextEntry(new ZipEntry(file.getKey()));
                    zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
