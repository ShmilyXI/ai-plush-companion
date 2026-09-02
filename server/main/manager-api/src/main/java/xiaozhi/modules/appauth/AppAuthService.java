package xiaozhi.modules.appauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import xiaozhi.modules.appauth.dao.AppAuthChallengeDao;
import xiaozhi.modules.appauth.dao.AppContactDao;
import xiaozhi.modules.appauth.dao.AppRefreshTokenDao;
import xiaozhi.modules.appauth.entity.AppAuthChallengeEntity;
import xiaozhi.modules.appauth.entity.AppContactEntity;
import xiaozhi.modules.appauth.entity.AppRefreshTokenEntity;
import xiaozhi.modules.security.password.PasswordUtils;
import xiaozhi.modules.security.service.SysUserTokenService;
import xiaozhi.modules.sys.dto.SysUserDTO;
import xiaozhi.modules.sys.dto.PasswordDTO;
import xiaozhi.modules.sys.service.SysUserService;
import xiaozhi.common.page.TokenDTO;
import xiaozhi.common.utils.Result;

@Service
public class AppAuthService {
    private static final int CODE_LENGTH = 6;
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int RETRY_AFTER_SECONDS = 60;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AppAuthChallengeDao challengeDao;
    private final AppContactDao contactDao;
    @SuppressWarnings("unused")
    private final AppRefreshTokenDao refreshTokenDao;
    private final AppContactNormalizer normalizer;
    private final String hmacSecret;
    private final AppMessageSender messageSender;
    private final SysUserService sysUserService;
    private final SysUserTokenService sysUserTokenService;

    public AppAuthService(AppAuthChallengeDao challengeDao, AppContactDao contactDao,
                AppRefreshTokenDao refreshTokenDao, AppContactNormalizer normalizer, String hmacSecret) {
        this(challengeDao, contactDao, refreshTokenDao, normalizer, hmacSecret, AppMessageSender.noop(), null, null);
    }

    @Autowired
    public AppAuthService(AppAuthChallengeDao challengeDao, AppContactDao contactDao,
            AppRefreshTokenDao refreshTokenDao, AppContactNormalizer normalizer,
            AppMessageSender messageSender,
            @Value("${companion.app-auth.hmac-secret:change-me-in-production}") String hmacSecret,
            SysUserService sysUserService, SysUserTokenService sysUserTokenService) {
        this(challengeDao, contactDao, refreshTokenDao, normalizer, hmacSecret, messageSender, sysUserService, sysUserTokenService);
    }

    private AppAuthService(AppAuthChallengeDao challengeDao, AppContactDao contactDao,
            AppRefreshTokenDao refreshTokenDao, AppContactNormalizer normalizer,
            String hmacSecret, AppMessageSender messageSender, SysUserService sysUserService,
            SysUserTokenService sysUserTokenService) {
        this.challengeDao = challengeDao;
        this.contactDao = contactDao;
        this.refreshTokenDao = refreshTokenDao;
        this.normalizer = normalizer;
        this.hmacSecret = hmacSecret == null || hmacSecret.isBlank() ? "change-me-in-production" : hmacSecret;
        this.messageSender = messageSender == null ? AppMessageSender.noop() : messageSender;
        this.sysUserService = sysUserService;
        this.sysUserTokenService = sysUserTokenService;
    }

