package zixuan.modules.appauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.context.MessageSource;
import org.springframework.test.util.ReflectionTestUtils;

import zixuan.common.exception.ErrorCode;
import zixuan.common.exception.RenException;
import zixuan.common.redis.RedisKeys;
import zixuan.common.redis.RedisUtils;
import zixuan.common.utils.MessageUtils;
import zixuan.common.utils.Sm2DecryptUtil;
import zixuan.modules.appauth.dao.AccountDataDao;
import zixuan.modules.appauth.dao.AppUserProfileDao;
import zixuan.modules.appauth.entity.AppUserProfileEntity;
import zixuan.modules.appauth.vo.AppProfileVO;
import zixuan.modules.appauth.dto.AppCodeLoginDTO;
import zixuan.modules.appauth.dto.AppPasswordLoginDTO;
import zixuan.modules.appauth.dto.AppRegisterDTO;
import zixuan.modules.appauth.service.AppUserTokenService;
import zixuan.modules.appauth.service.impl.AppAuthServiceImpl;
import zixuan.modules.appauth.vo.AppTokenVO;
import zixuan.modules.invite.service.InviteCodeService;
import zixuan.modules.security.password.PasswordUtils;
import zixuan.modules.security.service.CaptchaService;
import zixuan.modules.sys.dto.SysUserDTO;
import zixuan.modules.sys.service.SysParamsService;
import zixuan.modules.sys.service.SysUserService;

class AppAuthServiceTest {
    private static final String PHONE = "+8613800138000";
    private static final String IP = "203.0.113.7";

    private SysUserService users;
    private CaptchaService captcha;
    private AppUserTokenService tokens;
    private SysParamsService params;
    private RedisUtils redis;
    private InviteCodeService invites;
    private AccountDataDao accountData;
    private AppUserProfileDao profiles;
    private AppAuthServiceImpl service;

    @BeforeAll
    static void injectMessageSource() {
        // RenException 构造时会走 MessageUtils 解析国际化消息，单测环境下注入 mock
        ReflectionTestUtils.setField(MessageUtils.class, "messageSource", mock(MessageSource.class));
    }

    @BeforeEach
    void setUp() {
        users = mock(SysUserService.class);
        captcha = mock(CaptchaService.class);
        tokens = mock(AppUserTokenService.class);
        params = mock(SysParamsService.class);
        redis = mock(RedisUtils.class);
        invites = mock(InviteCodeService.class);
        accountData = mock(AccountDataDao.class);
        profiles = mock(AppUserProfileDao.class);
        service = new AppAuthServiceImpl(users, captcha, tokens, params, redis, invites, accountData, profiles);
    }

    @Test
    void registerRejectsWrongSmsCode() {
        AppRegisterDTO dto = register("000000", "enc");
        when(captcha.validateSMSValidateCode(PHONE, "000000", false)).thenReturn(false);

        RenException error = assertThrows(RenException.class, () -> service.register(dto, "iphone", IP));

        assertEquals(ErrorCode.SMS_CODE_ERROR, error.getCode());
        verify(users, never()).saveAppUser(any());
    }

    @Test
    void registerRejectsAlreadyRegisteredPhone() {
        AppRegisterDTO dto = register("123456", "enc");
        when(captcha.validateSMSValidateCode(PHONE, "123456", false)).thenReturn(true);
        when(users.getByPhone(PHONE)).thenReturn(new SysUserDTO());

        RenException error = assertThrows(RenException.class, () -> service.register(dto, "iphone", IP));

        assertEquals(ErrorCode.PHONE_ALREADY_REGISTERED, error.getCode());
        verify(users, never()).saveAppUser(any());
    }

