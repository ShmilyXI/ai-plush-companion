package xiaozhi.modules.websession.service.impl;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

import xiaozhi.modules.websession.service.WebSessionStore;

/**
 * Redis store whose consume operation is atomic on Redis 6.2+.
 */
@Component
public class RedisWebSessionStore implements WebSessionStore {
    private final RedisTemplate<String, Object> redis;

    public RedisWebSessionStore(RedisTemplate<String, Object> redis) {
        this.redis = redis;
    }

    @Override
    public void put(String key, String value, long ttlSeconds) {
        redis.opsForValue().set(key, value, ttlSeconds, TimeUnit.SECONDS);
    }

    @Override
    public String get(String key) {
        Object value = redis.opsForValue().get(key);
        return value == null ? null : value.toString();
    }

    @Override
    public String getAndDelete(String key) {
        Object value = redis.opsForValue().getAndDelete(key);
        return value == null ? null : value.toString();
    }
}
