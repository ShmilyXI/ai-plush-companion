package zixuan.modules.timbre.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import zixuan.common.constant.Constant;
import zixuan.common.exception.RenException;
import zixuan.modules.model.entity.ModelConfigEntity;
import zixuan.modules.model.service.ModelConfigService;
import zixuan.modules.sys.service.SysParamsService;
import zixuan.modules.timbre.vo.TimbreDetailsVO;

/**
 * 音色试听：用音色所属的语音合成模型把一句固定的话合成出来。
 *
 * 浏览器只能给音色 ID。模型配置（含平台密钥）只在服务端之间传：这里交给 zixuan-server 的
 * /internal/tts-preview，按服务端密钥鉴权。文本是固定的，结果按音色缓存，同一个音色只合成一次，
 * 音色或模型配置改了才会重新合成。
 */
@Service
public class VoicePreviewService {
    public static final String SAMPLE_TEXT = "你好呀，以后就用这个声音陪你聊天，好不好？";
    private static final int CACHE_SIZE = 64;
    private static final int MAX_AUDIO_BYTES = 2 * 1024 * 1024;

    public record Preview(byte[] audio, String contentType) {
    }

    private final TimbreService timbres;
    private final ModelConfigService models;
    private final SysParamsService params;
    private final RestTemplate restTemplate;
    private final Map<String, Preview> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Preview> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    public VoicePreviewService(TimbreService timbres, ModelConfigService models, SysParamsService params,
            RestTemplate restTemplate) {
        this.timbres = timbres;
        this.models = models;
        this.params = params;
        this.restTemplate = restTemplate;
    }

    public Preview preview(String voiceId) {
        TimbreDetailsVO voice = StringUtils.isBlank(voiceId) ? null : timbres.get(voiceId);
        if (voice == null || StringUtils.isBlank(voice.getTtsVoice())) throw new RenException("音色不存在");
        ModelConfigEntity model = StringUtils.isBlank(voice.getTtsModelId())
                ? null : models.getModelByIdFromCache(voice.getTtsModelId());
        if (model == null || !"TTS".equals(model.getModelType())
                || !Integer.valueOf(1).equals(model.getIsEnabled()) || model.getConfigJson() == null) {
            throw new RenException("这个声音暂时不能试听");
        }
        String key = cacheKey(voice, model);
        synchronized (cache) {
            Preview cached = cache.get(key);
            if (cached != null) return cached;
        }
        Preview preview = synthesize(voice, model);
        synchronized (cache) {
            cache.put(key, preview);
        }
        return preview;
    }

    private Preview synthesize(TimbreDetailsVO voice, ModelConfigEntity model) {
        String serverHttp = params.getValue(Constant.SERVER_HTTP, true);
        String secret = params.getValue(Constant.SERVER_SECRET, false);
        if (StringUtils.isAnyBlank(serverHttp, secret)) throw new RenException("试听服务未配置");
        // 模型配置是缓存里的对象，复制一份再加音色，免得改到别处用的配置。
        Map<String, Object> config = new LinkedHashMap<>(model.getConfigJson());
        config.put("private_voice", voice.getTtsVoice());
        if (voice.getReferenceAudio() != null) config.put("ref_audio", voice.getReferenceAudio());
        if (voice.getReferenceText() != null) config.put("ref_text", voice.getReferenceText());
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(secret);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String url = StringUtils.removeEnd(serverHttp.trim(), "/") + "/internal/tts-preview";
        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.POST,
                    new HttpEntity<>(Map.of("model", config, "text", SAMPLE_TEXT), headers), byte[].class);
            byte[] audio = response.getBody();
            MediaType type = response.getHeaders().getContentType();
            if (!response.getStatusCode().is2xxSuccessful() || audio == null || audio.length == 0
                    || audio.length > MAX_AUDIO_BYTES || type == null || !"audio".equals(type.getType())) {
                throw new RenException("试听合成失败，请稍后再试");
            }
            return new Preview(audio, type.toString());
        } catch (HttpStatusCodeException exception) {
            if (exception.getStatusCode().value() == HttpStatus.UNPROCESSABLE_ENTITY.value()) {
                throw new RenException("这个声音暂时不能试听");
            }
            throw new RenException("试听合成失败，请稍后再试");
        } catch (RestClientException exception) {
            throw new RenException("试听服务不可用，请稍后再试");
        }
    }

    private static String cacheKey(TimbreDetailsVO voice, ModelConfigEntity model) {
        long updated = model.getUpdateDate() == null ? 0L : model.getUpdateDate().getTime();
        return String.join("|", voice.getId(), voice.getTtsVoice(), model.getId(), String.valueOf(updated),
                String.valueOf(Objects.hash(model.getConfigJson())));
    }
}
