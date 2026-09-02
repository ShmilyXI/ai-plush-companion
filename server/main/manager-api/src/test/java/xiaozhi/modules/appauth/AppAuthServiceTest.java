package xiaozhi.modules.appauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

import java.util.Date;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import xiaozhi.modules.appauth.dao.AppAuthChallengeDao;
import xiaozhi.modules.appauth.dao.AppContactDao;
import xiaozhi.modules.appauth.dao.AppRefreshTokenDao;
import xiaozhi.modules.appauth.entity.AppAuthChallengeEntity;
import xiaozhi.modules.appauth.entity.AppContactEntity;
import xiaozhi.modules.appauth.entity.AppRefreshTokenEntity;
import xiaozhi.modules.security.service.SysUserTokenService;
import xiaozhi.modules.sys.dto.SysUserDTO;
import xiaozhi.modules.security.entity.SysUserTokenEntity;
import xiaozhi.common.page.TokenDTO;
import xiaozhi.common.utils.Result;

@ExtendWith(MockitoExtension.class)
class AppAuthServiceTest {

    @Mock
    private AppAuthChallengeDao challengeDao;
    @Mock
    private AppContactDao contactDao;
    @Mock
    private AppRefreshTokenDao refreshTokenDao;
    @Mock
    private xiaozhi.modules.sys.service.SysUserService sysUserService;
    @Mock
    private SysUserTokenService sysUserTokenService;

    @Test
    void verifiesChallengeExactlyOnceAndRejectsSecondUse() {
        AppAuthChallengeEntity challenge = new AppAuthChallengeEntity();
        challenge.setId("challenge-1");
        challenge.setChannel("email");
        challenge.setPurpose("login");
        challenge.setNormalizedValue("user@example.com");
        challenge.setCodeHash("placeholder");
        challenge.setExpiresAt(new Date(System.currentTimeMillis() + 60_000));
        challenge.setFailedAttempts(0);
        AppAuthService service = new AppAuthService(challengeDao, contactDao, refreshTokenDao,
                new AppContactNormalizer(), "test-secret");
        challenge.setCodeHash(service.hashCode("123456"));
        when(challengeDao.findActiveChallenge("email", "user@example.com", "login")).thenReturn(challenge);
        when(challengeDao.consumeChallenge(eq("challenge-1"), any(Date.class))).thenReturn(1);

        assertEquals("challenge-1", service.verifyChallenge("email", "user@example.com", "login", "123456"));
        when(challengeDao.findActiveChallenge("email", "user@example.com", "login")).thenReturn(null);
        assertThrows(IllegalArgumentException.class,
                () -> service.verifyChallenge("email", "user@example.com", "login", "123456"));
        verify(challengeDao).consumeChallenge(eq("challenge-1"), any(Date.class));
    }

    @Test
    void rejectsAContactOwnedByAnotherUserBeforeMutation() {
        AppContactEntity existing = new AppContactEntity();
        existing.setUserId(22L);
        when(contactDao.findByNormalizedValue("phone", "+8613800138000")).thenReturn(existing);
        AppAuthService service = new AppAuthService(challengeDao, contactDao, refreshTokenDao,
                new AppContactNormalizer(), "test-secret");

        assertThrows(AppAuthConflictException.class,
                () -> service.requireContactAvailable("phone", "13800138000", "+86", 7L));
        verify(contactDao, never()).insert(any(AppContactEntity.class));
    }

    @Test
    void registrationConsumesVerifiedCodeAndStoresTheContact() {
        AppAuthService service = new AppAuthService(challengeDao, contactDao, refreshTokenDao,
                new AppContactNormalizer(), AppMessageSender.noop(), "test-secret", sysUserService,
                sysUserTokenService);
        AppRegisterRequest request = new AppRegisterRequest();
        request.setChannel("email");
        request.setValue("User@Example.com");
        request.setCode("123456");
        request.setPassword("StrongPass1");
        AppAuthChallengeEntity challenge = new AppAuthChallengeEntity();
        challenge.setId("register-1");
        challenge.setExpiresAt(new Date(System.currentTimeMillis() + 60_000));
        challenge.setFailedAttempts(0);
        challenge.setCodeHash(service.hashCode("123456"));
        when(challengeDao.findActiveChallenge("email", "user@example.com", "register")).thenReturn(challenge);
        when(challengeDao.consumeChallenge(eq("register-1"), any(Date.class))).thenReturn(1);
        when(contactDao.findByNormalizedValue("email", "user@example.com")).thenReturn(null);
        SysUserDTO saved = new SysUserDTO();
        saved.setId(7L);
        saved.setUsername("user@example.com");
        when(sysUserService.getByUsername("user@example.com")).thenReturn(null, saved);
        when(sysUserService.getByUserId(7L)).thenReturn(saved);
        doAnswer(invocation -> { SysUserDTO dto = invocation.getArgument(0); dto.setId(7L); return null; }).when(sysUserService).saveAppUser(any(SysUserDTO.class));
        TokenDTO token = new TokenDTO();
        token.setToken("access");
        token.setExpire(3600);
        when(sysUserTokenService.createToken(7L)).thenReturn(new Result<TokenDTO>().ok(token));

        AppAuthTokenVO result = service.register(request);

        assertEquals(7L, result.userId());
        verify(challengeDao).consumeChallenge(eq("register-1"), any(Date.class));
        verify(contactDao).insert(any(AppContactEntity.class));
        verify(sysUserService).saveAppUser(any(SysUserDTO.class));
    }

    @Test
    void refreshRevokesThePresentedTokenBeforeIssuingAnother() throws Exception {
        AppAuthService service = new AppAuthService(challengeDao, contactDao, refreshTokenDao,
                new AppContactNormalizer(), "test-secret");
        AppRefreshTokenEntity old = new AppRefreshTokenEntity();
        old.setId("refresh-1");
        old.setUserId(7L);
        old.setTokenHash(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("refresh".getBytes())));
        old.setExpiresAt(new Date(System.currentTimeMillis() + 60_000));
        when(refreshTokenDao.findRefreshToken(any(String.class))).thenReturn(old);
        when(refreshTokenDao.revokeRefreshToken(eq("refresh-1"), any(Date.class))).thenReturn(1);
        AppAuthTokenVO result = service.refresh("refresh");
        assertEquals(7L, result.userId());
        verify(refreshTokenDao).revokeRefreshToken(eq("refresh-1"), any(Date.class));
    }
}
