package xiaozhi.modules.security.oauth2;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.apache.shiro.authc.AuthenticationToken;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class Oauth2FilterWebSessionTest {
    @Test
    void webCredentialIsLimitedToWebSessionAndPublicConversationRoutes() throws Exception {
        ExposedFilter filter = new ExposedFilter();
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockHttpServletRequest publicRequest = request("/api/v1/agents");
        assertInstanceOf(Oauth2Token.class, filter.token(publicRequest, response));

        MockHttpServletRequest adminRequest = request("/admin/users");
        assertNull(filter.token(adminRequest, response));
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addHeader("Authorization", "Bearer web_session");
        return request;
    }

    private static final class ExposedFilter extends Oauth2Filter {
        AuthenticationToken token(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
            return createToken(request, response);
        }
    }
}
