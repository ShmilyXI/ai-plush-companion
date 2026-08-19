package xiaozhi.modules.companion.capability.packagefile;

public interface SkillPackageStore {
    String put(String skillId, int version, String sha256, byte[] bytes);

    byte[] get(String storageKey);

    void delete(String storageKey);
}
