package xiaozhi.modules.websession.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class WebSessionExchangeDTO {
    @NotBlank
    private String code;
}
