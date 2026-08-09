package xiaozhi.modules.device.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.apache.ibatis.annotations.Select;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.redis.RedisKeys;
import xiaozhi.common.redis.RedisUtils;
import xiaozhi.common.user.UserDetail;
import xiaozhi.common.utils.MessageUtils;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.dto.DeviceManualAddDTO;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.service.DeviceAddressBookService;
import xiaozhi.modules.companion.service.CompanionSubscriptionService;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.sys.dao.SysUserDao;
import xiaozhi.modules.sys.entity.SysUserEntity;

class DeviceAgentLockingTest {
    @Test
    void normalizedMacQueriesUseGeneratedUniqueColumnWithoutLimit() throws Exception {
        for (String methodName : new String[] { "selectByNormalizedMac", "selectByNormalizedMacForUpdate" }) {
            Select select = DeviceDao.class.getMethod(methodName, String.class).getAnnotation(Select.class);
            String sql = String.join(" ", select.value());
            assertTrue(sql.contains("normalized_mac_address = #{normalizedMac}"));
            assertFalse(sql.toUpperCase().contains("LIMIT"));
        }
    }

    @Test
    void manualBindingLocksAgentBeforeCheckingAndWritingDevice() throws Exception {
        DeviceDao deviceDao = mock(DeviceDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        CompanionSubscriptionService subscriptionService = mock(CompanionSubscriptionService.class);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(agent(7L));
        when(deviceDao.selectOne(any())).thenReturn(null);
        DeviceServiceImpl service = service(deviceDao, agentDao, mock(RedisUtils.class), subscriptionService);
        DeviceManualAddDTO dto = new DeviceManualAddDTO();
        dto.setAgentId("agent-id");
        dto.setMacAddress("AA:BB");

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUser).thenReturn(user(7L, 0));
            service.manualAddDevice(7L, dto);
        }

