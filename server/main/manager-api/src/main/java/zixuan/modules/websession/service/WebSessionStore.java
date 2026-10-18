package zixuan.modules.websession.service;

/**
 * Small storage seam for short-lived web session artifacts.
 */
public interface WebSessionStore {
    void put(String key, String value, long ttlSeconds);

    String get(String key);

    String getAndDelete(String key);
}
