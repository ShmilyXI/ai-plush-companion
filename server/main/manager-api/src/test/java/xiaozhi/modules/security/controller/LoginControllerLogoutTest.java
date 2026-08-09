package xiaozhi.modules.security.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import xiaozhi.modules.security.service.CaptchaService;
import xiaozhi.modules.security.service.SysUserTokenService;
import xiaozhi.common.user.UserDetail;
import xiaozhi.modules.sys.service.SysDictDataService;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.sys.service.SysUserService;

class LoginControllerLogoutTest {

    @Test
    void exposesAnAuthenticatedLogoutEndpoint() throws Exception {
        SysUserTokenService tokenService = mock(SysUserTokenService.class);
        LoginController controller = new LoginController(
                mock(SysUserService.class),
                tokenService,
                mock(CaptchaService.class),
                mock(SysParamsService.class),
                mock(SysDictDataService.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        Subject subject = mock(Subject.class);
        UserDetail user = new UserDetail();
        user.setId(7L);
        when(subject.getPrincipal()).thenReturn(user);
        ThreadContext.bind(subject);
        try {
            mvc.perform(post("/user/logout"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
            verify(tokenService).logout(7L);
        } finally {
            ThreadContext.unbindSubject();
        }
    }
}
