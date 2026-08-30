package xiaozhi.modules.appauth;
public record AppAuthTokenVO(String accessToken, String accessExpiresAt, String refreshToken,
        String refreshExpiresAt, Long userId, AppAuthUserVO user) {
    public AppAuthTokenVO(String accessToken, String accessExpiresAt, String refreshToken,
            String refreshExpiresAt, Long userId) {
        this(accessToken, accessExpiresAt, refreshToken, refreshExpiresAt, userId, null);
    }
}
