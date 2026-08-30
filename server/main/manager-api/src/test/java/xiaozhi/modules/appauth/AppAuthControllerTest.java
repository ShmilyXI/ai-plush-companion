package xiaozhi.modules.appauth;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mockStatic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import org.mockito.MockedStatic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;

import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.appauth.controller.AppAccountController;

@ExtendWith(MockitoExtension.class)
class AppAuthControllerTest {

    @Mock
    private AppAuthService service;

    @Test
    void sendsAppCodeWithoutGraphCaptcha() throws Exception {
        when(service.sendCode(any())).thenReturn(new AppAuthCodeVO("challenge-1", "2099-01-01T00:00:00Z", 60));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AppAuthController(service)).build();

        mvc.perform(post("/app/auth/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"email\",\"value\":\"user@example.com\",\"purpose\":\"login\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.challengeId", is("challenge-1")))
                .andExpect(jsonPath("$.data.retryAfterSeconds", is(60)));
    }

    @Test
    void exposesAccountSummaryWithoutPassword() throws Exception {
        when(service.account(7L)).thenReturn(new AppAccountVO(
                new AppAuthUserVO(7L, "App User", "user@example.com", null),
                List.of("email")));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AppAccountController(service)).build();
        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            mvc.perform(get("/app/account"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.user.id", is(7)))
                    .andExpect(jsonPath("$.data.user.password").doesNotExist());
        }
    }
}