    @Test
    void registerCreatesNormalUserAndIssuesToken() {
        AppRegisterDTO dto = register("123456", "enc-password");
        when(captcha.validateSMSValidateCode(PHONE, "123456", false)).thenReturn(true);
        when(users.getByPhone(PHONE)).thenReturn(null, user(7L, "Right1Abc"));
        AppTokenVO issued = new AppTokenVO("app_secret", 43200, "appr_secret", 2592000);
        when(tokens.createSession(7L, "iphone", IP)).thenReturn(issued);

        try (MockedStatic<Sm2DecryptUtil> sm2 = mockStatic(Sm2DecryptUtil.class)) {
            sm2.when(() -> Sm2DecryptUtil.decryptLoginPassword("enc-password", null, params))
                    .thenReturn("Right1Abc");
            AppTokenVO result = service.register(dto, "iphone", IP);

            assertEquals(issued, result);
        }

        ArgumentCaptor<SysUserDTO> saved = ArgumentCaptor.forClass(SysUserDTO.class);
        verify(users).saveAppUser(saved.capture());
        assertEquals(PHONE, saved.getValue().getUsername());
        assertEquals(PHONE, saved.getValue().getPhone());
        assertEquals("Right1Abc", saved.getValue().getPassword());
    }

    @Test
    void registerRejectsAPasswordThatIsTooShortOrTooSimple() {
        when(captcha.validateSMSValidateCode(PHONE, "123456", false)).thenReturn(true);
        when(users.getByPhone(PHONE)).thenReturn(null);

        for (String weak : new String[] {"Ab1", "alllower1", "ALLUPPER1", "NoDigitsHere", "Ab1" + "x".repeat(30)}) {
            try (MockedStatic<Sm2DecryptUtil> sm2 = mockStatic(Sm2DecryptUtil.class)) {
                sm2.when(() -> Sm2DecryptUtil.decryptLoginPassword("enc", null, params)).thenReturn(weak);

                RenException error = assertThrows(RenException.class,
                        () -> service.register(register("123456", "enc"), "web", IP));

                assertEquals("密码需要 8 到 32 位，同时包含大写字母、小写字母和数字", error.getMsg());
            }
        }
        verify(users, never()).saveAppUser(any());
    }

    @Test
    void passwordLoginRecordsFailureAndRejectsUnknownPhone() {
        AppPasswordLoginDTO dto = passwordLogin("enc-wrong");
        when(users.getByPhone(PHONE)).thenReturn(null);

        try (MockedStatic<Sm2DecryptUtil> sm2 = mockStatic(Sm2DecryptUtil.class)) {
            sm2.when(() -> Sm2DecryptUtil.decryptLoginPassword("enc-wrong", null, params))
                    .thenReturn("Whatever1A");

            RenException error = assertThrows(RenException.class,
                    () -> service.loginByPassword(dto, "iphone", IP));

            assertEquals(ErrorCode.PHONE_NOT_REGISTERED, error.getCode());
        }
        verify(redis).increment(RedisKeys.getAppAuthLoginFailureKey(PHONE), 60);
    }

    @Test
    void passwordLoginLockedAfterFailureWindowLimit() {
        when(redis.get(RedisKeys.getAppAuthLoginFailureKey(PHONE))).thenReturn(10);

        RenException error = assertThrows(RenException.class,
                () -> service.loginByPassword(passwordLogin("enc"), "iphone", IP));

        assertEquals(ErrorCode.APP_LOGIN_LOCKED, error.getCode());
        verify(users, never()).getByPhone(anyString());
    }

    @Test
    void passwordLoginRejectsWrongPassword() {
        when(users.getByPhone(PHONE)).thenReturn(user(7L, "Right1A"));

        try (MockedStatic<Sm2DecryptUtil> sm2 = mockStatic(Sm2DecryptUtil.class)) {
            sm2.when(() -> Sm2DecryptUtil.decryptLoginPassword("enc-wrong", null, params))
                    .thenReturn("Wrong1A");

            RenException error = assertThrows(RenException.class,
                    () -> service.loginByPassword(passwordLogin("enc-wrong"), "iphone", IP));

            assertEquals(ErrorCode.ACCOUNT_PASSWORD_ERROR, error.getCode());
        }
        verify(redis).increment(RedisKeys.getAppAuthLoginFailureKey(PHONE), 60);
    }

    @Test
    void passwordLoginRejectsAccountWithoutPassword() {
        SysUserDTO codeLoginUser = user(7L, null);
        when(users.getByPhone(PHONE)).thenReturn(codeLoginUser);

        try (MockedStatic<Sm2DecryptUtil> sm2 = mockStatic(Sm2DecryptUtil.class)) {
            sm2.when(() -> Sm2DecryptUtil.decryptLoginPassword("enc", null, params)).thenReturn("Right1A");

            RenException error = assertThrows(RenException.class,
                    () -> service.loginByPassword(passwordLogin("enc"), "iphone", IP));

            assertEquals("这个账号还没有设置密码，请用验证码登录", error.getMsg());
        }
        verify(redis).increment(RedisKeys.getAppAuthLoginFailureKey(PHONE), 60);
    }

