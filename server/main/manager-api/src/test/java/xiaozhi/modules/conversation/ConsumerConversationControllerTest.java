package xiaozhi.modules.conversation;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import xiaozhi.modules.conversation.controller.ConsumerConversationController;
import xiaozhi.modules.conversation.entity.CompanionConversationEntity;
import xiaozhi.modules.conversation.service.CompanionConversationIndexService;
import xiaozhi.modules.conversation.service.PublicConversationService;
import xiaozhi.modules.security.user.SecurityUser;

class ConsumerConversationControllerTest {
    @Test
    void listAndRenameUseTheAuthenticatedOwner() throws Exception {
        CompanionConversationIndexService index = mock(CompanionConversationIndexService.class);
        PublicConversationService runtime = mock(PublicConversationService.class);
        CompanionConversationEntity row = new CompanionConversationEntity();
        row.setId("c1");
        row.setOwnerId(7L);
        row.setTitle("今天");
        when(index.list(7L, 100)).thenReturn(List.of(row));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ConsumerConversationController(index, runtime)).build();

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            mvc.perform(get("/api/v1/conversations"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id", is("c1")));
            mvc.perform(patch("/api/v1/conversations/c1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"新的标题\"}"))
                    .andExpect(status().isOk());
            verify(index).rename(7L, "c1", "新的标题");
        }
    }

    @Test
    void deleteUsesSoftDeleteIndex() throws Exception {
        CompanionConversationIndexService index = mock(CompanionConversationIndexService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ConsumerConversationController(index, mock(PublicConversationService.class))).build();
        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(11L);
            mvc.perform(delete("/api/v1/conversations/c1")).andExpect(status().isOk());
            verify(index).softDelete(11L, "c1");
        }
    }
}
