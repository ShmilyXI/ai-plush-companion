package xiaozhi.modules.companion.model.dto;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CompanionGlobalModelCredentialSaveDTO {
    @Size(max = 512)
    private String apiUrl;
    @Size(max = 255)
    private String modelId;
    private Map<String, Object> secrets = Map.of();
    private List<@Size(max = 100) String> clearSecretKeys = List.of();
}
