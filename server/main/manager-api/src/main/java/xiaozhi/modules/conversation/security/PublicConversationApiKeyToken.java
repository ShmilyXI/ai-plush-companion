package xiaozhi.modules.conversation.security;

import org.apache.shiro.authc.AuthenticationToken;

public final class PublicConversationApiKeyToken implements AuthenticationToken {
    private final String secret;

    public PublicConversationApiKeyToken(String secret) {
        this.secret = secret;
    }

    public String secret() {
        return secret;
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
