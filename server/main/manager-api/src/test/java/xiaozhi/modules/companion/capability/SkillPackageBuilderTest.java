package xiaozhi.modules.companion.capability;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import xiaozhi.modules.companion.capability.packagefile.SkillPackageBuilder;
import xiaozhi.modules.companion.capability.packagefile.SkillPackageParser;

class SkillPackageBuilderTest {

    @Test
    void buildsIdenticalCanonicalBytesForIdenticalContent() {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("id", "skill-weather");
        manifest.put("name", "Weather");
        manifest.put("version", 1);
        Map<String, byte[]> assets = Map.of("assets/note.txt", "note\r\n".getBytes(StandardCharsets.UTF_8));
        SkillPackageBuilder builder = new SkillPackageBuilder();

        byte[] first = builder.build(manifest, "# Weather\r\nUse weather.\r\n", assets);
        byte[] second = builder.build(manifest, "# Weather\r\nUse weather.\r\n", assets);

        assertArrayEquals(first, second);
        var parsed = new SkillPackageParser().parse(first);
        assertEquals("# Weather\nUse weather.\n", parsed.markdown());
    }
}
