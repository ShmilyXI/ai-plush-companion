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
import xiaozhi.modules.agent.vo.AgentInfoVO;
import xiaozhi.modules.companion.model.service.CompanionEffectiveModelService;
import xiaozhi.modules.companion.model.vo.CompanionRuntimeModel;
import xiaozhi.modules.conversation.dto.PublicConversationCreateDTO;
import xiaozhi.modules.conversation.service.ConversationRuntimeTokenService;
import xiaozhi.modules.conversation.service.ConversationRuntimeTokenService.RuntimeTokenClaims;
import xiaozhi.modules.conversation.service.PublicConversationService;
import xiaozhi.modules.conversation.service.PublicConversationAuthService;
import xiaozhi.modules.conversation.service.PublicConversationRuntimeBundleStore;
import xiaozhi.modules.conversation.service.PublicConversationSkillProjectionService;
import xiaozhi.modules.conversation.service.PublicConversationCapabilityProjection;
import xiaozhi.modules.conversation.service.PublicConversationQuotaService;
import xiaozhi.modules.conversation.vo.PublicConversationSessionVO;
import xiaozhi.modules.conversation.vo.PublicConversationRuntimeBundleVO;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.timbre.service.TimbreService;
import xiaozhi.modules.timbre.vo.TimbreDetailsVO;

@Service
public class PublicConversationServiceImpl implements PublicConversationService {
    private static final Duration TOKEN_TTL = Duration.ofMinutes(15);
    private final AgentService agents;
    private final CompanionEffectiveModelService models;
    private final TimbreService timbres;
    private final ConversationRuntimeTokenService tokens;
    private final SysParamsService params;
    private final PublicConversationAuthService auth;
    private final PublicConversationRuntimeBundleStore bundleStore;
    private final PublicConversationSkillProjectionService skillProjection;
    private final PublicConversationQuotaService quota;
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
            if (StringUtils.isNotBlank(request.getVoiceId())
                    || (request.getModelOverrides() != null && !request.getModelOverrides().isEmpty())) {
                auth.requireScope(caller, "conversation:override");
            }
        }

        AgentInfoVO agent = agents.getAgentById(request.getAgentId(), userId);
        if (agent == null || agent.getActiveVersionNo() == null || agent.getActiveVersionNo() <= 0) {
            throw new IllegalArgumentException("Agent 没有可用的已发布版本");
        }

        Map<String, String> overrides = request.getModelOverrides() == null
                ? Map.of() : Map.copyOf(request.getModelOverrides());
        Map<String, CompanionRuntimeModel> runtimeModels = models.resolveRuntimeForPlayground(userId, agent, overrides);
        if (runtimeModels == null || runtimeModels.isEmpty()) {
            throw new IllegalArgumentException("Agent 没有可用的模型配置");
        }
        validateVoice(userId, agent, request.getVoiceId());
        if (quota != null) quota.requireSession(userId, caller != null && caller.apiKey() ? caller.keyId() : null);

        String conversationId = UUID.randomUUID().toString();
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(TOKEN_TTL);
        Set<String> scopes = new LinkedHashSet<>();
        request.getInputModes().forEach(mode -> scopes.add("conversation:" + mode));
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
        publicConfig.put("memoryNamespace", "public:" + userId + ":" + agent.getId() + ":" + conversationId);
        PublicConversationCapabilityProjection projectedCapabilities = skillProjection == null
                ? PublicConversationCapabilityProjection.empty()
                : skillProjection.project(agent.getId(), agent.getActiveVersionNo());
        publicConfig.put("skills", projectedCapabilities.skills());
        publicConfig.put("tools", projectedCapabilities.tools());
        Map<String, Map<String, Object>> internalModels = new LinkedHashMap<>();
        runtimeModels.forEach((type, model) -> internalModels.put(type, model.getConfig()));
        PublicConversationRuntimeBundleVO bundle = new PublicConversationRuntimeBundleVO(
                conversationId, userId, agent.getId(), agent.getActiveVersionNo(), Map.copyOf(publicConfig), Map.copyOf(internalModels));
        bundles.put(conversationId, bundle);
        if (bundleStore != null) {
            try {
                bundleStore.put(bundle, TOKEN_TTL);
            } catch (RuntimeException error) {
                bundles.remove(conversationId);
                throw new IllegalStateException("运行时会话存储失败", error);
            }
        }
        return new PublicConversationSessionVO(
                conversationId, agent.getId(), agent.getActiveVersionNo(), streamUrl, runtimeToken,
                expiresAt, Set.copyOf(request.getInputModes()), Set.copyOf(request.getOutputModes()), publicMetadata);
    }

    @Override
    public PublicConversationRuntimeBundleVO runtimeBundle(String conversationId) {
        PublicConversationRuntimeBundleVO bundle = bundles.get(conversationId);
        if (bundle == null && bundleStore != null) bundle = bundleStore.get(conversationId);
        if (bundle == null) throw new IllegalArgumentException("会话不存在或已过期");
        return bundle;
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

    private String runtimeUrl(String conversationId) {
        String base = params.getValue(Constant.SERVER_HTTP, true);
        if (StringUtils.isBlank(base)) throw new IllegalArgumentException("运行时 WebSocket 地址未配置");
        String websocketBase = base.trim().replaceFirst("^http://", "ws://").replaceFirst("^https://", "wss://");
        return websocketBase.replaceAll("/+$", "") + "/api/v1/conversations/" + conversationId + "/stream";
    }
}
