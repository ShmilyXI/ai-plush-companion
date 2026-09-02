package xiaozhi.modules.companion.service;

import java.util.List;
import java.util.Map;

import xiaozhi.modules.companion.dto.AppProfileCreateDTO;
import xiaozhi.modules.companion.dto.AppProfileSaveDTO;
import xiaozhi.modules.companion.vo.AppCapabilityOptionVO;
import xiaozhi.modules.companion.vo.AppAvatarVO;
import xiaozhi.modules.companion.vo.AppAvatarContent;
import xiaozhi.modules.companion.vo.CompanionProfileVO;
import xiaozhi.modules.companion.model.vo.CompanionModelOptionVO;

public interface AppProfileFacade {
    List<CompanionProfileVO> list(Long userId);

    CompanionProfileVO get(Long userId, String profileId);

    String create(Long userId, AppProfileCreateDTO request);

    void save(Long userId, String profileId, AppProfileSaveDTO request);

    void setMemoryEnabled(Long userId, String profileId, boolean enabled);

    void delete(Long userId, String profileId);

    List<AppCapabilityOptionVO> capabilityOptions(Long userId, String profileId);

    default List<Map<String, Object>> templates() {
        return List.of();
    }

    default List<CompanionModelOptionVO> modelOptions(Long userId, String profileId) {
        return List.of();
    }

    AppAvatarVO saveAvatar(Long userId, String profileId, byte[] content, String contentType);

    AppAvatarContent loadAvatar(Long userId, String profileId, String checksum);
}
