package zixuan.modules.companion.service.impl;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.AllArgsConstructor;
import zixuan.common.constant.Constant;
import zixuan.common.exception.RenException;
import zixuan.modules.agent.entity.AgentTemplateEntity;
import zixuan.modules.agent.service.AgentTemplateService;
import zixuan.modules.companion.dto.AdminSystemSettingsSaveDTO;
import zixuan.modules.companion.service.AdminSystemSettingsService;
import zixuan.modules.companion.service.CompanionAuditService;
import zixuan.modules.companion.vo.AdminSystemSettingsVO;
import zixuan.modules.config.service.ConfigService;
import zixuan.modules.model.entity.ModelConfigEntity;
import zixuan.modules.model.service.ModelConfigService;
import zixuan.modules.sys.service.SysParamsService;
import zixuan.modules.timbre.service.TimbreService;
import zixuan.modules.timbre.entity.TimbreEntity;

@Service
@AllArgsConstructor
public class AdminSystemSettingsServiceImpl implements AdminSystemSettingsService {
    private static final List<String> MODEL_TYPES = List.of("LLM", "VLLM", "TTS", "ASR", "VAD", "Memory");
    private static final List<String> SYSTEM_PARAM_CODES = List.of(
            Constant.SERVER_WEBSOCKET, Constant.SERVER_OTA, Constant.SERVER_IP,
            Constant.SERVER_PORT, Constant.SERVER_OTA_IP, Constant.SERVER_OTA_PORT,
            Constant.COMPANION_PROACTIVE_PLANNER_PROMPT);
    private static final Pattern HOST_PATTERN = Pattern.compile(
            "^(localhost|[a-zA-Z0-9](?:[a-zA-Z0-9.-]{0,251}[a-zA-Z0-9])?|[0-9a-fA-F:]+)$");

    private final SysParamsService params;
    private final ModelConfigService models;
    private final AgentTemplateService templates;
    private final TimbreService timbres;
    private final ConfigService configService;
    private final CompanionAuditService auditService;

