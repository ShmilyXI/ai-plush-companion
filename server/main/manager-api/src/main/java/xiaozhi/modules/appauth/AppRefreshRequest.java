package xiaozhi.modules.appauth;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
@Data public class AppRefreshRequest { @NotBlank private String refreshToken; }
