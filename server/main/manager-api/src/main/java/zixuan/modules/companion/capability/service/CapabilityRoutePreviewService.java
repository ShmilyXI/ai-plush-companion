package zixuan.modules.companion.capability.service;

import zixuan.modules.companion.capability.vo.CapabilityRoutePreviewVO;

public interface CapabilityRoutePreviewService {
    CapabilityRoutePreviewVO preview(String deviceId, String utterance);
}
