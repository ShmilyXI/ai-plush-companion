package xiaozhi.modules.companion.playground.vo;

import java.time.Instant;

public record PlaygroundEventVO(long sequence, String capability, String stage, String status,
        Instant startedAt, Instant finishedAt, Long durationMs, String inputSummary,
        String outputSummary, String error) {
}