        InOrder order = inOrder(agentDao, deviceDao, subscriptionService);
        order.verify(agentDao).selectByIdForUpdate("agent-id");
        order.verify(deviceDao).selectOne(any());
        order.verify(deviceDao).selectCount(any());
        order.verify(subscriptionService).requireDeviceSlot(7L, 0);
        order.verify(deviceDao).insert(any(DeviceEntity.class));
        assertTransactional("manualAddDevice", Long.class, DeviceManualAddDTO.class);
    }

    @Test
    void manualBindingAllowsSuperAdminToUseOwnedAgent() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(agent(7L));
        when(deviceDao.selectOne(any())).thenReturn(null);
        DeviceServiceImpl service = service(deviceDao, agentDao, mock(RedisUtils.class));
        DeviceManualAddDTO dto = new DeviceManualAddDTO();
        dto.setAgentId("agent-id");
        dto.setMacAddress("AA:BB");

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUser).thenReturn(user(7L, 1));
            service.manualAddDevice(7L, dto);
        }

        verify(deviceDao).insert(any(DeviceEntity.class));
    }

    @Test
    void activationLocksAgentBeforeCheckingAndWritingDevice() throws Exception {
        DeviceDao deviceDao = mock(DeviceDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        CompanionSubscriptionService subscriptionService = mock(CompanionSubscriptionService.class);
        RedisUtils redis = mock(RedisUtils.class);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(agent(7L));
        String deviceKey = RedisKeys.getOtaActivationCode("code");
        String activationKey = RedisKeys.getOtaDeviceActivationInfo("device-id");
        when(redis.get(deviceKey)).thenReturn("device-id");
        when(redis.get(activationKey)).thenReturn(Map.of(
                "activation_code", "code",
                "mac_address", "AA:BB",
                "board", "board",
                "app_version", "1.0"));
        when(deviceDao.selectById("device-id")).thenReturn(null);
        DeviceServiceImpl service = service(deviceDao, agentDao, redis, subscriptionService);
        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUser).thenReturn(user(7L, 0));
            service.deviceActivation("agent-id", "code");
        }

        InOrder order = inOrder(agentDao, deviceDao, subscriptionService);
        order.verify(agentDao).selectByIdForUpdate("agent-id");
        order.verify(deviceDao).selectById("device-id");
        order.verify(deviceDao).selectCount(any());
        order.verify(subscriptionService).requireDeviceSlot(7L, 0);
        order.verify(deviceDao).insert(any(DeviceEntity.class));
        assertTransactional("deviceActivation", String.class, String.class);
    }

    @Test
    void activationLocksUserBeforeAgentAndDevice() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        SysUserDao userDao = mock(SysUserDao.class);
        when(userDao.selectByIdForUpdate(7L)).thenReturn(new SysUserEntity());
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(agent(7L));
        RedisUtils redis = mock(RedisUtils.class);
        when(redis.get(RedisKeys.getOtaActivationCode("code"))).thenReturn("device-id");
        when(redis.get(RedisKeys.getOtaDeviceActivationInfo("device-id"))).thenReturn(Map.of(
                "activation_code", "code", "mac_address", "AA:BB", "board", "board", "app_version", "1.0"));
        when(deviceDao.selectById("device-id")).thenReturn(null);
        DeviceServiceImpl service = service(deviceDao, agentDao, redis, mock(CompanionSubscriptionService.class),
                userDao);

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUser).thenReturn(user(7L, 0));
            service.deviceActivation("agent-id", "code");
        }

        InOrder order = inOrder(userDao, agentDao, deviceDao);
        order.verify(userDao).selectByIdForUpdate(7L);
        order.verify(agentDao).selectByIdForUpdate("agent-id");
        order.verify(deviceDao).selectById("device-id");
        order.verify(deviceDao).insert(any(DeviceEntity.class));
    }

    @Test
    void bindingActivationUsesExistingUserLockWithoutRelocking() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        SysUserDao userDao = mock(SysUserDao.class);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(agent(7L));
        RedisUtils redis = mock(RedisUtils.class);
        when(redis.get(RedisKeys.getOtaActivationCode("code"))).thenReturn("device-id");
        when(redis.get(RedisKeys.getOtaDeviceActivationInfo("device-id"))).thenReturn(Map.of(
                "activation_code", "code", "mac_address", "AA:BB", "board", "board", "app_version", "1.0"));
        when(deviceDao.selectById("device-id")).thenReturn(null);
        DeviceServiceImpl service = service(deviceDao, agentDao, redis, mock(CompanionSubscriptionService.class),
                userDao);

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUser).thenReturn(user(7L, 0));
            service.deviceActivationWithLockedUser(7L, "agent-id", "code");
        }

        verify(userDao, never()).selectByIdForUpdate(any());
        verify(agentDao).selectByIdForUpdate("agent-id");
        verify(deviceDao).insert(any(DeviceEntity.class));
    }

    @Test
    void bindingActivationRequiresOuterTransaction() throws Exception {
        Transactional transactional = DeviceServiceImpl.class
                .getMethod("deviceActivationWithLockedUser", Long.class, String.class, String.class)
                .getAnnotation(Transactional.class);

        assertNotNull(transactional);
        assertEquals(Propagation.MANDATORY, transactional.propagation());
    }

    @Test
    void activationCapturesCapabilitiesFromReportedBoard() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        RedisUtils redis = mock(RedisUtils.class);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(agent(7L));
        when(redis.get(RedisKeys.getOtaActivationCode("code"))).thenReturn("device-id");
        when(redis.get(RedisKeys.getOtaDeviceActivationInfo("device-id"))).thenReturn(Map.of(
                "activation_code", "code",
                "mac_address", "AA:BB",
                "board", "bread-compact-wifi-s3cam",
                "app_version", "1.0"));
        when(deviceDao.selectById("device-id")).thenReturn(null);
        DeviceServiceImpl service = service(deviceDao, agentDao, redis);

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUser).thenReturn(user(7L, 0));
            service.deviceActivation("agent-id", "code");
        }

        ArgumentCaptor<DeviceEntity> inserted = ArgumentCaptor.forClass(DeviceEntity.class);
        verify(deviceDao).insert(inserted.capture());
        assertEquals(1, inserted.getValue().getHasDisplay());
        assertEquals(1, inserted.getValue().getHasCamera());
    }

    @Test
    void activationPrefersExplicitHeadlessCapabilitiesOverBoardType() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        RedisUtils redis = mock(RedisUtils.class);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(agent(7L));
        when(redis.get(RedisKeys.getOtaActivationCode("code"))).thenReturn("device-id");
        when(redis.get(RedisKeys.getOtaDeviceActivationInfo("device-id"))).thenReturn(Map.of(
                "activation_code", "code",
                "mac_address", "AA:BB",
                "board", "bread-compact-wifi-s3cam",
                "app_version", "1.0",
                "has_display", false,
                "has_camera", false));
        when(deviceDao.selectById("device-id")).thenReturn(null);
        DeviceServiceImpl service = service(deviceDao, agentDao, redis);

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUser).thenReturn(user(7L, 0));
            service.deviceActivation("agent-id", "code");
        }

        ArgumentCaptor<DeviceEntity> inserted = ArgumentCaptor.forClass(DeviceEntity.class);
        verify(deviceDao).insert(inserted.capture());
        assertEquals(0, inserted.getValue().getHasDisplay());
        assertEquals(0, inserted.getValue().getHasCamera());
    }

    @Test
    void duplicateDeviceInsertMapsToAlreadyActivated() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        RedisUtils redis = mock(RedisUtils.class);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(agent(7L));
        when(redis.get(RedisKeys.getOtaActivationCode("code"))).thenReturn("device-id");
        when(redis.get(RedisKeys.getOtaDeviceActivationInfo("device-id"))).thenReturn(Map.of(
                "activation_code", "code", "mac_address", "AA:BB", "board", "board", "app_version", "1.0"));
        when(deviceDao.selectById("device-id")).thenReturn(null);
        when(deviceDao.insert(any(DeviceEntity.class))).thenThrow(new DuplicateKeyException("duplicate"));
        DeviceServiceImpl service = service(deviceDao, agentDao, redis);

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class);
                MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            security.when(SecurityUser::getUser).thenReturn(user(7L, 0));
            messages.when(() -> MessageUtils.getMessage(ErrorCode.DEVICE_ALREADY_ACTIVATED))
                    .thenReturn("already activated");

            RenException error = assertThrows(RenException.class,
                    () -> service.deviceActivation("agent-id", "code"));

            assertEquals(ErrorCode.DEVICE_ALREADY_ACTIVATED, error.getCode());
        }
    }

    @Test
    void rolledBackActivationPreservesActivationCache() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        RedisUtils redis = mock(RedisUtils.class);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(agent(7L));
        when(redis.get(RedisKeys.getOtaActivationCode("code"))).thenReturn("device-id");
        when(redis.get(RedisKeys.getOtaDeviceActivationInfo("device-id"))).thenReturn(Map.of(
                "activation_code", "code", "mac_address", "AA:BB", "board", "board", "app_version", "1.0"));
        when(deviceDao.selectById("device-id")).thenReturn(null);
        DeviceServiceImpl service = service(deviceDao, agentDao, redis);
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:activation_cache_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUser).thenReturn(user(7L, 0));
            transaction.executeWithoutResult(status -> {
                service.deviceActivation("agent-id", "code");
                status.setRollbackOnly();
            });
        }

        verify(redis, never()).delete(org.mockito.ArgumentMatchers.<java.util.Collection<String>>any());
    }

    @Test
    void profileSwitchLocksTargetAgentBeforeUpdatingOwnedDevice() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        AgentEntity companionAgent = agent(7L);
        companionAgent.setCompanionEnabled(1);
        when(agentDao.selectByIdForUpdate("new-agent")).thenReturn(companionAgent);
        DeviceEntity device = new DeviceEntity();
        device.setId("device-id");
        device.setUserId(7L);
        device.setAgentId("old-agent");
        when(deviceDao.selectById("device-id")).thenReturn(device);
        when(deviceDao.update(any(), any())).thenReturn(1);
        DeviceServiceImpl service = service(deviceDao, agentDao, mock(RedisUtils.class));

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUser).thenReturn(user(7L, 0));
            service.switchCompanionProfile(7L, "device-id", "new-agent");
        }

        InOrder order = inOrder(agentDao, deviceDao);
        order.verify(agentDao).selectByIdForUpdate("new-agent");
        order.verify(deviceDao).selectById("device-id");
        order.verify(deviceDao).update(any(), any());
    }

    @Test
    void profileSwitchRejectsRegularAgent() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        AgentEntity regularAgent = agent(7L);
        regularAgent.setCompanionEnabled(0);
        when(agentDao.selectByIdForUpdate("regular-agent")).thenReturn(regularAgent);
        DeviceServiceImpl service = service(deviceDao, agentDao, mock(RedisUtils.class));

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class);
                MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            security.when(SecurityUser::getUser).thenReturn(user(7L, 0));
            messages.when(() -> MessageUtils.getMessage(ErrorCode.AGENT_NOT_FOUND)).thenReturn("not found");

            RenException error = assertThrows(RenException.class,
                    () -> service.switchCompanionProfile(7L, "device-id", "regular-agent"));

            assertEquals(ErrorCode.AGENT_NOT_FOUND, error.getCode());
        }
        verify(deviceDao, never()).selectById(any());
        verify(deviceDao, never()).update(any(), any());
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1 })
    void manualBindingRejectsAnotherUsersAgentAfterLock(int superAdmin) {
        DeviceDao deviceDao = mock(DeviceDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(agent(8L));
        DeviceServiceImpl service = service(deviceDao, agentDao, mock(RedisUtils.class));
        DeviceManualAddDTO dto = new DeviceManualAddDTO();
        dto.setAgentId("agent-id");
        dto.setMacAddress("AA:BB");

        RenException error;
        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class);
                MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            security.when(SecurityUser::getUser).thenReturn(user(7L, superAdmin));
            messages.when(() -> MessageUtils.getMessage(ErrorCode.NO_PERMISSION)).thenReturn("no permission");
            error = assertThrows(RenException.class, () -> service.manualAddDevice(7L, dto));
        }

        assertEquals(ErrorCode.NO_PERMISSION, error.getCode());
        verify(agentDao).selectByIdForUpdate("agent-id");
        verify(deviceDao, never()).selectOne(any());
        verify(deviceDao, never()).insert(any(DeviceEntity.class));
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1 })
    void activationRejectsAnotherUsersAgentAfterLock(int superAdmin) {
        DeviceDao deviceDao = mock(DeviceDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        RedisUtils redis = mock(RedisUtils.class);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(agent(8L));
        String deviceKey = RedisKeys.getOtaActivationCode("code");
        String activationKey = RedisKeys.getOtaDeviceActivationInfo("device-id");
        when(redis.get(deviceKey)).thenReturn("device-id");
        when(redis.get(activationKey)).thenReturn(Map.of(
                "activation_code", "code",
                "mac_address", "AA:BB",
                "board", "board",
                "app_version", "1.0"));
        DeviceServiceImpl service = service(deviceDao, agentDao, redis);

        RenException error;
        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class);
                MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            security.when(SecurityUser::getUser).thenReturn(user(7L, superAdmin));
            messages.when(() -> MessageUtils.getMessage(ErrorCode.NO_PERMISSION)).thenReturn("no permission");
            error = assertThrows(RenException.class, () -> service.deviceActivation("agent-id", "code"));
        }

        assertEquals(ErrorCode.NO_PERMISSION, error.getCode());
        verify(agentDao).selectByIdForUpdate("agent-id");
        verify(deviceDao, never()).selectById(any());
        verify(deviceDao, never()).insert(any(DeviceEntity.class));
    }

    @Test
    void attachExistingDeviceRejectsAbsentMacWithoutCreatingRecord() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(agent(7L));
        stubDeviceByMac(deviceDao, null);
        DeviceServiceImpl service = service(deviceDao, agentDao, mock(RedisUtils.class));

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class);
                MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            security.when(SecurityUser::getUser).thenReturn(user(7L, 0));
            messages.when(() -> MessageUtils.getMessage(ErrorCode.DEVICE_NOT_EXIST)).thenReturn("not found");
            assertThrows(RenException.class, () -> service.attachExistingDevice(7L, "agent-id", "AA:BB"));
        }

        verify(deviceDao, never()).insert(any(DeviceEntity.class));
        verify(deviceDao, never()).updateById(any(DeviceEntity.class));
    }

    @Test
    void attachExistingDeviceRejectsForeignOwner() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(agent(7L));
        stubDeviceByMac(deviceDao, device("stable-device-id", 8L, "AA:BB", null));
        DeviceServiceImpl service = service(deviceDao, agentDao, mock(RedisUtils.class));

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class);
                MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            security.when(SecurityUser::getUser).thenReturn(user(7L, 0));
            messages.when(() -> MessageUtils.getMessage(ErrorCode.NO_PERMISSION)).thenReturn("no permission");
            assertThrows(RenException.class, () -> service.attachExistingDevice(7L, "agent-id", "AA:BB"));
        }

        verify(deviceDao, never()).updateById(any(DeviceEntity.class));
    }

    @Test
    void attachExistingDeviceFillsSameOwnersMissingAgentAndKeepsInternalId() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        SysUserDao userDao = userDao();
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(agent(7L));
        stubDeviceByMac(deviceDao, device("stable-device-id", 7L, "AA:BB", null));
        when(deviceDao.updateById(any(DeviceEntity.class))).thenReturn(1);
        DeviceServiceImpl service = service(deviceDao, agentDao, mock(RedisUtils.class),
                mock(CompanionSubscriptionService.class), userDao);

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUser).thenReturn(user(7L, 0));
            service.attachExistingDevice(7L, "agent-id", "AA:BB");
        }

        ArgumentCaptor<DeviceEntity> updated = ArgumentCaptor.forClass(DeviceEntity.class);
        InOrder order = inOrder(userDao, agentDao, deviceDao);
        order.verify(userDao).selectByIdForUpdate(7L);
        order.verify(agentDao).selectByIdForUpdate("agent-id");
        order.verify(deviceDao).selectByNormalizedMacForUpdate("aabb");
        order.verify(deviceDao).updateById(updated.capture());
        assertEquals("stable-device-id", updated.getValue().getId());
        assertEquals("agent-id", updated.getValue().getAgentId());
    }

    @Test
    void attachExistingUnownedDeviceFillsOwnerAndAgentWithinPlanLimit() throws Exception {
        DeviceDao deviceDao = mock(DeviceDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        CompanionSubscriptionService subscriptionService = mock(CompanionSubscriptionService.class);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(agent(7L));
        stubDeviceByMac(deviceDao, device("stable-device-id", null, "AA:BB", null));
        when(deviceDao.updateById(any(DeviceEntity.class))).thenReturn(1);
        DeviceServiceImpl service = service(deviceDao, agentDao, mock(RedisUtils.class), subscriptionService);

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUser).thenReturn(user(7L, 0));
            service.attachExistingDevice(7L, "agent-id", "AA:BB");
        }

        ArgumentCaptor<DeviceEntity> updated = ArgumentCaptor.forClass(DeviceEntity.class);
        verify(subscriptionService).requireDeviceSlot(7L, 0);
        verify(deviceDao).updateById(updated.capture());
        assertEquals(7L, updated.getValue().getUserId());
        assertEquals("agent-id", updated.getValue().getAgentId());
        Transactional transactional = DeviceServiceImpl.class
                .getMethod("attachExistingDevice", Long.class, String.class, String.class)
                .getAnnotation(Transactional.class);
        assertEquals(Propagation.MANDATORY, transactional.propagation());
    }

    @Test
    void attachExistingUnownedDevicePreservesNonemptyCurrentAgent() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(agent(7L));
        when(agentDao.selectByIdForUpdate("registered-agent")).thenReturn(agent(7L));
        stubDeviceByMac(deviceDao, device("stable-device-id", null, "AA:BB", "registered-agent"));
        when(deviceDao.updateById(any(DeviceEntity.class))).thenReturn(1);
        DeviceServiceImpl service = service(deviceDao, agentDao, mock(RedisUtils.class));

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUser).thenReturn(user(7L, 0));
            service.attachExistingDevice(7L, "agent-id", "AA:BB");
        }

        ArgumentCaptor<DeviceEntity> updated = ArgumentCaptor.forClass(DeviceEntity.class);
        InOrder order = inOrder(agentDao, deviceDao);
        order.verify(agentDao).selectByIdForUpdate("agent-id");
        order.verify(agentDao).selectByIdForUpdate("registered-agent");
        order.verify(deviceDao).selectByNormalizedMacForUpdate("aabb");
        order.verify(deviceDao).updateById(updated.capture());
        assertEquals(7L, updated.getValue().getUserId());
        assertEquals("registered-agent", updated.getValue().getAgentId());
    }

    @Test
    void attachExistingUnownedDeviceRejectsForeignCurrentAgent() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(agent(7L));
        when(agentDao.selectByIdForUpdate("registered-agent")).thenReturn(agent(8L));
        stubDeviceByMac(deviceDao, device("stable-device-id", null, "AA:BB", "registered-agent"));
        DeviceServiceImpl service = service(deviceDao, agentDao, mock(RedisUtils.class));

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class);
                MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            security.when(SecurityUser::getUser).thenReturn(user(7L, 0));
            messages.when(() -> MessageUtils.getMessage(ErrorCode.NO_PERMISSION)).thenReturn("no permission");
            assertThrows(RenException.class, () -> service.attachExistingDevice(7L, "agent-id", "AA:BB"));
        }

        verify(deviceDao, never()).updateById(any(DeviceEntity.class));
        verify(deviceDao, never()).selectByNormalizedMacForUpdate(any());
    }

    @Test
    void attachExistingUnownedDeviceRejectsMissingCurrentAgent() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(agent(7L));
        when(agentDao.selectByIdForUpdate("registered-agent")).thenReturn(null);
        stubDeviceByMac(deviceDao, device("stable-device-id", null, "AA:BB", "registered-agent"));
        DeviceServiceImpl service = service(deviceDao, agentDao, mock(RedisUtils.class));

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class);
                MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            security.when(SecurityUser::getUser).thenReturn(user(7L, 0));
            messages.when(() -> MessageUtils.getMessage(ErrorCode.AGENT_NOT_FOUND)).thenReturn("not found");
            assertThrows(RenException.class, () -> service.attachExistingDevice(7L, "agent-id", "AA:BB"));
        }

        verify(deviceDao, never()).updateById(any(DeviceEntity.class));
        verify(deviceDao, never()).selectByNormalizedMacForUpdate(any());
    }

    @Test
    void attachExistingDeviceLeavesExistingBindingUntouched() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(agent(7L));
        when(agentDao.selectByIdForUpdate("chosen-agent")).thenReturn(agent(7L));
        stubDeviceByMac(deviceDao, device("stable-device-id", 7L, "AA:BB", "chosen-agent"));
        DeviceServiceImpl service = service(deviceDao, agentDao, mock(RedisUtils.class));

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUser).thenReturn(user(7L, 0));
            service.attachExistingDevice(7L, "agent-id", "AA:BB");
        }

        verify(deviceDao, never()).updateById(any(DeviceEntity.class));
    }

    @Test
    void attachExistingDeviceRejectsMissingSameOwnersCurrentAgent() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(agent(7L));
        when(agentDao.selectByIdForUpdate("chosen-agent")).thenReturn(null);
        stubDeviceByMac(deviceDao, device("stable-device-id", 7L, "AA:BB", "chosen-agent"));
        DeviceServiceImpl service = service(deviceDao, agentDao, mock(RedisUtils.class));

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class);
                MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            security.when(SecurityUser::getUser).thenReturn(user(7L, 0));
            messages.when(() -> MessageUtils.getMessage(ErrorCode.AGENT_NOT_FOUND)).thenReturn("not found");
            assertThrows(RenException.class, () -> service.attachExistingDevice(7L, "agent-id", "AA:BB"));
        }

        verify(deviceDao, never()).updateById(any(DeviceEntity.class));
    }

    @Test
    void attachExistingDeviceRejectsForeignSameOwnersCurrentAgent() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(agent(7L));
        when(agentDao.selectByIdForUpdate("chosen-agent")).thenReturn(agent(8L));
        stubDeviceByMac(deviceDao, device("stable-device-id", 7L, "AA:BB", "chosen-agent"));
        DeviceServiceImpl service = service(deviceDao, agentDao, mock(RedisUtils.class));

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class);
                MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            security.when(SecurityUser::getUser).thenReturn(user(7L, 0));
            messages.when(() -> MessageUtils.getMessage(ErrorCode.NO_PERMISSION)).thenReturn("no permission");
            assertThrows(RenException.class, () -> service.attachExistingDevice(7L, "agent-id", "AA:BB"));
        }

        verify(deviceDao, never()).updateById(any(DeviceEntity.class));
    }

    @Test
    void attachExistingDeviceRejectsNonCompanionSameOwnersCurrentAgent() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(agent(7L));
        AgentEntity regular = agent(7L);
        regular.setCompanionEnabled(0);
        when(agentDao.selectByIdForUpdate("chosen-agent")).thenReturn(regular);
        stubDeviceByMac(deviceDao, device("stable-device-id", 7L, "AA:BB", "chosen-agent"));
        DeviceServiceImpl service = service(deviceDao, agentDao, mock(RedisUtils.class));

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class);
                MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            security.when(SecurityUser::getUser).thenReturn(user(7L, 0));
            messages.when(() -> MessageUtils.getMessage(ErrorCode.AGENT_NOT_FOUND)).thenReturn("not found");
            assertThrows(RenException.class, () -> service.attachExistingDevice(7L, "agent-id", "AA:BB"));
        }

        verify(deviceDao, never()).updateById(any(DeviceEntity.class));
    }

    @Test
    void attachExistingDeviceNormalizesWhitespaceCaseAndSeparators() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(agent(7L));
        stubDeviceByMac(deviceDao, device("stable-device-id", 7L, "AA:BB", null));
        when(deviceDao.updateById(any(DeviceEntity.class))).thenReturn(1);
        DeviceServiceImpl service = service(deviceDao, agentDao, mock(RedisUtils.class));

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUser).thenReturn(user(7L, 0));
            service.attachExistingDevice(7L, "agent-id", "  AA-BB  ");
        }

        verify(deviceDao).selectByNormalizedMac("aabb");
        verify(deviceDao).selectByNormalizedMacForUpdate("aabb");
    }

    @Test
    void attachExistingDeviceMatchesMysqlTrimByPreservingTabs() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        when(agentDao.selectByIdForUpdate("agent-id")).thenReturn(agent(7L));
        DeviceEntity registered = device("stable-device-id", 7L, "\tAA:BB\t", null);
        when(deviceDao.selectByNormalizedMac("aabb")).thenReturn(registered);
        when(deviceDao.selectByNormalizedMacForUpdate("aabb")).thenReturn(registered);
        when(deviceDao.selectByNormalizedMac("\taabb\t")).thenReturn(registered);
        when(deviceDao.selectByNormalizedMacForUpdate("\taabb\t")).thenReturn(registered);
        when(deviceDao.updateById(any(DeviceEntity.class))).thenReturn(1);
        DeviceServiceImpl service = service(deviceDao, agentDao, mock(RedisUtils.class));

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUser).thenReturn(user(7L, 0));
            service.attachExistingDevice(7L, "agent-id", "\tAA:BB\t");
        }

        verify(deviceDao).selectByNormalizedMac("\taabb\t");
        verify(deviceDao).selectByNormalizedMacForUpdate("\taabb\t");
    }

    private DeviceServiceImpl service(DeviceDao deviceDao, AgentDao agentDao, RedisUtils redis) {
        return service(deviceDao, agentDao, redis, mock(CompanionSubscriptionService.class));
    }

    private DeviceServiceImpl service(DeviceDao deviceDao, AgentDao agentDao, RedisUtils redis,
            CompanionSubscriptionService subscriptionService) {
        return service(deviceDao, agentDao, redis, subscriptionService, userDao());
    }

    private DeviceServiceImpl service(DeviceDao deviceDao, AgentDao agentDao, RedisUtils redis,
            CompanionSubscriptionService subscriptionService, SysUserDao userDao) {
        DeviceServiceImpl service = new DeviceServiceImpl(
                deviceDao, null, null, redis, null, mock(DeviceAddressBookService.class), agentDao,
                subscriptionService, userDao);
        ReflectionTestUtils.setField(service, "baseDao", deviceDao);
        return service;
    }

    private SysUserDao userDao() {
        SysUserDao dao = mock(SysUserDao.class);
        when(dao.selectByIdForUpdate(7L)).thenReturn(new SysUserEntity());
        return dao;
    }

    private AgentEntity agent(Long userId) {
        AgentEntity agent = new AgentEntity();
        agent.setId("agent-id");
        agent.setUserId(userId);
        agent.setCompanionEnabled(1);
        return agent;
    }

    private UserDetail user(Long id, Integer superAdmin) {
        UserDetail user = new UserDetail();
        user.setId(id);
        user.setSuperAdmin(superAdmin);
        return user;
    }

    private DeviceEntity device(String id, Long userId, String macAddress, String agentId) {
        DeviceEntity device = new DeviceEntity();
        device.setId(id);
        device.setUserId(userId);
        device.setMacAddress(macAddress);
        device.setAgentId(agentId);
        return device;
    }

    private void stubDeviceByMac(DeviceDao deviceDao, DeviceEntity device) {
        when(deviceDao.selectByNormalizedMac("aa:bb")).thenReturn(device);
        when(deviceDao.selectByNormalizedMacForUpdate("aa:bb")).thenReturn(device);
        when(deviceDao.selectByNormalizedMac("aa-bb")).thenReturn(device);
        when(deviceDao.selectByNormalizedMacForUpdate("aa-bb")).thenReturn(device);
        when(deviceDao.selectByNormalizedMac("aabb")).thenReturn(device);
        when(deviceDao.selectByNormalizedMacForUpdate("aabb")).thenReturn(device);
    }

    private void assertTransactional(String name, Class<?>... parameterTypes) throws Exception {
        Method method = DeviceServiceImpl.class.getMethod(name, parameterTypes);
        assertNotNull(method.getAnnotation(Transactional.class));
    }
}
