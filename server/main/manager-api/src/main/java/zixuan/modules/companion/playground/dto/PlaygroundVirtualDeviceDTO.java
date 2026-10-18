package zixuan.modules.companion.playground.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PlaygroundVirtualDeviceDTO {
    @Min(1)
    private int width = 240;
    @Min(1)
    private int height = 240;
    @Min(1)
    private int depth = 8;
    @NotBlank
    private String orientation = "square";
    private boolean screen = true;
    private boolean camera;
    private boolean microphone = true;
    private boolean activitySensor;
}
