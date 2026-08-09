package xiaozhi.modules.companion.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import xiaozhi.modules.companion.model.service.impl.CompanionModelTemplateServiceImpl;
import xiaozhi.modules.companion.model.vo.CompanionModelProviderTemplateVO;
import xiaozhi.modules.model.dto.ModelProviderDTO;
import xiaozhi.modules.model.service.ModelProviderService;

class CompanionModelTemplateServiceImplTest {
    private final ModelProviderService providers = mock(ModelProviderService.class);
    private final CompanionModelTemplateServiceImpl service = new CompanionModelTemplateServiceImpl(providers);

    @Test
    void parsesProviderFieldsAndMarksCredentials() {
        when(providers.getListByModelType("LLM")).thenReturn(List.of(provider(
                "SYSTEM_LLM_openai", "LLM", "openai", "OpenAI",
                "[{\"key\":\"api_key\",\"label\":\"API Key\",\"type\":\"password\"},"
                        + "{\"key\":\"temperature\",\"label\":\"温度\",\"type\":\"float\",\"default\":0.7},"
                        + "{\"key\":\"stream\",\"label\":\"流式\",\"type\":\"boolean\"}]",
                1)));

        List<CompanionModelProviderTemplateVO> result = service.list("LLM");

        assertEquals(1, result.size());
        CompanionModelProviderTemplateVO template = result.getFirst();
        assertEquals("openai", template.getProviderCode());
        assertEquals("string", template.getFields().getFirst().getType());
        assertTrue(template.getFields().getFirst().isSecret());
        assertEquals("number", template.getFields().get(1).getType());
        assertEquals(0.7D, ((Number) template.getFields().get(1).getDefaultValue()).doubleValue());
        assertFalse(template.getFields().get(2).isSecret());
    }

    @Test
    void skipsInvalidProvidersAndSortsValidTemplates() {
        when(providers.getListByModelType("TTS")).thenReturn(List.of(
                provider("bad", "TTS", "bad", "坏模板", "not-json", 1),
                provider("later", "TTS", "later", "后一个", "[]", 20),
                provider("first", "TTS", "first", "前一个", "[]", 10)));

        List<CompanionModelProviderTemplateVO> result = service.list("TTS");

        assertEquals(List.of("first", "later"), result.stream().map(CompanionModelProviderTemplateVO::getId).toList());
    }

    @Test
    void recognizesCredentialNamesConservatively() {
        assertTrue(service.isSecretKey("access_token"));
        assertTrue(service.isSecretKey("client_secret"));
        assertTrue(service.isSecretKey("Authorization"));
        assertFalse(service.isSecretKey("model_name"));
        assertFalse(service.isSecretKey("temperature"));
    }

    private ModelProviderDTO provider(String id, String modelType, String providerCode, String name,
            String fields, int sort) {
        ModelProviderDTO dto = new ModelProviderDTO();
        dto.setId(id);
        dto.setModelType(modelType);
        dto.setProviderCode(providerCode);
        dto.setName(name);
        dto.setFields(fields);
        dto.setSort(sort);
        return dto;
    }
}
