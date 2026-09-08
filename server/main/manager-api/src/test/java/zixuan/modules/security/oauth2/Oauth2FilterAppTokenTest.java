package zixuan.modules.security.oauth2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.IncorrectCredentialsException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import zixuan.common.user.UserDetail;
import zixuan.common.utils.MessageUtils;
import zixuan.modules.appauth.service.AppUserTokenService;
import zixuan.modules.security.service.ShiroService;
import zixuan.modules.sys.entity.SysUserEntity;

class Oauth2FilterAppTokenTest {
    @BeforeAll
    static void injectMessageSource() {
        // 认证失败路径会走 MessageUtils 解析国际化消息，单测环境下注入 mock
        ReflectionTestUtils.setField(MessageUtils.class, "messageSource", mock(MessageSource.class));
    }

    @Test
    void appCredentialIsLimitedToAppPublicAndCompanionRoutes() throws Exception {
        ExposedFilter filter = new ExposedFilter();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertInstanceOf(Oauth2Token.class, filter.token(request("/app/v1/auth/profile"), response));
        assertInstanceOf(Oauth2Token.class, filter.token(request("/api/v1/conversations"), response));
        assertInstanceOf(Oauth2Token.class, filter.token(request("/api/v1/agents"), response));
        assertInstanceOf(Oauth2Token.class, filter.token(request("/companion/devices"), response));
        assertInstanceOf(Oauth2Token.class, filter.token(request("/device/bind/agent-1/123456"), response));
        assertInstanceOf(Oauth2Token.class, filter.token(request("/device/unbind"), response));

        assertNull(filter.token(request("/admin/users"), response));
        assertNull(filter.token(request("/user/info"), response));
        assertNull(filter.token(request("/agent/list"), response));
    }

    @Test
    void realmResolvesAppTokenToNormalRoleEvenForConsoleSuperAdmin() {
        Oauth2Realm realm = new Oauth2Realm();
        AppUserTokenService tokens = mock(AppUserTokenService.class);
        ShiroService shiroService = mock(ShiroService.class);
        ReflectionTestUtils.setField(realm, "appUserTokens", tokens);
        ReflectionTestUtils.setField(realm, "shiroService", shiroService);
        when(tokens.resolveAccessToken("app_secret")).thenReturn(7L);
        SysUserEntity consoleAdmin = new SysUserEntity();
        consoleAdmin.setId(7L);
        consoleAdmin.setUsername("admin");
        consoleAdmin.setSuperAdmin(1);
        consoleAdmin.setStatus(1);
        when(shiroService.getUser(7L)).thenReturn(consoleAdmin);

        var info = realm.doGetAuthenticationInfo(new Oauth2Token("app_secret"));
        UserDetail user = (UserDetail) info.getPrincipals().getPrimaryPrincipal();

        assertEquals(7L, user.getId());
        assertEquals(0, user.getSuperAdmin());
        assertEquals("app_secret", user.getToken());
    }

    @Test
    void realmRejectsUnknownAppToken() {
        Oauth2Realm realm = new Oauth2Realm();
        AppUserTokenService tokens = mock(AppUserTokenService.class);
        ReflectionTestUtils.setField(realm, "appUserTokens", tokens);
        when(tokens.resolveAccessToken("app_unknown")).thenReturn(null);

        assertThrows(IncorrectCredentialsException.class,
                () -> realm.doGetAuthenticationInfo(new Oauth2Token("app_unknown")));
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addHeader("Authorization", "Bearer app_secret");
        return request;
    }

    private static final class ExposedFilter extends Oauth2Filter {
        AuthenticationToken token(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
            return createToken(request, response);
        }
    }
}
