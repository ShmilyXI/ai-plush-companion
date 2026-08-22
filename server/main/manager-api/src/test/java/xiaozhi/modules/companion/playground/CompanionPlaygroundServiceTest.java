package xiaozhi.modules.companion.playground;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import xiaozhi.modules.companion.playground.dto.PlaygroundInputDTO;
import xiaozhi.modules.companion.playground.dto.PlaygroundInputKind;
import xiaozhi.modules.companion.playground.dto.PlaygroundSessionCreateDTO;
import xiaozhi.modules.companion.playground.service.impl.CompanionPlaygroundServiceImpl;

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
        assertEquals(4, service.events(7L, session.sessionId(), 0).size());
        assertEquals(3, service.events(7L, session.sessionId(), 1).size());
        assertThrows(IllegalArgumentException.class, () -> service.get(8L, session.sessionId()));
    }

    @Test
    void rejectsMixedInputPayloads() {
        PlaygroundInputDTO input = new PlaygroundInputDTO();
        input.setKind(PlaygroundInputKind.TEXT);
        input.setText("hello");
        input.setAudioRef("audio");
        assertThrows(IllegalArgumentException.class, input::validatePayload);
    }
}
