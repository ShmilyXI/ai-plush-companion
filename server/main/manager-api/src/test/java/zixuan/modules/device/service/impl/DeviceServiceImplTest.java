package zixuan.modules.device.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import zixuan.common.redis.RedisUtils;
import zixuan.modules.agent.dao.AgentDao;
import zixuan.modules.companion.service.CompanionSubscriptionService;
import zixuan.modules.device.dao.DeviceDao;
import zixuan.modules.device.entity.DeviceEntity;
import zixuan.modules.device.service.DeviceAddressBookService;
import zixuan.modules.device.service.OtaService;
import zixuan.modules.sys.dao.SysUserDao;
import zixuan.modules.sys.service.SysParamsService;
import zixuan.modules.sys.service.SysUserUtilService;

class DeviceServiceImplTest {
    @Test
    void missingAgentFilterListsEveryDeviceOwnedByTheUser() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        when(deviceDao.selectList(any(QueryWrapper.class))).thenReturn(java.util.List.of());
        DeviceServiceImpl service = service(deviceDao);
        ReflectionTestUtils.setField(service, "baseDao", deviceDao);

        service.getUserDevices(7L, null);

        @SuppressWarnings({ "rawtypes", "unchecked" })
        ArgumentCaptor<QueryWrapper<DeviceEntity>> predicate = (ArgumentCaptor) ArgumentCaptor
                .forClass(QueryWrapper.class);
        verify(deviceDao).selectList(predicate.capture());
        assertTrue(predicate.getValue().getSqlSegment().contains("user_id"));
        assertFalse(predicate.getValue().getSqlSegment().contains("agent_id"));
    }

    @Test
    void allNullUserIdsDoNotQueryAnEmptyInClause() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        DeviceServiceImpl service = service(deviceDao);

        var result = service.countByUserIds(Arrays.asList(null, null));

        assertTrue(result.isEmpty());
        verify(deviceDao, never()).countByUserIds(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void heartbeatUpdatesOnlyAnExistingBoundDevice() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        when(deviceDao.update(any(DeviceEntity.class), any(UpdateWrapper.class))).thenReturn(1);

        assertTrue(service(deviceDao).touchHeartbeat("9c:13:9e:8a:14:a4"));

        ArgumentCaptor<DeviceEntity> entity = ArgumentCaptor.forClass(DeviceEntity.class);
        @SuppressWarnings({ "rawtypes", "unchecked" })
        ArgumentCaptor<UpdateWrapper<DeviceEntity>> predicate = (ArgumentCaptor) ArgumentCaptor
                .forClass(UpdateWrapper.class);
        verify(deviceDao).update(entity.capture(), predicate.capture());
        assertNotNull(entity.getValue().getLastConnectedAt());
        assertTrue(predicate.getValue().getSqlSegment().contains("id"));
        assertTrue(predicate.getValue().getSqlSegment().contains("user_id IS NOT NULL"));
        verify(deviceDao, never()).insert(any(DeviceEntity.class));
    }

    @Test
    void heartbeatReturnsFalseWhenNoBoundRowMatches() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        when(deviceDao.update(any(DeviceEntity.class), any(UpdateWrapper.class))).thenReturn(0);

        assertFalse(service(deviceDao).touchHeartbeat("9c:13:9e:8a:14:a4"));
        verify(deviceDao, never()).insert(any(DeviceEntity.class));
    }

    @Test
    void generatedTokenAuthenticatesOnlyItsDeviceAndClient() throws Exception {
        SysParamsService params = mock(SysParamsService.class);
        when(params.getValue("server.secret", false)).thenReturn("test-secret");
        when(params.getValue("server.auth.enabled", true)).thenReturn("true");
        DeviceServiceImpl service = service(mock(DeviceDao.class), params);
        String token = service.generateWebSocketToken("client-1", "9c:13:9e:8a:14:a4");

        assertTrue(service.verifyDeviceToken(token, "client-1", "9c:13:9e:8a:14:a4"));
        assertFalse(service.verifyDeviceToken(token, "client-2", "9c:13:9e:8a:14:a4"));
        assertFalse(service.verifyDeviceToken(token, "client-1", "9c:13:9e:8a:14:a5"));
        assertFalse(service.verifyDeviceToken("forged", "client-1", "9c:13:9e:8a:14:a4"));
    }

    @Test
    void disabledDeviceAuthenticationDoesNotAuthenticateWakeWordReports() {
        SysParamsService params = mock(SysParamsService.class);
        when(params.getValue("server.auth.enabled", true)).thenReturn("false");

        assertFalse(service(mock(DeviceDao.class), params).verifyDeviceToken(null, null, null));
    }

    private DeviceServiceImpl service(DeviceDao deviceDao) {
        return service(deviceDao, mock(SysParamsService.class));
    }

    private DeviceServiceImpl service(DeviceDao deviceDao, SysParamsService params) {
        return new DeviceServiceImpl(deviceDao, mock(SysUserUtilService.class),
                params, mock(RedisUtils.class), mock(OtaService.class),
                mock(DeviceAddressBookService.class), mock(AgentDao.class),
                mock(CompanionSubscriptionService.class), mock(SysUserDao.class));
    }
}
