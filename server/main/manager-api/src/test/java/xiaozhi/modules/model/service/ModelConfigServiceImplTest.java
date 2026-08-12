package xiaozhi.modules.model.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import xiaozhi.common.redis.RedisUtils;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.impl.ModelConfigServiceImpl;

class ModelConfigServiceImplTest {
    private final ModelConfigDao modelConfigDao = mock(ModelConfigDao.class);
    private final ModelProviderService providerService = mock(ModelProviderService.class);
    private final RedisUtils redisUtils = mock(RedisUtils.class);
    private final AgentDao agentDao = mock(AgentDao.class);
    private final ModelConfigServiceImpl service = new ModelConfigServiceImpl(
            modelConfigDao, providerService, redisUtils, agentDao);

    @Test
    void ttsPageRestrictsManagementModelsToSupportedIds() {
        when(modelConfigDao.selectPage(any(), any())).thenReturn(new Page<ModelConfigEntity>(1, 10));

        service.getPageList("TTS", null, "1", "10");

        ArgumentCaptor<QueryWrapper<ModelConfigEntity>> wrapper = queryCaptor();
        verify(modelConfigDao).selectPage(any(IPage.class), wrapper.capture());
        assertTrue(wrapper.getValue().getSqlSegment().toLowerCase().contains("id in"));
        assertTrue(wrapper.getValue().getParamNameValuePairs().values().containsAll(Set.of(
                "TTS_EdgeTTS", "TTS_HuoshanDoubleStreamTTS", "TTS_AliBLStreamTTS")));
    }

    @Test
    void nonTtsPageDoesNotReceiveTtsWhitelist() {
        when(modelConfigDao.selectPage(any(), any())).thenReturn(new Page<ModelConfigEntity>(1, 10));

        service.getPageList("LLM", null, "1", "10");

        ArgumentCaptor<QueryWrapper<ModelConfigEntity>> wrapper = queryCaptor();
        verify(modelConfigDao).selectPage(any(IPage.class), wrapper.capture());
        assertFalse(wrapper.getValue().getSqlSegment().toLowerCase().contains("id in"));
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private ArgumentCaptor<QueryWrapper<ModelConfigEntity>> queryCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(QueryWrapper.class);
    }
}
