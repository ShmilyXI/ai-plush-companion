package zixuan.modules.companion.service;

import org.springframework.web.multipart.MultipartFile;

import zixuan.modules.device.entity.OtaEntity;

public interface AdminFirmwareService {
    String upload(Long operatorId, MultipartFile file, String firmwareName, String type, String version, String remark);

    void update(Long operatorId, String id, OtaEntity firmware);

    void delete(Long operatorId, String id);

    void deleteAll(Long operatorId, String[] ids);
}
