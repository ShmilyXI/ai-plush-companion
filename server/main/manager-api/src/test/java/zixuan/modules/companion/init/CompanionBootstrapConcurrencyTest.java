package zixuan.modules.companion.init;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.subject.SubjectContext;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import zixuan.common.exception.ErrorCode;
import zixuan.common.exception.RenException;
import zixuan.common.redis.RedisUtils;
import zixuan.common.user.UserDetail;
import zixuan.common.utils.MessageUtils;
import zixuan.modules.agent.dao.AgentDao;
import zixuan.modules.agent.dao.AgentTagDao;
import zixuan.modules.agent.entity.AgentEntity;
import zixuan.modules.agent.entity.AgentTemplateEntity;
import zixuan.modules.agent.service.AgentChatHistoryService;
import zixuan.modules.agent.service.AgentContextProviderService;
import zixuan.modules.agent.service.AgentPluginMappingService;
import zixuan.modules.agent.service.AgentService;
import zixuan.modules.agent.service.AgentSnapshotService;
import zixuan.modules.agent.service.AgentTagService;
import zixuan.modules.agent.service.AgentTemplateService;
import zixuan.modules.agent.service.impl.AgentServiceImpl;
import zixuan.modules.companion.config.CompanionBootstrapProperties;
import zixuan.modules.companion.dao.CompanionPlanDao;
import zixuan.modules.companion.dao.CompanionSubscriptionDao;
import zixuan.modules.companion.entity.CompanionPlanEntity;
import zixuan.modules.companion.entity.CompanionSubscriptionEntity;
import zixuan.modules.companion.service.CompanionProfileService;
import zixuan.modules.companion.service.CompanionSubscriptionService;
import zixuan.modules.companion.service.impl.CompanionProfileServiceImpl;
import zixuan.modules.companion.service.impl.CompanionSubscriptionServiceImpl;
import zixuan.modules.correctword.service.CorrectWordFileService;
import zixuan.modules.device.dao.DeviceDao;
import zixuan.modules.device.entity.DeviceEntity;
import zixuan.modules.device.service.DeviceAddressBookService;
import zixuan.modules.device.service.DeviceService;
import zixuan.modules.device.service.impl.DeviceServiceImpl;
import zixuan.modules.model.service.ModelConfigService;
import zixuan.modules.model.service.ModelProviderService;
import zixuan.modules.sys.dao.SysUserDao;
import zixuan.modules.sys.entity.SysUserEntity;
import zixuan.modules.timbre.service.TimbreService;

class CompanionBootstrapConcurrencyTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-29T04:00:00Z"), ZoneOffset.UTC);

    @Test
    void concurrentBootstrapInstancesConvergeOnOneCompleteIdentity() throws Exception {
        DriverManagerDataSource dataSource = dataSource("bootstrap_race");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createBootstrapSchema(jdbc);
        jdbc.update("INSERT INTO ai_device(id,mac_address) VALUES ('hardware-1','AA:BB:CC:DD:EE:FF')");
        PlatformTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        CyclicBarrier insertRace = new CyclicBarrier(2);
        AtomicInteger deviceUpdates = new AtomicInteger();
        CompanionBootstrapService first = bootstrapService(
                jdbc, transactionManager, insertRace, deviceUpdates, 7L);
        CompanionBootstrapService second = bootstrapService(
                jdbc, transactionManager, insertRace, deviceUpdates, 8L);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        var executor = Executors.newFixedThreadPool(2);

        try {
            var firstRun = executor.submit(() -> initialize(first, failures));
            var secondRun = executor.submit(() -> initialize(second, failures));
            firstRun.get(5, TimeUnit.SECONDS);
            secondRun.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertTrue(failures.isEmpty(), () -> "bootstrap failure: " + failures);
        assertEquals(1, count(jdbc, "sys_user"));
        assertEquals(1, count(jdbc, "ai_companion_plan"));
        assertEquals(1, count(jdbc, "ai_agent_template"));
        assertEquals(1, count(jdbc, "ai_agent"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_companion_subscription WHERE status='active' AND plan_id='basic'",
                Integer.class));
        assertEquals(1, deviceUpdates.get());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_device d JOIN ai_agent a ON a.id=d.agent_id"
                        + " WHERE d.id='hardware-1' AND d.user_id=a.user_id AND a.companion_enabled=1",
                Integer.class));
    }

    @Test
    void differentUsersRacingSameMacLeaveOneConsistentOwner() throws Exception {
        DriverManagerDataSource dataSource = dataSource("attach_race");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE sys_user (id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE ai_agent (id VARCHAR(64) PRIMARY KEY, user_id BIGINT NOT NULL,"
                + " companion_template_id VARCHAR(64), companion_enabled INT NOT NULL)");
        jdbc.execute("CREATE TABLE ai_device (id VARCHAR(64) PRIMARY KEY, user_id BIGINT, agent_id VARCHAR(64),"
                + " mac_address VARCHAR(64), normalized_mac_address VARCHAR(64) GENERATED ALWAYS AS"
                + " (LOWER(REPLACE(REPLACE(TRIM(mac_address), ':', ''), '-', ''))),"
                + " CONSTRAINT uk_ai_device_normalized_mac UNIQUE(normalized_mac_address))");
        jdbc.update("INSERT INTO sys_user(id) VALUES (7),(8)");
        jdbc.update("INSERT INTO ai_agent(id,user_id,companion_enabled) VALUES ('agent-7',7,1),('agent-8',8,1)");
        jdbc.update("INSERT INTO ai_device(id,mac_address) VALUES ('hardware-1','AA:BB:CC:DD:EE:FF')");
        PlatformTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        CyclicBarrier candidateRace = new CyclicBarrier(2);
        DeviceService firstDeviceService = deviceService(
                jdbc, transactionManager, candidateRace, new AtomicInteger());
        DeviceService secondDeviceService = deviceService(
                jdbc, transactionManager, candidateRace, new AtomicInteger());
        AttachCommand first = transactionalProxy(
                new AttachCommandImpl(firstDeviceService), transactionManager, AttachCommand.class);
        AttachCommand second = transactionalProxy(
                new AttachCommandImpl(secondDeviceService), transactionManager, AttachCommand.class);
        MessageSource messages = mock(MessageSource.class);
        when(messages.getMessage(any(), any(), any())).thenReturn("error");
        Object originalMessageSource = ReflectionTestUtils.getField(MessageUtils.class, "messageSource");
        Subject originalSubject = ThreadContext.getSubject();
        ConcurrentHashMap<Long, Integer> outcomes = new ConcurrentHashMap<>();
        var executor = Executors.newFixedThreadPool(2);

        try {
            ReflectionTestUtils.setField(MessageUtils.class, "messageSource", messages);
            var firstRun = executor.submit(() -> attachAs(first, 7L, "agent-7", outcomes));
            var secondRun = executor.submit(() -> attachAs(second, 8L, "agent-8", outcomes));
            firstRun.get(5, TimeUnit.SECONDS);
            secondRun.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            ReflectionTestUtils.setField(MessageUtils.class, "messageSource", originalMessageSource);
            ThreadContext.unbindSubject();
            if (originalSubject != null) {
                ThreadContext.bind(originalSubject);
            }
        }

        assertEquals(1, outcomes.values().stream().filter(code -> code == 0).count());
        assertEquals(1, outcomes.values().stream().filter(code -> code == ErrorCode.NO_PERMISSION).count());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_device d JOIN ai_agent a ON a.id=d.agent_id"
                        + " WHERE d.id='hardware-1' AND d.user_id=a.user_id",
                Integer.class));
    }

    private CompanionBootstrapService bootstrapService(JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager, CyclicBarrier insertRace,
            AtomicInteger deviceUpdates, Long proposedUserId) {
        SysUserDao userDao = userDao(jdbc, insertRace, proposedUserId);
        CompanionPlanDao planDao = planDao(jdbc);
        CompanionSubscriptionDao subscriptionDao = subscriptionDao(jdbc);
        CompanionSubscriptionService subscriptionService = transactionalProxy(
                new CompanionSubscriptionServiceImpl(planDao, subscriptionDao, userDao, CLOCK),
                transactionManager, CompanionSubscriptionService.class);
        AgentTemplateService templateService = templateService(jdbc);
        AgentDao agentDao = agentDao(jdbc);
        AgentService agentService = agentService(agentDao);
        CompanionProfileService profileService = transactionalProxy(
                new CompanionProfileServiceImpl(
                        agentDao, agentService, templateService, mock(AgentSnapshotService.class),
                        mock(ModelConfigService.class), mock(TimbreService.class),
                        mock(zixuan.modules.voiceclone.service.VoiceCloneService.class), subscriptionService, userDao,
                        mock(zixuan.modules.companion.model.dao.CompanionProfileModelDao.class),
                        mock(zixuan.modules.companion.model.service.CompanionEffectiveModelService.class),
                        mock(zixuan.modules.companion.model.service.CompanionPrivateModelService.class)),
                transactionManager, CompanionProfileService.class);
        DeviceService deviceService = deviceService(
                jdbc, transactionManager, null, deviceUpdates, userDao, agentDao, subscriptionService);
        CompanionBootstrapProperties properties = new CompanionBootstrapProperties();
        properties.setEnabled(true);
        properties.setUsername("companion-user");
        properties.setPassword("SafePass9!");
        properties.setDeviceMac("AA-BB-CC-DD-EE-FF");
        return new CompanionBootstrapService(
                properties, userDao, templateService, agentDao, planDao, subscriptionDao,
                subscriptionService, profileService, deviceService, securityManager(), transactionManager, CLOCK);
    }

    private SysUserDao userDao(JdbcTemplate jdbc, CyclicBarrier insertRace, Long proposedUserId) {
        SysUserDao dao = mock(SysUserDao.class);
        when(dao.selectOne(any())).thenAnswer(invocation -> jdbc.query(
                "SELECT id,username,password,super_admin,status FROM sys_user WHERE username='companion-user'",
                result -> result.next() ? user(result) : null));
        when(dao.insert(any(SysUserEntity.class))).thenAnswer(invocation -> {
            SysUserEntity entity = invocation.getArgument(0);
            entity.setId(proposedUserId);
            if (insertRace != null) {
                insertRace.await(2, TimeUnit.SECONDS);
            }
            return jdbc.update("INSERT INTO sys_user(id,username,password,super_admin,status) VALUES (?,?,?,?,?)",
                    entity.getId(), entity.getUsername(), entity.getPassword(), entity.getSuperAdmin(),
                    entity.getStatus());
        });
        when(dao.selectByIdForUpdate(any())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return jdbc.query("SELECT id,username,password,super_admin,status FROM sys_user WHERE id=? FOR UPDATE",
                    result -> result.next() ? user(result) : null, id);
        });
        return dao;
    }

    private CompanionPlanDao planDao(JdbcTemplate jdbc) {
        CompanionPlanDao dao = mock(CompanionPlanDao.class);
        when(dao.selectOne(any())).thenAnswer(invocation -> jdbc.query(
                "SELECT id,plan_code,status,max_devices,max_profiles FROM ai_companion_plan WHERE plan_code='basic'",
                result -> result.next() ? plan(result) : null));
        when(dao.selectByIdForUpdate(any())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            return jdbc.query(
                    "SELECT id,plan_code,status,max_devices,max_profiles FROM ai_companion_plan WHERE id=? FOR UPDATE",
                    result -> result.next() ? plan(result) : null, id);
        });
        when(dao.selectById(any())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            return jdbc.query(
                    "SELECT id,plan_code,status,max_devices,max_profiles FROM ai_companion_plan WHERE id=?",
                    result -> result.next() ? plan(result) : null, id);
        });
        when(dao.insert(any(CompanionPlanEntity.class))).thenAnswer(invocation -> {
            CompanionPlanEntity entity = invocation.getArgument(0);
            return jdbc.update("INSERT INTO ai_companion_plan"
                    + " (id,plan_code,status,max_devices,max_profiles) VALUES (?,?,?,?,?)",
                    entity.getId(), entity.getPlanCode(), entity.getStatus(), entity.getMaxDevices(),
                    entity.getMaxProfiles());
        });
        return dao;
    }

    private CompanionSubscriptionDao subscriptionDao(JdbcTemplate jdbc) {
        CompanionSubscriptionDao dao = mock(CompanionSubscriptionDao.class);
        when(dao.selectOne(any())).thenAnswer(invocation -> jdbc.query(
                "SELECT id,user_id,plan_id,status,starts_at,expires_at"
                        + " FROM ai_companion_subscription WHERE status='active'",
                result -> result.next() ? subscription(result) : null));
        when(dao.update(isNull(), any())).thenAnswer(invocation -> jdbc.update(
                "UPDATE ai_companion_subscription SET status='replaced' WHERE status='active'"));
        when(dao.insert(any(CompanionSubscriptionEntity.class))).thenAnswer(invocation -> {
            CompanionSubscriptionEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(UUID.randomUUID().toString());
            }
            return jdbc.update("INSERT INTO ai_companion_subscription"
                    + " (id,user_id,plan_id,status,starts_at,expires_at) VALUES (?,?,?,?,?,?)",
                    entity.getId(), entity.getUserId(), entity.getPlanId(), entity.getStatus(),
                    entity.getStartsAt(), entity.getExpiresAt());
        });
        return dao;
    }

    private AgentTemplateService templateService(JdbcTemplate jdbc) {
        AgentTemplateService service = mock(AgentTemplateService.class);
        when(service.getOne(any())).thenAnswer(invocation -> jdbc.query(
                "SELECT id,agent_code,agent_name,system_prompt,companion_cue_config"
                        + " FROM ai_agent_template WHERE agent_code='xiaozhi-companion'",
                result -> result.next() ? template(result) : null));
        when(service.getById(any())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            return jdbc.query("SELECT id,agent_code,agent_name,system_prompt,companion_cue_config"
                            + " FROM ai_agent_template WHERE id=?",
                    result -> result.next() ? template(result) : null, id);
        });
        when(service.getDefaultTemplate()).thenReturn(null);
        when(service.getNextAvailableSort()).thenReturn(1);
        when(service.save(any(AgentTemplateEntity.class))).thenAnswer(invocation -> {
            AgentTemplateEntity entity = invocation.getArgument(0);
            return jdbc.update("INSERT INTO ai_agent_template"
                    + " (id,agent_code,agent_name,system_prompt,companion_cue_config) VALUES (?,?,?,?,?)",
                    entity.getId(), entity.getAgentCode(), entity.getAgentName(), entity.getSystemPrompt(),
                    entity.getCompanionCueConfig()) == 1;
        });
        return service;
    }

    private AgentDao agentDao(JdbcTemplate jdbc) {
        AgentDao dao = mock(AgentDao.class);
        when(dao.selectOne(any())).thenAnswer(invocation -> jdbc.query(
                "SELECT id,user_id,companion_template_id,companion_enabled FROM ai_agent"
                        + " WHERE companion_template_id='template-xiaozhi' AND companion_enabled=1",
                result -> result.next() ? agent(result) : null));
        when(dao.selectCount(any())).thenAnswer(invocation -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_agent WHERE companion_enabled=1", Long.class));
        when(dao.selectByIdForUpdate(any())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            return jdbc.query("SELECT id,user_id,companion_template_id,companion_enabled"
                            + " FROM ai_agent WHERE id=? FOR UPDATE",
                    result -> result.next() ? agent(result) : null, id);
        });
        when(dao.insert(any(AgentEntity.class))).thenAnswer(invocation -> {
            AgentEntity entity = invocation.getArgument(0);
            return jdbc.update("INSERT INTO ai_agent"
                    + " (id,user_id,companion_template_id,companion_enabled) VALUES (?,?,?,?)",
                    entity.getId(), entity.getUserId(), entity.getCompanionTemplateId(),
                    entity.getCompanionEnabled());
        });
        return dao;
    }

    private AgentService agentService(AgentDao agentDao) {
        AgentServiceImpl service = new AgentServiceImpl(
                agentDao, mock(AgentTagDao.class), mock(TimbreService.class),
                mock(ModelConfigService.class), mock(RedisUtils.class), mock(DeviceService.class),
                mock(AgentPluginMappingService.class), mock(AgentChatHistoryService.class),
                mock(AgentTemplateService.class), mock(ModelProviderService.class),
                mock(AgentContextProviderService.class), mock(AgentTagService.class),
                mock(CorrectWordFileService.class), mock(AgentSnapshotService.class));
        ReflectionTestUtils.setField(service, "baseDao", agentDao);
        return service;
    }

    private DeviceService deviceService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            CyclicBarrier candidateRace, AtomicInteger updates) {
        return deviceService(jdbc, transactionManager, candidateRace, updates,
                attachUserDao(jdbc), agentDao(jdbc), mock(CompanionSubscriptionService.class));
    }

    private SysUserDao attachUserDao(JdbcTemplate jdbc) {
        SysUserDao dao = mock(SysUserDao.class);
        when(dao.selectByIdForUpdate(any())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return jdbc.query("SELECT id FROM sys_user WHERE id=? FOR UPDATE", result -> {
                if (!result.next()) {
                    return null;
                }
                SysUserEntity user = new SysUserEntity();
                user.setId(result.getLong("id"));
                return user;
            }, id);
        });
        return dao;
    }

    private DeviceService deviceService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            CyclicBarrier candidateRace, AtomicInteger updates, SysUserDao userDao, AgentDao agentDao,
            CompanionSubscriptionService subscriptionService) {
        DeviceDao deviceDao = deviceDao(jdbc, candidateRace, updates);
        DeviceServiceImpl target = new DeviceServiceImpl(
                deviceDao, null, null, mock(RedisUtils.class), null, mock(DeviceAddressBookService.class),
                agentDao, subscriptionService, userDao);
        ReflectionTestUtils.setField(target, "baseDao", deviceDao);
        return transactionalProxy(target, transactionManager, DeviceService.class);
    }

    private DeviceDao deviceDao(JdbcTemplate jdbc, CyclicBarrier candidateRace, AtomicInteger updates) {
        DeviceDao dao = mock(DeviceDao.class);
        when(dao.selectByNormalizedMac(any())).thenAnswer(invocation -> {
            String mac = invocation.getArgument(0);
            DeviceEntity device = jdbc.query("SELECT id,user_id,agent_id,mac_address"
                            + " FROM ai_device WHERE normalized_mac_address=?",
                    result -> result.next() ? device(result) : null, mac);
            if (candidateRace != null) {
                candidateRace.await(2, TimeUnit.SECONDS);
            }
            return device;
        });
        when(dao.selectByNormalizedMacForUpdate(any())).thenAnswer(invocation -> {
            String mac = invocation.getArgument(0);
            return jdbc.query("SELECT id,user_id,agent_id,mac_address FROM ai_device"
                            + " WHERE normalized_mac_address=? FOR UPDATE",
                    result -> result.next() ? device(result) : null, mac);
        });
        when(dao.selectCount(any())).thenAnswer(invocation -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_device WHERE user_id IS NOT NULL", Long.class));
        when(dao.updateById(any(DeviceEntity.class))).thenAnswer(invocation -> {
            DeviceEntity entity = invocation.getArgument(0);
            int changed = jdbc.update("UPDATE ai_device SET user_id=?,agent_id=? WHERE id=?",
                    entity.getUserId(), entity.getAgentId(), entity.getId());
            if (changed == 1) {
                updates.incrementAndGet();
            }
            return changed;
        });
        return dao;
    }

    private SecurityManager securityManager() {
        SecurityManager manager = mock(SecurityManager.class);
        when(manager.createSubject(any())).thenAnswer(invocation -> {
            SubjectContext context = invocation.getArgument(0);
            Object principal = context.resolvePrincipals().getPrimaryPrincipal();
            Subject subject = mock(Subject.class);
            when(subject.getPrincipal()).thenReturn(principal);
            return subject;
        });
        return manager;
    }

    private void initialize(CompanionBootstrapService service, ConcurrentLinkedQueue<Throwable> failures) {
        try {
            service.initialize();
        } catch (Throwable throwable) {
            failures.add(throwable);
        } finally {
            ThreadContext.unbindSubject();
        }
    }

    private void attachAs(AttachCommand command, Long userId, String agentId, Map<Long, Integer> outcomes) {
        Subject previous = ThreadContext.getSubject();
        Subject subject = mock(Subject.class);
        UserDetail user = new UserDetail();
        user.setId(userId);
        when(subject.getPrincipal()).thenReturn(user);
        ThreadContext.bind(subject);
        try {
            command.attach(userId, agentId, "AA:BB:CC:DD:EE:FF");
            outcomes.put(userId, 0);
        } catch (RenException exception) {
            outcomes.put(userId, exception.getCode());
        } finally {
            ThreadContext.unbindSubject();
            if (previous != null) {
                ThreadContext.bind(previous);
            }
        }
    }

    private DriverManagerDataSource dataSource(String name) {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:" + name + "_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }

    private void createBootstrapSchema(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE sys_user (id BIGINT PRIMARY KEY, username VARCHAR(64) NOT NULL UNIQUE,"
                + " password VARCHAR(128), super_admin INT, status INT)");
        jdbc.execute("CREATE TABLE ai_companion_plan (id VARCHAR(32) PRIMARY KEY, plan_code VARCHAR(32) UNIQUE,"
                + " status INT, max_devices INT, max_profiles INT)");
        jdbc.execute("CREATE TABLE ai_companion_subscription (id VARCHAR(64) PRIMARY KEY, user_id BIGINT,"
                + " plan_id VARCHAR(32), status VARCHAR(16), starts_at TIMESTAMP, expires_at TIMESTAMP,"
                + " active_user_id BIGINT GENERATED ALWAYS AS"
                + " (CASE WHEN status='active' THEN user_id ELSE NULL END), UNIQUE(active_user_id))");
        jdbc.execute("CREATE TABLE ai_agent_template (id VARCHAR(64) PRIMARY KEY, agent_code VARCHAR(64) UNIQUE,"
                + " agent_name VARCHAR(64), system_prompt CLOB, companion_cue_config CLOB)");
        jdbc.execute("CREATE TABLE ai_agent (id VARCHAR(64) PRIMARY KEY, user_id BIGINT,"
                + " companion_template_id VARCHAR(64), companion_enabled INT)");
        jdbc.execute("CREATE TABLE ai_device (id VARCHAR(64) PRIMARY KEY, user_id BIGINT, agent_id VARCHAR(64),"
                + " mac_address VARCHAR(64), normalized_mac_address VARCHAR(64) GENERATED ALWAYS AS"
                + " (LOWER(REPLACE(REPLACE(TRIM(mac_address), ':', ''), '-', ''))),"
                + " CONSTRAINT uk_ai_device_normalized_mac UNIQUE(normalized_mac_address))");
    }

    private int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private SysUserEntity user(ResultSet result) throws SQLException {
        SysUserEntity entity = new SysUserEntity();
        entity.setId(result.getLong("id"));
        entity.setUsername(result.getString("username"));
        entity.setPassword(result.getString("password"));
        entity.setSuperAdmin(result.getInt("super_admin"));
        entity.setStatus(result.getInt("status"));
        return entity;
    }

    private CompanionPlanEntity plan(ResultSet result) throws SQLException {
        CompanionPlanEntity entity = new CompanionPlanEntity();
        entity.setId(result.getString("id"));
        entity.setPlanCode(result.getString("plan_code"));
        entity.setStatus(result.getInt("status"));
        entity.setMaxDevices(result.getInt("max_devices"));
        entity.setMaxProfiles(result.getInt("max_profiles"));
        return entity;
    }

    private CompanionSubscriptionEntity subscription(ResultSet result) throws SQLException {
        CompanionSubscriptionEntity entity = new CompanionSubscriptionEntity();
        entity.setId(result.getString("id"));
        entity.setUserId(result.getLong("user_id"));
        entity.setPlanId(result.getString("plan_id"));
        entity.setStatus(result.getString("status"));
        entity.setStartsAt(result.getTimestamp("starts_at"));
        entity.setExpiresAt(result.getTimestamp("expires_at"));
        return entity;
    }

    private AgentTemplateEntity template(ResultSet result) throws SQLException {
        AgentTemplateEntity entity = new AgentTemplateEntity();
        entity.setId(result.getString("id"));
        entity.setAgentCode(result.getString("agent_code"));
        entity.setAgentName(result.getString("agent_name"));
        entity.setSystemPrompt(result.getString("system_prompt"));
        entity.setCompanionCueConfig(result.getString("companion_cue_config"));
        return entity;
    }

    private AgentEntity agent(ResultSet result) throws SQLException {
        AgentEntity entity = new AgentEntity();
        entity.setId(result.getString("id"));
        entity.setUserId(result.getLong("user_id"));
        entity.setCompanionTemplateId(result.getString("companion_template_id"));
        entity.setCompanionEnabled(result.getInt("companion_enabled"));
        return entity;
    }

    private DeviceEntity device(ResultSet result) throws SQLException {
        DeviceEntity entity = new DeviceEntity();
        entity.setId(result.getString("id"));
        long userId = result.getLong("user_id");
        entity.setUserId(result.wasNull() ? null : userId);
        entity.setAgentId(result.getString("agent_id"));
        entity.setMacAddress(result.getString("mac_address"));
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

    private interface AttachCommand {
        void attach(Long userId, String agentId, String macAddress);
    }

    private static final class AttachCommandImpl implements AttachCommand {
        private final DeviceService deviceService;

        private AttachCommandImpl(DeviceService deviceService) {
            this.deviceService = deviceService;
        }

        @Override
        @Transactional(rollbackFor = Exception.class)
        public void attach(Long userId, String agentId, String macAddress) {
            deviceService.attachExistingDevice(userId, agentId, macAddress);
        }
    }
}
