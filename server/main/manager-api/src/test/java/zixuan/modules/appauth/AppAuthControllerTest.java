package zixuan.modules.appauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;

import zixuan.common.user.UserDetail;
import zixuan.modules.appauth.controller.AppAuthController;
import zixuan.modules.appauth.dto.AppPasswordLoginDTO;
import zixuan.modules.appauth.service.AppAuthService;
import zixuan.modules.appauth.vo.AppTokenVO;
import zixuan.modules.security.user.SecurityUser;

class AppAuthControllerTest {
    @Test
    void loginPasswordPassesDeviceLabelAndRemoteIp() {
        AppAuthService service = mock(AppAuthService.class);
        AppAuthController controller = new AppAuthController(service);
        AppPasswordLoginDTO dto = new AppPasswordLoginDTO();
        dto.setPhone("+8613800138000");
        dto.setPassword("enc");
        AppTokenVO issued = new AppTokenVO("app_s", 43200, "appr_s", 2592000);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/app/v1/auth/login-password");
        request.setRemoteAddr("203.0.113.7");
        when(service.loginByPassword(dto, "iphone", "203.0.113.7")).thenReturn(issued);

        var result = controller.loginPassword(dto, request, "iphone");

        assertEquals(issued, result.getData());
    }

    @Test
    void logoutAndProfileUseCurrentSession() {
        AppAuthService service = mock(AppAuthService.class);
        AppAuthController controller = new AppAuthController(service);

        UserDetail current = new UserDetail();
        current.setId(7L);
        current.setToken("app_secret");
        try (MockedStatic<SecurityUser> security = Mockito.mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUser).thenReturn(current);
            security.when(SecurityUser::getUserId).thenReturn(7L);

            assertEquals(0, controller.logout().getCode());
            controller.profile();
        }
        verify(service).logout("app_secret");
        verify(service).profile(7L);
    }
}
