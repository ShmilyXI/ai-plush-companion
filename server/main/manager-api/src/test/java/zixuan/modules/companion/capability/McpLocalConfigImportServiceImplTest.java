package zixuan.modules.companion.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import zixuan.common.page.PageData;
import zixuan.common.exception.RenException;
import zixuan.modules.companion.capability.dao.CapabilitySecretDao;
import zixuan.modules.companion.capability.dto.CapabilitySaveDTO;
import zixuan.modules.companion.capability.entity.CapabilitySecretEntity;
import zixuan.modules.companion.capability.service.CapabilitySecretService;
import zixuan.modules.companion.capability.service.CapabilityService;
import zixuan.modules.companion.capability.service.impl.McpLocalConfigImportServiceImpl;
import zixuan.modules.companion.capability.vo.CapabilityVO;

class McpLocalConfigImportServiceImplTest {
    private final CapabilityService capabilities = mock(CapabilityService.class);
    private final CapabilitySecretService secrets = mock(CapabilitySecretService.class);
    private final CapabilitySecretDao secretDao = mock(CapabilitySecretDao.class);
    private final McpLocalConfigImportServiceImpl service = new McpLocalConfigImportServiceImpl(
            capabilities, secrets, secretDao);

    @Test
    void importsLocalMcpJsonWithHeadersAndEnvironmentMovedToSecretStorage() {
        when(capabilities.page("MCP_SERVER", null, null, 1, 100))
                .thenReturn(new PageData<>(List.of(), 0));
        CapabilityVO draft = new CapabilityVO();
        draft.setId("mcp-search");
        draft.setName("search");
        when(capabilities.create(eq(7L), any())).thenReturn(draft);
        when(capabilities.update(eq(7L), eq("mcp-search"), any())).thenReturn(draft);
        CapabilityVO published = new CapabilityVO();
        published.setId("mcp-search");
        published.setName("search");
        published.setStatus("PUBLISHED");
        when(capabilities.publish(7L, "mcp-search")).thenReturn(published);
        when(secretDao.selectByCapabilityAndName(eq("mcp-search"), any())).thenAnswer(invocation -> {
            CapabilitySecretEntity secret = new CapabilitySecretEntity();
            secret.setId("secret-" + invocation.getArgument(1));
            secret.setCapabilityId("mcp-search");
            secret.setSecretName(invocation.getArgument(1));
            return secret;
        });
        Map<String, Object> document = Map.of("mcpServers", Map.of("search", Map.of(
                "url", "https://mcp.example/sse",
                "headers", Map.of("Authorization", "Bearer local-token"),
                "env", Map.of("API_KEY", "local-key"))));

        var result = service.importDocument(7L, document);

        assertEquals(1, result.getImported().size());
        assertEquals(List.of(), result.getSkipped());
        verify(secrets).save(7L, "mcp-search", "import.headers.authorization", "Bearer local-token");
        verify(secrets).save(7L, "mcp-search", "import.env.api-key", "local-key");
        ArgumentCaptor<CapabilitySaveDTO> created = ArgumentCaptor.forClass(CapabilitySaveDTO.class);
        verify(capabilities).create(eq(7L), created.capture());
        assertFalse(created.getValue().getMcp().getConnectionConfig().toString().contains("local-token"));
        assertFalse(created.getValue().getMcp().getConnectionConfig().toString().contains("local-key"));
        ArgumentCaptor<CapabilitySaveDTO> updated = ArgumentCaptor.forClass(CapabilitySaveDTO.class);
        verify(capabilities).update(eq(7L), eq("mcp-search"), updated.capture());
        assertEquals("secret-import.headers.authorization",
                updated.getValue().getMcp().getSecretRefs().get("headers.Authorization"));
        assertEquals("secret-import.env.api-key",
                updated.getValue().getMcp().getSecretRefs().get("env.API_KEY"));
        verify(capabilities).publish(7L, "mcp-search");
    }

    @Test
    void repeatedImportSkipsANameAlreadyManagedByTheCapabilityCatalog() {
        CapabilityVO existing = new CapabilityVO();
        existing.setId("existing-mcp");
        existing.setName("search");
        when(capabilities.page("MCP_SERVER", null, null, 1, 100))
                .thenReturn(new PageData<>(List.of(existing), 1));

        var result = service.importDocument(7L, Map.of(
                "mcpServers", Map.of("search", Map.of("url", "https://mcp.example/sse"))));

        assertEquals(List.of(), result.getImported());
        assertEquals(List.of("search"), result.getSkipped());
        verify(capabilities, never()).create(any(), any());
    }

    @Test
    void arbitraryStdioCommandsCannotBeApprovedByTheImportedDocument() {
        when(capabilities.page("MCP_SERVER", null, null, 1, 100))
                .thenReturn(new PageData<>(List.of(), 0));

        assertThrows(RenException.class, () -> service.importDocument(7L, Map.of(
                "mcpServers", Map.of("unsafe", Map.of("command", "python", "args", List.of("evil.py"))))));

        verify(capabilities, never()).create(any(), any());
    }
}
