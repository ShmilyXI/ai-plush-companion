package xiaozhi.modules.companion.memory;

import java.util.regex.Pattern;

/** Canonical memory identity shared by App and hardware entry points. */
public final class ProfileMemoryNamespace {
    private static final Pattern PROFILE_ID = Pattern.compile("[A-Za-z0-9_-]+");

    private ProfileMemoryNamespace() {
    }

    public static String of(Long userId, String profileId) {
        String normalizedProfileId = profileId == null ? null : profileId.trim();
        if (userId == null || userId < 1 || normalizedProfileId == null
                || normalizedProfileId.isBlank() || !PROFILE_ID.matcher(normalizedProfileId).matches()) {
            throw new IllegalArgumentException("user and profile are required");
        }
        return "companion:" + userId + ":" + normalizedProfileId;
    }
}