    @Transactional
    public AppAuthCodeVO sendCode(AppAuthCodeRequest request) {
        if (request == null) throw new IllegalArgumentException("code request is required");
        String purpose = normalizePurpose(request.getPurpose());
        AppContactNormalizer.NormalizedContact contact = normalizer.normalize(
                request.getChannel(), request.getValue(), request.getCountryCode());
        if (challengeDao.countRecent(contact.channel(), contact.value()) > 0) {
            throw new IllegalArgumentException("verification code retry too soon");
        }
        if (challengeDao.countToday(contact.channel(), contact.value()) >= 10) {
            throw new IllegalArgumentException("verification code daily limit exceeded");
        }
        String code = randomCode();
        Instant now = Instant.now();
        AppAuthChallengeEntity entity = new AppAuthChallengeEntity();
        entity.setId(randomId());
        entity.setChannel(contact.channel());
        entity.setPurpose(purpose);
        entity.setNormalizedValue(contact.value());
        entity.setCodeHash(hashCode(code));
        entity.setExpiresAt(Date.from(now.plus(10, ChronoUnit.MINUTES)));
        entity.setFailedAttempts(0);
        if (challengeDao.insert(entity) != 1) throw new IllegalStateException("failed to create challenge");
        try {
            messageSender.send(contact.channel(), contact.value(), code, purpose);
        } catch (RuntimeException error) {
            challengeDao.deleteById(entity.getId());
            throw error;
        }
        return new AppAuthCodeVO(entity.getId(), entity.getExpiresAt().toInstant().toString(), RETRY_AFTER_SECONDS);
    }

    public AppAuthTokenVO passwordLogin(AppPasswordLoginRequest request) {
        if (request == null) throw new IllegalArgumentException("login request is required");
        AppContactNormalizer.NormalizedContact contact = normalizer.normalize(request.getChannel(), request.getValue(), request.getCountryCode());
        SysUserDTO user = findUser(contact);
        if (user == null || user.getPassword() == null || !PasswordUtils.matches(request.getPassword(), user.getPassword())) {
            throw new IllegalArgumentException("account or password is invalid");
        }
        return issueTokens(user.getId());
    }

    @Transactional
    public AppAuthTokenVO codeLogin(AppCodeLoginRequest request) {
        if (request == null) throw new IllegalArgumentException("login request is required");
        verifyChallenge(request.getChannel(), request.getValue(), request.getCountryCode(), "login", request.getCode());
        SysUserDTO user = findUser(normalizer.normalize(request.getChannel(), request.getValue(), request.getCountryCode()));
        if (user == null) throw new IllegalArgumentException("account is not registered");
        return issueTokens(user.getId());
    }

    @Transactional
    public AppAuthTokenVO register(AppRegisterRequest request) {
        if (request == null) throw new IllegalArgumentException("register request is required");
        AppContactNormalizer.NormalizedContact contact = normalizer.normalize(request.getChannel(), request.getValue(), request.getCountryCode());
        verifyChallenge(contact.channel(), contact.value(), request.getCountryCode(), "register", request.getCode());
        if (contactDao.findByNormalizedValue(contact.channel(), contact.value()) != null) {
            throw new AppAuthConflictException("contact is already bound to another account");
        }
        if (sysUserService == null) throw new IllegalStateException("app auth user service is unavailable");
        SysUserDTO existing = findUser(contact);
        if (existing != null) throw new AppAuthConflictException("contact is already bound to another account");
        SysUserDTO dto = new SysUserDTO();
        dto.setUsername(usernameFor(contact.value()));
        dto.setPassword(request.getPassword());
        dto.setRealName("App User");
        sysUserService.saveAppUser(dto);
        SysUserDTO saved = sysUserService.getByUsername(dto.getUsername());
        if (saved == null) throw new IllegalStateException("failed to create user");
        AppContactEntity entity = new AppContactEntity();
        entity.setUserId(saved.getId()); entity.setChannel(contact.channel()); entity.setNormalizedValue(contact.value()); entity.setVerifiedAt(new Date());
        contactDao.insert(entity);
        return issueTokens(saved.getId());
    }

