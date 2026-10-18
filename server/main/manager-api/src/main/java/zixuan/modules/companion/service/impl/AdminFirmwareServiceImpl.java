package zixuan.modules.companion.service.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zixuan.common.exception.RenException;
import zixuan.modules.companion.service.AdminFirmwareService;
import zixuan.modules.companion.service.CompanionAuditService;
import zixuan.modules.device.entity.OtaEntity;
import zixuan.modules.device.service.OtaService;

@Service
@AllArgsConstructor
@Slf4j
public class AdminFirmwareServiceImpl implements AdminFirmwareService {
    private final OtaService otaService;
    private final CompanionAuditService auditService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String upload(Long operatorId, MultipartFile file, String firmwareName, String type, String version,
            String remark) {
        validateMetadata(firmwareName, type, version);
        requireTransactionSynchronization();
        String normalizedType = otaService.normalizeTypeKey(type);
        OtaEntity old = lockSlots(List.of(normalizedType)).get(normalizedType);
        if (old != null) {
            old = rereadLocked(old.getId(), normalizedType);
        }
        String newPath = otaService.storeFirmware(file);
        registerFileReplacement(newPath, old == null ? null : old.getFirmwarePath());
        OtaEntity firmware = new OtaEntity();
        if (old != null) {
            firmware.setId(old.getId());
        }
        firmware.setFirmwareName(firmwareName);
        firmware.setType(type);
        firmware.setVersion(version);
        firmware.setRemark(remark);
        firmware.setFirmwarePath(newPath);
        firmware.setSize(file.getSize());
        try {
            if (!otaService.save(firmware)) {
                throw new RenException("固件记录创建失败");
            }
        } catch (DuplicateKeyException | ConcurrencyFailureException exception) {
            throw new RenException("同类型固件正在更新，请重试", exception);
        }
        auditService.record(operatorId, null, "firmware.upload", "firmware", firmware.getId(),
                Map.of("firmwareName", firmwareName, "type", type == null ? "" : type,
                        "version", version, "size", file.getSize()));
        return firmware.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long operatorId, String id, OtaEntity firmware) {
        validateMetadata(firmware == null ? null : firmware.getFirmwareName(),
                firmware == null ? null : firmware.getType(), firmware == null ? null : firmware.getVersion());
        OtaEntity snapshot = otaService.selectById(id);
        if (snapshot == null) {
            throw new RenException("固件不存在");
        }
        String existingType = otaService.normalizeTypeKey(snapshot.getType());
        String requestedType = otaService.normalizeTypeKey(firmware.getType());
        Map<String, OtaEntity> slots = lockSlots(List.of(existingType, requestedType));
        OtaEntity existing = rereadLocked(id, existingType);
        OtaEntity destination = slots.get(requestedType);
        if (destination != null && !id.equals(destination.getId())) {
            throw new RenException("同类型固件已存在");
        }
        OtaEntity metadata = new OtaEntity();
        metadata.setId(id);
        metadata.setFirmwareName(firmware.getFirmwareName());
        metadata.setType(firmware.getType());
        metadata.setVersion(firmware.getVersion());
        metadata.setRemark(firmware.getRemark());
        metadata.setFirmwarePath(existing.getFirmwarePath());
        metadata.setSize(existing.getSize());
        metadata.setSort(existing.getSort());
        try {
            if (!otaService.updateById(metadata)) {
                throw new RenException("固件不存在");
            }
        } catch (DuplicateKeyException | ConcurrencyFailureException exception) {
            throw new RenException("同类型固件正在更新，请重试", exception);
        }
        auditService.record(operatorId, null, "firmware.update", "firmware", id,
                Map.of("firmwareName", firmware.getFirmwareName(),
                        "type", firmware.getType() == null ? "" : firmware.getType(),
                        "version", firmware.getVersion()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long operatorId, String id) {
        deleteAllLocked(operatorId, new String[] { id });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteAll(Long operatorId, String[] ids) {
        deleteAllLocked(operatorId, ids);
    }

    private void deleteAllLocked(Long operatorId, String[] ids) {
        if (ids == null || ids.length == 0) {
            throw new RenException("删除的固件ID不能为空");
        }
        Map<String, FirmwareSnapshot> snapshots = new LinkedHashMap<>();
        Arrays.stream(ids).distinct().forEach(id -> {
            OtaEntity snapshot = otaService.selectById(id);
            if (snapshot == null) {
                throw new RenException("固件不存在");
            }
            snapshots.put(id, new FirmwareSnapshot(id, otaService.normalizeTypeKey(snapshot.getType())));
        });
        lockSlots(snapshots.values().stream().map(FirmwareSnapshot::normalizedType).toList());
        List<FirmwareSnapshot> ordered = new ArrayList<>(snapshots.values());
        ordered.sort(Comparator.comparing(FirmwareSnapshot::normalizedType).thenComparing(FirmwareSnapshot::id));
        List<OtaEntity> locked = ordered.stream()
                .map(snapshot -> rereadLocked(snapshot.id(), snapshot.normalizedType()))
                .toList();
        try {
            for (OtaEntity existing : locked) {
                if (!otaService.deleteById(existing.getId())) {
                    throw new RenException("固件不存在");
                }
                auditService.record(operatorId, null, "firmware.delete", "firmware", existing.getId(), Map.of());
                afterCommitCleanup(existing.getFirmwarePath());
            }
        } catch (ConcurrencyFailureException exception) {
            throw concurrentFirmwareConflict(exception);
        }
    }

    private Map<String, OtaEntity> lockSlots(List<String> normalizedTypes) {
        Map<String, OtaEntity> locked = new LinkedHashMap<>();
        try {
            for (String normalizedType : new TreeSet<>(normalizedTypes)) {
                locked.put(normalizedType, otaService.selectByNormalizedTypeForUpdate(normalizedType));
            }
            return locked;
        } catch (ConcurrencyFailureException exception) {
            throw concurrentFirmwareConflict(exception);
        }
    }

    private OtaEntity rereadLocked(String id, String expectedType) {
        try {
            OtaEntity locked = otaService.selectByIdForUpdate(id);
            if (locked == null) {
                throw new RenException("固件不存在");
            }
            if (!expectedType.equals(otaService.normalizeTypeKey(locked.getType()))) {
                throw new RenException("固件正在更新，请重试");
            }
            return locked;
        } catch (ConcurrencyFailureException exception) {
            throw concurrentFirmwareConflict(exception);
        }
    }

    private RenException concurrentFirmwareConflict(RuntimeException exception) {
        return new RenException("固件正在更新，请重试", exception);
    }

    private record FirmwareSnapshot(String id, String normalizedType) {
    }

    private void validateMetadata(String firmwareName, String type, String version) {
        if (firmwareName == null || firmwareName.isBlank() || version == null || version.isBlank()) {
            throw new RenException("固件名称和版本不能为空");
        }
    }

    private void registerFileReplacement(String newPath, String oldPath) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (oldPath != null && !oldPath.equals(newPath)) {
                    cleanupUnreferencedPath(oldPath);
                }
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    deleteFileSafely(newPath, "rollback");
                }
            }
        });
    }

    private void afterCommitCleanup(String firmwarePath) {
        requireTransactionSynchronization();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cleanupUnreferencedPath(firmwarePath);
            }
        });
    }

    private void cleanupUnreferencedPath(String firmwarePath) {
        try {
            if (otaService.countByFirmwarePath(firmwarePath) == 0) {
                otaService.deleteFirmwareFile(firmwarePath);
            }
        } catch (Exception exception) {
            log.warn("固件事务已提交，文件清理失败，等待后续清理任务处理", exception);
        }
    }

    private void deleteFileSafely(String firmwarePath, String phase) {
        try {
            otaService.deleteFirmwareFile(firmwarePath);
        } catch (Exception exception) {
            log.warn("固件{}文件清理失败，等待后续清理任务处理", phase, exception);
        }
    }

    private void requireTransactionSynchronization() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("固件管理需要活动事务");
        }
    }
}
