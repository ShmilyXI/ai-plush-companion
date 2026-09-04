package zixuan.modules.companion.capability.packagefile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import zixuan.common.exception.RenException;

@Component
public class LocalSkillPackageStore implements SkillPackageStore {
    private static final Pattern SKILL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");

    private final Path root;

    @Autowired
    public LocalSkillPackageStore(@Value("${companion.skill-package.storage-root:data/skill-packages}") String root) {
        this(Path.of(root));
    }

    public LocalSkillPackageStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public String put(String skillId, int version, String sha256, byte[] bytes) {
        if (skillId == null || !SKILL_ID.matcher(skillId).matches() || version < 1 || sha256 == null
                || !SHA256.matcher(sha256).matches() || bytes == null) {
            throw new RenException("Skill 包存储参数无效");
        }
        String key = skillId + "/" + version + "/" + sha256 + ".skill.zip";
        Path target = resolve(key);
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), ".skill-package-", ".tmp");
            Files.write(temporary, bytes);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return key;
        } catch (IOException exception) {
            throw new RenException("Skill 包保存失败", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The target write already determines the operation result.
                }
            }
        }
    }

    @Override
    public byte[] get(String storageKey) {
        try {
            Path target = resolve(storageKey);
            if (!Files.isRegularFile(target)) {
                throw new RenException("Skill 包文件不存在");
            }
            return Files.readAllBytes(target);
        } catch (RenException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new RenException("Skill 包读取失败", exception);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException exception) {
            throw new RenException("Skill 包删除失败", exception);
        }
    }

    private Path resolve(String storageKey) {
        if (storageKey == null || storageKey.isBlank() || Path.of(storageKey).isAbsolute()
                || storageKey.indexOf('\\') >= 0) {
            throw new RenException("Skill 包存储键无效");
        }
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root)) {
            throw new RenException("Skill 包存储键越界");
        }
        return target;
    }
}
