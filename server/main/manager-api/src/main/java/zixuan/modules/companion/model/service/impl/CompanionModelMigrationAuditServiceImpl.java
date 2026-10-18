package zixuan.modules.companion.model.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import lombok.AllArgsConstructor;
import zixuan.modules.agent.dao.AgentDao;
import zixuan.modules.agent.entity.AgentEntity;
import zixuan.modules.companion.model.dao.CompanionProfileModelDao;
import zixuan.modules.companion.model.entity.CompanionProfileModelEntity;
import zixuan.modules.companion.model.service.CompanionModelMigrationAuditService;
import zixuan.modules.companion.model.vo.CompanionModelMigrationAuditReportVO;
import zixuan.modules.model.dao.ModelConfigDao;
import zixuan.modules.model.entity.ModelConfigEntity;
import zixuan.modules.timbre.dao.TimbreDao;
import zixuan.modules.timbre.entity.TimbreEntity;
import zixuan.modules.voiceclone.dao.VoiceCloneDao;
import zixuan.modules.voiceclone.entity.VoiceCloneEntity;

@Service
@AllArgsConstructor
public class CompanionModelMigrationAuditServiceImpl implements CompanionModelMigrationAuditService {
    private static final List<String> MODEL_TYPES = List.of("LLM", "ASR", "TTS", "VAD", "VLLM", "Memory");
    private static final long AGENT_PAGE_SIZE = 500;

    private final AgentDao agentDao;
    private final CompanionProfileModelDao profileModelDao;
    private final ModelConfigDao modelConfigDao;
    private final TimbreDao timbreDao;
    private final VoiceCloneDao voiceCloneDao;

    @Override
    @Transactional(readOnly = true)
    public CompanionModelMigrationAuditReportVO audit() {
        IPage<AgentEntity> agentPage = selectAgentPage(1);
        if (agentPage.getRecords() == null || agentPage.getRecords().isEmpty()) {
            return new CompanionModelMigrationAuditReportVO(0, List.of(), List.of(), List.of(), List.of(), List.of());
        }

        List<ModelConfigEntity> models = nullToEmpty(modelConfigDao.selectList(
                new QueryWrapper<ModelConfigEntity>()
                        .select("id", "model_type", "is_default", "is_enabled")
                        .in("model_type", MODEL_TYPES)));
        List<TimbreEntity> voices = nullToEmpty(timbreDao.selectList(
                new QueryWrapper<TimbreEntity>().select("id", "tts_model_id")));

        Map<String, ModelConfigEntity> modelsById = indexBy(models, ModelConfigEntity::getId);
        Map<String, ModelConfigEntity> enabledDefaults = enabledDefaults(models);
        Map<String, TimbreEntity> voicesById = indexBy(voices, TimbreEntity::getId);
        Set<String> ttsModelsWithVoices = new HashSet<>();
        for (TimbreEntity voice : voices) {
            if (voice != null && StringUtils.isNotBlank(voice.getTtsModelId())) {
                ttsModelsWithVoices.add(voice.getTtsModelId());
            }
        }
        Set<String> privateIds = new TreeSet<>();
        Set<String> missingIds = new TreeSet<>();
        Set<String> invalidTtsIds = new TreeSet<>();
        Set<String> allAgentIds = new TreeSet<>();
        long pageNumber = 1;
        while (true) {
            List<AgentEntity> agents = nullToEmpty(agentPage.getRecords());
            auditAgentPage(agents, modelsById, enabledDefaults, voicesById, ttsModelsWithVoices,
                    allAgentIds, privateIds, missingIds, invalidTtsIds);
            if (agents.size() < AGENT_PAGE_SIZE) break;
            agentPage = selectAgentPage(++pageNumber);
            if (agentPage.getRecords() == null || agentPage.getRecords().isEmpty()) break;
        }

        Set<String> needsSelectionIds = new TreeSet<>();
        needsSelectionIds.addAll(privateIds);
        needsSelectionIds.addAll(missingIds);
        needsSelectionIds.addAll(invalidTtsIds);
        Set<String> readyIds = new TreeSet<>(allAgentIds);
        readyIds.removeAll(needsSelectionIds);
        return new CompanionModelMigrationAuditReportVO(
                allAgentIds.size(), List.copyOf(readyIds), List.copyOf(privateIds), List.copyOf(missingIds),
                List.copyOf(invalidTtsIds), List.copyOf(needsSelectionIds));
    }

