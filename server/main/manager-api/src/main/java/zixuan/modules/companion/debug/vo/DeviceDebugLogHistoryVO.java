package zixuan.modules.companion.debug.vo;

import java.util.List;

import zixuan.modules.companion.debug.model.DeviceDebugLogEvent;

public record DeviceDebugLogHistoryVO(List<DeviceDebugLogEvent> events, String lastCursor) {
}
