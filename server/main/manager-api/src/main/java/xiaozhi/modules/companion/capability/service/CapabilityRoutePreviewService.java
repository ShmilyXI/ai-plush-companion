package xiaozhi.modules.companion.capability.service;

import xiaozhi.modules.companion.capability.vo.CapabilityRoutePreviewVO;

public interface CapabilityRoutePreviewService {
    CapabilityRoutePreviewVO preview(String deviceId, String utterance);
}
