package xiaozhi.modules.appauth;
public record AppAuthTokenVO(String accessToken, String accessExpiresAt, String refreshToken, String refreshExpiresAt, Long userId) {}
