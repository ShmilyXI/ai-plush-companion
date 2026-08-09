package xiaozhi.modules.device.service.impl;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;

import io.micrometer.common.util.StringUtils;
import xiaozhi.common.page.PageData;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.service.impl.BaseServiceImpl;
import xiaozhi.modules.device.dao.OtaDao;
import xiaozhi.modules.device.entity.OtaEntity;
import xiaozhi.modules.device.service.OtaService;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class OtaServiceImpl extends BaseServiceImpl<OtaDao, OtaEntity> implements OtaService {
    private static final long MAX_FIRMWARE_SIZE = 100L * 1024 * 1024;
    private static final Path FIRMWARE_DIRECTORY = Paths.get("uploadfile").toAbsolutePath().normalize();

    @Override
    public PageData<OtaEntity> page(Map<String, Object> params) {
        IPage<OtaEntity> page = baseDao.selectPage(
                getPage(params, "update_date", true),
                getWrapper(params));

        return new PageData<>(page.getRecords(), page.getTotal());
    }

    private QueryWrapper<OtaEntity> getWrapper(Map<String, Object> params) {
        String firmwareName = (String) params.get("firmwareName");

        QueryWrapper<OtaEntity> wrapper = new QueryWrapper<>();
        wrapper.like(StringUtils.isNotBlank(firmwareName), "firmware_name", firmwareName);

        return wrapper;
    }

    @Override
    public void update(OtaEntity entity) {
        // 检查是否存在相同类型和版本的固件（排除当前记录）
        QueryWrapper<OtaEntity> queryWrapper = new QueryWrapper<OtaEntity>()
                .eq("type", entity.getType())
                .eq("version", entity.getVersion())
                .ne("id", entity.getId()); // 排除当前记录

        if (baseDao.selectCount(queryWrapper) > 0) {
            throw new RuntimeException("已存在相同类型和版本的固件，请修改后重试");
        }

        entity.setUpdateDate(new Date());
        baseDao.updateById(entity);
    }

    @Override
    public void delete(String[] ids) {
        baseDao.deleteByIds(Arrays.asList(ids));
    }

    @Override
    public boolean save(OtaEntity entity) {
        if (entity.getId() != null && !entity.getId().isBlank()) {
            return baseDao.updateById(entity) == 1;
        }
        return baseDao.insert(entity) > 0;
    }

    @Override
    public OtaEntity getLatestOta(String type) {
        QueryWrapper<OtaEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("type", type)
                .orderByDesc("update_date")
                .last("LIMIT 1");
        return baseDao.selectOne(wrapper);
    }

    @Override
    public long countByFirmwarePath(String firmwarePath) {
        return baseDao.selectCount(new QueryWrapper<OtaEntity>().eq("firmware_path", firmwarePath));
    }

    @Override
    public String normalizeTypeKey(String type) {
        String normalized = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? "__default__" : normalized;
    }

    @Override
    public OtaEntity selectByNormalizedTypeForUpdate(String normalizedType) {
        return baseDao.selectByNormalizedTypeForUpdate(normalizedType);
    }

    @Override
    public OtaEntity selectByIdForUpdate(String id) {
        return baseDao.selectByIdForUpdate(id);
    }

    @Override
    public String storeFirmware(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new RenException("上传文件不能为空");
        }
        if (file.getSize() > MAX_FIRMWARE_SIZE) {
            throw new RenException("固件文件不能超过100MB");
        }
        String originalFilename = file.getOriginalFilename();
        int extensionIndex = originalFilename == null ? -1 : originalFilename.lastIndexOf('.');
        if (extensionIndex < 0) {
            throw new RenException("文件名不能为空");
        }
        String extension = originalFilename.substring(extensionIndex).toLowerCase();
        if (!extension.equals(".bin") && !extension.equals(".apk")) {
            throw new RenException("只允许上传.bin和.apk格式的文件");
        }
        Path filePath = null;
        try {
            Files.createDirectories(FIRMWARE_DIRECTORY);
            filePath = FIRMWARE_DIRECTORY.resolve(UUID.randomUUID() + extension).normalize();
            if (!filePath.startsWith(FIRMWARE_DIRECTORY) || !FIRMWARE_DIRECTORY.equals(filePath.getParent())) {
                throw new RenException("固件路径不合法");
            }
            Files.copy(file.getInputStream(), filePath);
            return Paths.get("uploadfile").resolve(filePath.getFileName()).toString();
        } catch (IOException exception) {
            if (filePath != null && !(exception instanceof FileAlreadyExistsException)) {
                try {
                    Files.deleteIfExists(filePath);
                } catch (IOException cleanupException) {
                    exception.addSuppressed(cleanupException);
                    log.warn("固件上传失败后清理部分文件失败", cleanupException);
                }
            }
            throw new RenException("文件上传失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public Path resolveManagedFirmwareFile(String firmwarePath) {
        if (firmwarePath == null || firmwarePath.isBlank()) {
            throw new RenException("固件文件不存在");
        }
        try {
            Path managedRoot = FIRMWARE_DIRECTORY.toRealPath();
            Path candidate = Paths.get(firmwarePath).toAbsolutePath().normalize();
            if (!candidate.startsWith(FIRMWARE_DIRECTORY)) {
                throw new RenException("固件路径不合法");
            }
            Path resolved = candidate.toRealPath();
            if (!resolved.startsWith(managedRoot)
                    || !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
                throw new RenException("固件路径不合法");
            }
            return resolved;
        } catch (IOException exception) {
            throw new RenException("固件文件不存在", exception);
        }
    }

    @Override
    public void deleteFirmwareFile(String firmwarePath) {
        if (firmwarePath == null || firmwarePath.isBlank()) {
            return;
        }
        Path candidate = Paths.get(firmwarePath).toAbsolutePath().normalize();
        if (!candidate.startsWith(FIRMWARE_DIRECTORY) || !FIRMWARE_DIRECTORY.equals(candidate.getParent())) {
            log.warn("跳过非受管目录中的固件文件清理");
            return;
        }
        try {
            Files.deleteIfExists(candidate);
        } catch (IOException exception) {
            throw new RenException("固件文件删除失败：" + exception.getMessage(), exception);
        }
    }
}
