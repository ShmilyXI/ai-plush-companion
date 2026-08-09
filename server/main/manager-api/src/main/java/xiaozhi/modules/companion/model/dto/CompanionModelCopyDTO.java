package xiaozhi.modules.companion.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CompanionModelCopyDTO {
    @NotBlank
    @Size(max = 80)
    private String reference;
    @Size(max = 64)
    private String name;
}
