package xiaozhi.modules.companion.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

import jakarta.validation.constraints.Size;

class CompanionProfileDtoValidationTest {
    @Test
    void stringLimitsMatchDatabaseColumns() throws Exception {
        assertMax(CompanionProfileCreateDTO.class, "templateId", 64);
        assertMax(CompanionProfileCreateDTO.class, "name", 64);
        assertMax(CompanionProfileSaveDTO.class, "agentName", 64);
        assertMax(CompanionProfileSaveDTO.class, "relationMode", 16);
        assertMax(CompanionProfileSaveDTO.class, "userAddress", 64);
        assertMax(CompanionProfileSaveDTO.class, "personality", 1000);
        assertMax(CompanionProfileSaveDTO.class, "systemPrompt", 16000);
        assertMax(CompanionProfileSaveDTO.class, "ttsVoiceId", 32);
    }

    private void assertMax(Class<?> type, String fieldName, int expected) throws Exception {
        Field field = type.getDeclaredField(fieldName);
        assertEquals(expected, field.getAnnotation(Size.class).max());
    }
}
