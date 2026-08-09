package xiaozhi.modules.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import xiaozhi.modules.agent.dto.AgentChatHistoryDTO;
import xiaozhi.modules.agent.dto.AgentUpdateDTO;
import xiaozhi.modules.agent.entity.AgentChatHistoryEntity;
import xiaozhi.modules.agent.service.AgentChatHistoryService;
import xiaozhi.modules.agent.service.AgentChatTitleService;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.agent.vo.AgentInfoVO;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.service.DeviceService;
import xiaozhi.modules.llm.service.LLMService;
import xiaozhi.modules.model.service.ModelConfigService;

class AgentChatSummaryServiceImplTest {

    @ParameterizedTest(name = "modelId={0}, summary={1}")
    @MethodSource("failureSummaries")
    void openAiStyleFailureSummaryIsNotWrittenToAgentMemory(String modelId, String failureSummary) {
        AgentChatHistoryService historyService = mock(AgentChatHistoryService.class);
        AgentService agentService = mock(AgentService.class);
        AgentChatTitleService titleService = mock(AgentChatTitleService.class);
        DeviceService deviceService = mock(DeviceService.class);
        LLMService llmService = mock(LLMService.class);
        ModelConfigService modelConfigService = mock(ModelConfigService.class);
        AgentChatSummaryServiceImpl service = new AgentChatSummaryServiceImpl(
                historyService, agentService, titleService, deviceService, llmService, modelConfigService);

        String sessionId = "session-id";
        String agentId = "agent-id";
        String macAddress = "device-mac";

        AgentChatHistoryEntity session = new AgentChatHistoryEntity();
        session.setAgentId(agentId);
        session.setMacAddress(macAddress);
        when(historyService.getOne(any())).thenReturn(session);

        DeviceEntity device = new DeviceEntity();
        device.setAgentId(agentId);
        when(deviceService.getDeviceByMacAddress(macAddress)).thenReturn(device);

        AgentInfoVO agent = new AgentInfoVO();
        agent.setMemModelId("Memory_mem_local_short");
        agent.setSlmModelId(modelId);
        when(agentService.getAgentById(agentId)).thenReturn(agent);
        when(modelConfigService.getEnabledModelsByType("LLM")).thenReturn(List.of());

        AgentChatHistoryDTO userMessage = new AgentChatHistoryDTO();
        userMessage.setChatType((byte) 1);
        userMessage.setContent("请记住我喜欢茉莉花茶");
        when(historyService.getChatHistoryBySessionId(agentId, sessionId)).thenReturn(List.of(userMessage));
        when(llmService.generateSummaryWithHistory(any(), any(), any(), eq(modelId)))
                .thenReturn(failureSummary);

        assertTrue(service.generateAndSaveChatSummary(sessionId));

        verify(agentService, never()).updateAgentById(eq(agentId), any(AgentUpdateDTO.class), eq(false));
    }

    private static Stream<Arguments> failureSummaries() {
        List<String> summaries = Arrays.asList(
                null,
                "   \t\n",
                "服务暂不可用",
                "总结生成失败",
                "生成总结失败，请稍后重试",
                "LLM服务不可用，无法生成总结",
                "未找到可用的LLM模型配置",
                "LLM配置不完整，无法生成总结");

        return Stream.<String>of(null, "LLM_Test")
                .flatMap(modelId -> summaries.stream().map(summary -> Arguments.of(modelId, summary)));
    }
}
