package zixuan.modules.sms.service.imp;

import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponseBody;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponseBody;
import com.aliyun.tea.TeaException;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zixuan.common.constant.Constant;
import zixuan.common.exception.RenException;
import zixuan.common.redis.RedisKeys;
import zixuan.common.redis.RedisUtils;
import zixuan.modules.sms.service.SmsService;
import zixuan.modules.sys.service.SysParamsService;

/**
 * 用阿里云发短信验证码。验证码由我们生成、存在 Redis 里、由我们校验，阿里云只负责送到手机上。
 *
 * <p>阿里云有两个能发验证码的接口，用参数 aliyun.sms.api 选：
 * <ul>
 *   <li>verify（默认）：号码认证服务的“短信认证”，SendSmsVerifyCode。可以用阿里云赠送的签名和模板
 *       （比如签名“恒创联众”、登录注册模板 100001），不用自己申请签名。</li>
 *   <li>sms：短信服务，SendSms。要用自己申请并审核通过的签名和模板。</li>
 * </ul>
 * 两种都读同样的四个参数：aliyun.sms.access_key_id、access_key_secret、sign_name、sms_code_template_code。
 */
@Service
@AllArgsConstructor
@Slf4j
public class ALiYunSmsService implements SmsService {
    static final String VERIFY_ENDPOINT = "dypnsapi.aliyuncs.com";
    static final String SMS_ENDPOINT = "dysmsapi.aliyuncs.com";
    /** 验证码在 Redis 里存 5 分钟（CaptchaServiceImpl），短信里写的有效时间要和它一致。 */
    static final int VALID_MINUTES = 5;
    /** 没配置好或者连不上阿里云时给用户看的话。原因写在日志里。 */
    static final String UNAVAILABLE = "短信服务暂时不可用，请稍后再试";

    private static final Set<String> TOO_FREQUENT = Set.of(
            "biz.FREQUENCY", "FREQUENCY_FAIL", "BUSINESS_LIMIT_CONTROL", "isv.BUSINESS_LIMIT_CONTROL",
            "LimitExceeded.SmsCode", "isv.DAY_LIMIT_CONTROL");
    private static final Set<String> BAD_NUMBER = Set.of(
            "MOBILE_NUMBER_ILLEGAL", "isv.MOBILE_NUMBER_ILLEGAL", "InvalidPhoneNumber.Check");

    private final SysParamsService sysParamsService;
    private final RedisUtils redisUtils;

    /** 阿里云返回的结果：Code 是 OK 才算发出去了，HTTP 200 不代表成功。 */
    record Outcome(String code, String message, String requestId) {
    }

