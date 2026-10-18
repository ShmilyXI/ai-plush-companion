package zixuan.modules.appauth.service;

import zixuan.modules.appauth.vo.AppTokenVO;

/**
 * App 用户多端会话 token 存取：明文只在签发/刷新响应中返回一次，库中只保存 SHA-256 哈希
 */
public interface AppUserTokenService {

    /**
     * 为用户创建一个新的多端会话；超过会话数量上限时淘汰最旧会话
     */
    AppTokenVO createSession(Long userId, String deviceLabel, String ip);

    /**
     * 解析访问令牌；无效或过期返回 null。剩余有效期不足一半时滑动续期。
     */
    Long resolveAccessToken(String token);

    /**
     * 使用刷新令牌轮换出一个新的访问令牌
     */
    AppTokenVO refresh(String refreshToken);

    /**
     * 吊销指定访问令牌所在的会话
     */
    void revoke(String accessToken);
}
