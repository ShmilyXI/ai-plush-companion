package xiaozhi.modules.companion.capability.packagefile;

import java.util.Map;

public record SkillPackageDocument(
        Map<String, Object> manifest,
        String markdown,
        Map<String, byte[]> assets,
        String sha256,
        long archiveSize) {
}
