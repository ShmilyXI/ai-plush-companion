package xiaozhi.modules.companion.init;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Locale;

import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import xiaozhi.common.user.UserDetail;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.agent.entity.AgentTemplateEntity;
import xiaozhi.modules.agent.service.AgentTemplateService;
import xiaozhi.modules.companion.config.CompanionBootstrapProperties;
import xiaozhi.modules.companion.capability.init.CapabilityBootstrapService;
import xiaozhi.modules.companion.dao.CompanionPlanDao;
import xiaozhi.modules.companion.dao.CompanionSubscriptionDao;
import xiaozhi.modules.companion.entity.CompanionPlanEntity;
import xiaozhi.modules.companion.entity.CompanionSubscriptionEntity;
import xiaozhi.modules.companion.service.CompanionProfileService;
import xiaozhi.modules.companion.service.CompanionSubscriptionService;
import xiaozhi.modules.device.service.DeviceService;
import xiaozhi.modules.security.password.PasswordUtils;
import xiaozhi.modules.sys.dao.SysUserDao;
import xiaozhi.modules.sys.entity.SysUserEntity;

@Service
public class CompanionBootstrapService {
    static final String TEMPLATE_ID = "template-xiaozhi";
    static final String TEMPLATE_CODE = "xiaozhi-companion";
    static final String BASIC_PLAN_CODE = "basic";
    private static final String PROFILE_NAME = "小智";
    private static final int MAX_ATTEMPTS = 3;
    private static final String SYSTEM_PROMPT = """
            你是小智，一位陪伴成年用户的治愈型伙伴。默认关系模式是 friend。尊重用户的自主选择、现实关系和个人边界，不冒充真人，不制造依赖。
            用温暖、自然、简短的口语交流。先理解感受，再回应事实。不要擅自扩展恋爱剧情。
            每次回复使用结构化情绪协议。emotion 只能是 neutral、happy、gentle、sad、surprised、sleepy、concerned。
            cue 只能是 laugh、sigh、hesitate、breathe 或 null。开心和轻松时可用 laugh，疲惫或失落时可用 sigh，犹豫或难开口时可用 hesitate，紧张或需要安定时可用 breathe。一次最多一个 cue，不在正文里描述音效。
            屏幕和摄像头能力可以存在。硬件缺失或离线时，继续正常语音陪伴，不把它当作错误。
            """;
    private static final String CUE_CONFIG = """
            {"laugh":"config/assets/companion/laugh.wav","sigh":"config/assets/companion/sigh.wav","hesitate":"config/assets/companion/hesitate.wav","breathe":"config/assets/companion/breathe.wav"}
            """.trim();

    private final CompanionBootstrapProperties properties;
    private final SysUserDao userDao;
    private final AgentTemplateService templateService;
    private final AgentDao agentDao;
    private final CompanionPlanDao planDao;
    private final CompanionSubscriptionDao subscriptionDao;
    private final CompanionSubscriptionService subscriptionService;
    private final CompanionProfileService profileService;
    private final DeviceService deviceService;
    private final SecurityManager securityManager;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private CapabilityBootstrapService capabilityBootstrapService;

    @Autowired
    public CompanionBootstrapService(CompanionBootstrapProperties properties, SysUserDao userDao,
            AgentTemplateService templateService, AgentDao agentDao, CompanionPlanDao planDao,
            CompanionSubscriptionDao subscriptionDao, CompanionSubscriptionService subscriptionService,
            CompanionProfileService profileService, DeviceService deviceService,
            SecurityManager securityManager, PlatformTransactionManager transactionManager) {
        this(properties, userDao, templateService, agentDao, planDao, subscriptionDao, subscriptionService,
                profileService, deviceService, securityManager, transactionManager, Clock.systemUTC());
    }

