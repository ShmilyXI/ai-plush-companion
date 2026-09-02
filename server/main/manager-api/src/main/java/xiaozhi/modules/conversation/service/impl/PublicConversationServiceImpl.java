package xiaozhi.modules.conversation.service.impl;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import xiaozhi.common.constant.Constant;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.agent.service.AgentSnapshotService;
import xiaozhi.modules.agent.vo.AgentInfoVO;
import xiaozhi.modules.companion.model.service.CompanionEffectiveModelService;
import xiaozhi.modules.companion.model.vo.CompanionRuntimeModel;
import xiaozhi.modules.companion.memory.ProfileMemoryNamespace;
import xiaozhi.modules.conversation.dto.PublicConversationCreateDTO;
import xiaozhi.modules.conversation.service.ConversationRuntimeTokenService;
import xiaozhi.modules.conversation.service.ConversationRuntimeTokenService.RuntimeTokenClaims;
import xiaozhi.modules.conversation.service.PublicConversationService;
import xiaozhi.modules.conversation.service.PublicConversationAuthService;
import xiaozhi.modules.conversation.service.PublicConversationRuntimeBundleStore;
import xiaozhi.modules.conversation.service.PublicConversationSkillProjectionService;
import xiaozhi.modules.conversation.service.PublicConversationCapabilityProjection;
import xiaozhi.modules.conversation.service.PublicConversationQuotaService;
import xiaozhi.modules.conversation.service.CompanionConversationIndexService;
import xiaozhi.modules.conversation.entity.CompanionConversationEntity;
import xiaozhi.modules.conversation.entity.CompanionConversationTurnEntity;
import xiaozhi.modules.conversation.vo.PublicConversationSessionVO;
import xiaozhi.modules.conversation.vo.PublicConversationRuntimeBundleVO;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.timbre.service.TimbreService;
import xiaozhi.modules.timbre.vo.TimbreDetailsVO;

@Service
public class PublicConversationServiceImpl implements PublicConversationService {
    private static final Duration TOKEN_TTL = Duration.ofMinutes(15);
    private static final Set<String> MODEL_OVERRIDE_TYPES = Set.of(
            "LLM", "ASR", "TTS", "VAD", "VLLM", "Memory");
    private final AgentService agents;
    private final CompanionEffectiveModelService models;
    private final TimbreService timbres;
    private final ConversationRuntimeTokenService tokens;
    private final SysParamsService params;
    private final PublicConversationAuthService auth;
    private final PublicConversationRuntimeBundleStore bundleStore;
    private final PublicConversationSkillProjectionService skillProjection;
    private final PublicConversationQuotaService quota;
    @Autowired(required = false)
    private CompanionConversationIndexService conversationIndex;
    @Autowired(required = false)
    private AgentSnapshotService snapshots;
    private final Map<String, PublicConversationRuntimeBundleVO> bundles = new ConcurrentHashMap<>();

    public PublicConversationServiceImpl(AgentService agents, CompanionEffectiveModelService models,
            TimbreService timbres, ConversationRuntimeTokenService tokens, SysParamsService params) {
        this(agents, models, timbres, tokens, params, null, null, null, null);
    }

    public PublicConversationServiceImpl(AgentService agents, CompanionEffectiveModelService models,
            TimbreService timbres, ConversationRuntimeTokenService tokens, SysParamsService params,
            PublicConversationAuthService auth) {
        this(agents, models, timbres, tokens, params, auth, null, null, null);
    }

    public PublicConversationServiceImpl(AgentService agents, CompanionEffectiveModelService models,
            TimbreService timbres, ConversationRuntimeTokenService tokens, SysParamsService params,
            PublicConversationAuthService auth, PublicConversationRuntimeBundleStore bundleStore) {
        this(agents, models, timbres, tokens, params, auth, bundleStore, null, null);
    }

