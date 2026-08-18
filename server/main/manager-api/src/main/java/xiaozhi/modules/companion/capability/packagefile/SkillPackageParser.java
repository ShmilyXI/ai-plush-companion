package xiaozhi.modules.companion.capability.packagefile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.springframework.stereotype.Component;

import xiaozhi.common.exception.RenException;

@Component
public class SkillPackageParser {
    private static final Set<String> ROOT_FILES = Set.of("skill.yaml", "SKILL.md");
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "schemaVersion", "id", "name", "version", "description", "runtime", "triggers", "tools",
            "deviceRequirements", "secretRefs", "assets");
    private static final Set<String> FORBIDDEN_EXTENSIONS = Set.of(
            ".py", ".pyc", ".js", ".mjs", ".cjs", ".sh", ".bash", ".zsh", ".exe", ".dll",
            ".dylib", ".so", ".jar", ".class", ".wasm", ".zip", ".tar", ".gz", ".tgz", ".rar", ".7z");
    private static final Set<String> RESOURCE_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".webp", ".gif", ".txt", ".md", ".json", ".yaml", ".yml", ".csv");

    public SkillPackageDocument parse(byte[] archive) {
        if (archive == null || archive.length == 0 || archive.length > SkillPackageLimits.MAX_ARCHIVE_BYTES) {
            throw invalid("Skill 包大小无效");
        }
        inspectCentralDirectory(archive);

        Map<String, byte[]> files = readEntries(archive);
        byte[] manifestBytes = files.remove("skill.yaml");
        byte[] markdownBytes = files.remove("SKILL.md");
        if (manifestBytes == null || markdownBytes == null) {
            throw invalid("Skill 包根目录必须包含 skill.yaml 和 SKILL.md");
        }

        Map<String, Object> manifest = parseManifest(decodeUtf8(manifestBytes, "skill.yaml"));
        String markdown = decodeUtf8(markdownBytes, "SKILL.md");
        return new SkillPackageDocument(Collections.unmodifiableMap(new LinkedHashMap<>(manifest)), markdown,
                Collections.unmodifiableMap(new LinkedHashMap<>(files)), sha256(archive), archive.length);
    }

    private Map<String, byte[]> readEntries(byte[] archive) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        long totalExpanded = 0;
        int count = 0;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String name = normalizeEntryName(entry.getName());
                if (entry.isDirectory()) {
                    continue;
                }
                if (++count > SkillPackageLimits.MAX_FILES) {
                    throw invalid("Skill 包文件数量超过限制");
                }
                requireAllowedPath(name);
                if (files.containsKey(name)) {
                    throw invalid("Skill 包包含重复路径: " + name);
                }
                byte[] bytes = readBounded(input, name);
                totalExpanded += bytes.length;
                if (totalExpanded > SkillPackageLimits.MAX_EXPANDED_BYTES) {
                    throw invalid("Skill 包解压后大小超过限制");
                }
                files.put(name, bytes);
            }
        } catch (RenException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            throw new RenException("Skill 包 ZIP 无法读取", exception);
        }
        return files;
    }

    private byte[] readBounded(ZipInputStream input, String name) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if ((long) output.size() + read > SkillPackageLimits.MAX_FILE_BYTES) {
                throw invalid("Skill 包文件超过大小限制: " + name);
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private String normalizeEntryName(String rawName) {
        if (rawName == null || rawName.isBlank() || rawName.indexOf('\\') >= 0 || rawName.indexOf('\0') >= 0
                || rawName.startsWith("/") || rawName.matches("^[A-Za-z]:.*")) {
            throw invalid("Skill 包路径无效");
        }
        Path normalized = Path.of(rawName).normalize();
        String name = normalized.toString().replace('\\', '/');
        if (name.isBlank() || name.equals(".") || name.equals("..") || name.startsWith("../")
                || !name.equals(rawName)) {
            throw invalid("Skill 包路径无效: " + rawName);
        }
        return name;
    }

    private void requireAllowedPath(String name) {
        boolean resource = name.startsWith("assets/") || name.startsWith("examples/");
        if (!ROOT_FILES.contains(name) && !resource) {
            throw invalid("Skill 包包含未允许的路径: " + name);
        }
        String fileName = name.substring(name.lastIndexOf('/') + 1);
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0) {
            throw invalid("Skill 包不允许无扩展名文件: " + name);
        }
        String extension = fileName.substring(extensionStart).toLowerCase(Locale.ROOT);
        if (FORBIDDEN_EXTENSIONS.contains(extension)) {
            throw invalid("Skill 包不允许可执行或嵌套归档文件: " + name);
        }
        if (resource && !RESOURCE_EXTENSIONS.contains(extension)) {
            throw invalid("Skill 包资源类型不受支持: " + name);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseManifest(String yamlText) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(20);
        options.setNestingDepthLimit(30);
        options.setCodePointLimit((int) SkillPackageLimits.MAX_FILE_BYTES);
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options)).load(yamlText);
        } catch (RuntimeException exception) {
            throw new RenException("skill.yaml 格式无效", exception);
        }
        if (!(loaded instanceof Map<?, ?> raw)) {
            throw invalid("skill.yaml 顶层必须是对象");
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !TOP_LEVEL_FIELDS.contains(key)) {
                throw invalid("skill.yaml 包含未知顶层字段: " + entry.getKey());
            }
            manifest.put(key, entry.getValue());
        }
        return manifest;
    }

    private String decodeUtf8(byte[] bytes, String name) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new RenException(name + " 必须使用 UTF-8 编码", exception);
        }
    }

    private void inspectCentralDirectory(byte[] archive) {
        int eocd = findEndOfCentralDirectory(archive);
        ByteBuffer end = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
        int disk = Short.toUnsignedInt(end.getShort(eocd + 4));
        int centralDisk = Short.toUnsignedInt(end.getShort(eocd + 6));
        int entries = Short.toUnsignedInt(end.getShort(eocd + 10));
        long centralOffset = Integer.toUnsignedLong(end.getInt(eocd + 16));
        int eocdCommentLength = Short.toUnsignedInt(end.getShort(eocd + 20));
        if (eocd + 22 + eocdCommentLength != archive.length) {
            throw invalid("Skill 包 ZIP 结束目录无效");
        }
        if (disk != 0 || centralDisk != 0 || entries == 0xffff || centralOffset == 0xffffffffL) {
            throw invalid("Skill 包不支持多卷或 ZIP64 格式");
        }
        int offset = Math.toIntExact(centralOffset);
        for (int index = 0; index < entries; index++) {
            requireRange(archive, offset, 46);
            if (end.getInt(offset) != 0x02014b50) {
                throw invalid("Skill 包中央目录无效");
            }
            int madeBy = Short.toUnsignedInt(end.getShort(offset + 4));
            int flags = Short.toUnsignedInt(end.getShort(offset + 8));
            int method = Short.toUnsignedInt(end.getShort(offset + 10));
            int nameLength = Short.toUnsignedInt(end.getShort(offset + 28));
            int extraLength = Short.toUnsignedInt(end.getShort(offset + 30));
            int commentLength = Short.toUnsignedInt(end.getShort(offset + 32));
            long externalAttributes = Integer.toUnsignedLong(end.getInt(offset + 38));
            if ((flags & 1) != 0) {
                throw invalid("Skill 包不能加密");
            }
            if (method != ZipEntry.STORED && method != ZipEntry.DEFLATED) {
                throw invalid("Skill 包使用了不支持的压缩算法");
            }
            int platform = madeBy >>> 8;
            int unixType = (int) ((externalAttributes >>> 16) & 0170000);
            if (platform == 3 && unixType != 0 && unixType != 0100000 && unixType != 0040000) {
                throw invalid("Skill 包不能包含链接或特殊文件");
            }
            int entryLength = 46 + nameLength + extraLength + commentLength;
            requireRange(archive, offset, entryLength);
            offset += entryLength;
        }
    }

    private int findEndOfCentralDirectory(byte[] archive) {
        int minimum = Math.max(0, archive.length - 65557);
        for (int offset = archive.length - 22; offset >= minimum; offset--) {
            if ((archive[offset] & 0xff) == 0x50 && (archive[offset + 1] & 0xff) == 0x4b
                    && (archive[offset + 2] & 0xff) == 0x05 && (archive[offset + 3] & 0xff) == 0x06) {
                return offset;
            }
        }
        throw invalid("Skill 包不是有效 ZIP");
    }

    private void requireRange(byte[] archive, int offset, int length) {
        if (offset < 0 || length < 0 || offset > archive.length - length) {
            throw invalid("Skill 包 ZIP 目录越界");
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private RenException invalid(String message) {
        return new RenException(message);
    }
}
