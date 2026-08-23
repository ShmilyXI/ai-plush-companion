package xiaozhi.modules.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import xiaozhi.modules.conversation.dto.PublicConversationCreateDTO;

class PublicConversationProtocolTest {

    @Test
    void acceptsSupportedModesAndKeepsOverridesAsPublicMetadata() {
        PublicConversationCreateDTO dto = new PublicConversationCreateDTO();
        dto.setAgentId("agent-a");
        dto.setInputModes(Set.of("text", "audio"));
        dto.setOutputModes(Set.of("text", "audio"));
        dto.setVoiceId("voice-a");
        dto.setModelOverrides(Map.of("LLM", "model-a"));

        dto.validateModes();

        assertEquals("agent-a", dto.getAgentId());
        assertEquals("voice-a", dto.getVoiceId());
        assertEquals("model-a", dto.getModelOverrides().get("LLM"));
    }

    @Test
    void rejectsUnsupportedModes() {
        PublicConversationCreateDTO dto = new PublicConversationCreateDTO();
        dto.setAgentId("agent-a");
        dto.setInputModes(Set.of("video"));
        dto.setOutputModes(Set.of("text"));

        assertThrows(IllegalArgumentException.class, dto::validateModes);
    }
}