    @Test
    void passwordLoginSucceedsAndClearsFailureCounter() {
        when(users.getByPhone(PHONE)).thenReturn(user(7L, "Right1A"));
        AppTokenVO issued = new AppTokenVO("app_secret", 43200, "appr_secret", 2592000);
        when(tokens.createSession(7L, "iphone", IP)).thenReturn(issued);

        AppTokenVO result;
        try (MockedStatic<Sm2DecryptUtil> sm2 = mockStatic(Sm2DecryptUtil.class)) {
            sm2.when(() -> Sm2DecryptUtil.decryptLoginPassword("enc-right", null, params))
                    .thenReturn("Right1A");
            result = service.loginByPassword(passwordLogin("enc-right"), "iphone", IP);
        }

        assertEquals(issued, result);
        verify(redis).delete(RedisKeys.getAppAuthLoginFailureKey(PHONE));
    }

    @Test
    void codeLoginForANewPhoneSendsThemToRegisterAndKeepsTheCode() {
        when(captcha.validateSMSValidateCode(PHONE, "123456", false)).thenReturn(true);
        when(users.getByPhone(PHONE)).thenReturn(null);

        RenException error = assertThrows(RenException.class,
                () -> service.loginBySmsCode(codeLogin("123456"), "web", IP));

        assertEquals(ErrorCode.PHONE_NOT_REGISTERED, error.getCode());
        verify(users, never()).saveAppUser(any());
        // 验证码没有作废，转到注册时可以接着用。
        verify(captcha, never()).validateSMSValidateCode(PHONE, "123456", true);
        verify(tokens, never()).createSession(any(), any(), any());
    }

    @Test
    void registeringANewPhoneNeedsAValidInviteWhenInvitesAreOn() {
        when(captcha.validateSMSValidateCode(PHONE, "123456", false)).thenReturn(true);
        // 第一次注册被拦下时查过一次，第二次注册查一次，建好账号后再查一次。
        when(users.getByPhone(PHONE)).thenReturn(null, null, user(9L, "Right1Abc"));
        when(invites.isRequired()).thenReturn(true);
        doThrow(new RenException(ErrorCode.INVITE_CODE_REQUIRED)).when(invites).requireUsable(null);
        when(tokens.createSession(9L, "web", IP)).thenReturn(new AppTokenVO("app_s", 1, "appr_s", 2));

        RenException missing = assertThrows(RenException.class,
                () -> service.register(register("123456", "enc"), "web", IP));
        assertEquals(ErrorCode.INVITE_CODE_REQUIRED, missing.getCode());
        verify(users, never()).saveAppUser(any());

        AppRegisterDTO withInvite = register("123456", "enc");
        withInvite.setInviteCode("abcd2345");
        try (MockedStatic<Sm2DecryptUtil> sm2 = mockStatic(Sm2DecryptUtil.class)) {
            sm2.when(() -> Sm2DecryptUtil.decryptLoginPassword("enc", null, params)).thenReturn("Right1Abc");
            assertNotNull(service.register(withInvite, "web", IP));
        }
        verify(invites).redeem("abcd2345", 9L);
        verify(captcha).validateSMSValidateCode(PHONE, "123456", true);
    }

    @Test
    void codeLoginForAnExistingAccountNeverAsksForAnInvite() {
        when(captcha.validateSMSValidateCode(PHONE, "123456", false)).thenReturn(true);
        when(users.getByPhone(PHONE)).thenReturn(user(9L, null));
        when(invites.isRequired()).thenReturn(true);
        when(tokens.createSession(eq(9L), any(), any())).thenReturn(new AppTokenVO("app_s", 1, "appr_s", 2));

        assertNotNull(service.loginBySmsCode(codeLogin("123456"), "web", IP));

        verify(invites, never()).requireUsable(any());
        verify(invites, never()).redeem(any(), any());
    }

