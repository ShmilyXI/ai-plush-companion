package zixuan.modules.companion.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import zixuan.common.constant.Constant;
import zixuan.modules.companion.capability.service.CapabilityRuntimeClient;
import zixuan.modules.companion.capability.service.impl.HttpCapabilityRuntimeClient;
import zixuan.modules.sys.service.SysParamsService;

class CapabilityRuntimeClientTest {

    @Test
    void loadsRegisteredPluginExecutorsThroughAuthenticatedInternalEndpoint() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        SysParamsService params = configuredParams();
        server.expect(requestTo("http://runtime:8003/internal/capabilities/plugin-executors"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer server-secret"))
                .andRespond(withSuccess("""
                        {"executors":[{"name":"get_weather","description":"查询天气","inputSchema":{"type":"object"}}]}
                        """, MediaType.APPLICATION_JSON));

        CapabilityRuntimeClient client = new HttpCapabilityRuntimeClient(rest, params);

        assertEquals(List.of("get_weather"), client.pluginExecutors().stream().map(item -> item.name()).toList());
        server.verify();
    }

    @Test
    void sendsMcpRuntimeConfigurationWithoutReturningSecretMaterial() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        SysParamsService params = configuredParams();
        server.expect(requestTo("http://runtime:8003/internal/capabilities/mcp-test"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer server-secret"))
                .andRespond(withSuccess("""
                        {"success":true,"tools":[{"name":"search","inputSchema":{"type":"object"}}]}
                        """, MediaType.APPLICATION_JSON));

        CapabilityRuntimeClient client = new HttpCapabilityRuntimeClient(rest, params);
        CapabilityRuntimeClient.McpTestResult result = client.testMcp(new CapabilityRuntimeClient.McpTestRequest(
                "SSE", Map.of("url", "https://mcp.example/sse",
                        "headers", Map.of("Authorization", "Bearer runtime-secret")), null));

        assertEquals(true, result.success());
        assertEquals(List.of("search"), result.tools().stream().map(item -> item.name()).toList());
        assertEquals(false, result.toString().contains("runtime-secret"));
        server.verify();
    }

    private SysParamsService configuredParams() {
        SysParamsService params = mock(SysParamsService.class);
        when(params.getValue(Constant.SERVER_HTTP, true)).thenReturn("http://runtime:8003/");
        when(params.getValue(Constant.SERVER_SECRET, false)).thenReturn("server-secret");
        return params;
    }
}
