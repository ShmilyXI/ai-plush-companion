package xiaozhi.modules.companion.wakeword.service;

import java.util.List;

import xiaozhi.modules.companion.wakeword.vo.DeviceWakeWordVO;
import xiaozhi.modules.device.dto.DeviceReportReqDTO;

public interface DeviceWakeWordService {
    DeviceWakeWordVO get(Long userId, String deviceId);

    DeviceWakeWordVO update(Long userId, String deviceId, String word);

    DeviceWakeWordVO retry(Long userId, String deviceId);

    List<String> activeWords(String deviceId);

    void report(String deviceId, String chipModel, long assetsPartitionSize,
            DeviceReportReqDTO.WakeWordInfo report);
}