    @Test
    void aSmsCodeIsDiscardedAfterFiveWrongAttempts() {
        when(captcha.validateSMSValidateCode(PHONE, "000000", false)).thenReturn(false);
        String failureKey = RedisKeys.getAppAuthSmsCodeFailureKey(PHONE);
        when(redis.increment(eq(failureKey), eq(300L))).thenReturn(4L, 5L);

        assertThrows(RenException.class, () -> service.loginBySmsCode(codeLogin("000000"), "web", IP));
        verify(captcha, never()).validateSMSValidateCode(PHONE, "-", true);

        assertThrows(RenException.class, () -> service.loginBySmsCode(codeLogin("000000"), "web", IP));
        verify(captcha).validateSMSValidateCode(PHONE, "-", true);
        verify(redis).delete(failureKey);
    }

    @Test
    void signInRecordsTheAgreedTermsVersionOnceItIsWellFormed() {
        when(captcha.validateSMSValidateCode(PHONE, "123456", false)).thenReturn(true);
        when(users.getByPhone(PHONE)).thenReturn(user(9L, null));
        when(tokens.createSession(eq(9L), any(), any())).thenReturn(new AppTokenVO("app_s", 1, "appr_s", 2));

        AppCodeLoginDTO agreed = codeLogin("123456");
        agreed.setConsentVersion(" 2026-11-20 ");
        service.loginBySmsCode(agreed, "web", IP);
        verify(accountData).recordConsent(9L, "2026-11-20");

        AppCodeLoginDTO malformed = codeLogin("123456");
        malformed.setConsentVersion("<script>");
        service.loginBySmsCode(malformed, "web", IP);
        service.loginBySmsCode(codeLogin("123456"), "web", IP);
        verify(accountData, org.mockito.Mockito.times(1)).recordConsent(any(), any());
    }

    @Test
    void deletingAnAccountNeedsAFreshSmsCodeAndRemovesOrAnonymizesEverything() {
        SysUserDTO account = user(9L, null);
        account.setPhone(PHONE);
        when(users.getByUserId(9L)).thenReturn(account);
        when(captcha.validateSMSValidateCode(PHONE, "654321", false)).thenReturn(true);

        service.deleteAccount(9L, "654321");

        verify(captcha).validateSMSValidateCode(PHONE, "654321", true);
        verify(accountData).deleteConversationTurns(9L);
        verify(accountData).deleteConversationMemories(9L);
        verify(accountData).anonymizeUsage(9L);
        verify(accountData).anonymizeSafetyEvents(9L);
        verify(accountData).deleteInviteUse(9L);
        verify(accountData).deleteConsents(9L);
        verify(accountData).deleteAppTokens(9L);
        verify(accountData).deleteConsoleTokens(9L);
        verify(profiles).delete(9L);
        verify(users).deleteById(9L);
    }

    @Test
    void aWrongCodeOrAnAdministratorAccountIsNeverDeleted() {
        SysUserDTO account = user(9L, null);
        account.setPhone(PHONE);
        SysUserDTO admin = user(1L, null);
        admin.setPhone(PHONE);
        admin.setSuperAdmin(1);
        when(users.getByUserId(9L)).thenReturn(account);
        when(users.getByUserId(1L)).thenReturn(admin);
        when(captcha.validateSMSValidateCode(PHONE, "000000", false)).thenReturn(false);
        when(captcha.validateSMSValidateCode(PHONE, "654321", false)).thenReturn(true);

        assertEquals(ErrorCode.SMS_CODE_ERROR,
                assertThrows(RenException.class, () -> service.deleteAccount(9L, "000000")).getCode());
        assertThrows(RenException.class, () -> service.deleteAccount(1L, "654321"));

        verify(users, never()).deleteById(any());
        verify(accountData, never()).deleteConversationTurns(any());
    }

    @Test
    void theDeletionCodeGoesToTheAccountsOwnPhone() {
        SysUserDTO account = user(9L, null);
        account.setPhone("13900139000");
        when(users.getByUserId(9L)).thenReturn(account);

        service.sendAccountCode(9L, IP);

        verify(captcha).sendSMSValidateCode("+8613900139000");
    }

    @Test
    void codeLoginRejectsWrongCode() {
        when(captcha.validateSMSValidateCode(PHONE, "000000", false)).thenReturn(false);

        RenException error = assertThrows(RenException.class,
                () -> service.loginBySmsCode(codeLogin("000000"), "android", IP));

        assertEquals(ErrorCode.SMS_CODE_ERROR, error.getCode());
        verify(users, never()).saveAppUser(any());
    }

