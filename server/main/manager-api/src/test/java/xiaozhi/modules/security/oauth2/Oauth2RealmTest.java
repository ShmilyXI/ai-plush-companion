package xiaozhi.modules.security.oauth2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

import java.lang.reflect.Field;
import java.util.Set;

import org.apache.shiro.authc.AuthenticationInfo;
import org.junit.jupiter.api.Test;

import xiaozhi.modules.conversation.security.PublicConversationApiKeyToken;
import xiaozhi.modules.conversation.security.PublicConversationUserDetail;
import xiaozhi.modules.conversation.service.PublicConversationApiKeyService;
import xiaozhi.modules.security.service.ShiroService;
import xiaozhi.modules.sys.entity.SysUserEntity;
import xiaozhi.modules.websession.service.WebSessionBootstrapService;

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

    @Test
    void webCredentialAuthenticatesTheOwningManagerUser() throws Exception {
        PublicConversationApiKeyService keys = mock(PublicConversationApiKeyService.class);
        ShiroService shiro = mock(ShiroService.class);
        WebSessionBootstrapService webSessions = mock(WebSessionBootstrapService.class);
        when(webSessions.resolve("web_token")).thenReturn(7L);
        SysUserEntity user = new SysUserEntity();
        user.setId(7L);
        user.setUsername("alice");
        user.setStatus(1);
        user.setSuperAdmin(0);
        when(shiro.getUser(7L)).thenReturn(user);

        Oauth2Realm realm = new Oauth2Realm();
        set(realm, "publicConversationApiKeys", keys);
        set(realm, "shiroService", shiro);
        set(realm, "webSessions", webSessions);

        AuthenticationInfo info = realm.doGetAuthenticationInfo(new Oauth2Token("web_token"));

        assertEquals(7L, ((xiaozhi.common.user.UserDetail) info.getPrincipals().getPrimaryPrincipal()).getId());
        assertEquals("web_token", info.getCredentials());
        verify(shiro, never()).getByToken("web_token");
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = Oauth2Realm.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
