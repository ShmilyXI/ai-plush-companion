package xiaozhi.modules.companion.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import xiaozhi.modules.companion.dto.AppProfileSaveDTO;
import xiaozhi.modules.companion.service.AppProfileFacade;
import xiaozhi.modules.companion.vo.CompanionProfileVO;
import xiaozhi.modules.security.user.SecurityUser;

class AppProfileControllerTest {
    @Test
    void memorySettingsUsesCurrentOwnerAndReturnsSuccessEnvelope() throws Exception {
        AppProfileFacade facade = mock(AppProfileFacade.class);
        AppProfileController controller = new AppProfileController(facade);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            mvc.perform(put("/app/profiles/p1/memory-settings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)));
            verify(facade).setMemoryEnabled(7L, "p1", false);
        }
    }

    @Test
    void listReturnsOnlyFacadeData() throws Exception {
        AppProfileFacade facade = mock(AppProfileFacade.class);
        CompanionProfileVO profile = new CompanionProfileVO();
        profile.setId("p1");
        profile.setName("露娜");
        when(facade.list(7L)).thenReturn(List.of(profile));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AppProfileController(facade)).build();

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            mvc.perform(get("/app/profiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id", is("p1")))
                .andExpect(jsonPath("$.data[0].name", is("露娜")));
        }
    }
}