    @Test
    void codeLoginAcceptsMainlandPhoneWithoutCountryCode() {
        when(captcha.validateSMSValidateCode("+8613900139000", "123456", false)).thenReturn(true);
        when(users.getByPhone("+8613900139000")).thenReturn(user(9L, null));
        when(tokens.createSession(eq(9L), any(), any())).thenReturn(new AppTokenVO("app_s", 1, "appr_s", 2));

        AppCodeLoginDTO dto = codeLogin("123456");
        dto.setPhone("13900139000");
        AppTokenVO result = service.loginBySmsCode(dto, "android", IP);

        assertNotNull(result);
        verify(users, never()).saveAppUser(any());
    }

    @Test
    void codeLoginTreatsPlus86AndBareMainlandPhoneAsSameAccount() {
        when(captcha.validateSMSValidateCode("+8613900139000", "123456", false)).thenReturn(true);
        when(users.getByPhone("+8613900139000")).thenReturn(user(9L, null));
        when(tokens.createSession(eq(9L), any(), any())).thenReturn(new AppTokenVO("app_s", 1, "appr_s", 2));

        AppCodeLoginDTO dto = codeLogin("123456");
        dto.setPhone("+86 139-0013-9000");
        assertNotNull(service.loginBySmsCode(dto, "android", IP));
    }

    @Test
    void smsCodeRejectsInvalidPhoneFormat() {
        RenException error = assertThrows(RenException.class,
                () -> service.sendSmsCode("12345", IP));

        assertEquals(ErrorCode.PHONE_FORMAT_ERROR, error.getCode());
        verify(captcha, never()).sendSMSValidateCode(anyString());
    }

    @Test
    void smsCodeNormalizesMainlandPhoneBeforeDelegating() {
        when(redis.get(RedisKeys.getAppAuthSmsIpCountKey(IP))).thenReturn(null);

        service.sendSmsCode("13900139000", IP);

        verify(captcha).sendSMSValidateCode("+8613900139000");
    }

    @Test
    void smsCodeRejectsWhenIpDailyLimitReached() {
        when(redis.get(RedisKeys.getAppAuthSmsIpCountKey(IP))).thenReturn(20);

        RenException error = assertThrows(RenException.class,
                () -> service.sendSmsCode(PHONE, IP));

        assertEquals(ErrorCode.APP_SMS_IP_LIMIT, error.getCode());
        verify(captcha, never()).sendSMSValidateCode(anyString());
    }

    @Test
    void aLoginCodeIsOnlySentAfterTheImageCaptchaMatches() {
        when(redis.get(RedisKeys.getAppAuthSmsIpCountKey(IP))).thenReturn(null);
        when(captcha.validate("captcha-1", "aB3dE", true)).thenReturn(true);

        service.sendLoginSmsCode(PHONE, IP, "captcha-1", "aB3dE");

        verify(captcha).sendSMSValidateCode(PHONE);
    }

    @Test
    void aWrongOrMissingImageCaptchaSendsNothing() {
        when(captcha.validate("captcha-1", "wrong", true)).thenReturn(false);

        RenException wrong = assertThrows(RenException.class,
                () -> service.sendLoginSmsCode(PHONE, IP, "captcha-1", "wrong"));
        RenException missing = assertThrows(RenException.class,
                () -> service.sendLoginSmsCode(PHONE, IP, " ", "aB3dE"));

        assertEquals(ErrorCode.SMS_CAPTCHA_ERROR, wrong.getCode());
        assertEquals(ErrorCode.SMS_CAPTCHA_ERROR, missing.getCode());
        verify(captcha, never()).sendSMSValidateCode(anyString());
        // 猜错不占用这个来源地址当天的发送次数。
        verify(redis, never()).increment(eq(RedisKeys.getAppAuthSmsIpCountKey(IP)), anyLong());
    }

