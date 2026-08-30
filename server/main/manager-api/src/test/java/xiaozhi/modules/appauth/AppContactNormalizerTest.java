package xiaozhi.modules.appauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AppContactNormalizerTest {

    private final AppContactNormalizer normalizer = new AppContactNormalizer();

    @Test
    void normalizesEmailByTrimmingAndLowercasing() {
        assertEquals(new AppContactNormalizer.NormalizedContact("email", "user@example.com"),
                normalizer.normalize("email", "  User@Example.COM ", null));
    }

    @Test
    void normalizesChinesePhoneToE164() {
        assertEquals(new AppContactNormalizer.NormalizedContact("phone", "+8613800138000"),
                normalizer.normalize("phone", "13800138000", "+86"));
    }

    @Test
    void rejectsUnsupportedOrMalformedContacts() {
        assertThrows(IllegalArgumentException.class,
                () -> normalizer.normalize("fax", "123", null));
        assertThrows(IllegalArgumentException.class,
                () -> normalizer.normalize("email", "not-an-email", null));
        assertThrows(IllegalArgumentException.class,
                () -> normalizer.normalize("phone", "123", "+86"));
    }
}
