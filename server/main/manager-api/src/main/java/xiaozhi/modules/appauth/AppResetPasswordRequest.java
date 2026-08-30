package xiaozhi.modules.appauth;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AppResetPasswordRequest {
    @NotBlank private String channel;
    @NotBlank private String value;
    @NotBlank private String code;
    @NotBlank private String password;
    private String countryCode;
}
