package zixuan.modules.companion.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CompanionGrantDTO {
    @NotBlank
    @Size(max = 32)
    private String planId;

    @NotNull
    private Instant expiresAt;
}
