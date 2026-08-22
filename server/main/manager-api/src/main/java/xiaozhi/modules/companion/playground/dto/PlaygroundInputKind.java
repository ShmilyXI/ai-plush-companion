package xiaozhi.modules.companion.playground.dto;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum PlaygroundInputKind {
    TEXT, AUDIO, TTS, VISION, ACTIVITY;

    @JsonCreator
    public static PlaygroundInputKind fromJson(String value) {
        if (value == null) return null;
        return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
