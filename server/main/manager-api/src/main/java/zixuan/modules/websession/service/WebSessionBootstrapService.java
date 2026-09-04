package zixuan.modules.websession.service;

public interface WebSessionBootstrapService {
    Bootstrap issue(Long userId);

    Bootstrap issue(Long userId, String audience, String origin);

    Exchange exchange(String code);

    Long resolve(String accessToken);

    record Bootstrap(String code, long expiresIn) {
    }

    record Exchange(String accessToken, long expiresIn) {
    }
}
