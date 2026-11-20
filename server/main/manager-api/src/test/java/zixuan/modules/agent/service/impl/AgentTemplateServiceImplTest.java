package zixuan.modules.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

import zixuan.modules.agent.entity.AgentTemplateEntity;

class AgentTemplateServiceImplTest {

    @ParameterizedTest
    @ValueSource(strings = {"Embedding", "RAG", "Plugin"})
    void modelTypesWithoutATemplateColumnLeaveTemplatesAlone(String modelType) {
        AgentTemplateServiceImpl service = spy(new AgentTemplateServiceImpl());

        service.updateDefaultTemplateModelId(modelType, "model-1");

        verify(service, never()).update(any(Wrapper.class));
    }

    @Test
    void conversationModelIsWrittenToEveryTemplate() {
        AgentTemplateServiceImpl service = spy(new AgentTemplateServiceImpl());
        doReturn(true).when(service).update(any(Wrapper.class));

        service.updateDefaultTemplateModelId("LLM", "model-1");

        assertTrue(capturedSet(service).startsWith("llm_model_id="));
    }

    @Test
    void changingTheSpeechModelClearsTheVoiceThatBelongedToTheOldOne() {
        AgentTemplateServiceImpl service = spy(new AgentTemplateServiceImpl());
        doReturn(true).when(service).update(any(Wrapper.class));

        service.updateDefaultTemplateModelId("TTS", "model-1");

        String set = capturedSet(service);
        assertTrue(set.contains("tts_model_id="));
        assertTrue(set.contains("tts_voice_id="));
    }

    @SuppressWarnings("unchecked")
    private static String capturedSet(AgentTemplateServiceImpl service) {
        ArgumentCaptor<Wrapper<AgentTemplateEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(service).update(captor.capture());
        return ((UpdateWrapper<AgentTemplateEntity>) captor.getValue()).getSqlSet();
    }
}
