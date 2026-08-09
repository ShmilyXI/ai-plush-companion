package xiaozhi.modules.companion.service;

import xiaozhi.modules.companion.dto.AdminSystemSettingsSaveDTO;
import xiaozhi.modules.companion.vo.AdminSystemSettingsVO;

public interface AdminSystemSettingsService {
    AdminSystemSettingsVO get();

    AdminSystemSettingsVO save(Long operatorId, AdminSystemSettingsSaveDTO request);
}
