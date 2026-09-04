package zixuan.modules.companion.init;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.subject.Subject;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.NameMatchTransactionAttributeSource;
import org.springframework.transaction.interceptor.RuleBasedTransactionAttribute;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import zixuan.modules.agent.dao.AgentDao;
import zixuan.modules.agent.entity.AgentEntity;
import zixuan.modules.agent.entity.AgentTemplateEntity;
import zixuan.modules.agent.service.AgentTemplateService;
import zixuan.modules.companion.config.CompanionBootstrapProperties;
import zixuan.modules.companion.capability.init.CapabilityBootstrapService;
import zixuan.modules.companion.dao.CompanionPlanDao;
import zixuan.modules.companion.dao.CompanionSubscriptionDao;
import zixuan.modules.companion.entity.CompanionPlanEntity;
import zixuan.modules.companion.entity.CompanionSubscriptionEntity;
import zixuan.modules.companion.service.CompanionProfileService;
import zixuan.modules.companion.service.CompanionSubscriptionService;
import zixuan.modules.device.entity.DeviceEntity;
import zixuan.modules.device.service.DeviceService;
import zixuan.modules.security.password.PasswordUtils;
import zixuan.common.user.UserDetail;
import zixuan.modules.sys.dao.SysUserDao;
import zixuan.modules.sys.entity.SysUserEntity;

class CompanionBootstrapServiceTest {

    @Test
    void officialCapabilitiesInitializeEvenWhenDemoCompanionBootstrapIsDisabled() {
        AgentTemplateService templates = mock(AgentTemplateService.class);
        when(templates.getOne(any())).thenReturn(null);
        when(templates.getDefaultTemplate()).thenReturn(null);
        when(templates.getNextAvailableSort()).thenReturn(1);
        when(templates.save(any(AgentTemplateEntity.class))).thenReturn(true);
        CompanionBootstrapService service = new CompanionBootstrapService(properties(false, null, null, null),
                mock(SysUserDao.class), templates, mock(AgentDao.class), mock(CompanionPlanDao.class),
                mock(CompanionSubscriptionDao.class), mock(CompanionSubscriptionService.class),
                mock(CompanionProfileService.class), mock(DeviceService.class), mock(SecurityManager.class),
                transactionManager(), Clock.systemUTC());
        CapabilityBootstrapService capabilities = mock(CapabilityBootstrapService.class);
        service.setCapabilityBootstrapService(capabilities);

        service.initialize();

        verify(capabilities).initialize();
        verify(templates).save(org.mockito.ArgumentMatchers.argThat(template ->
                "template-zixuan".equals(template.getId())
                        && "zixuan-companion".equals(template.getAgentCode())
                        && template.getCompanionCueConfig().contains("laugh")));
    }

    @Test
    void disabledDemoBootstrapRepairsAnExistingCompanionTemplateWithoutCueConfig() {
        AgentTemplateService templates = mock(AgentTemplateService.class);
        AgentTemplateEntity existing = new AgentTemplateEntity();
        existing.setId("template-zixuan");
        existing.setAgentCode("zixuan-companion");
        existing.setAgentName("紫萱");
        existing.setSystemPrompt("existing prompt");
        when(templates.getOne(any())).thenReturn(existing);
        when(templates.updateById(any(AgentTemplateEntity.class))).thenReturn(true);
        CompanionBootstrapService service = new CompanionBootstrapService(properties(false, null, null, null),
                mock(SysUserDao.class), templates, mock(AgentDao.class), mock(CompanionPlanDao.class),
                mock(CompanionSubscriptionDao.class), mock(CompanionSubscriptionService.class),
                mock(CompanionProfileService.class), mock(DeviceService.class), mock(SecurityManager.class),
                transactionManager(), Clock.systemUTC());

        service.initialize();

        verify(templates).updateById(org.mockito.ArgumentMatchers.argThat(template ->
                template == existing && template.getCompanionCueConfig().contains("breathe")));
        assertEquals("existing prompt", existing.getSystemPrompt());
    }

