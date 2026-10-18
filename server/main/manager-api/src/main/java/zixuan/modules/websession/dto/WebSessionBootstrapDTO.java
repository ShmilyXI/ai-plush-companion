package zixuan.modules.websession.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class WebSessionBootstrapDTO {
    @NotBlank
    private String audience;

    @NotBlank
    private String origin;
}
