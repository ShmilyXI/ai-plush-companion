package xiaozhi.modules.companion.service;

import java.util.List;

import xiaozhi.modules.companion.dto.AppProfileCreateDTO;
import xiaozhi.modules.companion.dto.AppProfileSaveDTO;
import xiaozhi.modules.companion.vo.AppCapabilityOptionVO;
import xiaozhi.modules.companion.vo.AppAvatarVO;
import xiaozhi.modules.companion.vo.CompanionProfileVO;

public interface AppProfileFacade {
    List<CompanionProfileVO> list(Long userId);

    CompanionProfileVO get(Long userId, String profileId);

    String create(Long userId, AppProfileCreateDTO request);

    void save(Long userId, String profileId, AppProfileSaveDTO request);

    void setMemoryEnabled(Long userId, String profileId, boolean enabled);

    void delete(Long userId, String profileId);

    List<AppCapabilityOptionVO> capabilityOptions(Long userId, String profileId);

    AppAvatarVO saveAvatar(Long userId, String profileId, byte[] content, String contentType);
}
