package xiaozhi.modules.device.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import xiaozhi.common.constant.Constant;
import xiaozhi.common.redis.RedisUtils;
import xiaozhi.common.user.UserDetail;
import xiaozhi.common.utils.SpringContextUtils;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.companion.service.CompanionSubscriptionService;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.service.DeviceAddressBookService;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.sys.dao.SysUserDao;
import xiaozhi.modules.sys.service.SysParamsService;

class DeviceToolsInventoryTest {

    @Test
    void successfulEmptyToolListIsOnlineInventory() {
        Object result = toolsResult("{\"success\":true,\"data\":{\"tools\":[]}}");

        Map<?, ?> inventory = assertInstanceOf(Map.class, result);
        assertEquals(List.of(), inventory.get("tools"));
    }

    @Test
    void missingToolsFieldIsInvalidInventory() {
        assertEquals(null, toolsResult("{\"success\":true,\"data\":{}}"));
    }

    @Test
    void laterPageFailureInvalidatesTheWholeInventory() {
        assertEquals(null, toolsResult(
                "{\"success\":true,\"data\":{\"tools\":[{\"name\":\"first\"}],\"nextCursor\":\"next\"}}",
                "{\"success\":false}"));
    }

    @Test
    void websocketControlFallbackCallsDeviceWhenMqttGatewayIsDisabled() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        DeviceEntity device = new DeviceEntity();
        device.setId("device-a");
        device.setUserId(7L);
        device.setMacAddress("AA:BB:CC:DD:EE:FF");
        when(deviceDao.selectById("device-a")).thenReturn(device);
        SysParamsService params = mock(SysParamsService.class);
        when(params.getValue("server.mqtt_manager_api", true)).thenReturn("null");
        when(params.getValue(Constant.SERVER_HTTP, true)).thenReturn("http://127.0.0.1:8003");
        when(params.getValue(Constant.SERVER_SECRET, false)).thenReturn("secret");
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                org.mockito.ArgumentMatchers.eq("http://127.0.0.1:8003/internal/device-control"),
                org.mockito.ArgumentMatchers.eq(HttpMethod.POST),
                org.mockito.ArgumentMatchers.any(HttpEntity.class),
                org.mockito.ArgumentMatchers.eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"success\":true,\"data\":true}"));
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBean(RestTemplate.class)).thenReturn(restTemplate);
        DeviceServiceImpl service = new DeviceServiceImpl(
                deviceDao, null, params, mock(RedisUtils.class), null, mock(DeviceAddressBookService.class),
                mock(AgentDao.class), mock(CompanionSubscriptionService.class), mock(SysUserDao.class));
        ReflectionTestUtils.setField(service, "baseDao", deviceDao);
        UserDetail user = new UserDetail();
        user.setId(7L);

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUser).thenReturn(user);
            ApplicationContext previous = SpringContextUtils.applicationContext;
            SpringContextUtils.applicationContext = applicationContext;
            try {
                assertEquals(true, service.callDeviceTool(
                        "device-a", "self.audio_speaker.set_volume", Map.of("volume", 35)));
            } finally {
                SpringContextUtils.applicationContext = previous;
            }
        }
    }

    private Object toolsResult(String... responses) {
        DeviceDao deviceDao = mock(DeviceDao.class);
        DeviceEntity device = new DeviceEntity();
        device.setId("device-a");
        device.setUserId(7L);
        device.setMacAddress("AA:BB");
        device.setBoard("board");
        when(deviceDao.selectById("device-a")).thenReturn(device);
        SysParamsService params = mock(SysParamsService.class);
        when(params.getValue("server.mqtt_manager_api", true)).thenReturn("gateway");
        when(params.getValue(anyString(), org.mockito.ArgumentMatchers.eq(false))).thenReturn("secret");
        DeviceServiceImpl service = new DeviceServiceImpl(
                deviceDao, null, params, mock(RedisUtils.class), null, mock(DeviceAddressBookService.class),
                mock(AgentDao.class), mock(CompanionSubscriptionService.class), mock(SysUserDao.class));
        ReflectionTestUtils.setField(service, "baseDao", deviceDao);
        UserDetail user = new UserDetail();
        user.setId(7L);

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class);
                MockedStatic<MqttGatewayAuthorization> gateway = mockStatic(MqttGatewayAuthorization.class)) {
            security.when(SecurityUser::getUser).thenReturn(user);
            var responseStub = gateway.when(
                    () -> MqttGatewayAuthorization.postJson(anyString(), anyString(), anyString(), any(Instant.class)))
                    .thenReturn(responses[0]);
            for (int index = 1; index < responses.length; index++) {
                responseStub = responseStub.thenReturn(responses[index]);
            }

            return service.getDeviceTools("device-a");
        }
    }
}
