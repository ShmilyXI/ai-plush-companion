package zixuan.modules.conversation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import zixuan.common.utils.JsonUtils;
import zixuan.modules.conversation.vo.PublicConversationApiKeyVO;

class PublicConversationApiKeyRedactionTest {
    @Test
    void nonCreationViewOmitsOneTimeSecretField() {
        PublicConversationApiKeyVO view = new PublicConversationApiKeyVO(
                "key-a", "App", "pc_abcd", Set.of("conversation:text"), Set.of(), null, false,
                null, null, null, null);

        String json = JsonUtils.toJsonString(view);

        assertFalse(json.contains("createdSecret"));
        assertFalse(json.contains("api_key"));
        assertFalse(json.contains("secret"));
    }

    @Test
    void creationViewContainsSecretOnlyWhenExplicitlyProvided() {
        PublicConversationApiKeyVO view = new PublicConversationApiKeyVO(
                "key-a", "App", "pc_abcd", Set.of("conversation:text"), Set.of(), null, false,
                null, null, null, "pc_secret");

        assertTrue(JsonUtils.toJsonString(view).contains("createdSecret"));
    }
}
