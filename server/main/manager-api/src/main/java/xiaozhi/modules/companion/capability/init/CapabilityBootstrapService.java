package xiaozhi.modules.companion.capability.init;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import xiaozhi.modules.companion.capability.dao.CapabilityDao;
import xiaozhi.modules.companion.capability.dto.CapabilitySaveDTO;
import xiaozhi.modules.companion.capability.dto.PluginDefinitionDTO;
import xiaozhi.modules.companion.capability.dto.SkillToolDTO;
import xiaozhi.modules.companion.capability.dto.SkillTriggerDTO;
import xiaozhi.modules.companion.capability.entity.CapabilityEntity;
import xiaozhi.modules.companion.capability.service.CapabilityService;
import xiaozhi.modules.companion.capability.service.SkillPackageService;

@Service
public class CapabilityBootstrapService {
    private static final long SYSTEM_OPERATOR = 0L;

    private final CapabilityDao capabilityDao;
    private final CapabilityService capabilities;
    private LegacyPluginCapabilityMigrationService legacyMigration;
    private LegacySkillPackageMigrationService legacySkillPackageMigration;
    private LegacyAgentSkillBindingMigrationService legacyAgentSkillBindingMigration;
    private SkillPackageService skillPackages;

    public CapabilityBootstrapService(CapabilityDao capabilityDao, CapabilityService capabilities) {
        this.capabilityDao = capabilityDao;
        this.capabilities = capabilities;
    }

    @Transactional(rollbackFor = Exception.class)
    public void initialize() {
        ensure("plugin-weather", "PLUGIN", "天气查询 Plugin", "服务端天气查询执行器", weatherPlugin());
        ensure("plugin-news", "PLUGIN", "新闻查询 Plugin", "服务端新闻聚合执行器", newsPlugin());
        ensure("plugin-web-search", "PLUGIN", "联网搜索 Plugin", "服务端联网搜索执行器", searchPlugin());
        ensure("skill-weather", "SKILL", "天气查询", "查询指定地区的天气", weatherSkill());
        ensure("skill-news", "SKILL", "新闻查询", "查询近期新闻和热点", newsSkill());
        ensure("skill-web-search", "SKILL", "联网搜索", "搜索需要联网获取的信息", searchSkill());
        if (legacyMigration != null) legacyMigration.migrate();
        if (legacySkillPackageMigration != null) legacySkillPackageMigration.migrate();
        if (legacyAgentSkillBindingMigration != null) legacyAgentSkillBindingMigration.migrate();
    }

    @Autowired
    public void setLegacyMigration(LegacyPluginCapabilityMigrationService legacyMigration) {
        this.legacyMigration = legacyMigration;
    }

    @Autowired
    public void setLegacySkillPackageMigration(LegacySkillPackageMigrationService migration) {
        this.legacySkillPackageMigration = migration;
    }

    @Autowired
    public void setLegacyAgentSkillBindingMigration(LegacyAgentSkillBindingMigrationService migration) {
        this.legacyAgentSkillBindingMigration = migration;
    }

    @Autowired
    public void setSkillPackageService(SkillPackageService skillPackages) {
        this.skillPackages = skillPackages;
    }

    private void ensure(String id, String type, String name, String description, CapabilitySaveDTO draft) {
        CapabilityEntity existing = capabilityDao.selectById(id);
        if (existing != null) {
            if (!type.equals(existing.getType())) throw new IllegalStateException("官方能力 ID 类型冲突: " + id);
            boolean published = "PUBLISHED".equals(existing.getStatus()) && existing.getPublishedVersion() != null;
            boolean packagePresent = !"SKILL".equals(type) || skillPackages == null
                    || skillPackages.selectVersion(id, existing.getPublishedVersion()) != null;
            if (published && packagePresent) return;
        } else {
            Date now = new Date();
            existing = new CapabilityEntity();
            existing.setId(id);
            existing.setCapabilityCode(id);
            existing.setType(type);
            existing.setName(name);
            existing.setDescription(description);
            existing.setStatus("DRAFT");
            existing.setDraftVersion(0);
            existing.setCreator(SYSTEM_OPERATOR);
            existing.setUpdater(SYSTEM_OPERATOR);
            existing.setCreatedAt(now);
            existing.setUpdatedAt(now);
            existing.setDeleted(0);
            if (capabilityDao.insert(existing) != 1) throw new IllegalStateException("官方能力创建失败: " + id);
        }
        capabilities.update(SYSTEM_OPERATOR, id, draft);
        capabilities.publish(SYSTEM_OPERATOR, id);
    }

    private CapabilitySaveDTO weatherPlugin() {
        return plugin("天气查询 Plugin", "服务端天气查询执行器", "get_weather",
                Map.of("type", "object", "properties", Map.of(
                        "location", Map.of("type", "string", "description", "城市或地区"))),
                Map.of("default_location", Map.of("type", "string"),
                        "api_host", Map.of("type", "string")),
                List.of(), Map.of());
    }

    private CapabilitySaveDTO newsPlugin() {
        return plugin("新闻查询 Plugin", "服务端新闻聚合执行器", "get_news_from_newsnow",
                Map.of("type", "object", "properties", Map.of(
                        "source", Map.of("type", "string", "description", "新闻源"),
                        "detail", Map.of("type", "boolean", "description", "获取新闻详情"),
                        "lang", Map.of("type", "string", "description", "回复语言"))),
                Map.of("news_sources", Map.of("type", "string", "description", "允许的新闻源，使用分号分隔"),
                        "url", Map.of("type", "string", "description", "自定义 NewsNow API 地址")),
                List.of(), Map.of());
    }