    @Override
    public AdminSystemSettingsVO get() {
        String publicWebsocketUrl = value(Constant.SERVER_WEBSOCKET, "");
        String publicOtaUrl = value(Constant.SERVER_OTA, "");
        String zixuanHost = value(Constant.SERVER_IP, "0.0.0.0");
        int zixuanPort = port(Constant.SERVER_PORT, 8000);
        String otaHost = value(Constant.SERVER_OTA_IP, "0.0.0.0");
        int otaPort = port(Constant.SERVER_OTA_PORT, 8002);
        AgentTemplateEntity template = templates.getDefaultTemplate();
        if (template == null) {
            throw new RenException("默认角色模板不存在");
        }

        Map<String, List<AdminSystemSettingsVO.Option>> options = new LinkedHashMap<>();
        for (String type : MODEL_TYPES) {
            List<ModelConfigEntity> enabled = models.getEnabledModelsByType(type);
            options.put(type, enabled == null ? List.of() : enabled.stream()
                    .map(model -> new AdminSystemSettingsVO.Option(
                            model.getId(), model.getModelName(), model.getModelType()))
                    .toList());
        }
        List<AdminSystemSettingsVO.Option> voices = StringUtils.isBlank(template.getTtsModelId())
                ? List.of()
                : timbres.getVoiceNames(template.getTtsModelId(), null).stream()
                        .map(voice -> new AdminSystemSettingsVO.Option(voice.getId(), voice.getName(), "TTS_VOICE"))
                        .toList();
        Map<String, AdminSystemSettingsVO.Health> health = Map.of(
                "zixuan", health(zixuanHost, zixuanPort),
                "ota", health(otaHost, otaPort));

        return AdminSystemSettingsVO.builder()
                .publicWebsocketUrl(publicWebsocketUrl)
                .publicOtaUrl(publicOtaUrl)
                .zixuanListenHost(zixuanHost)
                .zixuanListenPort(zixuanPort)
                .otaListenHost(otaHost)
                .otaListenPort(otaPort)
                .defaultLlmModelId(template.getLlmModelId())
                .defaultVllmModelId(template.getVllmModelId())
                .defaultTtsModelId(template.getTtsModelId())
                .defaultAsrModelId(template.getAsrModelId())
                .defaultVadModelId(template.getVadModelId())
                .defaultMemoryModelId(template.getMemModelId())
                .defaultTtsVoiceId(template.getTtsVoiceId())
                .proactivePlannerPrompt(value(Constant.COMPANION_PROACTIVE_PLANNER_PROMPT, ""))
                .modelOptions(options)
                .voices(voices)
                .health(health)
                .restartRequired(false)
                .restartServices(List.of())
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminSystemSettingsVO save(Long operatorId, AdminSystemSettingsSaveDTO request) {
        String websocketUrl = requireEndpoint("publicWebsocketUrl", request.getPublicWebsocketUrl(),
                Set.of("ws", "wss"));
        String otaUrl = requireEndpoint("publicOtaUrl", request.getPublicOtaUrl(), Set.of("http", "https"));
        String zixuanHost = requireHost("zixuanListenHost", request.getZixuanListenHost());
        String otaHost = requireHost("otaListenHost", request.getOtaListenHost());
        int zixuanPort = requirePort("zixuanListenPort", request.getZixuanListenPort());
        int otaPort = requirePort("otaListenPort", request.getOtaListenPort());

        requireEnabledModel("defaultLlmModelId", "LLM", request.getDefaultLlmModelId());
        requireOptionalEnabledModel("defaultVllmModelId", "VLLM", request.getDefaultVllmModelId());
        requireEnabledModel("defaultTtsModelId", "TTS", request.getDefaultTtsModelId());
        requireEnabledModel("defaultAsrModelId", "ASR", request.getDefaultAsrModelId());
        requireEnabledModel("defaultVadModelId", "VAD", request.getDefaultVadModelId());
        requireOptionalEnabledModel("defaultMemoryModelId", "Memory", request.getDefaultMemoryModelId());
        requireVoice(request.getDefaultTtsVoiceId(), request.getDefaultTtsModelId());
        Snapshot before = snapshot();

        params.upsertValueByCode(Constant.SERVER_WEBSOCKET, websocketUrl, "string", "设备公开 WebSocket 地址");
        params.upsertValueByCode(Constant.SERVER_OTA, otaUrl, "string", "设备公开 OTA 地址");
        params.upsertValueByCode(Constant.SERVER_IP, zixuanHost, "string", "zixuan-server 监听地址");
        params.upsertValueByCode(Constant.SERVER_PORT, String.valueOf(zixuanPort), "number", "zixuan-server 监听端口");
        params.upsertValueByCode(Constant.SERVER_OTA_IP, otaHost, "string", "OTA 服务监听地址");
        params.upsertValueByCode(Constant.SERVER_OTA_PORT, String.valueOf(otaPort), "number", "OTA 服务监听端口");
        params.upsertValueByCode(Constant.COMPANION_PROACTIVE_PLANNER_PROMPT,
                StringUtils.defaultString(request.getProactivePlannerPrompt()).trim(),
                "string", "主动陪伴全局规划提示词");

        selectDefault("LLM", request.getDefaultLlmModelId());
        selectOptionalDefault("VLLM", request.getDefaultVllmModelId());
        selectDefault("TTS", request.getDefaultTtsModelId());
        selectDefault("ASR", request.getDefaultAsrModelId());
        selectDefault("VAD", request.getDefaultVadModelId());
        selectOptionalDefault("Memory", request.getDefaultMemoryModelId());
        templates.updateDefaultTemplateVoiceId(request.getDefaultTtsVoiceId());

        try {
            params.evictCache(SYSTEM_PARAM_CODES);
            configService.evictCache();
            configService.getConfig(false);
        } catch (RuntimeException error) {
            params.evictCache(SYSTEM_PARAM_CODES);
            configService.evictCache();
            throw error;
        }

        Snapshot after = new Snapshot(websocketUrl, otaUrl, zixuanHost, zixuanPort, otaHost, otaPort,
                request.getDefaultLlmModelId(), blankToNull(request.getDefaultVllmModelId()),
                request.getDefaultTtsModelId(), request.getDefaultAsrModelId(), request.getDefaultVadModelId(),
                blankToNull(request.getDefaultMemoryModelId()), request.getDefaultTtsVoiceId(),
                blankToNull(request.getProactivePlannerPrompt()));
        auditService.record(operatorId, null, "system-settings.update", "system-settings", null,
                Map.of("changedFields", changes(before, after)));

        List<String> restartServices = new ArrayList<>();
        if (!before.zixuanListenHost().equals(after.zixuanListenHost())
                || before.zixuanListenPort() != after.zixuanListenPort()) {
            restartServices.add("zixuan");
        }
        if (!before.otaListenHost().equals(after.otaListenHost()) || before.otaListenPort() != after.otaListenPort()) {
            restartServices.add("ota");
        }
        AdminSystemSettingsVO result = get();
        result.setRestartRequired(!restartServices.isEmpty());
        result.setRestartServices(restartServices);
        return result;
    }

    private Snapshot snapshot() {
        AgentTemplateEntity template = templates.getDefaultTemplate();
        if (template == null) {
            throw new RenException("默认角色模板不存在");
        }
        return new Snapshot(
                value(Constant.SERVER_WEBSOCKET, ""), value(Constant.SERVER_OTA, ""),
                value(Constant.SERVER_IP, "0.0.0.0"), port(Constant.SERVER_PORT, 8000),
                value(Constant.SERVER_OTA_IP, "0.0.0.0"), port(Constant.SERVER_OTA_PORT, 8002),
                template.getLlmModelId(), template.getVllmModelId(), template.getTtsModelId(),
                template.getAsrModelId(), template.getVadModelId(), template.getMemModelId(),
                template.getTtsVoiceId(), value(Constant.COMPANION_PROACTIVE_PLANNER_PROMPT, ""));
    }

    private String requireEndpoint(String field, String value, Set<String> schemes) {
        try {
            URI uri = URI.create(StringUtils.trimToEmpty(value));
            if (!schemes.contains(StringUtils.lowerCase(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return uri.toString();
        } catch (IllegalArgumentException error) {
            throw new RenException(field + " 格式不正确");
        }
    }

    private String requireHost(String field, String value) {
        String host = StringUtils.trimToEmpty(value);
        if (!HOST_PATTERN.matcher(host).matches() || host.contains("..")) {
            throw new RenException(field + " 格式不正确");
        }
        return host;
    }

    private int requirePort(String field, Integer value) {
        if (value == null || value < 1 || value > 65535) {
            throw new RenException(field + " 必须在 1 到 65535 之间");
        }
        return value;
    }

    private ModelConfigEntity requireEnabledModel(String field, String type, String id) {
        if (StringUtils.isBlank(id)) {
            throw new RenException(field + " 不能为空");
        }
        ModelConfigEntity model = models.selectById(id.trim());
        if (model == null) {
            throw new RenException(field + " 模型不存在");
        }
        if (!type.equalsIgnoreCase(model.getModelType())) {
            throw new RenException(field + " 模型类型不匹配");
        }
        if (!Integer.valueOf(1).equals(model.getIsEnabled())) {
            throw new RenException(field + " 模型未启用");
        }
        return model;
    }

    private void requireOptionalEnabledModel(String field, String type, String id) {
        if (StringUtils.isNotBlank(id)) {
            requireEnabledModel(field, type, id);
        }
    }

    private void requireVoice(String voiceId, String ttsModelId) {
        if (StringUtils.isBlank(voiceId)) {
            throw new RenException("defaultTtsVoiceId 不能为空");
        }
        TimbreEntity voice = timbres.selectById(voiceId.trim());
        if (voice == null) {
            throw new RenException("defaultTtsVoiceId 音色不存在");
        }
        if (!ttsModelId.equals(voice.getTtsModelId())) {
            throw new RenException("defaultTtsVoiceId 对应音色不属于所选 TTS 模型");
        }
    }

    private void selectDefault(String type, String id) {
        ModelConfigEntity selected = requireEnabledModel("default" + type + "ModelId", type, id);
        models.setDefaultModel(type, 0);
        selected.setConfigJson(null);
        selected.setIsDefault(1);
        if (!models.updateById(selected)) {
            throw new RenException(type + " 默认模型保存失败");
        }
        templates.updateDefaultTemplateModelId(type, selected.getId());
    }

    private void selectOptionalDefault(String type, String id) {
        if (StringUtils.isBlank(id)) {
            models.setDefaultModel(type, 0);
            templates.updateDefaultTemplateModelId(type, null);
            return;
        }
        selectDefault(type, id.trim());
    }

    private Map<String, Map<String, Object>> changes(Snapshot before, Snapshot after) {
        Map<String, Map<String, Object>> changes = new LinkedHashMap<>();
        addChange(changes, "publicWebsocketUrl", before.publicWebsocketUrl(), after.publicWebsocketUrl());
        addChange(changes, "publicOtaUrl", before.publicOtaUrl(), after.publicOtaUrl());
        addChange(changes, "zixuanListenHost", before.zixuanListenHost(), after.zixuanListenHost());
        addChange(changes, "zixuanListenPort", before.zixuanListenPort(), after.zixuanListenPort());
        addChange(changes, "otaListenHost", before.otaListenHost(), after.otaListenHost());
        addChange(changes, "otaListenPort", before.otaListenPort(), after.otaListenPort());
        addChange(changes, "defaultLlmModelId", before.defaultLlmModelId(), after.defaultLlmModelId());
        addChange(changes, "defaultVllmModelId", before.defaultVllmModelId(), after.defaultVllmModelId());
        addChange(changes, "defaultTtsModelId", before.defaultTtsModelId(), after.defaultTtsModelId());
        addChange(changes, "defaultAsrModelId", before.defaultAsrModelId(), after.defaultAsrModelId());
        addChange(changes, "defaultVadModelId", before.defaultVadModelId(), after.defaultVadModelId());
        addChange(changes, "defaultMemoryModelId", before.defaultMemoryModelId(), after.defaultMemoryModelId());
        addChange(changes, "defaultTtsVoiceId", before.defaultTtsVoiceId(), after.defaultTtsVoiceId());
        addPresenceChange(changes, "proactivePlannerPrompt", before.proactivePlannerPrompt(), after.proactivePlannerPrompt());
        return changes;
    }

    private void addPresenceChange(Map<String, Map<String, Object>> changes, String field, String before, String after) {
        if (java.util.Objects.equals(before, after)) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("beforeConfigured", StringUtils.isNotBlank(before));
        values.put("afterConfigured", StringUtils.isNotBlank(after));
        changes.put(field, values);
    }

    private void addChange(Map<String, Map<String, Object>> changes, String field, Object before, Object after) {
        if (java.util.Objects.equals(before, after)) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("before", before);
        values.put("after", after);
        changes.put(field, values);
    }

    private String blankToNull(String value) {
        return StringUtils.isBlank(value) ? null : value.trim();
    }

    private String value(String code, String fallback) {
        String value = params.getValue(code, false);
        return StringUtils.isBlank(value) || "null".equalsIgnoreCase(value.trim()) ? fallback : value.trim();
    }

    private int port(String code, int fallback) {
        String value = value(code, String.valueOf(fallback));
        try {
            int port = Integer.parseInt(value);
            return port >= 1 && port <= 65535 ? port : fallback;
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private AdminSystemSettingsVO.Health health(String host, int port) {
        Instant checkedAt = Instant.now();
        String address = host + ":" + port;
        if ("0.0.0.0".equals(host) || "::".equals(host)) {
            return new AdminSystemSettingsVO.Health("unknown", address, checkedAt);
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 300);
            return new AdminSystemSettingsVO.Health("available", address, checkedAt);
        } catch (IOException | RuntimeException error) {
            return new AdminSystemSettingsVO.Health("unavailable", address, checkedAt);
        }
    }

    private record Snapshot(
            String publicWebsocketUrl,
            String publicOtaUrl,
            String zixuanListenHost,
            int zixuanListenPort,
            String otaListenHost,
            int otaListenPort,
            String defaultLlmModelId,
            String defaultVllmModelId,
            String defaultTtsModelId,
            String defaultAsrModelId,
            String defaultVadModelId,
            String defaultMemoryModelId,
            String defaultTtsVoiceId,
            String proactivePlannerPrompt) {
    }
}
