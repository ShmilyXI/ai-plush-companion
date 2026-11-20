package zixuan.modules.sms.service.imp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import zixuan.common.redis.RedisUtils;
import zixuan.modules.security.service.impl.CaptchaServiceImpl;
import zixuan.modules.sms.service.SmsService;
import zixuan.modules.sys.service.SysParamsService;

class LoggingSmsServiceTest {

    private static CaptchaServiceImpl captcha(SmsService sms, RedisUtils redis) {
        CaptchaServiceImpl captcha = new CaptchaServiceImpl();
        ReflectionTestUtils.setField(captcha, "redisUtils", redis);
        ReflectionTestUtils.setField(captcha, "smsService", sms);
        ReflectionTestUtils.setField(captcha, "sysParamsService", mock(SysParamsService.class));
        ReflectionTestUtils.setField(captcha, "open", true);
        return captcha;
    }

    @Test
    void hasNoFixedCodeUnlessOneIsConfigured() {
        assertNull(new LoggingSmsService("").fixedVerificationCode());
        assertNull(new LoggingSmsService(null).fixedVerificationCode());
        assertEquals("000000", new LoggingSmsService(" 000000 ").fixedVerificationCode());
    }

    @Test
    void refusesAFixedCodeThatIsNotDigits() {
        assertThrows(IllegalStateException.class, () -> new LoggingSmsService("abc"));
        assertThrows(IllegalStateException.class, () -> new LoggingSmsService("12"));
    }

    @Test
    void aRealSmsChannelNeverHasAFixedCode() {
        SmsService real = (phone, code) -> { };
        assertNull(real.fixedVerificationCode());
    }

    @Test
    void theFixedCodeSignsInAnyPhoneWithoutRequestingACodeFirst() {
        RedisUtils redis = mock(RedisUtils.class);
        CaptchaServiceImpl captcha = captcha(new LoggingSmsService("000000"), redis);

        assertTrue(captcha.validateSMSValidateCode("+8613800000001", "000000", false));
        assertTrue(captcha.validateSMSValidateCode("+8613900000002", "000000", true));
        // 其他验证码仍然要和发出去的那个对得上。
        assertFalse(captcha.validateSMSValidateCode("+8613800000001", "123456", false));
    }

    @Test
    void theFixedCodeIsAlsoWhatGetsSent() {
        RedisUtils redis = mock(RedisUtils.class);
        when(redis.getKeyOrCreate(anyString(), anyString(), eq(60L))).thenReturn(null);
        SmsService sms = mock(SmsService.class);
        when(sms.fixedVerificationCode()).thenReturn("000000");

        captcha(sms, redis).sendSMSValidateCode("+8613800000001");

        verify(sms).sendVerificationCodeSms("+8613800000001", "000000");
    }

    @Test
    void withoutAFixedCodeNothingPassesThatWasNotSent() {
        RedisUtils redis = mock(RedisUtils.class);
        SmsService real = (phone, code) -> { };
        assertFalse(captcha(real, redis).validateSMSValidateCode("+8613800000001", "000000", false));
    }
}
