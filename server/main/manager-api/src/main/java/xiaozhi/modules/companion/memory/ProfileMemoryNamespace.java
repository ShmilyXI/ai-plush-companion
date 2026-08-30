package xiaozhi.modules.companion.memory;

/** Canonical memory identity shared by App and hardware entry points. */
public final class ProfileMemoryNamespace {
    private ProfileMemoryNamespace() {
    }

    public static String of(Long userId, String profileId) {
        if (userId == null || userId < 1 || profileId == null || profileId.isBlank()) {
            throw new IllegalArgumentException("user and profile are required");
        }
        return "companion:" + userId + ":" + profileId.trim();
    }
}
