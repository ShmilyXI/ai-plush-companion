package zixuan.modules.companion.service;

import zixuan.modules.companion.dto.AdminSystemSettingsSaveDTO;
import zixuan.modules.companion.vo.AdminSystemSettingsVO;

public interface AdminSystemSettingsService {
    AdminSystemSettingsVO get();

    AdminSystemSettingsVO save(Long operatorId, AdminSystemSettingsSaveDTO request);
}
