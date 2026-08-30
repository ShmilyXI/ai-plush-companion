package xiaozhi.modules.appauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

@ExtendWith(MockitoExtension.class)
class AppAuthServiceTest {

    @Mock
    private AppAuthChallengeDao challengeDao;
    @Mock
    private AppContactDao contactDao;
    @Mock
    private AppRefreshTokenDao refreshTokenDao;

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
}
