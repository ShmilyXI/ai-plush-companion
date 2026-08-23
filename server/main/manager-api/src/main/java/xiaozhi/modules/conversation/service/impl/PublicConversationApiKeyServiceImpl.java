package xiaozhi.modules.conversation.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import xiaozhi.common.utils.JsonUtils;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.companion.service.CompanionAuditService;
import xiaozhi.modules.conversation.dao.PublicConversationApiKeyDao;
import xiaozhi.modules.conversation.dto.PublicConversationApiKeyCreateDTO;
import xiaozhi.modules.conversation.entity.PublicConversationApiKeyEntity;
import xiaozhi.modules.conversation.service.PublicConversationApiKeyService;
import xiaozhi.modules.conversation.service.PublicConversationApiKeyRateLimiter;
import xiaozhi.modules.conversation.vo.PublicConversationApiKeyVO;

@Service
public class PublicConversationApiKeyServiceImpl implements PublicConversationApiKeyService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SECRET_BYTES = 32;
    private static final int MAX_ACTIVE_KEYS_PER_USER = 20;
    private static final Set<String> DEFAULT_SCOPES = Set.of("conversation:text");

    private final PublicConversationApiKeyDao dao;
    private final AgentService agents;
    private final CompanionAuditService audit;
    private final PublicConversationApiKeyRateLimiter rateLimiter;

    public PublicConversationApiKeyServiceImpl(PublicConversationApiKeyDao dao, AgentService agents) {
        this(dao, agents, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PublicConversationApiKeyServiceImpl(PublicConversationApiKeyDao dao, AgentService agents,
            CompanionAuditService audit, PublicConversationApiKeyRateLimiter rateLimiter) {
        this.dao = dao;
        this.agents = agents;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
    }

    @Override
    @Transactional
    public PublicConversationApiKeyVO create(Long userId, PublicConversationApiKeyCreateDTO request) {
        if (userId == null) throw new IllegalArgumentException("用户身份不能为空");
        if (request == null) throw new IllegalArgumentException("API Key 请求不能为空");

        Set<String> scopes = normalizeScopes(request.getScopes());
        Set<String> agentIds = normalizeAgentIds(request.getAgentIds());
        if (activeKeyCount(userId) >= MAX_ACTIVE_KEYS_PER_USER) {
            throw new IllegalArgumentException("活跃 API Key 数量已达到上限");
        }
        for (String agentId : agentIds) {
            if (agents == null || !agents.checkAgentPermission(agentId, userId)) {
                throw new IllegalArgumentException("Agent 不存在或无权使用");
            }
        }
        Date expiresAt = request.getExpiresAt();
        if (expiresAt != null && !expiresAt.toInstant().isAfter(Instant.now())) {
            throw new IllegalArgumentException("过期时间必须晚于当前时间");
        }

        String secret = generateSecret();
        Instant now = Instant.now();
        PublicConversationApiKeyEntity entity = new PublicConversationApiKeyEntity();
        entity.setUserId(userId);
        entity.setName(StringUtils.defaultIfBlank(request.getName(), "API Key"));
        entity.setKeyPrefix(secret.substring(0, Math.min(secret.length(), 11)));
        entity.setKeyHash(hash(secret));
        entity.setScopesJson(JsonUtils.toJsonString(scopes));
        entity.setAgentIdsJson(JsonUtils.toJsonString(agentIds));
        entity.setExpiresAt(expiresAt);
        entity.setRevoked(0);
        entity.setCreatedAt(Date.from(now));
        entity.setUpdatedAt(Date.from(now));
        dao.insert(entity);
        audit(userId, userId, "public-api-key.create", entity.getId(),
                Map.of("prefix", entity.getKeyPrefix(), "scope_count", scopes.size()));
        return toView(entity, secret);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PublicConversationApiKeyVO> list(Long userId) {
        if (userId == null) throw new IllegalArgumentException("用户身份不能为空");
        return dao.selectByUser(userId).stream().map(entity -> toView(entity, null)).toList();
    }

    @Override
    @Transactional
    public void revoke(Long userId, String id) {
        if (userId == null) throw new IllegalArgumentException("用户身份不能为空");
        if (StringUtils.isBlank(id)) throw new IllegalArgumentException("API Key ID 不能为空");
        PublicConversationApiKeyEntity entity = dao.selectOwnedForUpdate(userId, id);
        if (entity == null) throw new IllegalArgumentException("API Key 不存在");
        if (!Objects.equals(entity.getRevoked(), 1)) {
            entity.setRevoked(1);
            entity.setUpdatedAt(new Date());
            dao.updateById(entity);
            audit(userId, userId, "public-api-key.revoke", entity.getId(), Map.of("prefix", entity.getKeyPrefix()));
        }
    }

    @Override
    @Transactional
    public ResolvedApiKey resolve(String plaintextKey) {
        return resolve(plaintextKey, null);
    }

    @Override
    @Transactional
    public ResolvedApiKey resolve(String plaintextKey, String source) {
        if (StringUtils.isBlank(plaintextKey)) throw new IllegalArgumentException("API Key 无效");
        if (rateLimiter != null && !rateLimiter.allow(source)) {
            throw new IllegalArgumentException("认证尝试过于频繁");
        }
        PublicConversationApiKeyEntity entity = dao.selectByHash(hash(plaintextKey));
        if (entity == null || Objects.equals(entity.getRevoked(), 1)
                || (entity.getExpiresAt() != null && !entity.getExpiresAt().toInstant().isAfter(Instant.now()))) {
            if (rateLimiter != null) rateLimiter.recordFailure(source);
            audit(0L, null, "public-api-key.auth-failed", null, Map.of("source", safeSource(source)));
            throw new IllegalArgumentException("API Key 无效或已过期");
        }
        entity.setLastUsedAt(new Date());
        entity.setUpdatedAt(new Date());
        dao.updateById(entity);
        audit(entity.getUserId(), entity.getUserId(), "public-api-key.use", entity.getId(),
                Map.of("prefix", entity.getKeyPrefix(), "source", safeSource(source)));
        return new ResolvedApiKey(entity.getId(), entity.getUserId(), parseSet(entity.getScopesJson()),
                parseSet(entity.getAgentIdsJson()));
    }

    private int activeKeyCount(Long userId) {
        Date now = new Date();
        return (int) dao.selectByUser(userId).stream()
                .filter(entity -> !Objects.equals(entity.getRevoked(), 1))
                .filter(entity -> entity.getExpiresAt() == null || entity.getExpiresAt().after(now))
                .count();
    }

    private void audit(Long operatorId, Long targetUserId, String action, String resourceId,
            Map<String, ?> details) {
        if (audit == null) return;
        try {
            audit.record(operatorId, targetUserId, action, "public-api-key", resourceId, details);
        } catch (RuntimeException ignored) {
            // Authentication and key management must not expose an audit backend failure.
        }
    }

    private String safeSource(String source) {
        if (StringUtils.isBlank(source)) return "unknown";
        return source.trim().replaceAll("[^A-Za-z0-9:.\\[\\]-]", "_");
    }

    private PublicConversationApiKeyVO toView(PublicConversationApiKeyEntity entity, String createdSecret) {
        return new PublicConversationApiKeyVO(entity.getId(), entity.getName(), entity.getKeyPrefix(),
                parseSet(entity.getScopesJson()), parseSet(entity.getAgentIdsJson()), entity.getExpiresAt(),
                Objects.equals(entity.getRevoked(), 1), entity.getLastUsedAt(), entity.getCreatedAt(),
                entity.getUpdatedAt(), createdSecret);
    }

    private Set<String> normalizeScopes(Set<String> requested) {
        Set<String> scopes = requested == null || requested.isEmpty()
                ? new LinkedHashSet<>(DEFAULT_SCOPES) : new LinkedHashSet<>(requested);
        if (!SUPPORTED_SCOPES.containsAll(scopes)) throw new IllegalArgumentException("API Key scope 不受支持");
        return Set.copyOf(scopes);
    }

    private Set<String> normalizeAgentIds(Set<String> requested) {
        if (requested == null || requested.isEmpty()) return Set.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String agentId : requested) {
            if (StringUtils.isBlank(agentId)) throw new IllegalArgumentException("Agent ID 不能为空");
            result.add(agentId);
        }
        return Set.copyOf(result);
    }

    private Set<String> parseSet(String json) {
        if (StringUtils.isBlank(json)) return Set.of();
        List<String> values = JsonUtils.parseArray(json, String.class);
        return values == null ? Set.of() : Set.copyOf(values);
    }

    private String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return "pc_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }
}