    @Transactional
    public void resetPassword(AppResetPasswordRequest request) {
        if (request == null) throw new IllegalArgumentException("reset request is required");
        AppContactNormalizer.NormalizedContact contact = normalizer.normalize(request.getChannel(), request.getValue(), request.getCountryCode());
        verifyChallenge(contact.channel(), contact.value(), request.getCountryCode(), "reset", request.getCode());
        SysUserDTO user = findUser(contact);
        if (user == null) throw new IllegalArgumentException("account is not registered");
        if (sysUserService == null) throw new IllegalStateException("app auth user service is unavailable");
        sysUserService.changePasswordDirectly(user.getId(), request.getPassword());
        refreshTokenDao.revokeAllForUser(user.getId(), new Date());
        if (sysUserTokenService != null) sysUserTokenService.logout(user.getId());
    }

    @Transactional
    public void bindContact(Long userId, AppBindContactRequest request) {
        if (userId == null || request == null) throw new IllegalArgumentException("account identity is required");
        AppContactNormalizer.NormalizedContact contact = normalizer.normalize(request.getChannel(), request.getValue(), request.getCountryCode());
        verifyChallenge(contact.channel(), contact.value(), request.getCountryCode(), "bind", request.getCode());
        requireContactAvailable(contact.channel(), contact.value(), request.getCountryCode(), userId);
        AppContactEntity entity = new AppContactEntity(); entity.setUserId(userId); entity.setChannel(contact.channel()); entity.setNormalizedValue(contact.value()); entity.setVerifiedAt(new Date());
        if (contactDao.insert(entity) != 1) throw new AppAuthConflictException("contact is already bound to another account");
    }

