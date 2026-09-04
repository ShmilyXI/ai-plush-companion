package zixuan.modules.companion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import zixuan.common.redis.RedisKeys;
import zixuan.common.redis.RedisUtils;
import zixuan.common.user.UserDetail;
import zixuan.modules.agent.dao.AgentDao;
import zixuan.modules.agent.dao.AgentTagDao;
import zixuan.modules.agent.entity.AgentEntity;
import zixuan.modules.agent.entity.AgentTemplateEntity;
import zixuan.modules.agent.service.AgentService;
import zixuan.modules.agent.service.AgentSnapshotService;
import zixuan.modules.agent.service.AgentTemplateService;
import zixuan.modules.agent.service.AgentChatHistoryService;
import zixuan.modules.agent.service.AgentContextProviderService;
import zixuan.modules.agent.service.AgentPluginMappingService;
import zixuan.modules.agent.service.AgentTagService;
import zixuan.modules.agent.service.impl.AgentServiceImpl;
import zixuan.modules.companion.dto.CompanionDeviceBindDTO;
import zixuan.modules.companion.debug.service.DeviceDebugLogService;
import zixuan.modules.companion.service.impl.CompanionDeviceServiceImpl;
import zixuan.modules.companion.service.impl.CompanionProfileServiceImpl;
import zixuan.modules.device.dao.DeviceDao;
import zixuan.modules.device.entity.DeviceEntity;
import zixuan.modules.device.service.DeviceAddressBookService;
import zixuan.modules.device.service.DeviceService;
import zixuan.modules.device.service.impl.DeviceServiceImpl;
import zixuan.modules.correctword.service.CorrectWordFileService;
import zixuan.modules.model.service.ModelConfigService;
import zixuan.modules.model.service.ModelProviderService;
import zixuan.modules.sys.dao.SysUserDao;
import zixuan.modules.timbre.service.TimbreService;

class CompanionDeviceBindingConcurrencyTest {

