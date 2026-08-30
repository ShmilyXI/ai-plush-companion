package xiaozhi.modules.appauth;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AppBindContactRequest {
    @NotBlank private String channel;
    @NotBlank private String value;
    @NotBlank private String code;
    private String countryCode;
}
