package xiaozhi.modules.volcengine.voice;

import java.net.URI;
import java.util.Map;

public record SignedVolcengineRequest(URI uri, String body, Map<String, String> headers) {
}
