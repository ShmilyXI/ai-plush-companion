package xiaozhi.modules.companion.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import xiaozhi.modules.companion.service.AppProfileFacade;
import xiaozhi.modules.companion.vo.AppAvatarContent;
import xiaozhi.modules.security.user.SecurityUser;

class AppProfileAvatarResourceControllerTest {
    @Test
    void servesOwnerAvatarBytesWithThePersistedMediaType() throws Exception {
        AppProfileFacade facade = mock(AppProfileFacade.class);
        when(facade.loadAvatar(7L, "p1", "a".repeat(64)))
                .thenReturn(new AppAvatarContent(new byte[] {1, 2, 3}, "image/png"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new AppProfileAvatarResourceController(facade)).build();

        try (MockedStatic<SecurityUser> security = Mockito.mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            mvc.perform(get("/app/assets/avatars/p1/" + "a".repeat(64)))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.IMAGE_PNG))
                    .andExpect(content().bytes(new byte[] {1, 2, 3}));
        }
    }

    @Test
    void hidesMissingOrUnauthorizedAvatarAsNotFound() throws Exception {
        AppProfileFacade facade = mock(AppProfileFacade.class);
        when(facade.loadAvatar(7L, "p1", "b".repeat(64)))
                .thenThrow(new IllegalArgumentException("not found"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new AppProfileAvatarResourceController(facade)).build();

        try (MockedStatic<SecurityUser> security = Mockito.mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            mvc.perform(get("/app/assets/avatars/p1/" + "b".repeat(64)))
                    .andExpect(status().isNotFound());
        }
    }
}
