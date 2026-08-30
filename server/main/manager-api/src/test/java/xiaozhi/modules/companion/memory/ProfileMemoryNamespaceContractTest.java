package xiaozhi.modules.companion.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.companion.memory.impl.ProfileMemoryServiceImpl;

class ProfileMemoryNamespaceContractTest {
    @Test
    void rejectsProfileIdsThatCannotBeRepresentedByThePythonNamespaceContract() {
        assertThrows(IllegalArgumentException.class, () -> ProfileMemoryNamespace.of(7L, "profile:other"));
        assertThrows(IllegalArgumentException.class, () -> ProfileMemoryNamespace.of(7L, "profile.other"));
    }

    @Test
    void mapsPythonSnakeCaseMemoryItemsWithoutChangingAppFieldNames() throws Exception {
        String json = "{\"id\":\"m1\",\"content\":\"内容\",\"updated_at\":\"2026-01-01T00:00:00Z\","
                + "\"source_device_id\":\"device-a\",\"source_profile_id\":\"profile-a\"}";

        ObjectMapper mapper = new ObjectMapper();
        ProfileMemoryService.MemoryItem item = mapper.readValue(json, ProfileMemoryService.MemoryItem.class);

        assertEquals("2026-01-01T00:00:00Z", item.updatedAt());
        assertEquals("device-a", item.sourceDeviceId());
        assertEquals("profile-a", item.sourceProfileId());
        String serialized = mapper.writeValueAsString(item);
        assertFalse(serialized.contains("updated_at"));
        assertFalse(serialized.contains("source_device_id"));
    }

    @Test
    void rejectsAnEmptyProviderResponseInsteadOfReportingAnEmptyLibrary() {
        AgentDao agentDao = mock(AgentDao.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(agentDao.selectById("profile-a")).thenReturn(profile());
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(),
                eq(ProfileMemoryServiceImpl.MemoryResponse.class)))
                .thenReturn(ResponseEntity.ok().build());
        ProfileMemoryServiceImpl service = new ProfileMemoryServiceImpl(
                agentDao, restTemplate, "http://memory", "secret");

        assertThrows(RenException.class, () -> service.list(7L, "profile-a"));
    }

    @Test
    void rejectsAFalseMutationResponse() {
        AgentDao agentDao = mock(AgentDao.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(agentDao.selectById("profile-a")).thenReturn(profile());
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(),
                eq(ProfileMemoryServiceImpl.MemoryResponse.class)))
                .thenReturn(ResponseEntity.ok(new ProfileMemoryServiceImpl.MemoryResponse(
                        java.util.List.of(), false, false)));
        ProfileMemoryServiceImpl service = new ProfileMemoryServiceImpl(
                agentDao, restTemplate, "http://memory", "secret");

        assertThrows(RenException.class, () -> service.clear(7L, "profile-a"));
    }

    private static AgentEntity profile() {
        AgentEntity profile = new AgentEntity();
        profile.setId("profile-a");
        profile.setUserId(7L);
        profile.setCompanionEnabled(1);
        return profile;
    }
}
