package xiaozhi.modules.companion.playground;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import xiaozhi.modules.companion.playground.controller.CompanionPlaygroundController;
import xiaozhi.modules.companion.playground.dto.PlaygroundSessionCreateDTO;
import xiaozhi.modules.companion.playground.service.CompanionPlaygroundService;

class CompanionPlaygroundControllerTest {
    @Test
    void exposesSessionCreateEndpoint() throws Exception {
        CompanionPlaygroundService service = mock(CompanionPlaygroundService.class);
        when(service.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(null);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new CompanionPlaygroundController(service)).build();

        mvc.perform(post("/companion/playground/sessions")
                .contentType("application/json")
                .content("{\"profileId\":\"profile-a\"}"))
                .andExpect(status().isOk());
        verify(service).create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(PlaygroundSessionCreateDTO.class));
    }
}
