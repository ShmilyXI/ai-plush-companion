package zixuan.modules.companion.init;

import java.util.List;

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
                        && template.getSystemPrompt().contains("治愈型伙伴")));
    }

    @Test
    void niangaoKeepsTheDefaultVoiceWhenDoubaoIsNotEnabled() {
        AgentTemplateService templates = mock(AgentTemplateService.class);
        AgentTemplateEntity defaults = new AgentTemplateEntity();
        defaults.setTtsModelId("TTS_EdgeTTS");
        defaults.setTtsVoiceId("TTS_EdgeTTS0001");
        when(templates.getOne(any())).thenReturn(null);
        when(templates.getDefaultTemplate()).thenReturn(defaults);
        when(templates.getNextAvailableSort()).thenReturn(1);
        when(templates.save(any(AgentTemplateEntity.class))).thenReturn(true);
        zixuan.modules.timbre.dao.TimbreDao voices = mock(zixuan.modules.timbre.dao.TimbreDao.class);
        zixuan.modules.timbre.entity.TimbreEntity doubaoVoice = new zixuan.modules.timbre.entity.TimbreEntity();
        doubaoVoice.setTtsModelId("TTS_DoubaoSeedTTS2");
        when(voices.selectById("TTS_DoubaoSeedTTS2_029")).thenReturn(doubaoVoice);
        zixuan.modules.model.dao.ModelConfigDao models = mock(zixuan.modules.model.dao.ModelConfigDao.class);
        zixuan.modules.model.entity.ModelConfigEntity doubao = new zixuan.modules.model.entity.ModelConfigEntity();
        doubao.setModelType("TTS");
        doubao.setIsEnabled(0);
        when(models.selectById("TTS_DoubaoSeedTTS2")).thenReturn(doubao);
        CompanionBootstrapService service = new CompanionBootstrapService(properties(false, null, null, null),
                mock(SysUserDao.class), templates, mock(AgentDao.class), mock(CompanionPlanDao.class),
                mock(CompanionSubscriptionDao.class), mock(CompanionSubscriptionService.class),
                mock(CompanionProfileService.class), mock(DeviceService.class), mock(SecurityManager.class),
                transactionManager(), Clock.systemUTC());
        service.setTimbreDao(voices);
        service.setModelConfigDao(models);

        service.initialize();

        org.mockito.ArgumentCaptor<AgentTemplateEntity> saved =
                org.mockito.ArgumentCaptor.forClass(AgentTemplateEntity.class);
        verify(templates, org.mockito.Mockito.times(CompanionPresetRoles.ALL.size())).save(saved.capture());
        AgentTemplateEntity niangao = saved.getAllValues().stream()
                .filter(item -> "niangao-companion".equals(item.getAgentCode())).findFirst().orElseThrow();
        // 豆包没启用：不换模型，沿用默认模板的音色。
        org.junit.jupiter.api.Assertions.assertEquals("TTS_EdgeTTS", niangao.getTtsModelId());
        org.junit.jupiter.api.Assertions.assertEquals("TTS_EdgeTTS0001", niangao.getTtsVoiceId());
    }

    @Test
    void everyPresetRoleGetsItsOwnTemplateAndAnExistingOneIsLeftAlone() {
        AgentTemplateService templates = mock(AgentTemplateService.class);
        AgentTemplateEntity defaults = new AgentTemplateEntity();
        defaults.setTtsModelId("TTS_EdgeTTS");
        defaults.setTtsVoiceId("TTS_EdgeTTS0001");
        defaults.setLlmModelId("llm-default");
        AgentTemplateEntity edited = new AgentTemplateEntity();
        edited.setId("template-zixuan");
        edited.setSystemPrompt("运营改过的人设");
        // 紫萱的模板已经存在，其余的还没有。
        java.util.concurrent.atomic.AtomicInteger lookups = new java.util.concurrent.atomic.AtomicInteger();
        when(templates.getOne(any())).thenAnswer(invocation -> lookups.getAndIncrement() == 0 ? edited : null);
        when(templates.getDefaultTemplate()).thenReturn(defaults);
        java.util.concurrent.atomic.AtomicInteger sort = new java.util.concurrent.atomic.AtomicInteger(7);
        when(templates.getNextAvailableSort()).thenAnswer(invocation -> sort.getAndIncrement());
        when(templates.save(any(AgentTemplateEntity.class))).thenReturn(true);
        zixuan.modules.timbre.dao.TimbreDao voices = mock(zixuan.modules.timbre.dao.TimbreDao.class);
        zixuan.modules.timbre.entity.TimbreEntity edgeVoice = new zixuan.modules.timbre.entity.TimbreEntity();
        edgeVoice.setTtsModelId("TTS_EdgeTTS");
        // 云希、晓伊在这个部署里存在；枫糖的双语音色艾玛没有，沿用默认音色。
        when(voices.selectById("TTS_EdgeTTS0003")).thenReturn(edgeVoice);
        when(voices.selectById("TTS_EdgeTTS0005")).thenReturn(edgeVoice);
        // 年糕写明了用豆包语音的甜美悦悦：豆包启用着、音色也在，就连模型一起换过去。
        zixuan.modules.timbre.entity.TimbreEntity doubaoVoice = new zixuan.modules.timbre.entity.TimbreEntity();
        doubaoVoice.setTtsModelId("TTS_DoubaoSeedTTS2");
        when(voices.selectById("TTS_DoubaoSeedTTS2_029")).thenReturn(doubaoVoice);
        zixuan.modules.model.dao.ModelConfigDao models = mock(zixuan.modules.model.dao.ModelConfigDao.class);
        zixuan.modules.model.entity.ModelConfigEntity doubao = new zixuan.modules.model.entity.ModelConfigEntity();
        doubao.setModelType("TTS");
        doubao.setIsEnabled(1);
        when(models.selectById("TTS_DoubaoSeedTTS2")).thenReturn(doubao);
        CompanionBootstrapService service = new CompanionBootstrapService(properties(false, null, null, null),
                mock(SysUserDao.class), templates, mock(AgentDao.class), mock(CompanionPlanDao.class),
                mock(CompanionSubscriptionDao.class), mock(CompanionSubscriptionService.class),
                mock(CompanionProfileService.class), mock(DeviceService.class), mock(SecurityManager.class),
                transactionManager(), Clock.systemUTC());
        service.setTimbreDao(voices);
        service.setModelConfigDao(models);

        service.initialize();

        org.mockito.ArgumentCaptor<AgentTemplateEntity> saved =
                org.mockito.ArgumentCaptor.forClass(AgentTemplateEntity.class);
        verify(templates, org.mockito.Mockito.times(CompanionPresetRoles.ALL.size() - 1)).save(saved.capture());
        java.util.function.Function<String, AgentTemplateEntity> byCode = code -> saved.getAllValues().stream()
                .filter(item -> code.equals(item.getAgentCode())).findFirst().orElseThrow();
        AgentTemplateEntity ache = byCode.apply("ache-companion");
        org.junit.jupiter.api.Assertions.assertEquals("阿澈", ache.getAgentName());
        org.junit.jupiter.api.Assertions.assertEquals("TTS_EdgeTTS0005", ache.getTtsVoiceId());
        org.junit.jupiter.api.Assertions.assertEquals("llm-default", ache.getLlmModelId());
        org.junit.jupiter.api.Assertions.assertTrue(ache.getSystemPrompt().contains("结构化情绪协议"));
        org.junit.jupiter.api.Assertions.assertEquals("TTS_EdgeTTS0003", byCode.apply("xiaoman-companion").getTtsVoiceId());
        AgentTemplateEntity niangao = byCode.apply("niangao-companion");
        org.junit.jupiter.api.Assertions.assertEquals("年糕", niangao.getAgentName());
        org.junit.jupiter.api.Assertions.assertEquals("TTS_DoubaoSeedTTS2", niangao.getTtsModelId());
        org.junit.jupiter.api.Assertions.assertEquals("TTS_DoubaoSeedTTS2_029", niangao.getTtsVoiceId());
        // 其余预设还是默认语音合成（Edge）下的音色。
        org.junit.jupiter.api.Assertions.assertEquals("TTS_EdgeTTS", byCode.apply("xiaoman-companion").getTtsModelId());
        AgentTemplateEntity jiangyu = byCode.apply("jiangyu-companion");
        org.junit.jupiter.api.Assertions.assertEquals("江屿", jiangyu.getAgentName());
        org.junit.jupiter.api.Assertions.assertEquals("TTS_EdgeTTS0005", jiangyu.getTtsVoiceId());
        AgentTemplateEntity xiaole = byCode.apply("xiaole-companion");
        org.junit.jupiter.api.Assertions.assertEquals("小乐", xiaole.getAgentName());
        org.junit.jupiter.api.Assertions.assertTrue(xiaole.getSystemPrompt().contains("《老街坊故事集》"));
        org.junit.jupiter.api.Assertions.assertEquals("晚舟", byCode.apply("wanzhou-companion").getAgentName());
        org.junit.jupiter.api.Assertions.assertEquals("秋白", byCode.apply("qiubai-companion").getAgentName());
        org.junit.jupiter.api.Assertions.assertEquals("苏念", byCode.apply("sunian-companion").getAgentName());
        AgentTemplateEntity maple = byCode.apply("maple-companion");
        org.junit.jupiter.api.Assertions.assertEquals("枫糖", maple.getAgentName());
        org.junit.jupiter.api.Assertions.assertEquals("TTS_EdgeTTS0001", maple.getTtsVoiceId());
        org.junit.jupiter.api.Assertions.assertEquals("运营改过的人设", edited.getSystemPrompt());
    }

    @Test
    void presetRolesAreDescribedForThePickerAndStayWithinTheSharedRules() {
        org.junit.jupiter.api.Assertions.assertEquals(12, CompanionPresetRoles.ALL.size());
        // 两个 Mowa Bot 角色模板排在最后，用豆包语音自己的音色。
        org.junit.jupiter.api.Assertions.assertEquals(List.of("nuonuo-companion", "bibi-companion"),
                CompanionPresetRoles.ALL.subList(10, 12).stream().map(CompanionPresetRoles.Preset::code).toList());
        org.junit.jupiter.api.Assertions.assertEquals("TTS_DoubaoSeedTTS2", CompanionPresetRoles.NUONUO.ttsModelId());
        org.junit.jupiter.api.Assertions.assertEquals("TTS_DoubaoSeedTTS2_392", CompanionPresetRoles.NUONUO.voiceId());
        org.junit.jupiter.api.Assertions.assertEquals("TTS_DoubaoSeedTTS2_053", CompanionPresetRoles.BIBI.voiceId());
        org.junit.jupiter.api.Assertions.assertEquals(CompanionPresetRoles.ALL.size(),
                CompanionPresetRoles.ALL.stream().map(CompanionPresetRoles.Preset::code).distinct().count());
        for (CompanionPresetRoles.Preset preset : CompanionPresetRoles.ALL) {
            org.junit.jupiter.api.Assertions.assertTrue(preset.code().contains("companion"),
                    "the template list only shows codes that contain companion");
            org.junit.jupiter.api.Assertions.assertFalse(preset.tagline().isBlank());
            org.junit.jupiter.api.Assertions.assertEquals(preset.tagline(), CompanionPresetRoles.taglineOf(preset.code()));
            // 恋人角色只对成年人以恋人身份相处；枫糖面向包括未成年学生在内的英语学习者，不写“陪伴成年用户”。
            boolean lover = "lover".equals(CompanionPresetRoles.relationModeOf(preset.code()));
            boolean studyBuddyForAllAges = CompanionPresetRoles.MAPLE.code().equals(preset.code());
            String[] rules = lover
                    ? new String[] { "不冒充真人", "不制造依赖", "结构化情绪协议", "关系模式是 lover", "未满 18 岁",
                            "不写性内容", "不吃醋" }
                    : studyBuddyForAllAges
                            ? new String[] { "未成年人", "不冒充真人", "不制造依赖", "不要擅自扩展恋爱剧情", "结构化情绪协议" }
                            : new String[] { "陪伴成年用户", "不冒充真人", "不制造依赖", "不要擅自扩展恋爱剧情", "结构化情绪协议" };
            for (String rule : rules) {
                org.junit.jupiter.api.Assertions.assertTrue(preset.systemPrompt().contains(rule), preset.name() + " must keep: " + rule);
            }
            if (studyBuddyForAllAges) {
                org.junit.jupiter.api.Assertions.assertFalse(preset.systemPrompt().contains("陪伴成年用户"),
                        "枫糖面向含未成年人在内的学习者，不应再自相矛盾地写“陪伴成年用户”");
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(CompanionPresetRoles.WANZHOU.systemPrompt().contains("男店主"));
        org.junit.jupiter.api.Assertions.assertFalse(CompanionPresetRoles.QIUBAI.systemPrompt().toLowerCase().contains("chief of staff"));
        org.junit.jupiter.api.Assertions.assertEquals("", CompanionPresetRoles.taglineOf("unknown-role"));
    }

    @Test
    void suNianAndJiangYuAreTheAdultsOnlyLovers() {
        org.junit.jupiter.api.Assertions.assertEquals("lover", CompanionPresetRoles.relationModeOf(CompanionPresetRoles.SUNIAN.code()));
        org.junit.jupiter.api.Assertions.assertEquals("lover", CompanionPresetRoles.relationModeOf(CompanionPresetRoles.JIANGYU.code()));
        org.junit.jupiter.api.Assertions.assertEquals("friend", CompanionPresetRoles.relationModeOf(CompanionPresetRoles.ZIXUAN.code()));
        org.junit.jupiter.api.Assertions.assertEquals("friend", CompanionPresetRoles.relationModeOf("unknown-role"));
        CompanionPresetRoles.Preset sunian = CompanionPresetRoles.SUNIAN;
        org.junit.jupiter.api.Assertions.assertEquals("template-sunian", sunian.id());
        org.junit.jupiter.api.Assertions.assertEquals("TTS_EdgeTTS0001", sunian.voiceId());
        for (String rule : new String[] { "普通朋友", "这句我要记下来", "去吧，回来再跟我说" }) {
            org.junit.jupiter.api.Assertions.assertTrue(sunian.systemPrompt().contains(rule), "苏念 must keep: " + rule);
        }
        CompanionPresetRoles.Preset jiangyu = CompanionPresetRoles.JIANGYU;
        org.junit.jupiter.api.Assertions.assertEquals("template-jiangyu", jiangyu.id());
        org.junit.jupiter.api.Assertions.assertEquals("jiangyu-companion", jiangyu.code());
        for (String rule : new String[] { "只在用户是成年人时以恋人身份相处", "不阻止用户离开或休息" }) {
            org.junit.jupiter.api.Assertions.assertTrue(jiangyu.systemPrompt().contains(rule), "江屿 must keep: " + rule);
        }
    }

    @Test
    void qiubaiIsAFemaleAssistantThatDoesNotClaimActionsItCannotTake() {
        CompanionPresetRoles.Preset qiubai = CompanionPresetRoles.QIUBAI;
        org.junit.jupiter.api.Assertions.assertEquals("template-qiubai", qiubai.id());
        org.junit.jupiter.api.Assertions.assertEquals("qiubai-companion", qiubai.code());
        org.junit.jupiter.api.Assertions.assertTrue(CompanionPresetRoles.ALL.contains(qiubai));
        for (String rule : new String[] { "已记下", "第一、第二、第三", "设备提供了工具时才使用", "不编造已经完成的操作",
                "不假装设了闹钟", "不给医疗、法律、投资方面的确定结论", "AI 助理" }) {
            org.junit.jupiter.api.Assertions.assertTrue(qiubai.systemPrompt().contains(rule), "must keep: " + rule);
        }
        org.junit.jupiter.api.Assertions.assertFalse(qiubai.systemPrompt().contains("管家"));
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
        assertFalse(template.get().getSystemPrompt().contains("cue"));
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
                + " system_prompt VARCHAR(2000))");
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
                "SELECT id,agent_code,system_prompt FROM ai_agent_template WHERE agent_code='zixuan-companion'",
                result -> {
                    if (!result.next()) {
                        return null;
                    }
                    AgentTemplateEntity template = new AgentTemplateEntity();
                    template.setId(result.getString("id"));
                    template.setAgentCode(result.getString("agent_code"));
                    template.setSystemPrompt(result.getString("system_prompt"));
                    return template;
                }));
        when(service.getDefaultTemplate()).thenReturn(null);
        when(service.getNextAvailableSort()).thenReturn(1);
        when(service.save(any(AgentTemplateEntity.class))).thenAnswer(invocation -> {
            AgentTemplateEntity template = invocation.getArgument(0);
            return jdbc.update("INSERT INTO ai_agent_template(id,agent_code,system_prompt) VALUES (?,?,?)",
                    template.getId(), template.getAgentCode(), template.getSystemPrompt()) == 1;
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
