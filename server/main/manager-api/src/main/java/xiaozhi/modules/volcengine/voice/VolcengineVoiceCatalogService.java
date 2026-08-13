package xiaozhi.modules.volcengine.voice;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.page.PageData;
import xiaozhi.common.utils.SensitiveDataUtils;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;

@Service
@RequiredArgsConstructor
public class VolcengineVoiceCatalogService {
    private static final String MODEL_ID = "TTS_HuoshanDoubleStreamTTS";
    private static final URI ENDPOINT = URI.create(
            "https://open.volcengineapi.com/?Action=ListSpeakers&Version=2025-05-20");
    private static final Set<String> RESOURCE_IDS = Set.of("seed-tts-1.0", "seed-tts-2.0");
    private static final int SEARCH_PAGE_SIZE = 100;

    private final ModelConfigService modelConfigService;
    private final VolcengineVoiceCatalogClient client;
    private final VolcengineRequestSigner signer;
    private final ObjectMapper objectMapper;

    public PageData<VolcengineVoiceDTO> list(String resourceId, int page, int limit, String name, String voiceType) {
        if (!RESOURCE_IDS.contains(resourceId)) {
            throw new RenException("火山引擎语音合成模型版本无效");
        }
        JSONObject config = modelConfig();
        String accessKeyId = config.getStr("access_key_id");
        String secretAccessKey = config.getStr("secret_access_key");
        if (missingCredential(accessKeyId) || missingCredential(secretAccessKey)) {
            throw new RenException("请先在模型管理中配置火山引擎 Access Key ID 和 Secret Access Key");
        }

        try {
            if (StringUtils.isBlank(name)) {
                CatalogPage remotePage = requestPage(resourceId, voiceType, page, limit, accessKeyId, secretAccessKey);
                return new PageData<>(remotePage.voices(), remotePage.total());
            }

            List<VolcengineVoiceDTO> matches = new ArrayList<>();
            String keyword = name.trim().toLowerCase(Locale.ROOT);
            int remotePageNumber = 1;
            int total;
            do {
                CatalogPage remotePage = requestPage(resourceId, voiceType, remotePageNumber, SEARCH_PAGE_SIZE,
                        accessKeyId, secretAccessKey);
                total = remotePage.total();
                remotePage.voices().stream()
                        .filter(voice -> voice.name().toLowerCase(Locale.ROOT).contains(keyword))
                        .forEach(matches::add);
                remotePageNumber++;
            } while ((long) (remotePageNumber - 1) * SEARCH_PAGE_SIZE < total);

            int fromIndex = Math.min((page - 1) * limit, matches.size());
            int toIndex = Math.min(fromIndex + limit, matches.size());
            return new PageData<>(new ArrayList<>(matches.subList(fromIndex, toIndex)), matches.size());
        } catch (RenException e) {
            throw e;
        } catch (Exception e) {
            throw new RenException("火山引擎音色列表响应格式错误", e);
        }
    }

    private CatalogPage requestPage(String resourceId, String voiceType, int page, int limit,
            String accessKeyId, String secretAccessKey) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.putArray("ResourceIDs").add(resourceId);
        if (StringUtils.isNotBlank(voiceType)) {
            body.putArray("VoiceTypes").add(voiceType.trim());
        }
        body.put("Page", page);
        body.put("Limit", limit);
        String payload = objectMapper.writeValueAsString(body);
        SignedVolcengineRequest request = signer.sign(ENDPOINT, payload, accessKeyId, secretAccessKey,
                "cn-beijing", "speech_saas_prod");
        VolcengineVoiceCatalogClient.Response response = client.execute(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RenException("火山引擎音色列表请求失败");
        }
        return parse(response.body());
    }

    private CatalogPage parse(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode error = root.path("ResponseMetadata").path("Error");
        if (!error.isMissingNode() && !error.isNull()) {
            String message = text(error, "Message");
            String code = text(error, "Code");
            throw new RenException("火山引擎返回错误：" + StringUtils.defaultIfBlank(message, code));
        }
        JsonNode speakers = root.path("Result").path("Speakers");
        if (!speakers.isArray()) {
            throw new RenException("火山引擎音色列表响应格式错误");
        }
        List<VolcengineVoiceDTO> voices = new ArrayList<>();
        for (JsonNode speaker : speakers) {
            voices.add(map(speaker));
        }
        int total = root.path("Result").path("Total").asInt(voices.size());
        return new CatalogPage(voices, total);
    }

    private VolcengineVoiceDTO map(JsonNode speaker) {
        String voiceType = text(speaker, "VoiceType");
        if (StringUtils.isBlank(voiceType)) {
            throw new RenException("火山引擎音色列表响应格式错误");
        }
        return new VolcengineVoiceDTO(
                voiceType,
                StringUtils.defaultIfBlank(text(speaker, "Name"), voiceType),
                voiceType,
                text(speaker, "Gender"),
                text(speaker, "Age"),
                languages(speaker.path("Languages")),
                tags(speaker),
                text(speaker, "Description"),
                text(speaker, "TrialURL"));
    }

    private String languages(JsonNode nodes) {
        if (!nodes.isArray()) {
            return null;
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (JsonNode node : nodes) {
            String language = text(node, "Language");
            if (StringUtils.isNotBlank(language)) {
                result.add(languageName(language));
            }
        }
        return result.isEmpty() ? null : String.join("、", result);
    }

    private String languageName(String code) {
        String normalized = code.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("zh")) {
            return "中文";
        }
        if (normalized.startsWith("en")) {
            return "英文";
        }
        if (normalized.startsWith("ja")) {
            return "日文";
        }
        if (normalized.startsWith("ko")) {
            return "韩文";
        }
        return code;
    }

    private List<String> tags(JsonNode speaker) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        addTextArray(result, speaker.path("NormalLabels"));
        addTextArray(result, speaker.path("SpecialLabels"));
        return List.copyOf(result);
    }

    private void addTextArray(Set<String> target, JsonNode nodes) {
        if (!nodes.isArray()) {
            return;
        }
        for (JsonNode node : nodes) {
            String value = node.asText();
            if (StringUtils.isNotBlank(value)) {
                target.add(value);
            }
        }
    }

    private JSONObject modelConfig() {
        ModelConfigEntity model = modelConfigService.getModelByIdFromCache(MODEL_ID);
        if (model == null || model.getConfigJson() == null) {
            return new JSONObject();
        }
        return model.getConfigJson();
    }

    private boolean missingCredential(String value) {
        return StringUtils.isBlank(value) || SensitiveDataUtils.isMaskedValue(value);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? StringUtils.trimToNull(value.asText()) : null;
    }

    private record CatalogPage(List<VolcengineVoiceDTO> voices, int total) {
    }
}
