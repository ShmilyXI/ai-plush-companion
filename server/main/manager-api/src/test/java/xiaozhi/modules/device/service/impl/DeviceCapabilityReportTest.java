package xiaozhi.modules.device.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import xiaozhi.common.constant.Constant;
import xiaozhi.common.redis.RedisKeys;
import xiaozhi.common.redis.RedisUtils;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.companion.service.CompanionSubscriptionService;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.dto.DeviceReportReqDTO;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.service.DeviceAddressBookService;
import xiaozhi.modules.device.service.OtaService;
import xiaozhi.modules.sys.dao.SysUserDao;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.sys.service.SysUserUtilService;

class DeviceCapabilityReportTest {

    @Test
    void boardReportAcceptsExplicitCapabilityFields() throws Exception {
        DeviceReportReqDTO report = new ObjectMapper().readValue(
                "{\"board\":{\"type\":\"bread-compact-wifi-s3cam\",\"has_display\":false,\"has_camera\":false}}",
                DeviceReportReqDTO.class);

        Object board = report.getBoard();
        assertEquals(Boolean.FALSE, board.getClass().getMethod("getHasDisplay").invoke(board));
        assertEquals(Boolean.FALSE, board.getClass().getMethod("getHasCamera").invoke(board));
    }

    @ParameterizedTest
    @CsvSource({
            "true, true, 1, 1",
            "false, false, 0, 0"
    })
    void boundDevicePrefersExplicitReportedCapabilities(
            boolean hasDisplay, boolean hasCamera, int expectedDisplay, int expectedCamera) {
        DeviceDao deviceDao = mock(DeviceDao.class);
        DeviceEntity device = device();
        DeviceServiceImpl service = proxiedService(device, deviceDao);

        service.checkDeviceActive("00:11:22:33:44:55", "client-id",
                deviceReport(hasDisplay, hasCamera));

        ArgumentCaptor<DeviceEntity> update = ArgumentCaptor.forClass(DeviceEntity.class);
        verify(deviceDao).updateById(update.capture());
        assertEquals(expectedDisplay, update.getValue().getHasDisplay());
        assertEquals(expectedCamera, update.getValue().getHasCamera());
    }

    @Test
    void activationCachePreservesExplicitHeadlessCapabilities() {
        RedisUtils redis = mock(RedisUtils.class);
        SysParamsService sysParamsService = mock(SysParamsService.class);
        when(sysParamsService.getValue(Constant.SERVER_FRONTED_URL, true)).thenReturn("http://console/");
        DeviceServiceImpl service = new DeviceServiceImpl(mock(DeviceDao.class), mock(SysUserUtilService.class),
                sysParamsService, redis, mock(OtaService.class), mock(DeviceAddressBookService.class),
                mock(AgentDao.class), mock(CompanionSubscriptionService.class), mock(SysUserDao.class));

        service.buildActivation("00:11:22:33:44:55", deviceReport(false, false));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> cache = ArgumentCaptor.forClass(Map.class);
        verify(redis).set(eq(RedisKeys.getOtaDeviceActivationInfo("00_11_22_33_44_55")), cache.capture());
        assertEquals(Boolean.FALSE, cache.getValue().get("has_display"));
        assertEquals(Boolean.FALSE, cache.getValue().get("has_camera"));
    }

    private DeviceServiceImpl proxiedService(DeviceEntity device, DeviceDao deviceDao) {
        SysParamsService sysParamsService = mock(SysParamsService.class);
        when(sysParamsService.getValue(Constant.SERVER_WEBSOCKET, true))
                .thenReturn("ws://127.0.0.1:8000/xiaozhi/v1/");
        when(sysParamsService.getValue(Constant.SERVER_AUTH_ENABLED, true)).thenReturn("false");
        when(sysParamsService.getValue(Constant.SERVER_MQTT_GATEWAY, true)).thenReturn(null);

        DeviceServiceImpl target = new DeviceServiceImpl(deviceDao, mock(SysUserUtilService.class),
                sysParamsService, mock(RedisUtils.class), mock(OtaService.class),
                mock(DeviceAddressBookService.class), mock(AgentDao.class),
                mock(CompanionSubscriptionService.class), mock(SysUserDao.class)) {
            @Override
            public DeviceEntity getDeviceByMacAddress(String macAddress) {
                return device;
            }
        };

        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.setExposeProxy(true);
        return (DeviceServiceImpl) proxyFactory.getProxy();
    }

    private DeviceEntity device() {
        DeviceEntity device = new DeviceEntity();
        device.setId("device-id");
        device.setMacAddress("00:11:22:33:44:55");
        device.setBoard("bread-compact-wifi-s3cam");
        device.setAutoUpdate(0);
        return device;
    }

    private DeviceReportReqDTO deviceReport(boolean hasDisplay, boolean hasCamera) {
        DeviceReportReqDTO.Application application = new DeviceReportReqDTO.Application();
        application.setVersion("1.0.0");
        DeviceReportReqDTO.BoardInfo board = new DeviceReportReqDTO.BoardInfo();
        board.setType("bread-compact-wifi-s3cam");
        board.setHasDisplay(hasDisplay);
        board.setHasCamera(hasCamera);
        DeviceReportReqDTO report = new DeviceReportReqDTO();
        report.setApplication(application);
        report.setBoard(board);
        return report;
    }
}
