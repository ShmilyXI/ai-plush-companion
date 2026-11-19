package zixuan.modules.appauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
        service = new AppAuthServiceImpl(users, captcha, tokens, params, redis, invites);
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
        when(users.getByPhone(PHONE)).thenReturn(null, user(7L, "Right1A"));
        AppTokenVO issued = new AppTokenVO("app_secret", 43200, "appr_secret", 2592000);
        when(tokens.createSession(7L, "iphone", IP)).thenReturn(issued);

        try (MockedStatic<Sm2DecryptUtil> sm2 = mockStatic(Sm2DecryptUtil.class)) {
            sm2.when(() -> Sm2DecryptUtil.decryptLoginPassword("enc-password", null, params))
                    .thenReturn("Right1A");
            AppTokenVO result = service.register(dto, "iphone", IP);

            assertEquals(issued, result);
        }

        ArgumentCaptor<SysUserDTO> saved = ArgumentCaptor.forClass(SysUserDTO.class);
        verify(users).saveAppUser(saved.capture());
        assertEquals(PHONE, saved.getValue().getUsername());
        assertEquals(PHONE, saved.getValue().getPhone());
        assertEquals("Right1A", saved.getValue().getPassword());
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

            assertEquals(ErrorCode.ACCOUNT_PASSWORD_ERROR, error.getCode());
        }
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
    void codeLoginAutoRegistersPasswordlessAccount() {
        when(captcha.validateSMSValidateCode(PHONE, "123456", false)).thenReturn(true);
        when(users.getByPhone(PHONE)).thenReturn(null, user(9L, null));
        when(tokens.createSession(9L, "android", IP)).thenReturn(new AppTokenVO("app_s", 1, "appr_s", 2));

        AppTokenVO result = service.loginBySmsCode(codeLogin("123456"), "android", IP);

        assertNotNull(result);
        ArgumentCaptor<SysUserDTO> saved = ArgumentCaptor.forClass(SysUserDTO.class);
        verify(users).saveAppUser(saved.capture());
        assertEquals(PHONE, saved.getValue().getUsername());
        // 验证码直登创建的账号没有密码，后续必须走验证码或先设置密码
        assertEquals(null, saved.getValue().getPassword());
    }

    @Test
    void codeLoginForANewPhoneNeedsAnInviteAndKeepsTheSmsCodeForARetry() {
        when(captcha.validateSMSValidateCode(PHONE, "123456", false)).thenReturn(true);
        when(users.getByPhone(PHONE)).thenReturn(null);
        when(invites.isRequired()).thenReturn(true);
        doThrow(new RenException(ErrorCode.INVITE_CODE_REQUIRED)).when(invites).requireUsable(null);

        RenException error = assertThrows(RenException.class,
                () -> service.loginBySmsCode(codeLogin("123456"), "android", IP));

        assertEquals(ErrorCode.INVITE_CODE_REQUIRED, error.getCode());
        verify(users, never()).saveAppUser(any());
        // 验证码没有被作废，补上邀请码后可以直接重试
        verify(captcha, never()).validateSMSValidateCode(PHONE, "123456", true);
        verify(tokens, never()).createSession(any(), any(), any());
    }

    @Test
    void codeLoginWithAValidInviteCreatesTheAccountAndRedeemsTheCode() {
        when(captcha.validateSMSValidateCode(PHONE, "123456", false)).thenReturn(true);
        when(users.getByPhone(PHONE)).thenReturn(null, user(9L, null));
        when(invites.isRequired()).thenReturn(true);
        when(tokens.createSession(9L, "web", IP)).thenReturn(new AppTokenVO("app_s", 1, "appr_s", 2));
        AppCodeLoginDTO dto = codeLogin("123456");
        dto.setInviteCode("abcd2345");

        assertNotNull(service.loginBySmsCode(dto, "web", IP));

        verify(invites).requireUsable("abcd2345");
        verify(users).saveAppUser(any());
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
