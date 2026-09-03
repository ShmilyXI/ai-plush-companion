package xiaozhi.modules.companion.wakeword.service.impl;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import cn.hutool.crypto.digest.DigestUtil;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.companion.wakeword.service.WakeWordAssetStore;

@Service
public class FileWakeWordAssetStore implements WakeWordAssetStore {
    private static final Pattern SAFE_DEVICE_ID = Pattern.compile("[A-Za-z0-9._:-]+");
    private static final Path LEGACY_ROOT = Path.of("/workspace", "uploadfile", "wake-word")
            .toAbsolutePath().normalize();
    private final Path root;

    public FileWakeWordAssetStore() {
        this(Path.of("uploadfile", "wake-word"));
    }

    public FileWakeWordAssetStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public String store(String deviceId, long version, byte[] content, String expectedSha256) {
        if (deviceId == null || !SAFE_DEVICE_ID.matcher(deviceId).matches()) {
            throw new RenException("设备ID不合法");
        }
        String actualSha256 = DigestUtil.sha256Hex(content);
        if (!actualSha256.equals(expectedSha256)) {
            throw new RenException("唤醒词资源哈希不匹配");
        }
        try {
            Files.createDirectories(root);
            Path target = root.resolve(deviceId + "-" + version + "-" + actualSha256 + ".bin").normalize();
            if (!target.startsWith(root) || !root.equals(target.getParent())) {
                throw new RenException("唤醒词资源路径不合法");
            }
            try {
                Files.write(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException ignored) {
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                        || !Arrays.equals(content, Files.readAllBytes(target))) {
                    throw new RenException("已有唤醒词资源内容不一致");
                }
            }
            return target.toString();
        } catch (IOException exception) {
            throw new RenException("唤醒词资源存储失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public Path resolve(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            throw new RenException("唤醒词资源不存在");
        }
        try {
            Path managedRoot = root.toRealPath();
            Path candidate = Path.of(storedPath).toAbsolutePath().normalize();
            if (!candidate.startsWith(root)) {
                if (candidate.getFileName() == null || !LEGACY_ROOT.equals(candidate.getParent())) {
                    throw new RenException("唤醒词资源路径不合法");
                }
                candidate = root.resolve(candidate.getFileName()).normalize();
            }
            Path resolved = candidate.toRealPath();
            if (!resolved.startsWith(managedRoot)
                    || !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
                throw new RenException("唤醒词资源路径不合法");
            }
            return resolved;
        } catch (IOException exception) {
            throw new RenException("唤醒词资源不存在", exception);
        }
    }
}