    @Test
    void aSignedInUserSetsAPasswordWithACodeSentToTheirOwnPhone() {
        SysUserDTO account = user(9L, null);
        account.setPhone(PHONE);
        when(users.getByUserId(9L)).thenReturn(account);
        when(captcha.validateSMSValidateCode(PHONE, "123456", false)).thenReturn(true);

        try (MockedStatic<Sm2DecryptUtil> sm2 = mockStatic(Sm2DecryptUtil.class)) {
            sm2.when(() -> Sm2DecryptUtil.decryptLoginPassword("enc-new", null, params)).thenReturn("NewPass123");
            service.setPassword(9L, "123456", "enc-new");
        }

        verify(users).changePasswordDirectly(9L, "NewPass123");
        verify(captcha).validateSMSValidateCode(PHONE, "123456", true);
        verify(redis).delete(RedisKeys.getAppAuthLoginFailureKey(PHONE));
    }

    @Test
    void settingAPasswordNeedsTheRightCodeAndAStrongEnoughPassword() {
        SysUserDTO account = user(9L, null);
        account.setPhone(PHONE);
        when(users.getByUserId(9L)).thenReturn(account);
        when(captcha.validateSMSValidateCode(PHONE, "000000", false)).thenReturn(false);
        when(captcha.validateSMSValidateCode(PHONE, "123456", false)).thenReturn(true);

        RenException wrongCode = assertThrows(RenException.class, () -> service.setPassword(9L, "000000", "enc"));
        assertEquals(ErrorCode.SMS_CODE_ERROR, wrongCode.getCode());

        try (MockedStatic<Sm2DecryptUtil> sm2 = mockStatic(Sm2DecryptUtil.class)) {
            sm2.when(() -> Sm2DecryptUtil.decryptLoginPassword("enc", null, params)).thenReturn("short1A");
            RenException weak = assertThrows(RenException.class, () -> service.setPassword(9L, "123456", "enc"));
            assertEquals("密码需要 8 到 32 位，同时包含大写字母、小写字母和数字", weak.getMsg());
        }
        verify(users, never()).changePasswordDirectly(any(), any());
        // 验证码还没用掉，改个好一点的密码可以直接再试。
        verify(captcha, never()).validateSMSValidateCode(PHONE, "123456", true);
    }

    @Test
    void checkingTheAccountCodeLeavesItUsableForSavingThePassword() {
        SysUserDTO account = user(9L, null);
        account.setPhone(PHONE);
        when(users.getByUserId(9L)).thenReturn(account);
        when(captcha.validateSMSValidateCode(PHONE, "123456", false)).thenReturn(true);
        when(captcha.validateSMSValidateCode(PHONE, "000000", false)).thenReturn(false);

        service.checkAccountCode(9L, "123456");
        RenException wrong = assertThrows(RenException.class, () -> service.checkAccountCode(9L, "000000"));

        assertEquals(ErrorCode.SMS_CODE_ERROR, wrong.getCode());
        verify(captcha, never()).validateSMSValidateCode(PHONE, "123456", true);
        verify(redis).increment(RedisKeys.getAppAuthSmsCodeFailureKey(PHONE), 300);
    }

    @Test
    void theProfileSaysWhetherAPasswordIsSetWithoutShowingIt() {
        when(users.getByUserId(9L)).thenReturn(user(9L, null));
        when(users.getByUserId(7L)).thenReturn(user(7L, "{bcrypt}hash"));

        assertEquals(false, service.profile(9L).isHasPassword());
        assertEquals(true, service.profile(7L).isHasPassword());
    }

    @Test
    void theProfileCarriesTheNicknameAndAvatarTheUserSet() {
        when(users.getByUserId(9L)).thenReturn(user(9L, null));
        AppUserProfileEntity own = new AppUserProfileEntity();
        own.setNickname("小林");
        own.setAvatar(JPEG);
        when(profiles.find(9L)).thenReturn(own);

        AppProfileVO profile = service.profile(9L);

        assertEquals("小林", profile.getNickname());
        assertEquals(JPEG, profile.getAvatar());
    }

    @Test
    void updatingTheProfileTrimsTheNicknameAndLeavesUnsentFieldsAlone() {
        when(users.getByUserId(9L)).thenReturn(user(9L, null));

        service.updateProfile(9L, "  小林 🌙 ", null);

        verify(profiles).saveNickname(9L, "小林 🌙");
        verify(profiles, never()).saveAvatar(any(), any());
    }

    @Test
    void emptyStringsClearTheNicknameAndAvatar() {
        when(users.getByUserId(9L)).thenReturn(user(9L, null));

        service.updateProfile(9L, "   ", "");

        verify(profiles).saveNickname(9L, null);
        verify(profiles).saveAvatar(9L, null);
    }

