package xiaozhi.modules.companion.model.vo;

import java.util.Map;

public record GlobalModelCredentialRuntime(String apiUrl, String modelId, Map<String, Object> secrets) {
}
