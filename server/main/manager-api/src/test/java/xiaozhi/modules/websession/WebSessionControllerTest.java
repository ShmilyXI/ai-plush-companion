package xiaozhi.modules.websession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.websession.controller.WebSessionController;
import xiaozhi.modules.websession.dto.WebSessionExchangeDTO;
import xiaozhi.modules.websession.dto.WebSessionBootstrapDTO;
import xiaozhi.modules.websession.service.WebSessionBootstrapService;

class WebSessionControllerTest {
    @Test
    void bootstrapUsesTheAuthenticatedManagerUser() {
        WebSessionBootstrapService service = mock(WebSessionBootstrapService.class);
        WebSessionController controller = new WebSessionController(service);
        WebSessionBootstrapDTO request = new WebSessionBootstrapDTO();
        request.setAudience("companion-web");
        request.setOrigin("http://127.0.0.1:8010");
        when(service.issue(7L, "companion-web", "http://127.0.0.1:8010"))
                .thenReturn(new WebSessionBootstrapService.Bootstrap("code", 60));

        try (MockedStatic<SecurityUser> security = Mockito.mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            var result = controller.bootstrap(request);
            assertEquals("code", result.getData().code());
        }
    }

    @Test
    void exchangeReturnsAWebCredential() {
        WebSessionBootstrapService service = mock(WebSessionBootstrapService.class);
        WebSessionController controller = new WebSessionController(service);
        WebSessionExchangeDTO request = new WebSessionExchangeDTO();
        request.setCode("code");
        when(service.exchange("code")).thenReturn(new WebSessionBootstrapService.Exchange("web-token", 900));

        var result = controller.exchange(request);

        assertEquals("web-token", result.getData().accessToken());
    }
}
