package zixuan.modules.sms.service.imp;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zixuan.common.constant.Constant;
import zixuan.common.exception.ErrorCode;
import zixuan.common.exception.RenException;
import zixuan.common.redis.RedisKeys;
import zixuan.common.redis.RedisUtils;
import zixuan.modules.sms.service.SmsService;
import zixuan.modules.sys.service.SysParamsService;

@Service
@AllArgsConstructor
@Slf4j
public class ALiYunSmsService implements SmsService {
    private final SysParamsService  sysParamsService;
    private final RedisUtils redisUtils;

    @Override
    public void sendVerificationCodeSms(String phone, String VerificationCode) {
        Client client = createClient();
        String SignName = sysParamsService.getValue(Constant.SysMSMParam
                .ALIYUN_SMS_SIGN_NAME.getValue(),true);
        String TemplateCode = sysParamsService.getValue(Constant.SysMSMParam
                .ALIYUN_SMS_SMS_CODE_TEMPLATE_CODE.getValue(),true);
        try {
            // 阿里云国内短信接口不接受 +86 前缀，发送前统一剥掉
            String aliyunPhone = phone.replaceFirst("^\\+86", "");
            SendSmsRequest sendSmsRequest = new SendSmsRequest()
                    .setSignName(SignName)
                    .setTemplateCode(TemplateCode)
                    .setPhoneNumbers(aliyunPhone)
                    .setTemplateParam(String.format("{\"code\":\"%s\"}", VerificationCode));
            RuntimeOptions runtime = new RuntimeOptions();
            SendSmsResponse sendSmsResponse = client.sendSmsWithOptions(sendSmsRequest, runtime);
            String bizCode = sendSmsResponse.getBody().getCode();
            String bizMessage = sendSmsResponse.getBody().getMessage();
            log.info("发送短信响应的requestID: {}, code: {}, message: {}",
                    sendSmsResponse.getBody().getRequestId(), bizCode, bizMessage);
            // HTTP 200 不代表发送成功，阿里云把业务结果放在响应体 Code 里（OK 才是成功）
            if (!"OK".equals(bizCode)) {
                refundTodayCount(phone);
                throw new RenException("短信发送失败：" + bizCode + " " + bizMessage);
            }
        } catch (RenException e) {
            throw e;
        } catch (Exception e) {
            // 如果发送失败了退还这次发送数
            refundTodayCount(phone);
            // 错误 message
            log.error(e.getMessage());
            throw new RenException(ErrorCode.SMS_SEND_FAILED);
        }

    }

    private void refundTodayCount(String phone) {
        String todayCountKey = RedisKeys.getSMSTodayCountKey(phone);
        redisUtils.delete(todayCountKey);
    }


    /**
     * 创建阿里云连接
     * @return 返回连接对象
     */
    private Client createClient(){
        String ACCESS_KEY_ID = sysParamsService.getValue(Constant.SysMSMParam
                .ALIYUN_SMS_ACCESS_KEY_ID.getValue(),true);
        String ACCESS_KEY_SECRET = sysParamsService.getValue(Constant.SysMSMParam
                .ALIYUN_SMS_ACCESS_KEY_SECRET.getValue(),true);
        try {
            Config config = new Config()
                    .setAccessKeyId(ACCESS_KEY_ID)
                    .setAccessKeySecret(ACCESS_KEY_SECRET);
            // 配置 Endpoint。中国站请使用dysmsapi.aliyuncs.com
            config.endpoint = "dysmsapi.aliyuncs.com";
            return new Client(config);
        }catch (Exception e){
            // 错误 message
            log.error(e.getMessage());
            throw new RenException(ErrorCode.SMS_CONNECTION_FAILED);
        }
    }
}