    @Test
    void nicknamesOverTwentyCharactersOrWithLineBreaksAreRejected() {
        when(users.getByUserId(9L)).thenReturn(user(9L, null));

        // 20 个表情是 20 个字，可以；21 个字不行。
        service.updateProfile(9L, "🌙".repeat(20), null);
        RenException tooLong = assertThrows(RenException.class, () -> service.updateProfile(9L, "字".repeat(21), null));
        RenException lineBreak = assertThrows(RenException.class, () -> service.updateProfile(9L, "小\n林", null));

        assertEquals(ErrorCode.APP_NICKNAME_INVALID, tooLong.getCode());
        assertEquals(ErrorCode.APP_NICKNAME_INVALID, lineBreak.getCode());
        verify(profiles).saveNickname(9L, "🌙".repeat(20));
    }

    @Test
    void avatarsMustBeRealJpegPngOrWebpDataUrls() {
        when(users.getByUserId(9L)).thenReturn(user(9L, null));

        service.updateProfile(9L, null, JPEG);
        verify(profiles).saveAvatar(9L, JPEG);

        String[] bad = {
            "https://example.com/me.jpg",
            "data:image/svg+xml;base64," + java.util.Base64.getEncoder().encodeToString("<svg/>".getBytes()),
            // 声明是 PNG，内容却是 JPEG
            "data:image/png;base64," + JPEG.substring(JPEG.indexOf(',') + 1),
            "data:image/jpeg;base64,not base64!",
            "data:image/jpeg;base64," + "A".repeat(300_001),
        };
        for (String avatar : bad) {
            RenException error = assertThrows(RenException.class, () -> service.updateProfile(9L, null, avatar));
            assertEquals(ErrorCode.APP_AVATAR_INVALID, error.getCode());
        }
    }

    @Test
    void aRejectedAvatarDoesNotSaveTheNicknameSentWithIt() {
        when(users.getByUserId(9L)).thenReturn(user(9L, null));

        assertThrows(RenException.class, () -> service.updateProfile(9L, "小林", "data:image/gif;base64,R0lGOD"));

        verify(profiles, never()).saveNickname(any(), any());
    }

    /** 最小的 JPEG 文件头，够验证类型。 */
    private static final String JPEG = "data:image/jpeg;base64," + java.util.Base64.getEncoder()
            .encodeToString(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'});

    @Test
    void smsCodeDelegatesToPlatformChannel() {
        when(redis.get(RedisKeys.getAppAuthSmsIpCountKey(IP))).thenReturn(null);

        service.sendSmsCode(PHONE, IP);

        verify(redis).increment(RedisKeys.getAppAuthSmsIpCountKey(IP), 3600 * 24);
        verify(captcha).sendSMSValidateCode(PHONE);
    }

    @Test
    void refreshAndLogoutDelegateToTokenStore() {
        service.refresh("appr_secret");
        verify(tokens).refresh("appr_secret");

        service.logout("app_secret");
        verify(tokens).revoke("app_secret");
    }

    private AppRegisterDTO register(String code, String password) {
        AppRegisterDTO dto = new AppRegisterDTO();
        dto.setPhone(PHONE);
        dto.setCode(code);
        dto.setPassword(password);
        return dto;
    }

    private AppPasswordLoginDTO passwordLogin(String password) {
        AppPasswordLoginDTO dto = new AppPasswordLoginDTO();
        dto.setPhone(PHONE);
        dto.setPassword(password);
        return dto;
    }

    private AppCodeLoginDTO codeLogin(String code) {
        AppCodeLoginDTO dto = new AppCodeLoginDTO();
        dto.setPhone(PHONE);
        dto.setCode(code);
        return dto;
    }

    private SysUserDTO user(Long id, String rawPassword) {
        SysUserDTO dto = new SysUserDTO();
        dto.setId(id);
        dto.setUsername(PHONE);
        dto.setPhone(PHONE);
        dto.setStatus(1);
        dto.setSuperAdmin(0);
        dto.setCreateDate(new Date());
        if (rawPassword != null) {
            dto.setPassword(PasswordUtils.encode(rawPassword));
        }
        return dto;
    }
}