    @Test
    void initializeTwiceCreatesIdentityOnlyOnce() {
        CompanionBootstrapProperties properties = properties(
                true, "companion-user", "  SafePass9!  ", "\taa:bb:cc:dd:ee:ff\t");
        SysUserDao userDao = mock(SysUserDao.class);
        AgentTemplateService templateService = mock(AgentTemplateService.class);
        AgentDao agentDao = mock(AgentDao.class);
        CompanionPlanDao planDao = mock(CompanionPlanDao.class);
        CompanionSubscriptionDao subscriptionDao = mock(CompanionSubscriptionDao.class);
        CompanionSubscriptionService subscriptionService = mock(CompanionSubscriptionService.class);
        CompanionProfileService profileService = mock(CompanionProfileService.class);
        DeviceService deviceService = mock(DeviceService.class);

        AtomicReference<SysUserEntity> user = new AtomicReference<>();
        AtomicReference<AgentTemplateEntity> template = new AtomicReference<>();
        AtomicReference<AgentEntity> profile = new AtomicReference<>();
        AtomicReference<CompanionSubscriptionEntity> subscription = new AtomicReference<>();

        when(userDao.selectOne(any())).thenAnswer(invocation -> user.get());
        when(userDao.insert(any(SysUserEntity.class))).thenAnswer(invocation -> {
            SysUserEntity inserted = invocation.getArgument(0);
            inserted.setId(7L);
            user.set(inserted);
            return 1;
        });
        when(userDao.selectByIdForUpdate(7L)).thenAnswer(invocation -> user.get());

        CompanionPlanEntity basic = new CompanionPlanEntity();
        basic.setId("basic");
        basic.setPlanCode("basic");
        basic.setStatus(1);
        basic.setMaxDevices(1);
        basic.setMaxProfiles(3);
        when(planDao.selectOne(any())).thenReturn(basic);
        when(planDao.selectByIdForUpdate("basic")).thenReturn(basic);

        when(templateService.getOne(any())).thenAnswer(invocation -> template.get());
        when(templateService.save(any())).thenAnswer(invocation -> {
            template.set(invocation.getArgument(0));
            return true;
        });
        when(agentDao.selectOne(any())).thenAnswer(invocation -> profile.get());
        when(profileService.createFromTemplate(7L, "template-zixuan", "紫萱")).thenAnswer(invocation -> {
            AgentEntity created = new AgentEntity();
            created.setId("profile-1");
            created.setUserId(7L);
            created.setCompanionTemplateId("template-zixuan");
            created.setCompanionEnabled(1);
            created.setCompanionCueConfig(template.get().getCompanionCueConfig());
            profile.set(created);
            return created.getId();
        });
        when(subscriptionDao.selectOne(any())).thenAnswer(invocation -> subscription.get());
        doAnswer(invocation -> {
            CompanionSubscriptionEntity active = new CompanionSubscriptionEntity();
            active.setUserId(7L);
            active.setPlanId("basic");
            active.setStatus(CompanionSubscriptionEntity.STATUS_ACTIVE);
            active.setStartsAt(Date.from(Instant.parse("2026-07-29T04:00:00Z")));
            active.setExpiresAt(Date.from(Instant.parse("2126-07-05T04:00:00Z")));
            subscription.set(active);
            return null;
        }).when(subscriptionService).grant(any(), any(), any(), any());

        DeviceEntity registered = new DeviceEntity();
        registered.setId("hardware-device-123");
        registered.setMacAddress(properties.getDeviceMac());
        doAnswer(invocation -> {
            assertEquals(7L, zixuan.modules.security.user.SecurityUser.getUserId());
            if (registered.getUserId() == null) {
                registered.setUserId(invocation.getArgument(0));
                registered.setAgentId(invocation.getArgument(1));
            } else if (registered.getAgentId() == null) {
                registered.setAgentId(invocation.getArgument(1));
            }
            return null;
        }).when(deviceService).attachExistingDevice(any(), any(), any());

        CompanionBootstrapService service = new CompanionBootstrapService(properties, userDao, templateService,
                agentDao, planDao, subscriptionDao, subscriptionService, profileService, deviceService,
                securityManager(7L), transactionManager(),
                Clock.fixed(Instant.parse("2026-07-29T04:00:00Z"), ZoneOffset.UTC));

        service.initialize();
        profile.get().setCompanionCueConfig("custom-user-cue");
        registered.setAgentId("chosen-profile");
        service.initialize();

        org.mockito.InOrder bootstrapOrder = org.mockito.Mockito.inOrder(
                subscriptionService, profileService, deviceService);
        bootstrapOrder.verify(subscriptionService).grant(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq("basic"), any());
        bootstrapOrder.verify(profileService).createFromTemplate(7L, "template-zixuan", "紫萱");
        bootstrapOrder.verify(deviceService).attachExistingDevice(7L, "profile-1", "\taabbccddeeff\t");

        verify(userDao, times(1)).insert(any(SysUserEntity.class));
        verify(templateService, times(1)).save(any(AgentTemplateEntity.class));
        verify(profileService, times(1)).createFromTemplate(7L, "template-zixuan", "紫萱");
        verify(subscriptionService, times(1)).grant(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq("basic"), any());
        verify(deviceService, times(2)).attachExistingDevice(7L, "profile-1", "\taabbccddeeff\t");
        assertEquals("hardware-device-123", registered.getId());
        assertEquals("chosen-profile", registered.getAgentId());
        assertTrue(PasswordUtils.matches(properties.getPassword(), user.get().getPassword()));
        assertFalse(user.get().getPassword().equals(properties.getPassword()));
        assertEquals(0, user.get().getSuperAdmin());
        assertEquals(1, user.get().getStatus());
        assertTrue(template.get().getSystemPrompt().contains("friend"));
        assertTrue(template.get().getSystemPrompt().contains("laugh"));
        assertTrue(template.get().getCompanionCueConfig().contains("hesitate"));
        assertEquals("custom-user-cue", profile.get().getCompanionCueConfig());
    }

