package xiaozhi.modules.companion.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompanionDeviceCommandDTO {
    @NotBlank
    @Pattern(regexp = "volume|brightness")
    private String command;

    @Min(0)
    @Max(100)
    @NotNull
    private Integer value;
}
