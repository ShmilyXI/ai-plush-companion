package zixuan.modules.volcengine.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class VolcengineRequestSignerTest {
    @Test
    void signsListSpeakersWithVolcengineHmacSha256() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-12T03:04:05Z"), ZoneOffset.UTC);
        VolcengineRequestSigner signer = new VolcengineRequestSigner(clock);

        SignedVolcengineRequest request = signer.sign(
                URI.create("https://open.volcengineapi.com/?Action=ListSpeakers&Version=2025-05-20"),
                "{\"ResourceIDs\":[\"seed-tts-1.0\"],\"Page\":1,\"Limit\":20}",
                "AKLT_TEST", "secret-test", "cn-beijing", "speech_saas_prod");

        assertEquals("20260812T030405Z", request.headers().get("X-Date"));
        assertEquals("27da47599458622013a6c69e6593dff54bb5c2b96d0f94cb954a5938ddeffbc4",
                request.headers().get("X-Content-Sha256"));
        assertEquals("application/json; charset=UTF-8", request.headers().get("Content-Type"));
        assertEquals("HMAC-SHA256 Credential=AKLT_TEST/20260812/cn-beijing/speech_saas_prod/request, "
                + "SignedHeaders=host;x-content-sha256;x-date, "
                + "Signature=0e7d113e2ff709037c171e82e56319dee0571f0b00053a9d7d3141fc1a7702f0",
                request.headers().get("Authorization"));
    }
}
