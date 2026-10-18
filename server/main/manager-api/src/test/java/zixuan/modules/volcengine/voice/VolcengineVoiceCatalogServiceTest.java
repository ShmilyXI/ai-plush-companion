package zixuan.modules.volcengine.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import cn.hutool.json.JSONObject;
import zixuan.common.exception.RenException;
import zixuan.modules.model.entity.ModelConfigEntity;
import zixuan.modules.model.service.ModelConfigService;

class VolcengineVoiceCatalogServiceTest {
    private final ModelConfigService models = mock(ModelConfigService.class);
    private final VolcengineVoiceCatalogClient client = mock(VolcengineVoiceCatalogClient.class);
    private final VolcengineRequestSigner signer = mock(VolcengineRequestSigner.class);
    private final VolcengineVoiceCatalogService service = new VolcengineVoiceCatalogService(
            models, client, signer, new ObjectMapper());

    @Test
    void mapsListSpeakersAndFiltersByName() {
        configureCredentials();
        SignedVolcengineRequest signed = new SignedVolcengineRequest(URI.create("https://example.com"), "{}", Map.of());
        when(signer.sign(any(), any(), eq("ak-id"), eq("sk-secret"), eq("cn-beijing"), eq("speech_saas_prod")))
                .thenReturn(signed);
        when(client.execute(signed)).thenReturn(new VolcengineVoiceCatalogClient.Response(200, """
                {"ResponseMetadata":{"RequestId":"r1"},"Result":{"Total":2,"Speakers":[
                  {"VoiceType":"voice-a","Name":"温柔女声","Gender":"女","Age":"青年",
                   "NormalLabels":["热门","温柔"],"SpecialLabels":["温柔","多情感"],
                   "TrialURL":"https://example.com/a.mp3","Languages":[{"Language":"zh-cn"},{"Language":"en-us"}],
                   "Description":"温柔自然"},
                  {"VoiceType":"voice-b","Name":"沉稳男声","Languages":[]}
                ]}}
                """));

        var page = service.list("seed-tts-2.0", 1, 20, "温柔", null);

        assertEquals(1, page.getTotal());
        VolcengineVoiceDTO voice = page.getList().getFirst();
        assertEquals("voice-a", voice.id());
        assertEquals("中文、英文", voice.languages());
        assertEquals(java.util.List.of("热门", "温柔", "多情感"), voice.tags());
        assertEquals("https://example.com/a.mp3", voice.trialUrl());
        verify(signer).sign(any(), org.mockito.ArgumentMatchers.contains("\"seed-tts-2.0\""),
                eq("ak-id"), eq("sk-secret"), eq("cn-beijing"), eq("speech_saas_prod"));
    }

    @Test
    void searchesAllRemotePagesBeforeApplyingLocalPagination() {
        configureCredentials();
        SignedVolcengineRequest signed = new SignedVolcengineRequest(URI.create("https://example.com"), "{}", Map.of());
        when(signer.sign(any(), any(), eq("ak-id"), eq("sk-secret"), eq("cn-beijing"), eq("speech_saas_prod")))
                .thenReturn(signed);
        when(client.execute(signed))
                .thenReturn(new VolcengineVoiceCatalogClient.Response(200, """
                        {"Result":{"Total":101,"Speakers":[
                          {"VoiceType":"voice-a","Name":"普通女声"}
                        ]}}
                        """))
                .thenReturn(new VolcengineVoiceCatalogClient.Response(200, """
                        {"Result":{"Total":101,"Speakers":[
                          {"VoiceType":"voice-b","Name":"跨页命中女声"}
                        ]}}
                        """));

        var page = service.list("seed-tts-1.0", 1, 20, "跨页命中", null);

        assertEquals(1, page.getTotal());
        assertEquals("voice-b", page.getList().getFirst().id());
        verify(signer).sign(any(), org.mockito.ArgumentMatchers.contains("\"Page\":1,\"Limit\":100"),
                eq("ak-id"), eq("sk-secret"), eq("cn-beijing"), eq("speech_saas_prod"));
        verify(signer).sign(any(), org.mockito.ArgumentMatchers.contains("\"Page\":2,\"Limit\":100"),
                eq("ak-id"), eq("sk-secret"), eq("cn-beijing"), eq("speech_saas_prod"));
        verify(client, times(2)).execute(signed);
    }

    @Test
    void sendsExactVoiceTypeAndRejectsMissingCredentials() {
        configureCredentials();
        SignedVolcengineRequest signed = new SignedVolcengineRequest(URI.create("https://example.com"), "{}", Map.of());
        when(signer.sign(any(), any(), any(), any(), any(), any())).thenReturn(signed);
        when(client.execute(signed)).thenReturn(new VolcengineVoiceCatalogClient.Response(200,
                "{\"Result\":{\"Total\":0,\"Speakers\":[]}}"));

        service.list("seed-tts-1.0", 2, 30, null, "voice-code");

        verify(signer).sign(any(), org.mockito.ArgumentMatchers.contains("\"VoiceTypes\":[\"voice-code\"]"),
                any(), any(), any(), any());

        ModelConfigEntity missing = new ModelConfigEntity();
        missing.setConfigJson(new JSONObject().set("access_key_id", "").set("secret_access_key", "****"));
        when(models.getModelByIdFromCache("TTS_HuoshanDoubleStreamTTS")).thenReturn(missing);
        RenException error = assertThrows(RenException.class,
                () -> service.list("seed-tts-1.0", 1, 20, null, null));
        assertEquals("请先在模型管理中配置火山引擎 Access Key ID 和 Secret Access Key", error.getMsg());
    }

    @Test
    void surfacesRemoteAndMalformedResponses() {
        configureCredentials();
        SignedVolcengineRequest signed = new SignedVolcengineRequest(URI.create("https://example.com"), "{}", Map.of());
        when(signer.sign(any(), any(), any(), any(), any(), any())).thenReturn(signed);
        when(client.execute(signed)).thenReturn(new VolcengineVoiceCatalogClient.Response(403, "denied"));
        assertEquals("火山引擎音色列表请求失败", assertThrows(RenException.class,
                () -> service.list("seed-tts-1.0", 1, 20, null, null)).getMsg());

        when(client.execute(signed)).thenReturn(new VolcengineVoiceCatalogClient.Response(200,
                "{\"ResponseMetadata\":{\"Error\":{\"Code\":\"InvalidParameter\",\"Message\":\"bad\"}}}"));
        assertEquals("火山引擎返回错误：bad", assertThrows(RenException.class,
                () -> service.list("seed-tts-1.0", 1, 20, null, null)).getMsg());

        when(client.execute(signed)).thenReturn(new VolcengineVoiceCatalogClient.Response(200, "not-json"));
        assertEquals("火山引擎音色列表响应格式错误", assertThrows(RenException.class,
                () -> service.list("seed-tts-1.0", 1, 20, null, null)).getMsg());
    }

    private void configureCredentials() {
        ModelConfigEntity model = new ModelConfigEntity();
        model.setConfigJson(new JSONObject()
                .set("access_key_id", "ak-id")
                .set("secret_access_key", "sk-secret"));
        when(models.getModelByIdFromCache("TTS_HuoshanDoubleStreamTTS")).thenReturn(model);
    }
}