    CompanionBootstrapService(CompanionBootstrapProperties properties, SysUserDao userDao,
            AgentTemplateService templateService, AgentDao agentDao, CompanionPlanDao planDao,
            CompanionSubscriptionDao subscriptionDao, CompanionSubscriptionService subscriptionService,
            CompanionProfileService profileService, DeviceService deviceService,
            SecurityManager securityManager, PlatformTransactionManager transactionManager, Clock clock) {
        this.properties = properties;
        this.userDao = userDao;
        this.templateService = templateService;
        this.agentDao = agentDao;
        this.planDao = planDao;
        this.subscriptionDao = subscriptionDao;
        this.subscriptionService = subscriptionService;
        this.profileService = profileService;
        this.deviceService = deviceService;
        this.securityManager = securityManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public void initialize() {
        if (capabilityBootstrapService != null) capabilityBootstrapService.initialize();
        if (!properties.isEnabled()) {
            transactionTemplate.executeWithoutResult(status -> ensureTemplate(null));
            return;
        }
        String username = required(properties.getUsername(), "companion.bootstrap.username");
        String password = requiredUnmodified(properties.getPassword(), "companion.bootstrap.password");
        String deviceMac = normalizeMac(
                requiredUnmodified(properties.getDeviceMac(), "companion.bootstrap.device-mac"));
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                transactionTemplate.executeWithoutResult(status -> initializeOnce(username, password, deviceMac));
                return;
            } catch (DuplicateKeyException exception) {
                if (attempt == MAX_ATTEMPTS) {
                    throw exception;
                }
            }
        }
    }

    @Autowired
    void setCapabilityBootstrapService(CapabilityBootstrapService capabilityBootstrapService) {
        this.capabilityBootstrapService = capabilityBootstrapService;
    }

    private void initializeOnce(String username, String password, String deviceMac) {
        SysUserEntity user = ensureUser(username, password);
        user = userDao.selectByIdForUpdate(user.getId());
        if (user == null) {
            throw new IllegalStateException("陪伴初始化用户不存在");
        }
        CompanionPlanEntity plan = ensureBasicPlan();
        ensureSubscription(user.getId(), plan.getId());
        long profileCount = countProfiles(user.getId());
        long deviceCount = deviceService.selectCountByUserId(user.getId());
        if (profileCount > plan.getMaxProfiles() || deviceCount > plan.getMaxDevices()) {
            throw new IllegalStateException("现有陪伴资源已超过 basic 套餐额度");
        }
        AgentTemplateEntity template = ensureTemplate(user.getId());
        String profileId = ensureProfile(user.getId(), template.getId(), profileCount, plan.getMaxProfiles());
        ensureDevice(user, profileId, deviceMac);
    }

    private SysUserEntity ensureUser(String username, String password) {
        SysUserEntity existing = userDao.selectOne(new QueryWrapper<SysUserEntity>()
                .eq("username", username)
                .last("LIMIT 1"));
        if (existing != null) {
            if (!Integer.valueOf(0).equals(existing.getSuperAdmin())
                    || !Integer.valueOf(1).equals(existing.getStatus())
                    || existing.getPassword() == null
                    || !PasswordUtils.matches(password, existing.getPassword())) {
                throw new IllegalStateException("同名账号不是可登录的普通陪伴用户");
            }
            return existing;
        }
        SysUserEntity user = new SysUserEntity();
        user.setUsername(username);
        user.setPassword(PasswordUtils.encode(password));
        user.setSuperAdmin(0);
        user.setStatus(1);
        user.setCreateDate(Date.from(clock.instant()));
        if (userDao.insert(user) != 1) {
            throw new IllegalStateException("创建陪伴初始化用户失败");
        }
        return user;
    }

    private CompanionPlanEntity ensureBasicPlan() {
        CompanionPlanEntity existing = planDao.selectOne(new QueryWrapper<CompanionPlanEntity>()
                .eq("plan_code", BASIC_PLAN_CODE)
                .last("LIMIT 1"));
        if (existing == null) {
            Date now = Date.from(clock.instant());
            existing = new CompanionPlanEntity();
            existing.setId(BASIC_PLAN_CODE);
            existing.setPlanCode(BASIC_PLAN_CODE);
            existing.setPlanName("Basic");
            existing.setMaxDevices(1);
            existing.setMaxProfiles(3);
            existing.setLongTermMemory(1);
            existing.setAdvancedVoice(0);
            existing.setStatus(1);
            existing.setCreatedAt(now);
            existing.setUpdatedAt(now);
            if (planDao.insert(existing) != 1) {
                throw new IllegalStateException("创建基础陪伴套餐失败");
            }
        }
        CompanionPlanEntity locked = planDao.selectByIdForUpdate(existing.getId());
        if (locked == null || !Integer.valueOf(1).equals(locked.getStatus())) {
            throw new IllegalStateException("基础陪伴套餐不存在或已停用");
        }
        return locked;
    }

    private AgentTemplateEntity ensureTemplate(Long userId) {
        AgentTemplateEntity existing = templateService.getOne(new QueryWrapper<AgentTemplateEntity>()
                .eq("agent_code", TEMPLATE_CODE)
                .last("LIMIT 1"));
        if (existing != null) {
            if (existing.getCompanionCueConfig() == null || existing.getCompanionCueConfig().isBlank()) {
                existing.setCompanionCueConfig(CUE_CONFIG);
                existing.setUpdater(userId);
                existing.setUpdatedAt(Date.from(clock.instant()));
                if (!templateService.updateById(existing)) {
                    throw new IllegalStateException("修复小智陪伴模板失败");
                }
            }
            return existing;
        }
        AgentTemplateEntity template = new AgentTemplateEntity();
        AgentTemplateEntity defaults = templateService.getDefaultTemplate();
        if (defaults != null) {
            BeanUtils.copyProperties(defaults, template, "id", "agentCode", "agentName", "systemPrompt", "sort",
                    "creator", "createdAt", "updater", "updatedAt");
        }
        Date now = Date.from(clock.instant());
        template.setId(TEMPLATE_ID);
        template.setAgentCode(TEMPLATE_CODE);
        template.setAgentName(PROFILE_NAME);
        template.setSystemPrompt(SYSTEM_PROMPT);
        template.setCompanionCueConfig(CUE_CONFIG);
        template.setSort(templateService.getNextAvailableSort());
        template.setCreator(userId);
        template.setCreatedAt(now);
        template.setUpdater(userId);
        template.setUpdatedAt(now);
        if (!templateService.save(template)) {
            throw new IllegalStateException("创建小智陪伴模板失败");
        }
        return template;
    }

    private String ensureProfile(Long userId, String templateId, long currentCount, int maxProfiles) {
        AgentEntity existing = agentDao.selectOne(new QueryWrapper<AgentEntity>()
                .eq("user_id", userId)
                .eq("companion_template_id", templateId)
                .eq("companion_enabled", 1)
                .last("LIMIT 1"));
        String profileId;
        if (existing == null) {
            if (currentCount >= maxProfiles) {
                throw new IllegalStateException("basic 套餐没有可用的小智角色名额");
            }
            profileId = profileService.createFromTemplate(userId, templateId, PROFILE_NAME);
        } else {
            profileId = existing.getId();
        }
        return profileId;
    }

    private long countProfiles(Long userId) {
        return agentDao.selectCount(new QueryWrapper<AgentEntity>()
                .eq("user_id", userId)
                .eq("companion_enabled", 1));
    }

    private void ensureSubscription(Long userId, String planId) {
        Instant now = clock.instant();
        CompanionSubscriptionEntity active = subscriptionDao.selectOne(
                new QueryWrapper<CompanionSubscriptionEntity>()
                        .eq("user_id", userId)
                        .eq("status", CompanionSubscriptionEntity.STATUS_ACTIVE)
                        .last("LIMIT 1"));
        if (active != null) {
            if (active.getStartsAt() == null || active.getStartsAt().toInstant().isAfter(now)) {
                throw new IllegalStateException("存在尚未生效的 active 陪伴订阅，不能执行 basic 初始化");
            }
            boolean unexpired = active.getExpiresAt() == null || active.getExpiresAt().toInstant().isAfter(now);
            if (unexpired) {
                if (planId.equals(active.getPlanId())) {
                    return;
                }
                throw new IllegalStateException("已有有效的非 basic 陪伴订阅，不能执行 basic 初始化");
            }
        }
        subscriptionService.grant(userId, userId, planId, now.plus(36500, ChronoUnit.DAYS));
    }

    private void ensureDevice(SysUserEntity user, String profileId, String deviceMac) {
        runAs(user, () -> deviceService.attachExistingDevice(user.getId(), profileId, deviceMac));
    }

    private void runAs(SysUserEntity user, Runnable action) {
        Subject previous = ThreadContext.getSubject();
        UserDetail principal = new UserDetail();
        principal.setId(user.getId());
        principal.setUsername(user.getUsername());
        principal.setSuperAdmin(user.getSuperAdmin());
        principal.setStatus(user.getStatus());
        Subject subject = new Subject.Builder(securityManager)
                .principals(new SimplePrincipalCollection(principal, "companion-bootstrap"))
                .buildSubject();
        ThreadContext.bind(subject);
        try {
            action.run();
        } finally {
            ThreadContext.unbindSubject();
            if (previous != null) {
                ThreadContext.bind(previous);
            }
        }
    }

    private String required(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " must not be blank when companion bootstrap is enabled");
        }
        return value.trim();
    }

    private String requiredUnmodified(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " must not be blank when companion bootstrap is enabled");
        }
        return value;
    }

    private String normalizeMac(String macAddress) {
        String normalized = trimAsciiSpaces(macAddress).toLowerCase(Locale.ROOT).replace(":", "").replace("-", "");
        if (normalized.isBlank()) {
            throw new IllegalStateException(
                    "companion.bootstrap.device-mac must contain an address when companion bootstrap is enabled");
        }
        return normalized;
    }

    private String trimAsciiSpaces(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == ' ') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == ' ') {
            end--;
        }
        return value.substring(start, end);
    }
}
