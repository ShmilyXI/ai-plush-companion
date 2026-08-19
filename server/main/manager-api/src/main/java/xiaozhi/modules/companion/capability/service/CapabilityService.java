package xiaozhi.modules.companion.capability.service;

import org.springframework.web.multipart.MultipartFile;

import xiaozhi.common.page.PageData;
import xiaozhi.modules.companion.capability.dto.CapabilitySaveDTO;
import xiaozhi.modules.companion.capability.vo.CapabilityVO;

public interface CapabilityService {
    PageData<CapabilityVO> page(String type, String status, String keyword, int page, int limit);

    CapabilityVO get(String id);

    CapabilityVO create(Long operatorId, CapabilitySaveDTO dto);

    CapabilityVO update(Long operatorId, String id, CapabilitySaveDTO dto);

    CapabilityVO publish(Long operatorId, String id);

    CapabilityVO savePackage(Long operatorId, String id, MultipartFile file);

    CapabilityVO createPackage(Long operatorId, MultipartFile file);

    void updateStatus(Long operatorId, String id, String status);

    void delete(Long operatorId, String id);
}
