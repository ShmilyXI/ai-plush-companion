package xiaozhi.modules.websession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import xiaozhi.common.exception.RenException;
import xiaozhi.common.utils.MessageUtils;
import xiaozhi.modules.websession.service.WebSessionStore;
import xiaozhi.modules.websession.service.impl.WebSessionBootstrapServiceImpl;

class WebSessionBootstrapServiceTest {
    @Test
    void issuesShortLivedCodeAndExchangesItOnce() {
        MemoryStore store = new MemoryStore();
        WebSessionBootstrapServiceImpl service = new WebSessionBootstrapServiceImpl(store, 60, 900,
                Set.of("https://companion.example"));

        var bootstrap = service.issue(7L, "companion-web", "https://companion.example");
        var exchanged = service.exchange(bootstrap.code());

        assertTrue(bootstrap.code().length() >= 32);
        assertEquals(60L, store.ttlByKey.values().stream().filter(ttl -> ttl == 60L).findFirst().orElse(-1L));
        assertEquals(900L, store.ttlByKey.values().stream().filter(ttl -> ttl == 900L).findFirst().orElse(-1L));
        assertNotEquals(bootstrap.code(), exchanged.accessToken());
        assertEquals(7L, service.resolve(exchanged.accessToken()));
        try (MockedStatic<MessageUtils> messages = Mockito.mockStatic(MessageUtils.class)) {
            messages.when(() -> MessageUtils.getMessage(Mockito.anyInt())).thenReturn("unauthorized");
            assertThrows(RenException.class, () -> service.exchange(bootstrap.code()));
        }
    }

    @Test
    void rejectsBlankAndUnknownCredentials() {
        WebSessionBootstrapServiceImpl service = new WebSessionBootstrapServiceImpl(new MemoryStore(), 60, 900,
                Set.of("https://companion.example"));

        try (MockedStatic<MessageUtils> messages = Mockito.mockStatic(MessageUtils.class)) {
            messages.when(() -> MessageUtils.getMessage(Mockito.anyInt())).thenReturn("unauthorized");
            assertThrows(RenException.class, () -> service.issue(7L, "other-app", "https://companion.example"));
            assertThrows(RenException.class, () -> service.issue(7L, "companion-web", "https://evil.example/path"));
            assertThrows(RenException.class, () -> service.exchange(" "));
            assertThrows(RenException.class, () -> service.exchange("unknown-code"));
        }
        assertEquals(null, service.resolve("unknown-token"));
    }

    private static final class MemoryStore implements WebSessionStore {
        private final Map<String, String> values = new HashMap<>();
        private final Map<String, Long> ttlByKey = new HashMap<>();

        @Override
        public void put(String key, String value, long ttlSeconds) {
            values.put(key, value);
            ttlByKey.put(key, ttlSeconds);
        }

        @Override
        public String get(String key) {
            return values.get(key);
        }

        @Override
        public String getAndDelete(String key) {
            return values.remove(key);
        }
    }
}
