package zixuan.modules.companion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import zixuan.common.exception.ErrorCode;
import zixuan.common.exception.RenException;
import zixuan.common.redis.RedisKeys;
import zixuan.common.redis.RedisUtils;
import zixuan.common.user.UserDetail;
import zixuan.common.utils.MessageUtils;
import zixuan.modules.agent.dao.AgentDao;
import zixuan.modules.agent.entity.AgentEntity;
import zixuan.modules.companion.dto.CompanionDeviceBindDTO;
import zixuan.modules.companion.debug.service.DeviceDebugLogService;
import zixuan.modules.companion.service.impl.CompanionDeviceServiceImpl;
import zixuan.modules.device.dao.DeviceDao;
import zixuan.modules.device.entity.DeviceEntity;
import zixuan.modules.device.service.DeviceAddressBookService;
import zixuan.modules.device.service.DeviceService;
import zixuan.modules.device.service.impl.DeviceServiceImpl;
import zixuan.modules.sys.dao.SysUserDao;

class CompanionDeviceActivationConcurrencyTest {

    @Test
    void differentUsersRacingSameActivationCodeYieldOneSuccessAndOneAlreadyActivated() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:activation_race_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE ai_device (id VARCHAR(64) PRIMARY KEY, user_id BIGINT, agent_id VARCHAR(64), mac_address VARCHAR(64))");
        PlatformTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        CyclicBarrier insertBarrier = new CyclicBarrier(2);
        DeviceDao deviceDao = h2DeviceDao(jdbc, insertBarrier);
        RedisUtils redis = activationRedis();
        AgentDao agentDao = mock(AgentDao.class);
        when(agentDao.selectByIdForUpdate(any())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            AgentEntity agent = new AgentEntity();
            agent.setId(id);
            agent.setUserId(Long.valueOf(id.substring(id.lastIndexOf('-') + 1)));
            return agent;
        });
        DeviceServiceImpl deviceTarget = new DeviceServiceImpl(
                deviceDao, null, null, redis, null, mock(DeviceAddressBookService.class), agentDao,
                mock(CompanionSubscriptionService.class), mock(SysUserDao.class));
        ReflectionTestUtils.setField(deviceTarget, "baseDao", deviceDao);
        DeviceService deviceService = transactionalProxy(deviceTarget, transactionManager, DeviceService.class);
        CompanionProfileService profileService = mock(CompanionProfileService.class);
        when(profileService.resolveForDeviceBinding(any(), any(), any(), any()))
                .thenAnswer(invocation -> "agent-" + invocation.getArgument(0));
        CompanionDeviceService companionService = transactionalProxy(
                new CompanionDeviceServiceImpl(deviceService, profileService, mock(DeviceDebugLogService.class)),
                transactionManager,
                CompanionDeviceService.class);
        MessageSource messages = mock(MessageSource.class);
        when(messages.getMessage(any(), any(), any())).thenReturn("error");
        Object originalMessageSource = ReflectionTestUtils.getField(MessageUtils.class, "messageSource");
        ReflectionTestUtils.setField(MessageUtils.class, "messageSource", messages);
        var outcomes = new ConcurrentHashMap<Long, Integer>();
        var executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() -> bind(companionService, 7L, outcomes));
            var second = executor.submit(() -> bind(companionService, 8L, outcomes));
            first.get(3, TimeUnit.SECONDS);
            second.get(3, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            ReflectionTestUtils.setField(MessageUtils.class, "messageSource", originalMessageSource);
        }

        assertEquals(1, outcomes.values().stream().filter(code -> code == 0).count());
        assertEquals(1, outcomes.values().stream().filter(code -> code == ErrorCode.DEVICE_ALREADY_ACTIVATED).count());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_device WHERE id = 'device-id'", Integer.class));
    }

    private void bind(CompanionDeviceService service, Long userId, Map<Long, Integer> outcomes) {
        Subject subject = mock(Subject.class);
        UserDetail user = new UserDetail();
        user.setId(userId);
        when(subject.getPrincipal()).thenReturn(user);
        ThreadContext.bind(subject);
        try {
            service.bind(userId, new CompanionDeviceBindDTO("123456", null));
            outcomes.put(userId, 0);
        } catch (RenException exception) {
            outcomes.put(userId, exception.getCode());
        } finally {
            ThreadContext.unbindSubject();
        }
    }

    private DeviceDao h2DeviceDao(JdbcTemplate jdbc, CyclicBarrier barrier) {
        DeviceDao dao = mock(DeviceDao.class);
        when(dao.selectById("device-id")).thenAnswer(invocation -> jdbc.query(
                "SELECT id, user_id, agent_id, mac_address FROM ai_device WHERE id = 'device-id'",
                result -> result.next() ? device(result.getString(1), result.getLong(2), result.getString(3),
                        result.getString(4)) : null));
        when(dao.selectCount(any())).thenReturn(0L);
        when(dao.insert(any(DeviceEntity.class))).thenAnswer(invocation -> {
            barrier.await(2, TimeUnit.SECONDS);
            DeviceEntity device = invocation.getArgument(0);
            return jdbc.update("INSERT INTO ai_device(id, user_id, agent_id, mac_address) VALUES (?, ?, ?, ?)",
                    device.getId(), device.getUserId(), device.getAgentId(), device.getMacAddress());
        });
        return dao;
    }

    private RedisUtils activationRedis() {
        RedisUtils redis = mock(RedisUtils.class);
        when(redis.get(RedisKeys.getOtaActivationCode("123456"))).thenReturn("device-id");
        when(redis.get(RedisKeys.getOtaDeviceActivationInfo("device-id"))).thenReturn(Map.of(
                "activation_code", "123456",
                "mac_address", "AA:BB",
                "board", "board",
                "app_version", "1.0"));
        return redis;
    }

    private DeviceEntity device(String id, Long userId, String agentId, String macAddress) {
        DeviceEntity device = new DeviceEntity();
        device.setId(id);
        device.setUserId(userId);
        device.setAgentId(agentId);
        device.setMacAddress(macAddress);
        return device;
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
}
