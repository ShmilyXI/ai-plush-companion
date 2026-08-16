package xiaozhi.modules.companion.capability.service;

import java.util.List;

import xiaozhi.modules.companion.capability.dto.DeviceSkillBindingDTO;
import xiaozhi.modules.companion.capability.vo.DeviceSkillBindingVO;
import xiaozhi.modules.companion.capability.vo.DeviceSkillCatalogVO;
import xiaozhi.modules.companion.capability.vo.EffectiveCapabilityBundleVO;

public interface DeviceCapabilityService {
    List<DeviceSkillBindingVO> list(Long callerId, String deviceId, boolean superAdmin);

    List<DeviceSkillCatalogVO> catalog(Long callerId, String deviceId, boolean superAdmin);

    List<DeviceSkillBindingVO> save(Long callerId, String deviceId, List<DeviceSkillBindingDTO> bindings,
            boolean superAdmin);

    EffectiveCapabilityBundleVO effectiveBundle(String deviceId);
}
