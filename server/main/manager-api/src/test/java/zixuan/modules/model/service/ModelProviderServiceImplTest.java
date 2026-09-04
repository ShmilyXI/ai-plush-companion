package zixuan.modules.model.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import zixuan.common.exception.RenException;
import zixuan.common.page.PageData;
import zixuan.modules.companion.capability.dto.PluginDefinitionDTO;
import zixuan.modules.companion.capability.service.CapabilityService;
import zixuan.modules.companion.capability.vo.CapabilityVO;
import zixuan.modules.knowledge.dao.KnowledgeBaseDao;
import zixuan.modules.model.dao.ModelProviderDao;
import zixuan.modules.model.dto.ModelProviderDTO;
import zixuan.modules.model.entity.ModelProviderEntity;
import zixuan.modules.model.service.impl.ModelProviderServiceImpl;

class ModelProviderServiceImplTest {
    private final ModelProviderDao providerDao = mock(ModelProviderDao.class);
    private final KnowledgeBaseDao knowledgeBaseDao = mock(KnowledgeBaseDao.class);
    private final CapabilityService capabilities = mock(CapabilityService.class);
    private final ModelProviderServiceImpl service = new ModelProviderServiceImpl(
            providerDao, knowledgeBaseDao, capabilities);

    @Test
    void legacyPluginListProjectsTheCapabilityCatalogInsteadOfTheOldProviderTable() {
        CapabilityVO weather = new CapabilityVO();
        weather.setId("plugin-weather");
        weather.setType("PLUGIN");
        weather.setName("天气查询");
        weather.setStatus("PUBLISHED");
        PluginDefinitionDTO plugin = new PluginDefinitionDTO();
        plugin.setExecutorName("get_weather");
        plugin.setConfigSchema(java.util.Map.of(
                "type", "object",
                "properties", java.util.Map.of(
                        "location", java.util.Map.of("type", "string", "title", "默认城市", "default", "广州"))));
        weather.setPlugin(plugin);
        when(capabilities.page("PLUGIN", null, null, 1, 100))
                .thenReturn(new PageData<>(List.of(weather), 1));

        List<ModelProviderDTO> result = service.getPluginList();

        assertEquals(1, result.size());
        assertEquals("plugin-weather", result.get(0).getId());
        assertEquals("get_weather", result.get(0).getProviderCode());
        assertTrue(result.get(0).getFields().contains("location"));
        verify(providerDao, never()).selectList(any());
    }

    @Test
    void legacyPluginWritesCannotCreateASecondSourceOfTruth() {
        ModelProviderDTO request = new ModelProviderDTO();
        request.setModelType("Plugin");
        request.setProviderCode("custom_plugin");
        request.setName("旧入口插件");
        request.setFields("[]");
        request.setSort(1);

        assertThrows(RenException.class, () -> service.add(request));
        verify(providerDao, never()).insert(any(ModelProviderEntity.class));
    }

    @Test
    void ttsProviderQueryRestrictsManagementProviders() {
        when(providerDao.selectList(any())).thenReturn(List.of());

        service.getListByModelType("TTS");

        ArgumentCaptor<QueryWrapper<ModelProviderEntity>> wrapper = queryCaptor();
        verify(providerDao).selectList(wrapper.capture());
        assertTrue(wrapper.getValue().getSqlSegment().toLowerCase().contains("provider_code in"));
        assertTrue(wrapper.getValue().getParamNameValuePairs().values().containsAll(List.of(
                "edge", "huoshan_double_stream", "alibl_stream")));
    }

    @Test
    void nonTtsProviderQueryDoesNotReceiveTtsWhitelist() {
        when(providerDao.selectList(any())).thenReturn(List.of());

        service.getListByModelType("LLM");

        ArgumentCaptor<QueryWrapper<ModelProviderEntity>> wrapper = queryCaptor();
        verify(providerDao).selectList(wrapper.capture());
        assertFalse(wrapper.getValue().getSqlSegment().toLowerCase().contains("provider_code in"));
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private ArgumentCaptor<QueryWrapper<ModelProviderEntity>> queryCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(QueryWrapper.class);
    }
}
