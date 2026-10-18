package zixuan.modules.conversation.security;

import org.apache.shiro.authc.AuthenticationToken;

public final class PublicConversationApiKeyToken implements AuthenticationToken {
    private final String secret;
    private final String source;

    public PublicConversationApiKeyToken(String secret) {
        this(secret, null);
    }

    public PublicConversationApiKeyToken(String secret, String source) {
        this.secret = secret;
        this.source = source;
    }

    public String secret() {
        return secret;
    }

    public String source() {
        return source;
    }

    @Override
    public Object getPrincipal() {
        return secret;
    }

    @Override
    public Object getCredentials() {
        return secret;
    }
}
