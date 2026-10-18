package zixuan.modules.companion.playground;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import zixuan.modules.companion.service.CompanionProfileService;

import zixuan.modules.companion.playground.dto.PlaygroundInputDTO;
import zixuan.modules.companion.playground.dto.PlaygroundInputKind;
import zixuan.modules.companion.playground.dto.PlaygroundSessionCreateDTO;
import zixuan.modules.companion.playground.service.impl.CompanionPlaygroundServiceImpl;
import org.springframework.web.server.ResponseStatusException;

class CompanionPlaygroundServiceTest {
    @Test
    void createsImmutableOwnedSnapshotAndFiltersEventsByCursor() {
        CompanionPlaygroundServiceImpl service = new CompanionPlaygroundServiceImpl();
        PlaygroundSessionCreateDTO request = new PlaygroundSessionCreateDTO();
        request.setProfileId("profile-a");
        var session = service.create(7L, request);

        request.setProfileId("changed-after-create");
        assertEquals("profile-a", service.get(7L, session.sessionId()).effectiveConfig().get("profileId"));

        PlaygroundInputDTO input = new PlaygroundInputDTO();
        input.setKind(PlaygroundInputKind.TEXT);
        input.setText("你好");
        service.acceptInput(7L, session.sessionId(), input);
        assertEquals(1, service.events(7L, session.sessionId(), 0).size());
        assertEquals(0, service.events(7L, session.sessionId(), 1).size());
        assertThrows(ResponseStatusException.class, () -> service.get(8L, session.sessionId()));
    }

    @Test
    void rejectsMixedInputPayloads() {
        PlaygroundInputDTO input = new PlaygroundInputDTO();
        input.setKind(PlaygroundInputKind.TEXT);
        input.setText("hello");
        input.setAudioRef("audio");
        assertThrows(IllegalArgumentException.class, input::validatePayload);
    }

    @Test
    void validatesProfileOwnershipBeforeCreatingSession() {
        CompanionProfileService profiles = mock(CompanionProfileService.class);
        when(profiles.get(7L, "missing")).thenThrow(new IllegalArgumentException("角色不存在"));
        CompanionPlaygroundServiceImpl service = new CompanionPlaygroundServiceImpl(profiles);
        PlaygroundSessionCreateDTO request = new PlaygroundSessionCreateDTO();
        request.setProfileId("missing");
        assertThrows(IllegalArgumentException.class, () -> service.create(7L, request));
    }
}
