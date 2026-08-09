package xiaozhi.modules.companion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompanionDeviceBindDTO {
    @NotBlank
    @Size(max = 16)
    @Pattern(regexp = "\\d{6}")
    private String activationCode;

    @Size(max = 64)
    private String profileId;
}
