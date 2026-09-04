package zixuan.modules.companion.service;

import java.util.List;

import zixuan.modules.companion.dto.CompanionDeviceBindDTO;
import zixuan.modules.companion.dto.CompanionDeviceCommandDTO;
import zixuan.modules.companion.vo.CompanionDeviceVO;
import zixuan.modules.device.dto.DeviceUpdateDTO;

public interface CompanionDeviceService {
    List<CompanionDeviceVO> list(Long userId);

    void bind(Long userId, CompanionDeviceBindDTO dto);

    CompanionDeviceVO get(Long userId, String deviceId);

    void update(Long userId, String deviceId, DeviceUpdateDTO dto);

    void setDebugLogEnabled(Long userId, String deviceId, boolean enabled);

    void switchProfile(Long userId, String deviceId, String profileId);

    void unbind(Long userId, String deviceId);

    Object command(Long userId, String deviceId, CompanionDeviceCommandDTO dto);
}
