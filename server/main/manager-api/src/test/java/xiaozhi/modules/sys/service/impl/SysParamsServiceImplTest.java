package xiaozhi.modules.sys.service.impl;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import xiaozhi.common.constant.Constant;
import xiaozhi.modules.agent.service.AgentPluginMappingService;
import xiaozhi.modules.sys.dao.SysParamsDao;
import xiaozhi.modules.sys.entity.SysParamsEntity;
import xiaozhi.modules.sys.redis.SysParamsRedis;

class SysParamsServiceImplTest {

    @Test
    void evictsOnlyRequestedParameterCodes() {
        SysParamsRedis redis = mock(SysParamsRedis.class);
        SysParamsServiceImpl service = new SysParamsServiceImpl(redis, mock(AgentPluginMappingService.class));

        service.evictCache(List.of("server.ip", "server.port"));

        verify(redis).delete(new Object[] { "server.ip", "server.port" });
    }

    @Test
    void upsertsMissingParameterCode() {
        SysParamsRedis redis = mock(SysParamsRedis.class);
        SysParamsDao dao = mock(SysParamsDao.class);
        SysParamsServiceImpl service = new SysParamsServiceImpl(redis, mock(AgentPluginMappingService.class));
        ReflectionTestUtils.setField(service, "baseDao", dao);
        when(dao.updateValueByCode("server.ota_port", "8002")).thenReturn(0);
        when(dao.insert(org.mockito.ArgumentMatchers.any(SysParamsEntity.class))).thenReturn(1);

        service.upsertValueByCode("server.ota_port", "8002", "number", "OTA 服务监听端口");

        verify(dao).insert((SysParamsEntity) org.mockito.ArgumentMatchers.argThat((SysParamsEntity entity) ->
                "server.ota_port".equals(entity.getParamCode())
                        && "8002".equals(entity.getParamValue())
                        && Integer.valueOf(1).equals(entity.getParamType())));
        verify(redis).set("server.ota_port", "8002");
    }

    @Test
    void disablingAddressBookStillDeletesItsSystemPlugin() {
        SysParamsRedis sysParamsRedis = mock(SysParamsRedis.class);
        AgentPluginMappingService pluginMappingService = mock(AgentPluginMappingService.class);
        SysParamsDao sysParamsDao = mock(SysParamsDao.class);
        SysParamsServiceImpl service = new SysParamsServiceImpl(sysParamsRedis, pluginMappingService);
        ReflectionTestUtils.setField(service, "baseDao", sysParamsDao);

        String currentConfig = "{\"features\":{\"addressBook\":{\"enabled\":true}}}";
        String newConfig = "{\"features\":{\"addressBook\":{\"enabled\":false}}}";
        when(sysParamsDao.getValueByCode(Constant.SYSTEM_WEB_MENU)).thenReturn(currentConfig);
        when(sysParamsDao.updateValueByCode(Constant.SYSTEM_WEB_MENU, newConfig)).thenReturn(1);

        service.updateSystemWebMenu(newConfig);

        verify(pluginMappingService).deleteByPluginId("SYSTEM_PLUGIN_CALL_DEVICE");
        verify(sysParamsDao).updateValueByCode(Constant.SYSTEM_WEB_MENU, newConfig);
        verify(sysParamsRedis).set(Constant.SYSTEM_WEB_MENU, newConfig);
    }
}
