package xiaozhi.modules.appauth;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Data
public class AppAuthCodeRequest {
    @NotBlank private String channel;
    @NotBlank private String value;
    private String countryCode;
    @NotBlank @Pattern(regexp = "login|register|reset|bind") private String purpose;
}
