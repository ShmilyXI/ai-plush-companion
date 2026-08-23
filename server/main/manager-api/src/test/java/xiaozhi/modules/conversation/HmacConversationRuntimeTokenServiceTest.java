package xiaozhi.modules.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import xiaozhi.modules.conversation.service.ConversationRuntimeTokenService.RuntimeTokenClaims;
import xiaozhi.modules.conversation.service.impl.HmacConversationRuntimeTokenServiceImpl;

class HmacConversationRuntimeTokenServiceTest {
    private final HmacConversationRuntimeTokenServiceImpl service =
            new HmacConversationRuntimeTokenServiceImpl("runtime-secret");

    @Test
    void signsAndVerifiesClaimsWithoutProviderSecrets() {
        Instant now = Instant.ofEpochSecond(1_710_000_000L);
        RuntimeTokenClaims claims = new RuntimeTokenClaims(
                "conversation-a", "user-a", "agent-a", 4,
                Set.of("conversation:text"), Set.of("text"), Set.of("text"),
                now, now.plusSeconds(900));

        String token = service.issue(claims);
        RuntimeTokenClaims restored = service.verify(token, now.plusSeconds(1));

        assertEquals(claims, restored);
        assertEquals(3, token.split("\\.").length);
    }

    @Test
    void rejectsTamperedExpiredAndWrongAudienceTokens() {
        Instant now = Instant.ofEpochSecond(1_710_000_000L);
        RuntimeTokenClaims claims = new RuntimeTokenClaims(
                "conversation-a", "user-a", "agent-a", 4,
                Set.of("conversation:text"), Set.of("text"), Set.of("text"),
                now, now.plusSeconds(900));
        String token = service.issue(claims);

        assertThrows(IllegalArgumentException.class,
                () -> service.verify(token.substring(0, token.length() - 1) + "x", now));
        assertThrows(IllegalArgumentException.class,
                () -> service.verify(token, now.plusSeconds(901)));
        assertThrows(IllegalArgumentException.class,
                () -> service.verify("v1.e30.abc", now));
    }
}