    public PublicConversationServiceImpl(AgentService agents, CompanionEffectiveModelService models,
            TimbreService timbres, ConversationRuntimeTokenService tokens, SysParamsService params,
            PublicConversationAuthService auth, PublicConversationRuntimeBundleStore bundleStore,
            PublicConversationSkillProjectionService skillProjection) {
        this(agents, models, timbres, tokens, params, auth, bundleStore, skillProjection, null);
    }

    @Autowired
    public PublicConversationServiceImpl(AgentService agents, CompanionEffectiveModelService models,
            TimbreService timbres, ConversationRuntimeTokenService tokens, SysParamsService params,
            PublicConversationAuthService auth, PublicConversationRuntimeBundleStore bundleStore,
            PublicConversationSkillProjectionService skillProjection, PublicConversationQuotaService quota) {
        this.agents = agents;
        this.models = models;
        this.timbres = timbres;
        this.tokens = tokens;
        this.params = params;
        this.auth = auth;
        this.bundleStore = bundleStore;
        this.skillProjection = skillProjection;
        this.quota = quota;
    }

    @Override
    public PublicConversationSessionVO create(Long userId, PublicConversationCreateDTO request) {
        if (userId == null) throw new IllegalArgumentException("用户身份不能为空");
        if (request == null) throw new IllegalArgumentException("会话请求不能为空");
        request.validateModes();
        PublicConversationAuthService.AuthenticatedCaller caller = null;
        if (auth != null) {
            caller = auth.current();
            auth.requireAgent(caller, request.getAgentId());
            for (String mode : request.getInputModes()) auth.requireScope(caller, "conversation:" + mode);
            for (String mode : request.getOutputModes()) auth.requireScope(caller, "conversation:" + mode);
            if (StringUtils.isNotBlank(request.getVoiceId())
                    || (request.getModelOverrides() != null && !request.getModelOverrides().isEmpty())) {
                auth.requireScope(caller, "conversation:override");
            }
        }

        AgentInfoVO agent = agents.getAgentById(request.getAgentId(), userId);
        if (agent == null || agent.getActiveVersionNo() == null || agent.getActiveVersionNo() <= 0) {
            throw new IllegalArgumentException("Agent 没有可用的已发布版本");
        }
        agent = resolvePublishedAgent(agent, userId);

        validateModelOverrides(request.getModelOverrides());
        Map<String, String> overrides = request.getModelOverrides() == null
                ? Map.of() : Map.copyOf(request.getModelOverrides());
        Map<String, CompanionRuntimeModel> runtimeModels = models.resolveRuntimeForPlayground(
                userId, agent, runtimeModelSelections(agent, overrides));
        validateRuntimeModels(runtimeModels, request.getInputModes(), request.getOutputModes());
        validateVoice(userId, agent, request.getVoiceId());
        if (quota != null) quota.requireSession(userId, caller != null && caller.apiKey() ? caller.keyId() : null);

        String conversationId = UUID.randomUUID().toString();
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(TOKEN_TTL);
        Set<String> scopes = new LinkedHashSet<>();
        request.getInputModes().forEach(mode -> scopes.add("conversation:" + mode));
        request.getOutputModes().forEach(mode -> scopes.add("conversation:" + mode));
        RuntimeTokenClaims claims = new RuntimeTokenClaims(
                conversationId, String.valueOf(userId), agent.getId(), agent.getActiveVersionNo(),
                scopes, request.getInputModes(), request.getOutputModes(), issuedAt, expiresAt);
        String runtimeToken = tokens.issue(claims);
        String streamUrl = runtimeUrl(conversationId);
        Map<String, String> publicMetadata = new LinkedHashMap<>();
        publicMetadata.put("agent_name", StringUtils.defaultString(agent.getAgentName()));
        publicMetadata.put("tts_voice_id", StringUtils.defaultString(request.getVoiceId(), agent.getTtsVoiceId()));
        Map<String, Object> publicConfig = new LinkedHashMap<>();
        publicConfig.put("systemPrompt", StringUtils.defaultString(agent.getSystemPrompt()));
        publicConfig.put("rolePrompt", StringUtils.defaultString(agent.getPersonality()));
        publicConfig.put("profileName", StringUtils.defaultString(agent.getAgentName()));
        String profileMemoryNamespace = ProfileMemoryNamespace.of(userId, agent.getId());
        publicConfig.put("memoryNamespace", profileMemoryNamespace);
        publicConfig.put("profileMemoryNamespace", profileMemoryNamespace);
        publicConfig.put("memory_namespace", profileMemoryNamespace);
        publicConfig.put("memoryEnabled", agent.getMemoryEnabled() == null || agent.getMemoryEnabled() == 1);
        PublicConversationCapabilityProjection projectedCapabilities = skillProjection == null
                ? PublicConversationCapabilityProjection.empty()
                : skillProjection.project(agent.getId(), agent.getActiveVersionNo());
        publicConfig.put("skills", projectedCapabilities.skills());
        publicConfig.put("tools", projectedCapabilities.tools());
        Map<String, Map<String, Object>> internalModels = runtimeModelConfigs(
                runtimeModels, agent, request.getVoiceId());
        PublicConversationRuntimeBundleVO bundle = new PublicConversationRuntimeBundleVO(
                conversationId, userId, agent.getId(), agent.getActiveVersionNo(), Map.copyOf(publicConfig), Map.copyOf(internalModels));
        if (bundleStore != null) {
            try {
                bundleStore.put(bundle, TOKEN_TTL);
            } catch (RuntimeException error) {
                throw new IllegalStateException("运行时会话存储失败", error);
            }
        } else {
            bundles.put(conversationId, bundle);
        }
        if (conversationIndex != null) {
            conversationIndex.createWithId(userId, conversationId, agent.getId(), agent.getActiveVersionNo(), "app", "");
        }
        return new PublicConversationSessionVO(
                conversationId, agent.getId(), agent.getActiveVersionNo(), streamUrl, runtimeToken,
                expiresAt, Set.copyOf(request.getInputModes()), Set.copyOf(request.getOutputModes()), publicMetadata);
    }

