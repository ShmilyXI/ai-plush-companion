package zixuan.modules.config.init;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import jakarta.annotation.PostConstruct;
import zixuan.common.constant.Constant;
import zixuan.common.redis.RedisKeys;
import zixuan.common.redis.RedisUtils;
import zixuan.modules.config.service.ConfigService;
import zixuan.modules.companion.init.CompanionBootstrapService;
import zixuan.modules.device.service.DeviceAddressBookService;
import zixuan.modules.sys.service.SysParamsService;

@Configuration
@DependsOn("liquibase")
public class SystemInitConfig {

    @Autowired
    private SysParamsService sysParamsService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private RedisUtils redisUtils;

    @Autowired
    private DeviceAddressBookService deviceAddressBookService;

    @Autowired
    private CompanionBootstrapService companionBootstrapService;

    @PostConstruct
    public void init() {
        // 检查版本号
        String redisVersion = (String) redisUtils.get(RedisKeys.getVersionKey());
        if (!Constant.VERSION.equals(redisVersion)) {
            // 如果版本不一致，清空Redis
            redisUtils.emptyAll();
            // 存储新版本号
            redisUtils.set(RedisKeys.getVersionKey(), Constant.VERSION);
        }

        sysParamsService.initServerSecret();
        configService.getConfig(false);

        // 初始化设备通讯录缓存
        deviceAddressBookService.refreshCache();

        companionBootstrapService.initialize();
    }
}
