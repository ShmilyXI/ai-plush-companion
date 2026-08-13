package xiaozhi.modules.companion.wakeword;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import cn.hutool.crypto.digest.DigestUtil;
import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.companion.wakeword.entity.DeviceWakeWordEntity;
import xiaozhi.modules.companion.wakeword.service.impl.HttpWakeWordGenerationClient;
import xiaozhi.modules.sys.service.SysParamsService;

class HttpWakeWordGenerationClientTest {
    @Test
    void sendsAuthenticatedRequestAndVerifiesResponseHeaders() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        SysParamsService params = params();
        byte[] content = new byte[] { 1, 2, 3 };
        String sha256 = DigestUtil.sha256Hex(content);
        server.expect(requestTo("http://server/internal/wake-word-assets"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer secret"))
                .andExpect(jsonPath("$.device_id").value("device-1"))
                .andExpect(jsonPath("$.word").value("小布小布"))
                .andExpect(jsonPath("$.version").value(7))
                .andExpect(jsonPath("$.chip").value("esp32s3"))
                .andExpect(jsonPath("$.slot_size").value(3145728))
                .andRespond(withSuccess(content, MediaType.APPLICATION_OCTET_STREAM)
                        .header("X-Wake-Word-Sha256", sha256)
                        .header("X-Wake-Word-Size", "3")
                        .header("X-Wake-Word-Version", "7"));

        var result = new HttpWakeWordGenerationClient(restTemplate, params).generate(row());

        assertArrayEquals(content, result.content());
        assertEquals(sha256, result.sha256());
        server.verify();
    }

    @Test
    void rejectsMismatchedHashHeader() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://server/internal/wake-word-assets"))
                .andRespond(withSuccess(new byte[] { 1 }, MediaType.APPLICATION_OCTET_STREAM)
                        .header("X-Wake-Word-Sha256", "0".repeat(64))
                        .header("X-Wake-Word-Size", "1")
                        .header("X-Wake-Word-Version", "7"));

        assertThrows(RenException.class,
                () -> new HttpWakeWordGenerationClient(restTemplate, params()).generate(row()));
    }

    private SysParamsService params() {
        SysParamsService params = mock(SysParamsService.class);
        when(params.getValue(Constant.SERVER_HTTP, true)).thenReturn("http://server/");
        when(params.getValue(Constant.SERVER_SECRET, false)).thenReturn("secret");
        return params;
    }

    private DeviceWakeWordEntity row() {
        DeviceWakeWordEntity row = new DeviceWakeWordEntity();
        row.setDeviceId("device-1");
        row.setDesiredWord("小布小布");
        row.setDesiredVersion(7L);
        row.setChipModel("esp32s3");
        row.setSlotSize(0x300000L);
        return row;
    }
}
