package zixuan.modules.websession.service.impl;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import zixuan.common.exception.ErrorCode;
import zixuan.common.exception.RenException;
import zixuan.modules.websession.service.WebSessionBootstrapService;
import zixuan.modules.websession.service.WebSessionStore;

@Service
public class WebSessionBootstrapServiceImpl implements WebSessionBootstrapService {
    private static final String CODE_PREFIX = "web-session:bootstrap:";
    private static final String TOKEN_PREFIX = "web-session:token:";
    private static final String AUDIENCE = "companion-web";
    private static final Set<String> LOCAL_ORIGINS = Set.of(
            "http://localhost:8010", "http://127.0.0.1:8010");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final WebSessionStore store;
    private final long bootstrapTtlSeconds;
    private final long credentialTtlSeconds;
    private final Set<String> allowedOrigins;

    public WebSessionBootstrapServiceImpl(WebSessionStore store) {
        this(store, 300, 900, LOCAL_ORIGINS);
    }

    @Autowired
    public WebSessionBootstrapServiceImpl(WebSessionStore store,
            @Value("${companion.web.allowed-origins:http://localhost:8010,http://127.0.0.1:8010}") String configuredOrigins) {
        this(store, 300, 900, parseOrigins(configuredOrigins));
    }

    public WebSessionBootstrapServiceImpl(WebSessionStore store, long bootstrapTtlSeconds,
            long credentialTtlSeconds) {
        this(store, bootstrapTtlSeconds, credentialTtlSeconds, LOCAL_ORIGINS);
    }

    public WebSessionBootstrapServiceImpl(WebSessionStore store, long bootstrapTtlSeconds,
            long credentialTtlSeconds, Set<String> allowedOrigins) {
        if (store == null || bootstrapTtlSeconds <= 0 || credentialTtlSeconds <= 0
                || allowedOrigins == null || allowedOrigins.isEmpty()) {
            throw new IllegalArgumentException("web session configuration is invalid");
        }
        this.store = store;
        this.bootstrapTtlSeconds = bootstrapTtlSeconds;
        this.credentialTtlSeconds = credentialTtlSeconds;
        this.allowedOrigins = Set.copyOf(allowedOrigins);
    }

    @Override
    public Bootstrap issue(Long userId) {
        return issue(userId, AUDIENCE, LOCAL_ORIGINS.iterator().next());
    }

    @Override
    public Bootstrap issue(Long userId, String audience, String origin) {
        if (userId == null || userId <= 0) {
            throw new RenException(ErrorCode.UNAUTHORIZED);
        }
        if (!AUDIENCE.equals(audience) || !isAllowedOrigin(origin)) {
            throw new RenException(ErrorCode.UNAUTHORIZED);
        }
        String code = randomSecret();
        store.put(CODE_PREFIX + digest(code), userId.toString(), bootstrapTtlSeconds);
        return new Bootstrap(code, bootstrapTtlSeconds);
    }

    @Override
    public Exchange exchange(String code) {
        if (StringUtils.isBlank(code)) {
            throw new RenException(ErrorCode.UNAUTHORIZED);
        }
        String userId = store.getAndDelete(CODE_PREFIX + digest(code.trim()));
        long parsedUserId;
        try {
            parsedUserId = Long.parseLong(userId == null ? "" : userId);
        } catch (NumberFormatException error) {
            throw new RenException(ErrorCode.UNAUTHORIZED);
        }
        if (parsedUserId <= 0) {
            throw new RenException(ErrorCode.UNAUTHORIZED);
        }
        String accessToken = "web_" + randomSecret();
        store.put(TOKEN_PREFIX + digest(accessToken), Long.toString(parsedUserId), credentialTtlSeconds);
        return new Exchange(accessToken, credentialTtlSeconds);
    }

    @Override
    public Long resolve(String accessToken) {
        if (StringUtils.isBlank(accessToken) || !accessToken.startsWith("web_")) {
            return null;
        }
        String value = store.get(TOKEN_PREFIX + digest(accessToken));
        if (StringUtils.isBlank(value)) return null;
        try {
            long userId = Long.parseLong(value);
            return userId > 0 ? userId : null;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String digest(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception error) {
            throw new IllegalStateException("cannot hash web session artifact", error);
        }
    }

    private boolean isAllowedOrigin(String origin) {
        if (StringUtils.isBlank(origin) || !allowedOrigins.contains(origin)) return false;
        try {
            URI parsed = URI.create(origin);
            return parsed.isAbsolute() && ("http".equalsIgnoreCase(parsed.getScheme())
                    || "https".equalsIgnoreCase(parsed.getScheme()))
                    && StringUtils.isNotBlank(parsed.getHost())
                    && parsed.getRawPath().isEmpty()
                    && parsed.getRawQuery() == null
                    && parsed.getRawFragment() == null
                    && parsed.getRawUserInfo() == null;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static Set<String> parseOrigins(String configuredOrigins) {
        if (StringUtils.isBlank(configuredOrigins)) return LOCAL_ORIGINS;
        Set<String> origins = java.util.Arrays.stream(configuredOrigins.split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return origins.isEmpty() ? LOCAL_ORIGINS : origins;
    }
}