    private CapabilitySaveDTO searchPlugin() {
        return plugin("联网搜索 Plugin", "服务端联网搜索执行器", "web_search",
                Map.of("type", "object", "properties", Map.of(
                        "query", Map.of("type", "string", "description", "搜索内容"))),
                Map.of("provider", Map.of("type", "string"), "api_key", Map.of("type", "string"),
                        "max_results", Map.of("type", "integer", "description", "返回结果数量")),
                List.of("api_key"), Map.of("max_results", 5));
    }

    private CapabilitySaveDTO plugin(String name, String description, String executor,
            Map<String, Object> inputSchema, Map<String, Object> configSchema,
            List<String> secretFields, Map<String, Object> defaults) {
        PluginDefinitionDTO plugin = new PluginDefinitionDTO();
        plugin.setExecutorName(executor);
        plugin.setInputSchema(inputSchema);
        plugin.setConfigSchema(configSchema);
        plugin.setSecretFields(secretFields);
        plugin.setDefaultConfig(defaults);
        CapabilitySaveDTO dto = new CapabilitySaveDTO();
        dto.setType("PLUGIN");
        dto.setName(name);
        dto.setDescription(description);
        dto.setPlugin(plugin);
        return dto;
    }

    private CapabilitySaveDTO weatherSkill() {
        return skill("天气查询", "查询指定地区的当前天气和预报",
                "识别用户想查询的地区并调用天气工具。用户未说明地区时使用设备默认地区直接调用；仅当设备也未配置默认地区时再询问。不要编造天气。",
                "plugin-weather", "get_weather", Map.of("location", ""),
                keywords("天气", "气温", "下雨", "穿什么衣服"),
                examples("北京今天冷不冷", "明天上海会下雨吗"),
                negativeExamples("把声音调小一点", "今天心情不好"));
    }

    private CapabilitySaveDTO newsSkill() {
        return skill("新闻查询", "查询近期新闻和热点",
                "调用新闻工具查询用户关注的新闻。说明信息来源和时间，不把旧闻说成刚发生。",
                "plugin-news", "get_news_from_newsnow", Map.of("source", ""),
                keywords("新闻", "热点", "头条", "发生了什么"),
                examples("今天有什么科技新闻", "最近有什么热点"),
                negativeExamples("给我讲个故事", "今天天气怎么样"));
    }

    private CapabilitySaveDTO searchSkill() {
        return skill("联网搜索", "搜索需要联网获取的信息",
                "仅在问题需要外部最新资料时调用联网搜索。根据搜索结果回答并区分事实与推断。",
                "plugin-web-search", "web_search", Map.of(),
                java.util.stream.Stream.concat(
                        keywords("搜索", "网上找", "最新资料").stream(),
                        triggers("KEYWORD", 50, "查一下").stream()).toList(),
                examples("帮我搜索这家公司最近的消息", "查一下这个报错怎么解决"),
                negativeExamples("把亮度调高", "陪我聊聊天"));
    }

    private CapabilitySaveDTO skill(String name, String description, String prompt,
            String pluginId, String toolName, Map<String, Object> defaults,
            List<SkillTriggerDTO> keywords, List<SkillTriggerDTO> positives,
            List<SkillTriggerDTO> negatives) {
        SkillToolDTO tool = new SkillToolDTO();
        tool.setToolType("PLUGIN");
        tool.setToolRefId(pluginId);
        tool.setToolName(toolName);
        tool.setDefaultParams(defaults);
        tool.setRequired(true);
        tool.setSortOrder(0);
        CapabilitySaveDTO dto = new CapabilitySaveDTO();
        dto.setType("SKILL");
        dto.setName(name);
        dto.setDescription(description);
        dto.setExecutionPrompt(prompt);
        dto.setSemanticThreshold(new BigDecimal("0.7000"));
        dto.setResponseMode("LLM");
        dto.setTimeoutMs(30000);
        dto.setFailureMessage("暂时无法完成这次查询，请稍后再试。");
        dto.setTriggers(java.util.stream.Stream.of(keywords, positives, negatives).flatMap(List::stream).toList());
        dto.setTools(List.of(tool));
        return dto;
    }

    private List<SkillTriggerDTO> keywords(String... values) {
        return triggers("KEYWORD", 100, values);
    }

    private List<SkillTriggerDTO> examples(String... values) {
        return triggers("POSITIVE_EXAMPLE", 0, values);
    }

    private List<SkillTriggerDTO> negativeExamples(String... values) {
        return triggers("NEGATIVE_EXAMPLE", 0, values);
    }

    private List<SkillTriggerDTO> triggers(String type, int priority, String... values) {
        return java.util.Arrays.stream(values).map(value -> {
            SkillTriggerDTO trigger = new SkillTriggerDTO();
            trigger.setType(type);
            trigger.setValue(value);
            trigger.setPriority(priority);
            trigger.setCaseSensitive(false);
            trigger.setEnabled(true);
            return trigger;
        }).toList();
    }
}