    @Test
    void enabledBootstrapRejectsBlankRequiredProperties() {
        assertThrows(IllegalStateException.class,
                () -> validationService(properties(true, "", "SafePass9!", "aa:bb:cc:dd:ee:ff")).initialize());
        assertThrows(IllegalStateException.class,
                () -> validationService(properties(true, "companion-user", "", "aa:bb:cc:dd:ee:ff")).initialize());
        assertThrows(IllegalStateException.class,
                () -> validationService(properties(true, "companion-user", "SafePass9!", "")).initialize());
    }

    @Test
    void activePremiumSubscriptionIsRejectedWithoutReplacement() {
        CapacityFixture fixture = capacityFixture(0, 0, activeSubscription("premium", null));

        assertThrows(IllegalStateException.class, fixture.service()::initialize);

        verify(fixture.subscriptionService(), org.mockito.Mockito.never()).grant(any(), any(), any(), any());
        verify(fixture.profileService(), org.mockito.Mockito.never()).createFromTemplate(any(), any(), any());
        verify(fixture.deviceService(), org.mockito.Mockito.never()).attachExistingDevice(any(), any(), any());
    }

    @Test
    void scheduledActiveSubscriptionIsRejectedWithoutReplacement() {
        CompanionSubscriptionEntity scheduled = activeSubscription("basic",
                Date.from(Instant.now().plusSeconds(3600)));
        CapacityFixture fixture = capacityFixture(0, 0, scheduled);

        IllegalStateException error = assertThrows(IllegalStateException.class, fixture.service()::initialize);

        assertTrue(error.getMessage().contains("尚未生效"));
        verify(fixture.subscriptionService(), org.mockito.Mockito.never()).grant(any(), any(), any(), any());
    }

    @Test
    void basicProfileLimitCannotCreateZixuan() {
        CapacityFixture fixture = capacityFixture(3, 0);

        assertThrows(IllegalStateException.class, fixture.service()::initialize);

        verify(fixture.subscriptionService()).grant(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq("basic"), any());
        verify(fixture.profileService(), org.mockito.Mockito.never()).createFromTemplate(any(), any(), any());
        verify(fixture.deviceService(), org.mockito.Mockito.never()).attachExistingDevice(any(), any(), any());
    }

    @Test
    void usageAboveBasicLimitsFailsAfterBasicGrant() {
        CapacityFixture tooManyProfiles = capacityFixture(4, 0);
        CapacityFixture tooManyDevices = capacityFixture(0, 2);

        assertThrows(IllegalStateException.class, tooManyProfiles.service()::initialize);
        assertThrows(IllegalStateException.class, tooManyDevices.service()::initialize);

        verify(tooManyProfiles.subscriptionService()).grant(any(), any(), org.mockito.ArgumentMatchers.eq("basic"), any());
        verify(tooManyDevices.subscriptionService()).grant(any(), any(), org.mockito.ArgumentMatchers.eq("basic"), any());
        verify(tooManyProfiles.profileService(), org.mockito.Mockito.never()).createFromTemplate(any(), any(), any());
        verify(tooManyDevices.deviceService(), org.mockito.Mockito.never()).attachExistingDevice(any(), any(), any());
    }