    @Override
    public PublicConversationRuntimeBundleVO runtimeBundle(String conversationId) {
        PublicConversationRuntimeBundleVO bundle = bundleStore == null
                ? bundles.get(conversationId)
                : bundleStore.get(conversationId);
        if (bundle == null) throw new IllegalArgumentException("会话不存在或已过期");
        return bundle;
    }

    @Override
    public PublicConversationSessionVO continueConversation(Long userId, String conversationId) {
        if (conversationIndex == null) throw new IllegalArgumentException("会话持久化未配置");
        CompanionConversationEntity conversation = conversationIndex.requireReadable(userId, conversationId);
        AgentInfoVO agent = agents.getAgentById(conversation.getProfileId(), userId);
        if (agent == null) throw new IllegalArgumentException("角色不存在");
        int version = conversation.getProfileVersionNo() == null || conversation.getProfileVersionNo() <= 0
                ? (agent.getActiveVersionNo() == null ? 0 : agent.getActiveVersionNo())
                : conversation.getProfileVersionNo();
        if (version <= 0) throw new IllegalArgumentException("角色没有可用版本");
        if (agent.getActiveVersionNo() == null || !Integer.valueOf(version).equals(agent.getActiveVersionNo())) {
            throw new IllegalArgumentException("角色版本已更新，请新开会话");
        }
        agent = resolvePublishedAgent(agent, userId);
        Set<String> inputModes = Set.of("text", "audio");
        Set<String> outputModes = Set.of("text", "audio");
        Map<String, CompanionRuntimeModel> runtimeModels = models.resolveRuntimeForPlayground(
                userId, agent, runtimeModelSelections(agent, Map.of()));
        validateRuntimeModels(runtimeModels, inputModes, outputModes);
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(TOKEN_TTL);
        Set<String> scopes = Set.of("conversation:text", "conversation:audio");
        String runtimeToken = tokens.issue(new RuntimeTokenClaims(conversationId, String.valueOf(userId), agent.getId(), version, scopes, inputModes, outputModes, issuedAt, expiresAt));
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("systemPrompt", StringUtils.defaultString(agent.getSystemPrompt()));
        config.put("rolePrompt", StringUtils.defaultString(agent.getPersonality()));
        config.put("profileName", StringUtils.defaultString(agent.getAgentName()));
        String profileMemoryNamespace = ProfileMemoryNamespace.of(userId, agent.getId());
        config.put("memoryNamespace", profileMemoryNamespace);
        config.put("profileMemoryNamespace", profileMemoryNamespace);
        config.put("memory_namespace", profileMemoryNamespace);
        config.put("memoryEnabled", agent.getMemoryEnabled() == null || agent.getMemoryEnabled() == 1);
        List<CompanionConversationTurnEntity> persisted = conversationIndex.history(userId, conversationId);
        // A turn can be persisted before the assistant has produced a reply.
        // Normalize nullable text before putting it into the immutable runtime
        // bundle, because Map.of/Map.copyOf reject null values.
        config.put("history", persisted.stream().map(turn -> {
            Map<String, Object> historyTurn = new LinkedHashMap<>();
            historyTurn.put("role", "user");
            historyTurn.put("content", StringUtils.defaultString(turn.getUserText()));
            historyTurn.put("reply", StringUtils.defaultString(turn.getAssistantText()));
            return historyTurn;
        }).toList());
        Map<String, Map<String, Object>> modelConfigs = runtimeModelConfigs(
                runtimeModels, agent, null);
        PublicConversationRuntimeBundleVO bundle = new PublicConversationRuntimeBundleVO(conversationId, userId, agent.getId(), version, Map.copyOf(config), Map.copyOf(modelConfigs));
        if (bundleStore != null) {
            bundleStore.put(bundle, TOKEN_TTL);
        } else {
            bundles.put(conversationId, bundle);
        }
        return new PublicConversationSessionVO(conversationId, agent.getId(), version, runtimeUrl(conversationId), runtimeToken, expiresAt, inputModes, outputModes, Map.of("agent_name", StringUtils.defaultString(agent.getAgentName())));
    }

