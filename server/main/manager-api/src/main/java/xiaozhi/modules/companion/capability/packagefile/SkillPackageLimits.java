package xiaozhi.modules.companion.capability.packagefile;

public final class SkillPackageLimits {
    public static final long MAX_ARCHIVE_BYTES = 10L * 1024 * 1024;
    public static final long MAX_EXPANDED_BYTES = 30L * 1024 * 1024;
    public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    public static final int MAX_FILES = 200;

    private SkillPackageLimits() {
    }
}
