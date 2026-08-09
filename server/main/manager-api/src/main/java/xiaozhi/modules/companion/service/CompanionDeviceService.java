package xiaozhi.modules.companion.service;

import java.util.List;

import xiaozhi.modules.companion.dto.CompanionDeviceBindDTO;
import xiaozhi.modules.companion.dto.CompanionDeviceCommandDTO;
import xiaozhi.modules.companion.vo.CompanionDeviceVO;
import xiaozhi.modules.device.dto.DeviceUpdateDTO;

public interface CompanionDeviceService {
    List<CompanionDeviceVO> list(Long userId);

    void bind(Long userId, CompanionDeviceBindDTO dto);

    CompanionDeviceVO get(Long userId, String deviceId);

    void update(Long userId, String deviceId, DeviceUpdateDTO dto);

    void switchProfile(Long userId, String deviceId, String profileId);

    void unbind(Long userId, String deviceId);

    Object command(Long userId, String deviceId, CompanionDeviceCommandDTO dto);
}