    private void validateRuntimeModels(Map<String, CompanionRuntimeModel> runtimeModels,
            Set<String> inputModes, Set<String> outputModes) {
        if (runtimeModels == null || runtimeModels.isEmpty()) {
            throw new IllegalArgumentException("角色没有可用的模型配置");
        }
        if (!runtimeModels.containsKey("LLM")) {
            throw new IllegalArgumentException("角色缺少可用的大语言模型");
        }
        if (inputModes != null && inputModes.contains("audio") && !runtimeModels.containsKey("ASR")) {
            throw new IllegalArgumentException("角色缺少可用的语音识别模型");
        }
        if (outputModes != null && outputModes.contains("audio") && !runtimeModels.containsKey("TTS")) {
            throw new IllegalArgumentException("角色缺少可用的语音合成模型");
        }
    }

    private AgentInfoVO resolvePublishedAgent(AgentInfoVO current, Long userId) {
        if (snapshots == null) return current;
        AgentInfoVO resolved;
        try {
            resolved = snapshots.getPublishedAgent(current.getId(), userId, current.getActiveVersionNo());
        } catch (RuntimeException error) {
            throw new IllegalStateException("角色已发布版本无法读取", error);
        }
        if (resolved == null) {
            throw new IllegalStateException("角色已发布版本不存在");
        }
        return resolved;
    }

    private void validateModelOverrides(Map<String, String> overrides) {
        if (overrides == null || overrides.isEmpty()) return;
        for (Map.Entry<String, String> entry : overrides.entrySet()) {
            String type = entry.getKey();
            String resourceId = entry.getValue();
            if (!MODEL_OVERRIDE_TYPES.contains(type)
                    || StringUtils.isBlank(resourceId)
                    || resourceId.length() > 128
                    || resourceId.indexOf('\n') >= 0
                    || resourceId.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("模型覆盖只能指定有效的模型资源");
            }
        }
    }