    @Test
    void laterDeviceFailureRollsBackAllBootstrapWritesInRealTransaction() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:companion_bootstrap_tx_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE sys_user (id BIGINT PRIMARY KEY, username VARCHAR(64), password VARCHAR(128),"
                + " super_admin INT, status INT)");
        jdbc.execute("CREATE TABLE ai_companion_subscription (id VARCHAR(64) PRIMARY KEY, user_id BIGINT,"
                + " plan_id VARCHAR(32), status VARCHAR(16), starts_at TIMESTAMP, expires_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE ai_agent_template (id VARCHAR(64) PRIMARY KEY, agent_code VARCHAR(64),"
                + " companion_cue_config VARCHAR(1000))");
        jdbc.execute("CREATE TABLE ai_agent (id VARCHAR(64) PRIMARY KEY, user_id BIGINT,"
                + " companion_template_id VARCHAR(64), companion_enabled INT)");
        jdbc.execute("CREATE TABLE device_touch (id VARCHAR(64) PRIMARY KEY)");
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);

        CompanionBootstrapProperties properties = properties(
                true, "companion-user", "  SafePass9!  ", "AA-BB-CC-DD-EE-FF");
        SysUserDao userDao = h2UserDao(jdbc);
        CompanionPlanEntity basic = basicPlan();
        CompanionPlanDao planDao = mock(CompanionPlanDao.class);
        when(planDao.selectOne(any())).thenReturn(basic);
        when(planDao.selectByIdForUpdate("basic")).thenReturn(basic);
        CompanionSubscriptionDao subscriptionDao = mock(CompanionSubscriptionDao.class);
        when(subscriptionDao.selectOne(any())).thenAnswer(invocation -> jdbc.query(
                "SELECT id, user_id, plan_id, status, starts_at, expires_at"
                        + " FROM ai_companion_subscription WHERE user_id=7 AND status='active'",
                result -> result.next() ? subscription(result) : null));
        CompanionSubscriptionService subscriptionService = mock(CompanionSubscriptionService.class);
        doAnswer(invocation -> jdbc.update(
                "INSERT INTO ai_companion_subscription"
                        + " (id,user_id,plan_id,status,starts_at,expires_at) VALUES ('subscription-1',7,'basic','active',CURRENT_TIMESTAMP,DATEADD('DAY',36500,CURRENT_TIMESTAMP))"))
                .when(subscriptionService).grant(any(), any(), any(), any());
        AgentTemplateService templateService = h2TemplateService(jdbc);
        AgentDao agentDao = mock(AgentDao.class);
        when(agentDao.selectOne(any())).thenReturn(null);
        when(agentDao.selectCount(any())).thenReturn(0L);
        CompanionProfileService profileService = mock(CompanionProfileService.class);
        when(profileService.createFromTemplate(7L, "template-zixuan", "紫萱")).thenAnswer(invocation -> {
            jdbc.update("INSERT INTO ai_agent(id,user_id,companion_template_id,companion_enabled)"
                    + " VALUES ('profile-1',7,'template-zixuan',1)");
            return "profile-1";
        });
        DeviceService deviceTarget = mock(DeviceService.class);
        when(deviceTarget.selectCountByUserId(7L)).thenReturn(0L);
        doAnswer(invocation -> {
            jdbc.update("INSERT INTO device_touch(id) VALUES ('late-write')");
            throw new IllegalStateException("late device failure");
        }).when(deviceTarget).attachExistingDevice(any(), any(), any());
        DeviceService deviceService = mandatoryTransactionProxy(deviceTarget, transactionManager);

        CompanionBootstrapService service = new CompanionBootstrapService(properties, userDao, templateService,
                agentDao, planDao, subscriptionDao, subscriptionService, profileService, deviceService,
                securityManager(7L), transactionManager,
                Clock.fixed(Instant.parse("2026-07-29T04:00:00Z"), ZoneOffset.UTC));

        IllegalStateException error = assertThrows(IllegalStateException.class, service::initialize);

        assertEquals("late device failure", error.getMessage());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_companion_subscription", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent_template", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM device_touch", Integer.class));
    }

    private CompanionBootstrapService validationService(CompanionBootstrapProperties properties) {
        return new CompanionBootstrapService(properties, mock(SysUserDao.class),
                mock(AgentTemplateService.class), mock(AgentDao.class), mock(CompanionPlanDao.class),
                mock(CompanionSubscriptionDao.class), mock(CompanionSubscriptionService.class),
                mock(CompanionProfileService.class), mock(DeviceService.class), mock(SecurityManager.class),
                transactionManager(), Clock.systemUTC());
    }

    private SysUserDao h2UserDao(JdbcTemplate jdbc) {
        SysUserDao dao = mock(SysUserDao.class);
        when(dao.selectOne(any())).thenAnswer(invocation -> jdbc.query(
                "SELECT id,username,password,super_admin,status FROM sys_user WHERE username='companion-user'",
                result -> result.next() ? user(result) : null));
        when(dao.insert(any(SysUserEntity.class))).thenAnswer(invocation -> {
            SysUserEntity inserted = invocation.getArgument(0);
            inserted.setId(7L);
            return jdbc.update("INSERT INTO sys_user(id,username,password,super_admin,status) VALUES (?,?,?,?,?)",
                    inserted.getId(), inserted.getUsername(), inserted.getPassword(), inserted.getSuperAdmin(),
                    inserted.getStatus());
        });
        when(dao.selectByIdForUpdate(7L)).thenAnswer(invocation -> jdbc.query(
                "SELECT id,username,password,super_admin,status FROM sys_user WHERE id=7 FOR UPDATE",
                result -> result.next() ? user(result) : null));
        return dao;
    }

    private AgentTemplateService h2TemplateService(JdbcTemplate jdbc) {
        AgentTemplateService service = mock(AgentTemplateService.class);
        when(service.getOne(any())).thenAnswer(invocation -> jdbc.query(
                "SELECT id,agent_code,companion_cue_config FROM ai_agent_template WHERE agent_code='zixuan-companion'",
                result -> {
                    if (!result.next()) {
                        return null;
                    }
                    AgentTemplateEntity template = new AgentTemplateEntity();
                    template.setId(result.getString("id"));
                    template.setAgentCode(result.getString("agent_code"));
                    template.setCompanionCueConfig(result.getString("companion_cue_config"));
                    return template;
                }));
        when(service.getDefaultTemplate()).thenReturn(null);
        when(service.getNextAvailableSort()).thenReturn(1);
        when(service.save(any(AgentTemplateEntity.class))).thenAnswer(invocation -> {
            AgentTemplateEntity template = invocation.getArgument(0);
            return jdbc.update("INSERT INTO ai_agent_template(id,agent_code,companion_cue_config) VALUES (?,?,?)",
                    template.getId(), template.getAgentCode(), template.getCompanionCueConfig()) == 1;
        });
        when(service.updateById(any(AgentTemplateEntity.class))).thenAnswer(invocation -> {
            AgentTemplateEntity template = invocation.getArgument(0);
            return jdbc.update("UPDATE ai_agent_template SET companion_cue_config=? WHERE id=?",
                    template.getCompanionCueConfig(), template.getId()) == 1;
        });
        return service;
    }

    private SysUserEntity user(java.sql.ResultSet result) throws java.sql.SQLException {
        SysUserEntity user = new SysUserEntity();
        user.setId(result.getLong("id"));
        user.setUsername(result.getString("username"));
        user.setPassword(result.getString("password"));
        user.setSuperAdmin(result.getInt("super_admin"));
        user.setStatus(result.getInt("status"));
        return user;
    }

    private CompanionSubscriptionEntity subscription(java.sql.ResultSet result) throws java.sql.SQLException {
        CompanionSubscriptionEntity subscription = new CompanionSubscriptionEntity();
        subscription.setId(result.getString("id"));
        subscription.setUserId(result.getLong("user_id"));
        subscription.setPlanId(result.getString("plan_id"));
        subscription.setStatus(result.getString("status"));
        subscription.setStartsAt(result.getTimestamp("starts_at"));
        subscription.setExpiresAt(result.getTimestamp("expires_at"));
        return subscription;
    }

    private CompanionPlanEntity basicPlan() {
        CompanionPlanEntity basic = new CompanionPlanEntity();
        basic.setId("basic");
        basic.setPlanCode("basic");
        basic.setStatus(1);
        basic.setMaxDevices(1);
        basic.setMaxProfiles(3);
        return basic;
    }

    private DeviceService mandatoryTransactionProxy(DeviceService target,
            PlatformTransactionManager transactionManager) {
        RuleBasedTransactionAttribute attribute = new RuleBasedTransactionAttribute();
        attribute.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
        NameMatchTransactionAttributeSource source = new NameMatchTransactionAttributeSource();
        source.addTransactionalMethod("attachExistingDevice", attribute);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(source);
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvice(interceptor);
        return (DeviceService) proxyFactory.getProxy();
    }

    private CapacityFixture capacityFixture(long profileCount, long deviceCount) {
        return capacityFixture(profileCount, deviceCount, null);
    }

    private CapacityFixture capacityFixture(long profileCount, long deviceCount,
            CompanionSubscriptionEntity subscription) {
        CompanionBootstrapProperties properties = properties(
                true, "companion-user", "SafePass9!", "aa:bb:cc:dd:ee:ff");
        SysUserDao userDao = mock(SysUserDao.class);
        SysUserEntity user = new SysUserEntity();
        user.setId(7L);
        user.setUsername(properties.getUsername());
        user.setPassword(PasswordUtils.encode(properties.getPassword()));
        user.setSuperAdmin(0);
        user.setStatus(1);
        when(userDao.selectOne(any())).thenReturn(user);
        when(userDao.selectByIdForUpdate(7L)).thenReturn(user);
        CompanionPlanDao planDao = mock(CompanionPlanDao.class);
        CompanionPlanEntity basic = new CompanionPlanEntity();
        basic.setId("basic");
        basic.setPlanCode("basic");
        basic.setStatus(1);
        basic.setMaxProfiles(3);
        basic.setMaxDevices(1);
        when(planDao.selectOne(any())).thenReturn(basic);
        when(planDao.selectByIdForUpdate("basic")).thenReturn(basic);
        CompanionSubscriptionDao subscriptionDao = mock(CompanionSubscriptionDao.class);
        when(subscriptionDao.selectOne(any())).thenReturn(subscription);
        CompanionSubscriptionService subscriptionService = mock(CompanionSubscriptionService.class);
        AgentDao agentDao = mock(AgentDao.class);
        when(agentDao.selectCount(any())).thenReturn(profileCount);
        CompanionProfileService profileService = mock(CompanionProfileService.class);
        DeviceService deviceService = mock(DeviceService.class);
        when(deviceService.selectCountByUserId(7L)).thenReturn(deviceCount);
        CompanionBootstrapService service = new CompanionBootstrapService(properties, userDao,
                mock(AgentTemplateService.class), agentDao, planDao, subscriptionDao, subscriptionService,
                profileService, deviceService, securityManager(7L), transactionManager(), Clock.systemUTC());
        return new CapacityFixture(service, subscriptionService, profileService, deviceService);
    }

    private CompanionSubscriptionEntity activeSubscription(String planId, Date startsAt) {
        CompanionSubscriptionEntity subscription = new CompanionSubscriptionEntity();
        subscription.setPlanId(planId);
        subscription.setStatus(CompanionSubscriptionEntity.STATUS_ACTIVE);
        subscription.setStartsAt(startsAt == null ? Date.from(Instant.now().minusSeconds(60)) : startsAt);
        subscription.setExpiresAt(Date.from(Instant.now().plusSeconds(3600)));
        return subscription;
    }

    private record CapacityFixture(CompanionBootstrapService service,
            CompanionSubscriptionService subscriptionService,
            CompanionProfileService profileService,
            DeviceService deviceService) {
    }

    private CompanionBootstrapProperties properties(boolean enabled, String username, String password, String deviceMac) {
        CompanionBootstrapProperties properties = new CompanionBootstrapProperties();
        properties.setEnabled(enabled);
        properties.setUsername(username);
        properties.setPassword(password);
        properties.setDeviceMac(deviceMac);
        return properties;
    }

    private PlatformTransactionManager transactionManager() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return manager;
    }

    private SecurityManager securityManager(Long userId) {
        SecurityManager manager = mock(SecurityManager.class);
        Subject subject = mock(Subject.class);
        UserDetail principal = new UserDetail();
        principal.setId(userId);
        when(subject.getPrincipal()).thenReturn(principal);
        when(manager.createSubject(any())).thenReturn(subject);
        return manager;
    }
}
