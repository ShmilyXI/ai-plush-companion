package xiaozhi.modules.config.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class CompanionNamespace {
    private CompanionNamespace() {
    }

    public static String create(Long userId, String agentId, String deviceId) {
        if (userId == null || agentId == null || agentId.isBlank()
                || deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("companion identity is incomplete");
        }
        String source = userId + "\n" + agentId + "\n" + deviceId;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return "companion:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