    private IPage<AgentEntity> selectAgentPage(long pageNumber) {
        return agentDao.selectPage(new Page<>(pageNumber, AGENT_PAGE_SIZE, false),
                new QueryWrapper<AgentEntity>()
                        .select("id", "user_id", "companion_enabled", "llm_model_id", "asr_model_id",
                                "tts_model_id", "vad_model_id", "vllm_model_id", "mem_model_id", "tts_voice_id")
                        .eq("companion_enabled", 1)
                        .orderByAsc("id"));
    }

    private void auditAgentPage(List<AgentEntity> agents, Map<String, ModelConfigEntity> modelsById,
            Map<String, ModelConfigEntity> enabledDefaults, Map<String, TimbreEntity> voicesById,
            Set<String> ttsModelsWithVoices, Set<String> allAgentIds, Set<String> privateIds,
            Set<String> missingIds, Set<String> invalidTtsIds) {
        List<AgentEntity> companionAgents = agents.stream()
                .filter(agent -> agent != null && Integer.valueOf(1).equals(agent.getCompanionEnabled()))
                .toList();
        List<String> agentIds = companionAgents.stream()
                .filter(agent -> agent != null && StringUtils.isNotBlank(agent.getId()))
                .map(AgentEntity::getId).distinct().toList();
        if (agentIds.isEmpty()) return;
        List<CompanionProfileModelEntity> bindings = nullToEmpty(profileModelDao.selectList(
                new QueryWrapper<CompanionProfileModelEntity>()
                        .select("agent_id", "model_type", "source_type", "resource_id")
                        .in("agent_id", agentIds)));
        Map<String, List<CompanionProfileModelEntity>> bindingsByAgent = new HashMap<>();
        for (CompanionProfileModelEntity binding : bindings) {
            if (binding != null && StringUtils.isNotBlank(binding.getAgentId())) {
                bindingsByAgent.computeIfAbsent(binding.getAgentId(), ignored -> new ArrayList<>()).add(binding);
            }
        }
        List<String> cloneCandidateIds = companionAgents.stream()
                .map(AgentEntity::getTtsVoiceId)
                .filter(StringUtils::isNotBlank)
                .filter(voiceId -> !voicesById.containsKey(voiceId))
                .distinct()
                .toList();
        Map<String, VoiceCloneEntity> clonesById = cloneCandidateIds.isEmpty()
                ? Map.of()
                : indexBy(nullToEmpty(voiceCloneDao.selectList(
                        new QueryWrapper<VoiceCloneEntity>()
                                .select("id", "model_id", "user_id", "train_status")
                                .in("id", cloneCandidateIds))), VoiceCloneEntity::getId);
        for (AgentEntity agent : companionAgents) {
            if (agent == null || StringUtils.isBlank(agent.getId())) continue;
            String agentId = agent.getId();
            allAgentIds.add(agentId);
            auditAgent(agentId, agent, bindingsByAgent.getOrDefault(agentId, List.of()),
                    modelsById, enabledDefaults, voicesById, clonesById, ttsModelsWithVoices,
                    privateIds, missingIds, invalidTtsIds);
        }
    }

    private void auditAgent(String agentId, AgentEntity agent, List<CompanionProfileModelEntity> bindings,
            Map<String, ModelConfigEntity> modelsById, Map<String, ModelConfigEntity> enabledDefaults,
            Map<String, TimbreEntity> voicesById, Map<String, VoiceCloneEntity> clonesById,
            Set<String> ttsModelsWithVoices,
            Set<String> privateIds, Set<String> missingIds, Set<String> invalidTtsIds) {
        Map<String, CompanionProfileModelEntity> bindingsByType = new HashMap<>();
        boolean bindingAnomaly = false;
        for (CompanionProfileModelEntity binding : bindings) {
            if (binding == null) continue;
            if (!MODEL_TYPES.contains(binding.getModelType())) {
                bindingAnomaly = true;
            } else if (bindingsByType.putIfAbsent(binding.getModelType(), binding) != null) {
                bindingAnomaly = true;
            }
        }
        // Malformed or duplicate bindings have no trustworthy final selection, so they share the missing bucket.
        if (bindingAnomaly) missingIds.add(agentId);
        for (String modelType : MODEL_TYPES) {
            ModelResolution resolution = resolveModel(
                    modelType, bindingsByType.get(modelType), legacyModelId(agent, modelType),
                    modelsById, enabledDefaults);
            if (resolution.privateBinding()) {
                privateIds.add(agentId);
                continue;
            }
            if (!resolution.valid()) {
                missingIds.add(agentId);
            }
            if ("TTS".equals(modelType) && resolution.runtimeValid()) {
                if (!validTtsVoice(agent, resolution.model().getId(), voicesById, clonesById,
                        ttsModelsWithVoices)) {
                    invalidTtsIds.add(agentId);
                }
            }
        }
    }

