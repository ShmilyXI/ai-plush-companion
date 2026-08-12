package xiaozhi.modules.companion.debug.vo;

import java.util.List;

import xiaozhi.modules.companion.debug.model.DeviceDebugLogEvent;

public record DeviceDebugLogHistoryVO(List<DeviceDebugLogEvent> events, String lastCursor) {
}
