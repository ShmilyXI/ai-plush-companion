package zixuan.modules.appauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.MessageSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.conditions.Wrapper;

import zixuan.common.exception.ErrorCode;
import zixuan.common.exception.RenException;
import zixuan.common.utils.MessageUtils;
import zixuan.modules.appauth.dao.AppUserTokenDao;
import zixuan.modules.appauth.entity.AppUserTokenEntity;
import zixuan.modules.appauth.service.impl.AppUserTokenServiceImpl;
import zixuan.modules.appauth.vo.AppTokenVO;

class AppUserTokenServiceTest {

    @BeforeAll
    static void injectMessageSource() {
        // RenException 构造时会走 MessageUtils 解析国际化消息，单测环境下注入 mock
        ReflectionTestUtils.setField(MessageUtils.class, "messageSource", mock(MessageSource.class));
    }

    @Test
    void createSessionStoresHashesInsteadOfPlaintextTokens() {
        AppUserTokenDao dao = mock(AppUserTokenDao.class);
        when(dao.selectCount(any())).thenReturn(1L);
        AppUserTokenServiceImpl service = new AppUserTokenServiceImpl(dao);

        AppTokenVO vo = service.createSession(7L, "iphone-15", "203.0.113.7");

        assertTrue(vo.getAccessToken().startsWith("app_"));
        assertTrue(vo.getRefreshToken().startsWith("appr_"));
        assertEquals(AppUserTokenServiceImpl.ACCESS_TOKEN_TTL_SECONDS, vo.getExpiresIn());
        ArgumentCaptor<AppUserTokenEntity> saved = ArgumentCaptor.forClass(AppUserTokenEntity.class);
        verify(dao).insert(saved.capture());
        AppUserTokenEntity entity = saved.getValue();
        assertEquals(AppUserTokenServiceImpl.sha256Hex(vo.getAccessToken()), entity.getTokenHash());
        assertEquals(AppUserTokenServiceImpl.sha256Hex(vo.getRefreshToken()), entity.getRefreshHash());
        assertEquals(7L, entity.getUserId());
        assertEquals("iphone-15", entity.getDeviceLabel());
        assertNotNull(entity.getRefreshExpireDate());
    }

    @Test
    void resolveAccessTokenReturnsNullForExpiredOrUnknownToken() {
        AppUserTokenDao dao = mock(AppUserTokenDao.class);
        AppUserTokenServiceImpl service = new AppUserTokenServiceImpl(dao);

        assertNull(service.resolveAccessToken("appr_not_access"));
        assertNull(service.resolveAccessToken("app_unknown"));

        AppUserTokenEntity expired = entity(7L,
                AppUserTokenServiceImpl.sha256Hex("app_expired"),
                new Date(System.currentTimeMillis() - 1000), new Date(System.currentTimeMillis() + 100000));
        when(dao.selectOne(any())).thenReturn(expired);
        assertNull(service.resolveAccessToken("app_expired"));
    }

    @Test
    void resolveAccessTokenSlidingRenewsWhenHalfExpired() {
        AppUserTokenDao dao = mock(AppUserTokenDao.class);
        long half = AppUserTokenServiceImpl.ACCESS_TOKEN_TTL_SECONDS * 1000L / 2;
        AppUserTokenEntity almostExpired = entity(7L,
                AppUserTokenServiceImpl.sha256Hex("app_active"),
                new Date(System.currentTimeMillis() + half - 60000),
                new Date(System.currentTimeMillis() + 1000000));
        when(dao.selectOne(any())).thenReturn(almostExpired);

        assertEquals(Long.valueOf(7L), service(dao).resolveAccessToken("app_active"));

        ArgumentCaptor<AppUserTokenEntity> update = ArgumentCaptor.forClass(AppUserTokenEntity.class);
        verify(dao).updateById(update.capture());
        assertTrue(update.getValue().getExpireDate().getTime() > almostExpired.getExpireDate().getTime());
    }

    @Test
    void refreshRejectsWrongPrefixOrExpiredToken() {
        AppUserTokenDao dao = mock(AppUserTokenDao.class);

        RenException wrongPrefix = assertThrows(RenException.class,
                () -> service(dao).refresh("app_not_refresh"));
        assertEquals(ErrorCode.APP_REFRESH_TOKEN_INVALID, wrongPrefix.getCode());

        AppUserTokenEntity expiredRefresh = entity(7L,
                AppUserTokenServiceImpl.sha256Hex("app_old"),
                new Date(System.currentTimeMillis() - 1000),
                new Date(System.currentTimeMillis() - 1000));
        when(dao.selectOne(any())).thenReturn(expiredRefresh);
        RenException expired = assertThrows(RenException.class,
                () -> service(dao).refresh("appr_expired"));
        assertEquals(ErrorCode.APP_REFRESH_TOKEN_INVALID, expired.getCode());
    }

    @Test
    void refreshRotatesAccessTokenOnly() {
        AppUserTokenDao dao = mock(AppUserTokenDao.class);
        AppUserTokenEntity active = entity(7L,
                AppUserTokenServiceImpl.sha256Hex("app_old"),
                new Date(System.currentTimeMillis() - 1000),
                new Date(System.currentTimeMillis() + 1000000));
        when(dao.selectOne(any())).thenReturn(active);

        AppTokenVO vo = service(dao).refresh("appr_active");

        assertTrue(vo.getAccessToken().startsWith("app_"));
        assertEquals("appr_active", vo.getRefreshToken());
        ArgumentCaptor<AppUserTokenEntity> update = ArgumentCaptor.forClass(AppUserTokenEntity.class);
        verify(dao).updateById(update.capture());
        assertEquals(AppUserTokenServiceImpl.sha256Hex(vo.getAccessToken()), update.getValue().getTokenHash());
        assertEquals(active.getId(), update.getValue().getId());
    }

    @Test
    void revokeDeletesOnlyTheGivenSessionRow() {
        AppUserTokenDao dao = mock(AppUserTokenDao.class);

        service(dao).revoke("app_secret");

        verify(dao).delete(any(Wrapper.class));
    }

    @Test
    void staleSessionsAreEvictedBeyondPerUserLimit() {
        AppUserTokenDao dao = mock(AppUserTokenDao.class);
        when(dao.selectCount(any())).thenReturn(Long.valueOf(AppUserTokenServiceImpl.MAX_SESSIONS_PER_USER + 2));
        AppUserTokenEntity oldest = new AppUserTokenEntity();
        oldest.setId(1L);
        when(dao.selectList(any())).thenReturn(List.of(oldest));

        service(dao).createSession(7L, null, null);

        verify(dao).deleteById(1L);
    }

    private AppUserTokenServiceImpl service(AppUserTokenDao dao) {
        return new AppUserTokenServiceImpl(dao);
    }

    private AppUserTokenEntity entity(Long userId, String tokenHash, Date expireDate, Date refreshExpireDate) {
        AppUserTokenEntity entity = new AppUserTokenEntity();
        entity.setId(100L);
        entity.setUserId(userId);
        entity.setTokenHash(tokenHash);
        entity.setRefreshHash(AppUserTokenServiceImpl.sha256Hex("appr_active"));
        entity.setExpireDate(expireDate);
        entity.setRefreshExpireDate(refreshExpireDate);
        return entity;
    }
}
