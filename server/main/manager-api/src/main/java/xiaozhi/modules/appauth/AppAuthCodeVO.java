package xiaozhi.modules.appauth;

public record AppAuthCodeVO(String challengeId, String expiresAt, int retryAfterSeconds) {
}
