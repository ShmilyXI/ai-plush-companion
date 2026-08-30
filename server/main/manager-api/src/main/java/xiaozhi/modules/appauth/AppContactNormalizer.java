package xiaozhi.modules.appauth;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Normalizes the two contact forms accepted by the consumer application. */
@Component
public final class AppContactNormalizer {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    public record NormalizedContact(String channel, String value) {
    }

    public NormalizedContact normalize(String channel, String raw, String countryCode) {
        if (channel == null || raw == null) {
            throw new IllegalArgumentException("contact is required");
        }
        return switch (channel.trim().toLowerCase(Locale.ROOT)) {
            case "email" -> new NormalizedContact("email", normalizeEmail(raw));
            case "phone" -> new NormalizedContact("phone", normalizePhone(raw, countryCode));
            default -> throw new IllegalArgumentException("unsupported contact channel");
        };
    }

    private String normalizeEmail(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.length() > 320 || !EMAIL.matcher(value).matches()) {
            throw new IllegalArgumentException("email format is invalid");
        }
        return value;
    }

    private String normalizePhone(String raw, String countryCode) {
        String value = raw.trim().replaceAll("[\\s()\\-]", "");
        if (value.startsWith("+")) {
            if (!E164.matcher(value).matches()) throw new IllegalArgumentException("phone format is invalid");
            return value;
        }
        String prefix = countryCode == null ? "" : countryCode.trim().replaceAll("[\\s()\\-]", "");
        if (!prefix.startsWith("+")) prefix = "+" + prefix;
        if (!prefix.matches("\\+[1-9]\\d{0,3}") || !value.matches("\\d{6,14}")) {
            throw new IllegalArgumentException("phone format is invalid");
        }
        String normalized = prefix + value.replaceFirst("^0+(?=\\d)", "");
        if (!E164.matcher(normalized).matches()) throw new IllegalArgumentException("phone format is invalid");
        return normalized;
    }
}