    @Test
    void concurrentRealBindingsCreateOneDefaultProfileAndJoinTheOuterTransaction() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:device_binding_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE sys_user (id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE ai_agent (id VARCHAR(64) PRIMARY KEY, user_id BIGINT, companion_enabled INT, created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE ai_device (id VARCHAR(64) PRIMARY KEY, user_id BIGINT, agent_id VARCHAR(64), mac_address VARCHAR(64))");
        jdbc.update("INSERT INTO sys_user(id) VALUES (7)");
        PlatformTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        CountDownLatch firstUserLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean firstLockOwner = new AtomicBoolean(true);

        SysUserDao userDao = h2UserDao(jdbc, firstUserLock, releaseFirst, firstLockOwner);
        AgentDao agentDao = h2AgentDao(jdbc);
        AgentService agentService = h2AgentService(agentDao);
        CompanionProfileService profileService = transactionalProxy(
                realProfileService(userDao, agentDao, agentService), transactionManager, CompanionProfileService.class);
        DeviceService deviceService = transactionalProxy(
                realDeviceService(userDao, agentDao, h2DeviceDao(jdbc), activationRedis()),
                transactionManager, DeviceService.class);
        CompanionDeviceService companionService = transactionalProxy(
                new CompanionDeviceServiceImpl(deviceService, profileService, mock(DeviceDebugLogService.class)),
                transactionManager, CompanionDeviceService.class);
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch secondFinished = new CountDownLatch(1);

        try {
            var first = executor.submit(() -> bind(companionService, "code-a"));
            assertTrue(firstUserLock.await(2, TimeUnit.SECONDS));
            var second = executor.submit(() -> {
                try {
                    bind(companionService, "code-b");
                } finally {
                    secondFinished.countDown();
                }
            });

            assertFalse(secondFinished.await(Duration.ofMillis(200).toMillis(), TimeUnit.MILLISECONDS));
            releaseFirst.countDown();
            first.get(3, TimeUnit.SECONDS);
            second.get(3, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_agent WHERE user_id = 7 AND companion_enabled = 1", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_device WHERE user_id = 7", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(DISTINCT agent_id) FROM ai_device", Integer.class));
        assertThrows(IllegalTransactionStateException.class,
                () -> deviceService.deviceActivationWithLockedUser(7L, "unused", "unused"));
    }

    private CompanionProfileServiceImpl realProfileService(SysUserDao userDao, AgentDao agentDao,
            AgentService agentService) {
        AgentTemplateEntity template = new AgentTemplateEntity();
        template.setId("template-zixuan");
        AgentTemplateService templateService = mock(AgentTemplateService.class);
        when(templateService.getById("template-zixuan")).thenReturn(template);
        return new CompanionProfileServiceImpl(
                agentDao,
                agentService,
                templateService,
                mock(AgentSnapshotService.class),
                mock(ModelConfigService.class),
                mock(TimbreService.class),
                mock(zixuan.modules.voiceclone.service.VoiceCloneService.class),
                mock(CompanionSubscriptionService.class),
                userDao,
                mock(zixuan.modules.companion.model.dao.CompanionProfileModelDao.class),
                mock(zixuan.modules.companion.model.service.CompanionEffectiveModelService.class),
                mock(zixuan.modules.companion.model.service.CompanionPrivateModelService.class));
    }

    private DeviceServiceImpl realDeviceService(SysUserDao userDao, AgentDao agentDao, DeviceDao deviceDao,
            RedisUtils redis) {
        DeviceServiceImpl service = new DeviceServiceImpl(
                deviceDao,
                null,
                null,
                redis,
                null,
                mock(DeviceAddressBookService.class),
                agentDao,
                mock(CompanionSubscriptionService.class),
                userDao);
        ReflectionTestUtils.setField(service, "baseDao", deviceDao);
        return service;
    }

    private SysUserDao h2UserDao(JdbcTemplate jdbc, CountDownLatch firstUserLock, CountDownLatch releaseFirst,
            AtomicBoolean firstLockOwner) {
        SysUserDao dao = mock(SysUserDao.class);
        when(dao.selectByIdForUpdate(7L)).thenAnswer(invocation -> {
            var user = jdbc.queryForObject("SELECT id FROM sys_user WHERE id = 7 FOR UPDATE", Long.class);
            if (firstLockOwner.compareAndSet(true, false)) {
                firstUserLock.countDown();
                await(releaseFirst);
            }
            zixuan.modules.sys.entity.SysUserEntity entity = new zixuan.modules.sys.entity.SysUserEntity();
            entity.setId(user);
            return entity;
        });
        return dao;
    }

    private AgentDao h2AgentDao(JdbcTemplate jdbc) {
        AgentDao dao = mock(AgentDao.class);
        when(dao.selectList(any())).thenAnswer(invocation -> jdbc.query(
                "SELECT id, user_id, companion_enabled FROM ai_agent WHERE user_id = 7 AND companion_enabled = 1 ORDER BY created_at DESC",
                (result, row) -> agent(result.getString(1), result.getLong(2), result.getInt(3))));
        when(dao.selectCount(any())).thenAnswer(invocation -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_agent WHERE user_id = 7 AND companion_enabled = 1", Long.class));
        when(dao.selectByIdForUpdate(any())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            List<AgentEntity> rows = jdbc.query(
                    "SELECT id, user_id, companion_enabled FROM ai_agent WHERE id = ? FOR UPDATE",
                    (result, row) -> agent(result.getString(1), result.getLong(2), result.getInt(3)), id);
            return rows.isEmpty() ? null : rows.get(0);
        });
        when(dao.insert(any(AgentEntity.class))).thenAnswer(invocation -> {
            AgentEntity entity = invocation.getArgument(0);
            return jdbc.update(
                    "INSERT INTO ai_agent(id, user_id, companion_enabled, created_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                    entity.getId(), entity.getUserId(), entity.getCompanionEnabled());
        });
        return dao;
    }

    private AgentService h2AgentService(AgentDao agentDao) {
        AgentServiceImpl service = new AgentServiceImpl(
                agentDao,
                mock(AgentTagDao.class),
                mock(TimbreService.class),
                mock(ModelConfigService.class),
                mock(RedisUtils.class),
                mock(DeviceService.class),
                mock(AgentPluginMappingService.class),
                mock(AgentChatHistoryService.class),
                mock(AgentTemplateService.class),
                mock(ModelProviderService.class),
                mock(AgentContextProviderService.class),
                mock(AgentTagService.class),
                mock(CorrectWordFileService.class),
                mock(AgentSnapshotService.class));
        ReflectionTestUtils.setField(service, "baseDao", agentDao);
        return service;
    }

    private DeviceDao h2DeviceDao(JdbcTemplate jdbc) {
        DeviceDao dao = mock(DeviceDao.class);
        when(dao.selectById(any())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            List<DeviceEntity> rows = jdbc.query(
                    "SELECT id, user_id, agent_id, mac_address FROM ai_device WHERE id = ?",
                    (result, row) -> device(result.getString(1), result.getLong(2), result.getString(3),
                            result.getString(4)), id);
            return rows.isEmpty() ? null : rows.get(0);
        });
        when(dao.selectCount(any())).thenAnswer(invocation -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_device WHERE user_id = 7", Long.class));
        when(dao.insert(any(DeviceEntity.class))).thenAnswer(invocation -> {
            DeviceEntity entity = invocation.getArgument(0);
            return jdbc.update("INSERT INTO ai_device(id, user_id, agent_id, mac_address) VALUES (?, ?, ?, ?)",
                    entity.getId(), entity.getUserId(), entity.getAgentId(), entity.getMacAddress());
        });
        return dao;
    }

    private RedisUtils activationRedis() {
        RedisUtils redis = mock(RedisUtils.class);
        when(redis.get(RedisKeys.getOtaActivationCode("code-a"))).thenReturn("device-a");
        when(redis.get(RedisKeys.getOtaActivationCode("code-b"))).thenReturn("device-b");
        when(redis.get(RedisKeys.getOtaDeviceActivationInfo("device-a"))).thenReturn(activation("code-a", "AA:AA"));
        when(redis.get(RedisKeys.getOtaDeviceActivationInfo("device-b"))).thenReturn(activation("code-b", "BB:BB"));
        return redis;
    }

    private Map<String, Object> activation(String code, String macAddress) {
        return Map.of(
                "activation_code", code,
                "mac_address", macAddress,
                "board", "board",
                "app_version", "1.0");
    }

    private void bind(CompanionDeviceService service, String activationCode) {
        Subject subject = mock(Subject.class);
        UserDetail user = new UserDetail();
        user.setId(7L);
        when(subject.getPrincipal()).thenReturn(user);
        ThreadContext.bind(subject);
        try {
            service.bind(7L, new CompanionDeviceBindDTO(activationCode, null));
        } finally {
            ThreadContext.unbindSubject();
        }
    }

    private AgentEntity agent(String id, Long userId, Integer companionEnabled) {
        AgentEntity entity = new AgentEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setCompanionEnabled(companionEnabled);
        return entity;
    }

    private DeviceEntity device(String id, Long userId, String agentId, String macAddress) {
        DeviceEntity entity = new DeviceEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setAgentId(agentId);
        entity.setMacAddress(macAddress);
        return entity;
    }

    @SuppressWarnings("unchecked")
    private <T> T transactionalProxy(Object target, PlatformTransactionManager transactionManager, Class<T> type) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvice(interceptor);
        return (T) proxyFactory.getProxy();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for lock release");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