    /**
     * Pass the immutable agent model ids explicitly. The effective-model
     * resolver also supports legacy mutable bindings, so leaving this map
     * empty would let an unpublished draft change a new session's runtime.
     * Request overrides are applied last and remain limited to resource ids.
     */
    private Map<String, String> runtimeModelSelections(
            AgentInfoVO agent, Map<String, String> requestedOverrides) {
        Map<String, String> selections = new LinkedHashMap<>();
        if (snapshots != null) {
            putModelSelection(selections, "LLM", agent.getLlmModelId());
            putModelSelection(selections, "ASR", agent.getAsrModelId());
            putModelSelection(selections, "TTS", agent.getTtsModelId());
            putModelSelection(selections, "VAD", agent.getVadModelId());
            putModelSelection(selections, "VLLM", agent.getVllmModelId());
            putModelSelection(selections, "Memory", agent.getMemModelId());
        }
        if (requestedOverrides != null) selections.putAll(requestedOverrides);
        return Map.copyOf(selections);
    }

    private void putModelSelection(Map<String, String> selections, String type, String resourceId) {
        if (StringUtils.isNotBlank(resourceId)) selections.put(type, resourceId);
    }

    private void validateVoice(Long userId, AgentInfoVO agent, String requestedVoiceId) {
        String voiceId = StringUtils.defaultIfBlank(requestedVoiceId, agent.getTtsVoiceId());
        if (voiceId == null || timbres == null) return;
        TimbreDetailsVO voice = timbres.get(voiceId);
        if (voice == null) throw new IllegalArgumentException("音色不存在或无权使用");
        if (StringUtils.isNotBlank(agent.getTtsModelId()) && StringUtils.isNotBlank(voice.getTtsModelId())
                && !agent.getTtsModelId().equals(voice.getTtsModelId())) {
            throw new IllegalArgumentException("音色与 TTS 模型不匹配");
        }
    }

    /**
     * Project the validated voice resource into the private runtime model
     * config. The public voice id is only metadata; providers need the vendor
     * voice code (for example Edge's zh-CN-XiaoxiaoNeural) under private_voice.
     */
    private Map<String, Map<String, Object>> runtimeModelConfigs(
            Map<String, CompanionRuntimeModel> runtimeModels,
            AgentInfoVO agent,
            String requestedVoiceId) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        String voiceId = StringUtils.defaultIfBlank(requestedVoiceId, agent.getTtsVoiceId());
        TimbreDetailsVO voice = null;
        if (StringUtils.isNotBlank(voiceId) && timbres != null) {
            voice = timbres.get(voiceId);
        }
        for (Map.Entry<String, CompanionRuntimeModel> entry : runtimeModels.entrySet()) {
            Map<String, Object> config = new LinkedHashMap<>();
            if (entry.getValue() != null && entry.getValue().getConfig() != null) {
                config.putAll(entry.getValue().getConfig());
            }
            if ("TTS".equals(entry.getKey()) && voice != null) {
                String vendorVoice = StringUtils.defaultIfBlank(voice.getTtsVoice(), voiceId);
                if (StringUtils.isNotBlank(vendorVoice)) config.put("private_voice", vendorVoice);
                if (StringUtils.isNotBlank(voice.getReferenceAudio())) {
                    config.put("ref_audio", voice.getReferenceAudio());
                }
                if (StringUtils.isNotBlank(voice.getReferenceText())) {
                    config.put("ref_text", voice.getReferenceText());
                }
            }
            result.put(entry.getKey(), config);
        }
        return result;
    }

    private String runtimeUrl(String conversationId) {
        String base = params.getValue(Constant.SERVER_HTTP, true);
        if (StringUtils.isBlank(base)) throw new IllegalArgumentException("运行时 WebSocket 地址未配置");
        String websocketBase = base.trim().replaceFirst("^http://", "ws://").replaceFirst("^https://", "wss://");
        return websocketBase.replaceAll("/+$", "") + "/api/v1/conversations/" + conversationId + "/stream";
    }
}
