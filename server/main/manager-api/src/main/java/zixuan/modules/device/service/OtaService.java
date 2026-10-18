package zixuan.modules.device.service;

import java.nio.file.Path;
import java.util.Map;

import org.springframework.web.multipart.MultipartFile;

import zixuan.common.page.PageData;
import zixuan.common.service.BaseService;
import zixuan.modules.device.entity.OtaEntity;

/**
 * OTA固件管理
 */
public interface OtaService extends BaseService<OtaEntity> {
    PageData<OtaEntity> page(Map<String, Object> params);

    boolean save(OtaEntity entity);

    void update(OtaEntity entity);

    void delete(String[] ids);

    OtaEntity getLatestOta(String type);

    long countByFirmwarePath(String firmwarePath);

    String normalizeTypeKey(String type);

    OtaEntity selectByNormalizedTypeForUpdate(String normalizedType);

    OtaEntity selectByIdForUpdate(String id);

    String storeFirmware(MultipartFile file);

    Path resolveManagedFirmwareFile(String firmwarePath);

    void deleteFirmwareFile(String firmwarePath);
}
