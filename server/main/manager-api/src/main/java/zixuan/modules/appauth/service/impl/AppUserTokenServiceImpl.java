package zixuan.modules.appauth.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import lombok.AllArgsConstructor;
import zixuan.common.exception.ErrorCode;
import zixuan.common.exception.RenException;
import zixuan.modules.appauth.dao.AppUserTokenDao;
import zixuan.modules.appauth.entity.AppUserTokenEntity;
import zixuan.modules.appauth.service.AppUserTokenService;
import zixuan.modules.appauth.vo.AppTokenVO;

/**
 * App 用户多端会话 token 存取实现
 */
@AllArgsConstructor
@Service
public class AppUserTokenServiceImpl implements AppUserTokenService {

    public static final String ACCESS_TOKEN_PREFIX = "app_";
    public static final String REFRESH_TOKEN_PREFIX = "appr_";
    /** 访问令牌 12 小时 */
    public static final int ACCESS_TOKEN_TTL_SECONDS = 3600 * 12;
    /** 刷新令牌 30 天 */
    public static final int REFRESH_TOKEN_TTL_SECONDS = 3600 * 24 * 30;
    /** 每用户最多并存会话数 */
    public static final int MAX_SESSIONS_PER_USER = 10;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AppUserTokenDao dao;

    @Override
    public AppTokenVO createSession(Long userId, String deviceLabel, String ip) {
        String accessToken = ACCESS_TOKEN_PREFIX + randomSecret();
        String refreshToken = REFRESH_TOKEN_PREFIX + randomSecret();

        Date now = new Date();
        AppUserTokenEntity entity = new AppUserTokenEntity();
        entity.setUserId(userId);
        entity.setTokenHash(sha256Hex(accessToken));
        entity.setRefreshHash(sha256Hex(refreshToken));
        entity.setDeviceLabel(trimTo(deviceLabel, 100));
        entity.setIp(trimTo(ip, 64));
        entity.setExpireDate(new Date(now.getTime() + ACCESS_TOKEN_TTL_SECONDS * 1000L));
        entity.setRefreshExpireDate(new Date(now.getTime() + REFRESH_TOKEN_TTL_SECONDS * 1000L));
        dao.insert(entity);

        evictStaleSessions(userId);

        return new AppTokenVO(accessToken, ACCESS_TOKEN_TTL_SECONDS, refreshToken, REFRESH_TOKEN_TTL_SECONDS);
    }

    @Override
    public Long resolveAccessToken(String token) {
        if (StringUtils.isBlank(token) || !token.startsWith(ACCESS_TOKEN_PREFIX)) {
            return null;
        }
        AppUserTokenEntity entity = selectByTokenHash(sha256Hex(token));
        if (entity == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (entity.getExpireDate() == null || entity.getExpireDate().getTime() < now) {
            return null;
        }
        // 剩余不足一半时滑动续期，避免活跃用户被频繁打断
        if (entity.getExpireDate().getTime() - now < ACCESS_TOKEN_TTL_SECONDS * 1000L / 2) {
            AppUserTokenEntity update = new AppUserTokenEntity();
            update.setId(entity.getId());
            update.setExpireDate(new Date(now + ACCESS_TOKEN_TTL_SECONDS * 1000L));
            dao.updateById(update);
        }
        return entity.getUserId();
    }

    @Override
    public AppTokenVO refresh(String refreshToken) {
        if (StringUtils.isBlank(refreshToken) || !refreshToken.startsWith(REFRESH_TOKEN_PREFIX)) {
            throw new RenException(ErrorCode.APP_REFRESH_TOKEN_INVALID);
        }
        QueryWrapper<AppUserTokenEntity> query = new QueryWrapper<>();
        query.eq("refresh_hash", sha256Hex(refreshToken));
        AppUserTokenEntity entity = dao.selectOne(query);
        long now = System.currentTimeMillis();
        if (entity == null || entity.getRefreshExpireDate() == null
                || entity.getRefreshExpireDate().getTime() < now) {
            throw new RenException(ErrorCode.APP_REFRESH_TOKEN_INVALID);
        }

        String newAccessToken = ACCESS_TOKEN_PREFIX + randomSecret();
        AppUserTokenEntity update = new AppUserTokenEntity();
        update.setId(entity.getId());
        update.setTokenHash(sha256Hex(newAccessToken));
        update.setExpireDate(new Date(now + ACCESS_TOKEN_TTL_SECONDS * 1000L));
        dao.updateById(update);

        long refreshRemaining = Math.max(0, (entity.getRefreshExpireDate().getTime() - now) / 1000);
        return new AppTokenVO(newAccessToken, ACCESS_TOKEN_TTL_SECONDS, refreshToken, (int) refreshRemaining);
    }

    @Override
    public void revoke(String accessToken) {
        if (StringUtils.isBlank(accessToken) || !accessToken.startsWith(ACCESS_TOKEN_PREFIX)) {
            return;
        }
        QueryWrapper<AppUserTokenEntity> query = new QueryWrapper<>();
        query.eq("token_hash", sha256Hex(accessToken));
        dao.delete(query);
    }

    private AppUserTokenEntity selectByTokenHash(String tokenHash) {
        QueryWrapper<AppUserTokenEntity> query = new QueryWrapper<>();
        query.eq("token_hash", tokenHash);
        return dao.selectOne(query);
    }

    private void evictStaleSessions(Long userId) {
        QueryWrapper<AppUserTokenEntity> countQuery = new QueryWrapper<>();
        countQuery.eq("user_id", userId);
        Long count = dao.selectCount(countQuery);
        if (count == null || count <= MAX_SESSIONS_PER_USER) {
            return;
        }
        QueryWrapper<AppUserTokenEntity> oldestQuery = new QueryWrapper<>();
        oldestQuery.eq("user_id", userId).orderByAsc("create_date")
                .last("limit " + (count - MAX_SESSIONS_PER_USER));
        List<AppUserTokenEntity> oldest = dao.selectList(oldestQuery);
        for (AppUserTokenEntity stale : oldest) {
            dao.deleteById(stale.getId());
        }
    }

    public static String randomSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String trimTo(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
