package zixuan.modules.companion.capability.packagefile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.springframework.stereotype.Component;

import zixuan.common.exception.RenException;

@Component
public class SkillPackageBuilder {

    public byte[] build(Map<String, Object> manifest, String markdown, Map<String, byte[]> assets) {
        if (manifest == null || markdown == null || assets == null) {
            throw new RenException("Skill 包内容不能为空");
        }
        Map<String, byte[]> files = new TreeMap<>();
        files.put("skill.yaml", normalizeLf(dumpManifest(manifest)).getBytes(StandardCharsets.UTF_8));
        files.put("SKILL.md", normalizeLf(markdown).getBytes(StandardCharsets.UTF_8));
        assets.forEach((path, bytes) -> {
            if (path == null || bytes == null || files.putIfAbsent(path, normalizeAsset(path, bytes)) != null) {
                throw new RenException("Skill 包资源路径重复或无效");
            }
        });

        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                zip.setLevel(9);
                for (var file : files.entrySet()) {
                    ZipEntry entry = new ZipEntry(file.getKey());
                    entry.setTime(0L);
                    zip.putNextEntry(entry);
                    zip.write(file.getValue());
                    zip.closeEntry();
                }
            }
            byte[] archive = output.toByteArray();
            if (archive.length > SkillPackageLimits.MAX_ARCHIVE_BYTES) {
                throw new RenException("Skill 包压缩后大小超过限制");
            }
            return archive;
        } catch (IOException exception) {
            throw new RenException("Skill 包构建失败", exception);
        }
    }

    private String dumpManifest(Map<String, Object> manifest) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setSplitLines(false);
        options.setIndent(2);
        options.setIndicatorIndent(0);
        options.setLineBreak(DumperOptions.LineBreak.UNIX);
        return new Yaml(options).dump(canonicalize(manifest));
    }

    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, child) -> sorted.put(String.valueOf(key), canonicalize(child)));
            return new LinkedHashMap<>(sorted);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> values = new ArrayList<>(collection.size());
            collection.forEach(child -> values.add(canonicalize(child)));
            return values;
        }
        return value;
    }

    private byte[] normalizeAsset(String path, byte[] bytes) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".json")
                || lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".csv")) {
            return normalizeLf(new String(bytes, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        }
        return bytes.clone();
    }

    private String normalizeLf(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }
}
