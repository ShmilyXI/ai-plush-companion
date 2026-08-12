package xiaozhi.modules.model.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import xiaozhi.modules.knowledge.dao.KnowledgeBaseDao;
import xiaozhi.modules.model.dao.ModelProviderDao;
import xiaozhi.modules.model.entity.ModelProviderEntity;
import xiaozhi.modules.model.service.impl.ModelProviderServiceImpl;

class ModelProviderServiceImplTest {
    private final ModelProviderDao providerDao = mock(ModelProviderDao.class);
    private final KnowledgeBaseDao knowledgeBaseDao = mock(KnowledgeBaseDao.class);
    private final ModelProviderServiceImpl service = new ModelProviderServiceImpl(providerDao, knowledgeBaseDao);

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
