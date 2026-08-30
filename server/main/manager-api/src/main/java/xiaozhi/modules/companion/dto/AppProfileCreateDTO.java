package xiaozhi.modules.companion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AppProfileCreateDTO {
    @NotBlank
    @Size(max = 16)
    private String source = "template";

    @Size(max = 64)
    private String templateId;

    @NotBlank
    @Size(max = 64)
    private String name;
}
