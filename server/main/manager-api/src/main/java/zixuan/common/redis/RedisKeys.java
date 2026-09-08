package zixuan.common.redis;

/**
 * Redis Key 常量类
 * Copyright (c) 人人开源 All rights reserved.
 * Website: https://www.renren.io
 */
public class RedisKeys {
    private static final String PREFIX = "zixuan:";

    /**
     * 系统参数Key
     */
    public static String getSysParamsKey() {
        return PREFIX + "sys:params";
    }

    /**
     * 验证码Key
     */
    public static String getCaptchaKey(String uuid) {
        return PREFIX + "sys:captcha:" + uuid;
    }

    /**
     * 未注册设备验证码Key
     */
    public static String getDeviceCaptchaKey(String captcha) {
        return PREFIX + "sys:device:captcha:" + captcha;
    }

    /**
     * 用户id的Key
     */
    public static String getUserIdKey(Long userid) {
        return PREFIX + "sys:username:id:" + userid;
    }

    /**
     * 模型名称的Key
     */
    public static String getModelNameById(String id) {
        return PREFIX + "model:name:" + id;
    }

    /**
     * 模型配置的Key
     */
    public static String getModelConfigById(String id) {
        return PREFIX + "model:data:" + id;
    }

    /**
     * 获取音色名称缓存key
     */
    public static String getTimbreNameById(String id) {
        return PREFIX + "timbre:name:" + id;
    }

    /**
     * 获取设备数量缓存key
     */
    public static String getAgentDeviceCountById(String id) {
        return PREFIX + "agent:device:count:" + id;
    }

    /**
     * 获取智能体最后连接时间缓存key
     */
    public static String getAgentDeviceLastConnectedAtById(String id) {
        return PREFIX + "agent:device:lastConnected:" + id;
    }

    /**
     * 获取系统配置缓存key
     */
    public static String getServerConfigKey() {
        return PREFIX + "server:config";
    }

    /**
     * 获取音色详情缓存key
     */
    public static String getTimbreDetailsKey(String id) {
        return PREFIX + "timbre:details:" + id;
    }

    /**
     * 获取版本号Key
     */
    public static String getVersionKey() {
        return PREFIX + "sys:version";
    }

    /**
     * OTA固件ID的Key
     */
    public static String getOtaIdKey(String uuid) {
        return PREFIX + "ota:id:" + uuid;
    }

    /**
     * OTA固件下载次数的Key
     */
    public static String getOtaDownloadCountKey(String uuid) {
        return PREFIX + "ota:download:count:" + uuid;
    }

    /**
     * 获取字典数据的缓存key
     */
    public static String getDictDataByTypeKey(String dictType) {
        return PREFIX + "sys:dict:data:" + dictType;
    }

    /**
     * 获取智能体音频ID的缓存key
     */
    public static String getAgentAudioIdKey(String uuid) {
        return PREFIX + "agent:audio:id:" + uuid;
    }

    /**
     * 获取短信验证码的缓存key
     */
    public static String getSMSValidateCodeKey(String phone) {
        return PREFIX + "sms:Validate:Code:" + phone;
    }

    /**
     * 获取短信验证码最后发送时间的缓存key
     */
    public static String getSMSLastSendTimeKey(String phone) {
        return PREFIX + "sms:Validate:Code:" + phone + ":last_send_time";
    }

    /**
     * 获取短信验证码今日发送次数的缓存key
     */
    public static String getSMSTodayCountKey(String phone) {
        return PREFIX + "sms:Validate:Code:" + phone + ":today_count";
    }

    /**
     * App 密码登录失败计数的缓存key
     */
    public static String getAppAuthLoginFailureKey(String phone) {
        return PREFIX + "appauth:login-failure:" + phone;
    }

    /**
     * App 短信验证码按来源IP当日发送次数的缓存key
     */
    public static String getAppAuthSmsIpCountKey(String ip) {
        return PREFIX + "appauth:sms-ip-count:" + ip;
    }

    /**
     * 聊天记录UUID映射的Key
     */
    public static String getChatHistoryKey(String uuid) {
        return PREFIX + "agent:chat:history:" + uuid;
    }

    /**
     * 获取音色克隆音频ID的缓存key
     */
    public static String getVoiceCloneAudioIdKey(String uuid) {
        return PREFIX + "voiceClone:audio:id:" + uuid;
    }

    /**
     * 获取知识库缓存key
     */
    public static String getKnowledgeBaseCacheKey(String datasetId) {
        return PREFIX + "knowledge:base:" + datasetId;
    }

    /**
     * 获取临时注册设备标记key
     */
    public static String getTmpRegisterMacKey(String deviceId) {
        return PREFIX + "tmp_register_mac:" + deviceId;
    }

    /**
     * OTA绑定设备
     */
    public static String getOtaActivationCode(String activationCode) {
        return PREFIX + "ota:activation:code:" + activationCode;
    }

    /**
     * OTA获取设备mac相关信息
     */
    public static String getOtaDeviceActivationInfo(String deviceId) {
        return PREFIX + "ota:activation:data:" + deviceId;
    }

    /**
     * OTA上传次数
     */
    public static String getOtaUploadCountKey(Long username) {
        return PREFIX + "ota:upload:count:" + username;
    }

    /**
     * 设备通讯录缓存Key
     */
    public static String getAddressBookKey() {
        return PREFIX + "device:address_book:all";
    }

    /**
     * 设备调试日志流缓存Key
     */
    public static String getDeviceDebugLogKey(String deviceId) {
        return PREFIX + "device:debug:logs:" + deviceId;
    }

}