    @Transactional
    public AppAuthTokenVO refresh(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) throw new IllegalArgumentException("refresh token is required");
        String hash = sha256(rawToken);
        var old = refreshTokenDao.findRefreshToken(hash);
        if (old == null || old.getRevokedAt() != null || old.getExpiresAt() == null || old.getExpiresAt().before(new Date())) throw new IllegalArgumentException("refresh token is invalid");
        Date usedAt = new Date();
        refreshTokenDao.markUsed(old.getId(), usedAt);
        if (refreshTokenDao.revokeRefreshToken(old.getId(), usedAt) != 1) throw new IllegalArgumentException("refresh token is invalid");
        return issueTokens(old.getUserId());
    }

    public void logout(Long userId) {
        if (userId == null) return;
        refreshTokenDao.revokeAllForUser(userId, new Date());
        if (sysUserTokenService != null) sysUserTokenService.logout(userId);
    }

    private SysUserDTO findUser(AppContactNormalizer.NormalizedContact contact) {
        AppContactEntity binding = contactDao.findByNormalizedValue(contact.channel(), contact.value());
        if (binding != null && sysUserService != null) return sysUserService.getByUserId(binding.getUserId());
        return sysUserService == null ? null : sysUserService.getByUsername(contact.value());
    }

    private AppAuthTokenVO issueTokens(Long userId) {
        String access = java.util.UUID.randomUUID().toString().replace("-", "");
        String accessExpiry = Instant.now().plus(12, ChronoUnit.HOURS).toString();
        if (sysUserTokenService != null) {
            Result<TokenDTO> result = sysUserTokenService.createToken(userId);
            if (result != null && result.getData() != null) { access = result.getData().getToken(); accessExpiry = Instant.now().plusSeconds(result.getData().getExpire()).toString(); }
        }
        String refresh = java.util.UUID.randomUUID().toString().replace("-", "") + java.util.UUID.randomUUID().toString().replace("-", "");
        AppRefreshTokenEntity row = new AppRefreshTokenEntity(); row.setId(java.util.UUID.randomUUID().toString().replace("-", "")); row.setUserId(userId); row.setTokenHash(sha256(refresh)); row.setExpiresAt(Date.from(Instant.now().plus(30, ChronoUnit.DAYS))); row.setCreatedAt(new Date());
        refreshTokenDao.insert(row);
        return new AppAuthTokenVO(access, accessExpiry, refresh, row.getExpiresAt().toInstant().toString(), userId,
                userSummary(sysUserService == null ? null : sysUserService.getByUserId(userId)));
    }

    public AppAccountVO account(Long userId) {
        if (userId == null || sysUserService == null) throw new IllegalArgumentException("account is unavailable");
        SysUserDTO user = sysUserService.getByUserId(userId);
        if (user == null) throw new IllegalArgumentException("account is not registered");
        java.util.List<AppContactEntity> contacts = contactDao.findByUserId(userId);
        java.util.List<String> channels = contacts == null ? java.util.List.of()
                : contacts.stream().map(AppContactEntity::getChannel).toList();
        return new AppAccountVO(userSummary(user), channels);
    }

    @Transactional
    public void changePassword(Long userId, AppChangePasswordRequest request) {
        if (userId == null || request == null || sysUserService == null) throw new IllegalArgumentException("password request is invalid");
        PasswordDTO password = new PasswordDTO();
        password.setPassword(request.getCurrentPassword());
        password.setNewPassword(request.getNewPassword());
        sysUserService.changePassword(userId, password);
        refreshTokenDao.revokeAllForUser(userId, new Date());
    }

    private AppAuthUserVO userSummary(SysUserDTO user) {
        if (user == null) return null;
        return new AppAuthUserVO(user.getId(), user.getRealName(), user.getUsername(), user.getHeadUrl());
    }

    private String usernameFor(String contact) {
        if (contact != null && contact.length() <= 50) return contact;
        return "app_" + java.util.UUID.randomUUID().toString().replace("-", "");
    }

    private String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    @Transactional
    public String verifyChallenge(String channel, String value, String purpose, String code) {
        return verifyChallenge(channel, value, null, purpose, code);
    }

    public String verifyChallenge(String channel, String value, String countryCode, String purpose, String code) {
        AppContactNormalizer.NormalizedContact contact = normalizer.normalize(channel, value, countryCode);
        AppAuthChallengeEntity challenge = challengeDao.findActiveChallenge(contact.channel(), contact.value(),
                normalizePurpose(purpose));
        if (challenge == null || challenge.getExpiresAt() == null
                || challenge.getExpiresAt().before(new Date())) {
            throw new IllegalArgumentException("verification code is invalid or expired");
        }
        int failed = challenge.getFailedAttempts() == null ? 0 : challenge.getFailedAttempts();
        if (failed >= MAX_FAILED_ATTEMPTS) throw new IllegalArgumentException("verification code is locked");
        if (!MessageDigest.isEqual(hashCode(code).getBytes(StandardCharsets.UTF_8),
                challenge.getCodeHash().getBytes(StandardCharsets.UTF_8))) {
            challengeDao.incrementFailedAttempts(challenge.getId());
            throw new IllegalArgumentException("verification code is invalid");
        }
        if (challengeDao.consumeChallenge(challenge.getId(), new Date()) != 1) {
            throw new IllegalArgumentException("verification code is already used");
        }
        return challenge.getId();
    }

    public void requireContactAvailable(String channel, String value, String countryCode, Long userId) {
        AppContactNormalizer.NormalizedContact contact = normalizer.normalize(channel, value, countryCode);
        AppContactEntity existing = contactDao.findByNormalizedValue(contact.channel(), contact.value());
        if (existing != null && (userId == null || !userId.equals(existing.getUserId()))) {
            throw new AppAuthConflictException("contact is already bound to another account");
        }
    }

    public String hashCode(String code) {
        if (code == null || !code.matches("\\d{" + CODE_LENGTH + "}")) {
            throw new IllegalArgumentException("verification code is invalid");
        }
        try {
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(code.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("unable to hash verification code", exception);
        }
    }

    private String normalizePurpose(String purpose) {
        if (purpose == null || !purpose.matches("login|register|reset|bind")) {
            throw new IllegalArgumentException("verification purpose is invalid");
        }
        return purpose;
    }

    private String randomCode() {
        return "%06d".formatted(RANDOM.nextInt(1_000_000));
    }

    private String randomId() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }
}