    private boolean validTtsVoice(AgentEntity agent, String resolvedTtsModelId,
            Map<String, TimbreEntity> voicesById, Map<String, VoiceCloneEntity> clonesById,
            Set<String> ttsModelsWithVoices) {
        String voiceId = agent.getTtsVoiceId();
        if (StringUtils.isBlank(voiceId)) {
            return !ttsModelsWithVoices.contains(resolvedTtsModelId);
        }
        TimbreEntity voice = voicesById.get(voiceId);
        if (voice != null) {
            return resolvedTtsModelId.equals(voice.getTtsModelId());
        }
        VoiceCloneEntity clone = clonesById.get(voiceId);
        return clone != null
                && Integer.valueOf(2).equals(clone.getTrainStatus())
                && agent.getUserId() != null
                && agent.getUserId().equals(clone.getUserId())
                && resolvedTtsModelId.equals(clone.getModelId());
    }

    private ModelResolution resolveModel(String modelType, CompanionProfileModelEntity binding, String legacyId,
            Map<String, ModelConfigEntity> modelsById, Map<String, ModelConfigEntity> enabledDefaults) {
        if (binding != null && "private".equals(binding.getSourceType())) {
            return new ModelResolution(true, null, false, false);
        }
        ModelConfigEntity runtimeModel = StringUtils.isBlank(legacyId) ? null : modelsById.get(legacyId);
        boolean runtimeValid = validModel(runtimeModel, modelType);
        if (binding == null) {
            return new ModelResolution(false, runtimeModel, runtimeValid, runtimeValid);
        }
        if ("global".equals(binding.getSourceType())) {
            boolean aligned = runtimeValid && Objects.equals(legacyId, binding.getResourceId());
            return new ModelResolution(false, runtimeModel, aligned, runtimeValid);
        }
        if ("default".equals(binding.getSourceType())) {
            ModelConfigEntity enabledDefault = enabledDefaults.get(modelType);
            boolean aligned = runtimeValid && enabledDefault != null
                    && Objects.equals(legacyId, enabledDefault.getId());
            return new ModelResolution(false, runtimeModel, aligned, runtimeValid);
        }
        return new ModelResolution(false, runtimeModel, false, runtimeValid);
    }

    private String legacyModelId(AgentEntity agent, String modelType) {
        return switch (modelType) {
            case "LLM" -> agent.getLlmModelId();
            case "ASR" -> agent.getAsrModelId();
            case "TTS" -> agent.getTtsModelId();
            case "VAD" -> agent.getVadModelId();
            case "VLLM" -> agent.getVllmModelId();
            case "Memory" -> agent.getMemModelId();
            default -> null;
        };
    }

    private boolean validModel(ModelConfigEntity model, String expectedType) {
        return model != null && expectedType.equals(model.getModelType())
                && Integer.valueOf(1).equals(model.getIsEnabled());
    }

    private Map<String, ModelConfigEntity> enabledDefaults(List<ModelConfigEntity> models) {
        Map<String, List<ModelConfigEntity>> candidatesByType = new HashMap<>();
        for (ModelConfigEntity model : models) {
            if (model != null && MODEL_TYPES.contains(model.getModelType())
                    && Integer.valueOf(1).equals(model.getIsEnabled())
                    && Integer.valueOf(1).equals(model.getIsDefault())) {
                candidatesByType.computeIfAbsent(model.getModelType(), ignored -> new ArrayList<>()).add(model);
            }
        }
        Map<String, ModelConfigEntity> result = new HashMap<>();
        candidatesByType.forEach((type, candidates) -> {
            if (candidates.size() == 1) result.put(type, candidates.getFirst());
        });
        return result;
    }

    private <T> Map<String, T> indexBy(List<T> values, Function<T, String> idExtractor) {
        Map<String, T> result = new LinkedHashMap<>();
        for (T value : values) {
            if (value == null) continue;
            String id = idExtractor.apply(value);
            if (StringUtils.isNotBlank(id)) result.putIfAbsent(id, value);
        }
        return result;
    }

    private <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record ModelResolution(boolean privateBinding, ModelConfigEntity model, boolean valid,
            boolean runtimeValid) {
    }
}
