package zixuan.modules.conversation.service.impl;

import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import zixuan.modules.conversation.service.PublicConversationApiKeyService;
import zixuan.modules.conversation.service.PublicConversationAuthService;
import zixuan.modules.conversation.security.PublicConversationUserDetail;
import zixuan.common.user.UserDetail;
import zixuan.modules.security.user.SecurityUser;

@Service
public class PublicConversationAuthServiceImpl implements PublicConversationAuthService {
    private final PublicConversationApiKeyService apiKeys;

    public PublicConversationAuthServiceImpl(PublicConversationApiKeyService apiKeys) {
        this.apiKeys = apiKeys;
    }

    @Override
    public AuthenticatedCaller resolve(String authorizationHeader) {
        if (StringUtils.isBlank(authorizationHeader)) throw new IllegalArgumentException("未提供认证信息");
        int separator = authorizationHeader.indexOf(' ');
        if (separator <= 0) throw new IllegalArgumentException("认证信息格式无效");
        String scheme = authorizationHeader.substring(0, separator);
        String credential = authorizationHeader.substring(separator + 1).trim();
        if (StringUtils.isBlank(credential)) throw new IllegalArgumentException("认证信息格式无效");

        if ("ApiKey".equalsIgnoreCase(scheme)) {
            PublicConversationApiKeyService.ResolvedApiKey resolved = apiKeys.resolve(credential);
            return new AuthenticatedCaller(resolved.userId(), resolved.scopes(), resolved.agentIds(), true,
                    resolved.id());
        }
        if ("Bearer".equalsIgnoreCase(scheme)) {
            Long userId = SecurityUser.getUserId();
            if (userId == null) throw new IllegalArgumentException("用户身份无效");
            return new AuthenticatedCaller(userId, PublicConversationApiKeyService.SUPPORTED_SCOPES, Set.of(), false,
                    null);
        }
        throw new IllegalArgumentException("认证方式不受支持");
    }

    @Override
    public AuthenticatedCaller current() {
        UserDetail user = SecurityUser.getUser();
        if (user == null || user.getId() == null) throw new IllegalArgumentException("用户身份无效");
        if (user instanceof PublicConversationUserDetail publicUser) {
            return new AuthenticatedCaller(publicUser.getId(), publicUser.getScopes(), publicUser.getAgentIds(), true,
                    publicUser.getApiKeyId());
        }
        return new AuthenticatedCaller(user.getId(), PublicConversationApiKeyService.SUPPORTED_SCOPES, Set.of(), false,
                null);
    }

    @Override
    public void requireScope(AuthenticatedCaller caller, String scope) {
        if (caller == null || StringUtils.isBlank(scope) || !caller.hasScope(scope)) {
            throw new IllegalArgumentException("API Key scope 不足");
        }
    }

    @Override
    public void requireAgent(AuthenticatedCaller caller, String agentId) {
        if (caller == null || StringUtils.isBlank(agentId) || !caller.canUseAgent(agentId)) {
            throw new IllegalArgumentException("Agent 不在授权范围内");
        }
    }
}