    @Override
    public void sendVerificationCodeSms(String phone, String verificationCode) {
        String accessKeyId = param(Constant.SysMSMParam.ALIYUN_SMS_ACCESS_KEY_ID);
        String accessKeySecret = param(Constant.SysMSMParam.ALIYUN_SMS_ACCESS_KEY_SECRET);
        String signName = param(Constant.SysMSMParam.ALIYUN_SMS_SIGN_NAME);
        String templateCode = param(Constant.SysMSMParam.ALIYUN_SMS_SMS_CODE_TEMPLATE_CODE);
        boolean useSmsApi = "sms".equalsIgnoreCase(param(Constant.SysMSMParam.ALIYUN_SMS_API));
        if (accessKeyId == null || accessKeySecret == null || signName == null || templateCode == null) {
            // 具体缺哪个只写日志，不告诉来登录的人。
            log.error("阿里云短信没有配置完整：参数管理里的 aliyun.sms.access_key_id、access_key_secret、sign_name、sms_code_template_code 都要填");
            refundTodayCount(phone);
            throw new RenException(UNAVAILABLE);
        }
        // 两个接口都只接受国内号码本身，不带 +86。
        String number = phone.replaceFirst("^\\+86", "");
        String templateParam = useSmsApi
                ? String.format("{\"code\":\"%s\"}", verificationCode)
                : String.format("{\"code\":\"%s\",\"min\":\"%d\"}", verificationCode, VALID_MINUTES);

        Outcome outcome;
        try {
            Config config = new Config().setAccessKeyId(accessKeyId).setAccessKeySecret(accessKeySecret);
            if (useSmsApi) {
                config.endpoint = SMS_ENDPOINT;
                outcome = callSms(config, new SendSmsRequest()
                        .setSignName(signName)
                        .setTemplateCode(templateCode)
                        .setPhoneNumbers(number)
                        .setTemplateParam(templateParam));
            } else {
                config.endpoint = VERIFY_ENDPOINT;
                // 验证码是我们自己生成的，直接写在模板参数里；阿里云不保存、也不替我们校验它。
                outcome = callVerify(config, new SendSmsVerifyCodeRequest()
                        .setSignName(signName)
                        .setTemplateCode(templateCode)
                        .setPhoneNumber(number)
                        .setCountryCode("86")
                        .setTemplateParam(templateParam)
                        .setCodeLength((long) verificationCode.length())
                        .setValidTime(VALID_MINUTES * 60L)
                        .setReturnVerifyCode(false));
            }
        } catch (TeaException e) {
            // 参数不对、限流这类业务错误，阿里云用 HTTP 4xx 返回，SDK 抛成 TeaException。
            outcome = new Outcome(e.getCode(), e.getMessage(), null);
        } catch (Exception e) {
            refundTodayCount(phone);
            log.error("阿里云短信接口调用失败: {}", e.getMessage());
            throw new RenException(UNAVAILABLE);
        }

        log.info("阿里云短信（{}）回执: {}, code: {}, message: {}",
                useSmsApi ? "SendSms" : "SendSmsVerifyCode", outcome.requestId(), outcome.code(), outcome.message());
        if (!"OK".equals(outcome.code())) {
            // 没发出去，这次不算进今天的发送次数。
            refundTodayCount(phone);
            throw new RenException(explain(outcome.code()));
        }
    }

    /** 给来登录的人看的话：频率太高、号码不对说清楚，其他的带上阿里云的错误码方便查。 */
    static String explain(String code) {
        if (code != null && TOO_FREQUENT.contains(code)) return "验证码发送太频繁，请稍后再试";
        if (code != null && BAD_NUMBER.contains(code)) return "手机号格式不正确";
        return "短信发送失败，请稍后再试（" + StringUtils.defaultIfBlank(code, "未知错误") + "）";
    }

    /** 调号码认证服务的 SendSmsVerifyCode。单独成一个方法，测试里可以换掉，不真的发短信。 */
    Outcome callVerify(Config config, SendSmsVerifyCodeRequest request) throws Exception {
        SendSmsVerifyCodeResponseBody body = new com.aliyun.dypnsapi20170525.Client(config)
                .sendSmsVerifyCodeWithOptions(request, new RuntimeOptions()).getBody();
        // 这个 SDK 不填顶层的 RequestId，记下 Model 里的发送回执号（BizId），在控制台“发送记录”里能查到。
        String receipt = body.getModel() == null ? body.getRequestId()
                : StringUtils.firstNonBlank(body.getModel().getBizId(), body.getModel().getRequestId(), body.getRequestId());
        return new Outcome(body.getCode(), body.getMessage(), receipt);
    }

    /** 调短信服务的 SendSms。 */
    Outcome callSms(Config config, SendSmsRequest request) throws Exception {
        SendSmsResponseBody body = new com.aliyun.dysmsapi20170525.Client(config)
                .sendSmsWithOptions(request, new RuntimeOptions()).getBody();
        return new Outcome(body.getCode(), body.getMessage(), body.getRequestId());
    }

    private String param(Constant.SysMSMParam key) {
        String value = StringUtils.trimToNull(sysParamsService.getValue(key.getValue(), true));
        return value == null || "null".equalsIgnoreCase(value) ? null : value;
    }

    private void refundTodayCount(String phone) {
        redisUtils.delete(RedisKeys.getSMSTodayCountKey(phone));
    }
}
