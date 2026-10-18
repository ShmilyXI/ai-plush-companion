package zixuan.modules.companion.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import zixuan.common.exception.RenException;
import zixuan.modules.companion.model.dao.CompanionModelPresetMetaDao;
import zixuan.modules.companion.model.entity.CompanionModelPresetMetaEntity;
import zixuan.modules.companion.model.service.impl.CompanionModelPresetServiceImpl;
import zixuan.modules.companion.model.vo.CompanionModelPresetVO;

class CompanionModelPresetServiceImplTest {
    private final CompanionModelPresetMetaDao dao = mock(CompanionModelPresetMetaDao.class);
    private final CompanionModelPresetServiceImpl service = new CompanionModelPresetServiceImpl(dao);

    @Test
    void parsesVendorMetadataWithoutTreatingAdapterAsVendor() {
        CompanionModelPresetMetaEntity row = row("LLM_DeepSeekLLM", "deepseek", "DeepSeek", "OpenAI 兼容");
        row.setDefaultApiUrl("https://api.deepseek.com");
        row.setCredentialRequirement("required");
        row.setCredentialFieldsJson(
                "[{\"key\":\"api_key\",\"label\":\"API Key\",\"type\":\"string\",\"required\":true,\"secret\":true}]");
        row.setKeyUrl("https://platform.deepseek.com/api_keys");
        row.setDocsUrl("https://api-docs.deepseek.com/");
        row.setSetupGuideJson("[\"创建密钥\",\"保存后测试连接\"]");
        when(dao.selectById("LLM_DeepSeekLLM")).thenReturn(row);

        CompanionModelPresetVO result = service.get("LLM_DeepSeekLLM");

        assertEquals("DeepSeek", result.getVendorName());
        assertEquals("OpenAI 兼容", result.getProtocol());
        assertEquals("https://api.deepseek.com", result.getDefaultApiUrl());
        assertEquals(List.of("api_key"), result.getCredentialFields().stream().map(field -> field.getKey()).toList());
        assertEquals(Set.of("api_key"), service.credentialKeys("LLM_DeepSeekLLM"));
    }

    @Test
    void parsesLocalPresetThatDoesNotNeedCredentials() {
        CompanionModelPresetMetaEntity row = row("LLM_OllamaLLM", "ollama", "Ollama", "OpenAI 兼容");
        row.setDefaultApiUrl("http://localhost:11434/v1");
        row.setCredentialRequirement("not_required");
        row.setCredentialFieldsJson("[]");
        row.setDocsUrl("https://docs.ollama.com/api/openai-compatibility");
        row.setSetupGuideJson("[\"启动 Ollama\"]");
        when(dao.selectById("LLM_OllamaLLM")).thenReturn(row);

        CompanionModelPresetVO result = service.get("LLM_OllamaLLM");

        assertEquals("not_required", result.getCredentialRequirement());
        assertEquals(List.of(), result.getCredentialFields());
    }

    @Test
    void returnsUnknownFallbackWhenMetadataIsMissing() {
        when(dao.selectById("LLM_Unmapped")).thenReturn(null);

        CompanionModelPresetVO result = service.get("LLM_Unmapped");

        assertEquals("LLM_Unmapped", result.getGlobalModelId());
        assertNull(result.getVendorName());
        assertNull(result.getVendorCode());
        assertNull(result.getProtocol());
        assertEquals("unknown", result.getCredentialRequirement());
    }

    @Test
    void rejectsUnsafeGuideOrApiUrls() {
        CompanionModelPresetMetaEntity row = row("LLM_Bad", "bad", "Bad", "OpenAI 兼容");
        row.setDefaultApiUrl("file:///tmp/key");
        row.setCredentialRequirement("not_required");
        row.setCredentialFieldsJson("[]");
        row.setDocsUrl("https://example.com/docs");
        row.setSetupGuideJson("[]");
        when(dao.selectById("LLM_Bad")).thenReturn(row);

        assertThrows(RenException.class, () -> service.get("LLM_Bad"));
    }

    @Test
    void rejectsMalformedCredentialFieldJson() {
        CompanionModelPresetMetaEntity row = row("LLM_BadFields", "bad", "Bad", "OpenAI 兼容");
        row.setCredentialRequirement("required");
        row.setCredentialFieldsJson("[{\"key\":\"api_key\"}]");
        row.setDocsUrl("https://example.com/docs");
        row.setSetupGuideJson("[]");
        when(dao.selectById("LLM_BadFields")).thenReturn(row);

        assertThrows(RenException.class, () -> service.get("LLM_BadFields"));
    }

    private CompanionModelPresetMetaEntity row(String id, String vendorCode, String vendorName, String protocol) {
        CompanionModelPresetMetaEntity row = new CompanionModelPresetMetaEntity();
        row.setGlobalModelId(id);
        row.setVendorCode(vendorCode);
        row.setVendorName(vendorName);
        row.setProtocol(protocol);
        return row;
    }
}
