package xiaozhi.modules.companion.service.impl;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import xiaozhi.common.exception.RenException;

/** Stores consumer profile avatars outside the database and serves only
 * checksum-addressed files. */
@Service
public class FileProfileAvatarStore {
    private static final Pattern SAFE_PROFILE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Map<String, String> MIME_EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");

    private final Path root;

    @Autowired
    public FileProfileAvatarStore() {
        this(Path.of("uploadfile", "avatars"));
    }

    public FileProfileAvatarStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public void save(String profileId, String checksum, String contentType, byte[] content) {
        validateIdentifiers(profileId, checksum);
        String normalizedType = normalizeContentType(contentType);
        if (content == null || content.length == 0 || content.length > 2 * 1024 * 1024) {
            throw new RenException("头像大小必须在 1B 到 2MB 之间");
        }
        rejectExecutableContent(content);
        String actual = sha256(content);
        if (!actual.equalsIgnoreCase(checksum)) {
            throw new RenException("头像校验失败");
        }
        Path directory = profileDirectory(profileId);
        Path target = directory.resolve(checksum.toLowerCase(Locale.ROOT) + "." + MIME_EXTENSIONS.get(normalizedType))
                .normalize();
        ensureManagedPath(target, directory);
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            ensureManagedDirectory(directory);
            temporary = Files.createTempFile(directory, ".avatar-", ".tmp");
            Files.write(temporary, content, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new RenException("头像存储失败", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The move normally removes the temporary file.
                }
            }
        }
    }

    public Optional<StoredAvatar> load(String profileId, String checksum) {
        try {
            validateIdentifiers(profileId, checksum);
            Path directory = profileDirectory(profileId);
            ensureManagedDirectory(directory);
            String normalizedChecksum = checksum.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, String> entry : MIME_EXTENSIONS.entrySet()) {
                Path candidate = directory.resolve(normalizedChecksum + "." + entry.getValue()).normalize();
                if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) continue;
                Path real = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
                if (!real.startsWith(root.toRealPath()) || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                byte[] content = Files.readAllBytes(real);
                if (!sha256(content).equalsIgnoreCase(normalizedChecksum)) continue;
                return Optional.of(new StoredAvatar(content, entry.getKey(), real));
            }
            return Optional.empty();
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    private Path profileDirectory(String profileId) {
        Path directory = root.resolve(profileId).normalize();
        ensureManagedPath(directory, root);
        return directory;
    }

    private void ensureManagedDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("avatar directory missing");
        }
        Path managedRoot = root.toRealPath();
        Path realDirectory = directory.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!realDirectory.startsWith(managedRoot)) throw new IOException("avatar directory escaped root");
    }

    private void ensureManagedPath(Path path, Path parent) {
        if (!path.startsWith(parent) || !parent.equals(path.getParent())) {
            throw new RenException("头像资源路径不合法");
        }
    }

    private static void validateIdentifiers(String profileId, String checksum) {
        if (profileId == null || !SAFE_PROFILE_ID.matcher(profileId).matches()
                || checksum == null || !SHA256.matcher(checksum).matches()) {
            throw new RenException("头像资源路径不合法");
        }
    }

    private static String normalizeContentType(String contentType) {
        String normalized = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).trim();
        if (!MIME_EXTENSIONS.containsKey(normalized)) throw new RenException("头像格式不支持");
        return normalized;
    }

    private static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("头像校验失败", exception);
        }
    }

    private static void rejectExecutableContent(byte[] content) {
        boolean mz = content.length >= 2 && content[0] == 'M' && content[1] == 'Z';
        boolean elf = content.length >= 4 && content[0] == 0x7f && content[1] == 'E'
                && content[2] == 'L' && content[3] == 'F';
        boolean zip = content.length >= 4 && content[0] == 'P' && content[1] == 'K'
                && content[2] == 0x03 && content[3] == 0x04;
        boolean script = content.length >= 2 && content[0] == '#' && content[1] == '!';
        if (mz || elf || zip || script) throw new RenException("头像内容不合法");
    }

    public record StoredAvatar(byte[] content, String contentType, Path path) {
    }
}
