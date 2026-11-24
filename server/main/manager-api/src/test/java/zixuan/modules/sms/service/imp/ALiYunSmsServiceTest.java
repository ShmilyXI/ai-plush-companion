package zixuan.modules.sms.service.imp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.tea.TeaException;
import com.aliyun.teaopenapi.models.Config;

import zixuan.common.exception.RenException;
import zixuan.common.redis.RedisKeys;
import zixuan.common.redis.RedisUtils;
import zixuan.modules.sys.service.SysParamsService;

class ALiYunSmsServiceTest {

    private final SysParamsService params = mock(SysParamsService.class);
    private final RedisUtils redis = mock(RedisUtils.class);

    /** 不真的调阿里云：记下发出去的请求，返回预先设好的结果。 */
    private static class Recording extends ALiYunSmsService {
        Config config;
        SendSmsVerifyCodeRequest verify;
        SendSmsRequest sms;
        Outcome reply = new Outcome("OK", "OK", "req-1");
        Exception error;

        Recording(SysParamsService params, RedisUtils redis) {
            super(params, redis);
        }

        @Override
        Outcome callVerify(Config config, SendSmsVerifyCodeRequest request) throws Exception {
            this.config = config; this.verify = request;
            if (error != null) throw error;
            return reply;
        }

        @Override
        Outcome callSms(Config config, SendSmsRequest request) throws Exception {
            this.config = config; this.sms = request;
            if (error != null) throw error;
            return reply;
        }
    }

    private Recording service(String api) {
        Map<String, String> values = new HashMap<>();
        values.put("aliyun.sms.access_key_id", "test-key-id");
        values.put("aliyun.sms.access_key_secret", "test-key-secret");
        values.put("aliyun.sms.sign_name", "恒创联众");
        values.put("aliyun.sms.sms_code_template_code", "100001");
        values.put("aliyun.sms.api", api);
        when(params.getValue(anyString(), eq(true))).thenAnswer(call -> values.get(call.getArgument(0, String.class)));
        return new Recording(params, redis);
    }

    @Test
    void sendsOurOwnCodeThroughSmsVerificationByDefault() {
        Recording sms = service(null);

        sms.sendVerificationCodeSms("+8613900000001", "482913");

        assertEquals("dypnsapi.aliyuncs.com", sms.config.endpoint);
        assertEquals("13900000001", sms.verify.getPhoneNumber());
        assertEquals("86", sms.verify.getCountryCode());
        assertEquals("恒创联众", sms.verify.getSignName());
        assertEquals("100001", sms.verify.getTemplateCode());
        // 验证码是我们生成的那个，写死在模板参数里；有效时间和 Redis 里的 5 分钟一致。
        assertEquals("{\"code\":\"482913\",\"min\":\"5\"}", sms.verify.getTemplateParam());
        assertEquals(6L, sms.verify.getCodeLength());
        assertEquals(300L, sms.verify.getValidTime());
        assertNull(sms.sms);
        verify(redis, never()).delete(anyString());
    }

    @Test
    void canStillUseTheSmsServiceWithItsOwnSignature() {
        Recording sms = service("sms");

        sms.sendVerificationCodeSms("13900000001", "482913");

        assertEquals("dysmsapi.aliyuncs.com", sms.config.endpoint);
        assertEquals("13900000001", sms.sms.getPhoneNumbers());
        assertEquals("{\"code\":\"482913\"}", sms.sms.getTemplateParam());
        assertNull(sms.verify);
    }

    @Test
    void aRefusedSendDoesNotCountAndSaysWhy() {
        Recording sms = service("verify");
        sms.reply = new ALiYunSmsService.Outcome("biz.FREQUENCY", "Check frequency fail.", "req-2");

        RenException error = assertThrows(RenException.class, () -> sms.sendVerificationCodeSms("13900000001", "482913"));

        assertEquals("验证码发送太频繁，请稍后再试", error.getMsg());
        verify(redis).delete(RedisKeys.getSMSTodayCountKey("13900000001"));
    }

    @Test
    void businessErrorsThrownAsExceptionsAreExplainedToo() {
        Recording sms = service("verify");
        Map<String, Object> details = new HashMap<>();
        details.put("code", "isv.MOBILE_NUMBER_ILLEGAL");
        details.put("message", "number illegal");
        sms.error = new TeaException(details);

        RenException error = assertThrows(RenException.class, () -> sms.sendVerificationCodeSms("12345", "482913"));

        assertEquals("手机号格式不正确", error.getMsg());
        verify(redis).delete(RedisKeys.getSMSTodayCountKey("12345"));
    }

    @Test
    void otherErrorsCarryAliyunsCodeForTroubleshooting() {
        assertEquals("短信发送失败，请稍后再试（EntityNotExist.SmsSignName）", ALiYunSmsService.explain("EntityNotExist.SmsSignName"));
        assertEquals("短信发送失败，请稍后再试（未知错误）", ALiYunSmsService.explain(null));
    }

    @Test
    void refusesToSendWhenTheKeysAreMissing() {
        when(params.getValue(anyString(), eq(true))).thenReturn("");
        Recording sms = new Recording(params, redis);

        RenException error = assertThrows(RenException.class, () -> sms.sendVerificationCodeSms("13900000001", "482913"));

        assertEquals("短信服务暂时不可用，请稍后再试", error.getMsg());
        assertNull(sms.verify);
        verify(redis).delete(RedisKeys.getSMSTodayCountKey("13900000001"));
    }
}
