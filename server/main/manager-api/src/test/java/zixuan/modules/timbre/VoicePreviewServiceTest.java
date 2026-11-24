package zixuan.modules.timbre;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import cn.hutool.json.JSONObject;
import zixuan.common.constant.Constant;
import zixuan.common.exception.RenException;
import zixuan.modules.model.entity.ModelConfigEntity;
import zixuan.modules.model.service.ModelConfigService;
import zixuan.modules.sys.service.SysParamsService;
import zixuan.modules.timbre.service.TimbreService;
import zixuan.modules.timbre.service.VoicePreviewService;
import zixuan.modules.timbre.vo.TimbreDetailsVO;

class VoicePreviewServiceTest {
    private TimbreService timbres;
    private ModelConfigService models;
    private SysParamsService params;
    private RestTemplate rest;
    private VoicePreviewService service;
    private ModelConfigEntity model;

    @BeforeEach
    void setUp() {
        timbres = mock(TimbreService.class);
        models = mock(ModelConfigService.class);
        params = mock(SysParamsService.class);
        rest = mock(RestTemplate.class);
        service = new VoicePreviewService(timbres, models, params, rest);
        TimbreDetailsVO voice = new TimbreDetailsVO();
        voice.setId("TTS_DoubaoSeedTTS2_06");
        voice.setTtsModelId("TTS_DoubaoSeedTTS2");
        voice.setTtsVoice("zh_female_wenrouxiaoya_uranus_bigtts");
        when(timbres.get("TTS_DoubaoSeedTTS2_06")).thenReturn(voice);
        model = new ModelConfigEntity();
        model.setId("TTS_DoubaoSeedTTS2");
        model.setModelType("TTS");
        model.setIsEnabled(1);
        model.setConfigJson(new JSONObject(Map.of("type", "doubao_seedtts", "voice", "zh_female_vv_uranus_bigtts")));
        when(models.getModelByIdFromCache("TTS_DoubaoSeedTTS2")).thenReturn(model);
        when(params.getValue(Constant.SERVER_HTTP, true)).thenReturn("http://runtime.example/");
        when(params.getValue(Constant.SERVER_SECRET, false)).thenReturn("server-secret");
    }

    private void runtimeReturns(byte[] audio, MediaType type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(type);
        when(rest.exchange(eq("http://runtime.example/internal/tts-preview"), eq(HttpMethod.POST), any(HttpEntity.class),
                eq(byte[].class))).thenReturn(new ResponseEntity<>(audio, headers, HttpStatus.OK));
    }

    @Test
    @SuppressWarnings("unchecked")
    void synthesizesTheFixedSampleWithTheVoiceAndCachesIt() {
        byte[] audio = "ID3audio".getBytes(StandardCharsets.UTF_8);
        runtimeReturns(audio, MediaType.parseMediaType("audio/mpeg"));

        VoicePreviewService.Preview first = service.preview("TTS_DoubaoSeedTTS2_06");
        VoicePreviewService.Preview second = service.preview("TTS_DoubaoSeedTTS2_06");

        assertArrayEquals(audio, first.audio());
        assertEquals("audio/mpeg", first.contentType());
        assertEquals(first, second);
        ArgumentCaptor<HttpEntity<Map<String, Object>>> request = ArgumentCaptor.forClass(HttpEntity.class);
        verify(rest, times(1)).exchange(any(String.class), eq(HttpMethod.POST), request.capture(), eq(byte[].class));
        Map<String, Object> body = request.getValue().getBody();
        assertEquals(VoicePreviewService.SAMPLE_TEXT, body.get("text"));
        Map<String, Object> config = (Map<String, Object>) body.get("model");
        assertEquals("doubao_seedtts", config.get("type"));
        assertEquals("zh_female_wenrouxiaoya_uranus_bigtts", config.get("private_voice"));
        assertEquals("Bearer server-secret", request.getValue().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        assertFalse(model.getConfigJson().containsKey("private_voice"));
    }

    @Test
    void unknownVoicesAndDisabledModelsAreRejectedWithoutCallingTheRuntime() {
        assertThrows(RenException.class, () -> service.preview("nope"));
        model.setIsEnabled(0);
        assertThrows(RenException.class, () -> service.preview("TTS_DoubaoSeedTTS2_06"));
        verify(rest, never()).exchange(any(String.class), any(HttpMethod.class), any(HttpEntity.class), eq(byte[].class));
    }

    @Test
    void nonAudioResponsesAreFailures() {
        runtimeReturns("{}".getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_JSON);
        assertThrows(RenException.class, () -> service.preview("TTS_DoubaoSeedTTS2_06"));
    }

    @Test
    void runtimeErrorsBecomeReadableMessages() {
        when(rest.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(byte[].class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.UNPROCESSABLE_ENTITY))
                .thenThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY));
        assertEquals("这个声音暂时不能试听",
                assertThrows(RenException.class, () -> service.preview("TTS_DoubaoSeedTTS2_06")).getMsg());
        assertEquals("试听合成失败，请稍后再试",
                assertThrows(RenException.class, () -> service.preview("TTS_DoubaoSeedTTS2_06")).getMsg());
    }
}
