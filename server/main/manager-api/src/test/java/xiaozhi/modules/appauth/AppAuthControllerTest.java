package xiaozhi.modules.appauth;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
}
