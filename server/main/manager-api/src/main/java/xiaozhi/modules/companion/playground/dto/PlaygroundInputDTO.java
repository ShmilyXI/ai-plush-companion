package xiaozhi.modules.companion.playground.dto;

import java.util.Map;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class PlaygroundInputDTO {
    @NotNull
    private PlaygroundInputKind kind;
    private String text;
    private String audioRef;
    private String imageRef;
    private Map<String, Object> activity;

    public void validatePayload() {
        int count = (text == null || text.isBlank() ? 0 : 1)
                + (audioRef == null || audioRef.isBlank() ? 0 : 1)
                + (imageRef == null || imageRef.isBlank() ? 0 : 1)
                + (activity == null ? 0 : 1);
        if (count != 1) throw new IllegalArgumentException("操练输入必须且只能包含一种载荷");
        boolean validKind = kind == PlaygroundInputKind.TEXT && text != null && !text.isBlank()
                || kind == PlaygroundInputKind.AUDIO && audioRef != null && !audioRef.isBlank()
                || kind == PlaygroundInputKind.VISION && imageRef != null && !imageRef.isBlank()
                || kind == PlaygroundInputKind.ACTIVITY && activity != null;
        if (!validKind) throw new IllegalArgumentException("操练输入类型与载荷不匹配");
    }
}
