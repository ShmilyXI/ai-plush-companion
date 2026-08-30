package xiaozhi.modules.appauth;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AppChangePasswordRequest {
    @NotBlank
    private String currentPassword;
    @NotBlank
    private String newPassword;
}
