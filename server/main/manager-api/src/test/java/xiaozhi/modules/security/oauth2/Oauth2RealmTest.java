package xiaozhi.modules.security.oauth2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Set;

import org.apache.shiro.authc.AuthenticationInfo;
import org.junit.jupiter.api.Test;

import xiaozhi.modules.conversation.security.PublicConversationApiKeyToken;
import xiaozhi.modules.conversation.security.PublicConversationUserDetail;
import xiaozhi.modules.conversation.service.PublicConversationApiKeyService;

class Oauth2RealmTest {
    @Test
    void apiKeyAuthenticationUsesTheSubmittedSecretForShiroCredentialMatching() throws Exception {
        PublicConversationApiKeyService keys = mock(PublicConversationApiKeyService.class);
        when(keys.resolve("secret", "127.0.0.1")).thenReturn(
                new PublicConversationApiKeyService.ResolvedApiKey(
                        "key-1", 7L, Set.of("conversation:text"), Set.of("agent-a")));

        Oauth2Realm realm = new Oauth2Realm();
        Field field = Oauth2Realm.class.getDeclaredField("publicConversationApiKeys");
        field.setAccessible(true);
        field.set(realm, keys);

        AuthenticationInfo info = realm.doGetAuthenticationInfo(
                new PublicConversationApiKeyToken("secret", "127.0.0.1"));

        assertInstanceOf(PublicConversationUserDetail.class, info.getPrincipals().getPrimaryPrincipal());
        assertEquals("secret", info.getCredentials());
    }
}
