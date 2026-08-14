package xiaozhi.modules.companion.wakeword.service.impl;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.companion.wakeword.dao.DeviceWakeWordDao;
import xiaozhi.modules.companion.wakeword.entity.DeviceWakeWordEntity;
import xiaozhi.modules.companion.wakeword.service.DeviceWakeWordService;
import xiaozhi.modules.companion.wakeword.vo.DeviceWakeWordVO;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.dto.DeviceReportReqDTO;

@Service
public class DeviceWakeWordServiceImpl implements DeviceWakeWordService {
    private static final Pattern SUPPORTED_WORD = Pattern.compile("^[\\x{3400}-\\x{4DBF}\\x{4E00}-\\x{9FFF}]{2,8}$");

    private final DeviceDao deviceDao;
    private final DeviceWakeWordDao wakeWordDao;

    public DeviceWakeWordServiceImpl(DeviceDao deviceDao, DeviceWakeWordDao wakeWordDao) {
        this.deviceDao = deviceDao;
        this.wakeWordDao = wakeWordDao;
    }

    @Override
    public DeviceWakeWordVO get(Long userId, String deviceId) {
        DeviceEntity device = deviceDao.selectById(deviceId);
        if (device == null || userId == null || !userId.equals(device.getUserId())) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
        return toVO(wakeWordDao.selectById(deviceId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DeviceWakeWordVO update(Long userId, String deviceId, String rawWord) {
        String word = normalizeWord(rawWord);
        requireOwnedForUpdate(userId, deviceId);
        DeviceWakeWordEntity row = requireRowForUpdate(deviceId);
        requireCapable(row);
        if (Objects.equals(row.getDesiredWord(), word)) {
            return toVO(row);
        }
        row.setDesiredWord(word);
        row.setDesiredVersion(value(row.getDesiredVersion()) + 1);
        row.setStatus(DeviceWakeWordEntity.GENERATING);
        clearCandidate(row);
        clearErrorAndLease(row);
        save(row);
        return toVO(row);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DeviceWakeWordVO retry(Long userId, String deviceId) {
        requireOwnedForUpdate(userId, deviceId);
        DeviceWakeWordEntity row = requireRowForUpdate(deviceId);
        requireCapable(row);
        if (!DeviceWakeWordEntity.FAILED.equals(row.getStatus())) {
            throw new RenException("只有失败的唤醒词任务可以重试");
        }
        row.setStatus(hasCompleteCandidate(row)
                ? DeviceWakeWordEntity.WAITING_DEVICE
                : DeviceWakeWordEntity.GENERATING);
        row.setLastErrorCode(null);
        row.setLastErrorMessage(null);
        row.setLockToken(null);
        row.setLockUntil(null);
        save(row);
        return toVO(row);
    }

    @Override
    public List<String> activeWords(String deviceId) {
        DeviceWakeWordEntity row = wakeWordDao.selectById(deviceId);
        if (row == null || row.getActiveWord() == null || row.getActiveWord().isBlank()) {
            return List.of();
        }
        return List.of(row.getActiveWord().strip());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void report(String deviceId, String chipModel, long assetsPartitionSize,
            DeviceReportReqDTO.WakeWordInfo report) {
        if (deviceId == null || deviceId.isBlank() || report == null) {
            return;
        }
        DeviceWakeWordEntity row = wakeWordDao.selectByDeviceIdForUpdate(deviceId);
        if (row == null) {
            row = new DeviceWakeWordEntity();
            row.setDeviceId(deviceId);
            row.setDesiredVersion(0L);
            row.setActiveVersion(0L);
            row.setStatus(DeviceWakeWordEntity.IDLE);
            Date now = new Date();
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            if (wakeWordDao.insert(row) != 1) {
                throw new RenException(ErrorCode.ADD_DATA_FAILED);
            }
        }

        boolean capable = Boolean.TRUE.equals(report.getSupported())
                && "esp32s3".equalsIgnoreCase(chipModel)
                && assetsPartitionSize >= 0x800000L
                && Integer.valueOf(2).equals(report.getLayoutVersion())
                && Long.valueOf(0x300000L).equals(report.getSlotSize());
        row.setCapable(capable);
        row.setChipModel(chipModel);
        row.setAssetsPartitionSize(assetsPartitionSize);
        row.setLayoutVersion(report.getLayoutVersion());
        row.setSlotSize(report.getSlotSize());
        row.setCapabilityReason(capable ? null : capabilityReason(report, chipModel, assetsPartitionSize));

        long desiredVersion = value(row.getDesiredVersion());
        long activeVersion = report.getActiveVersion() == null ? 0 : report.getActiveVersion();
        long pendingVersion = report.getPendingVersion() == null ? 0 : report.getPendingVersion();
        if ("active".equalsIgnoreCase(report.getStatus())
                && desiredVersion > 0 && activeVersion == desiredVersion
                && Objects.equals(row.getDesiredWord(), report.getActiveWord())) {
            row.setActiveVersion(activeVersion);
            row.setActiveWord(report.getActiveWord());
            row.setStatus(DeviceWakeWordEntity.ACTIVE);
            row.setLastErrorCode(null);
            row.setLastErrorMessage(null);
        } else if ("failed".equalsIgnoreCase(report.getStatus())
                && desiredVersion > 0 && pendingVersion == desiredVersion) {
            row.setStatus(DeviceWakeWordEntity.FAILED);
            row.setLastErrorCode(report.getErrorCode());
            row.setLastErrorMessage(report.getErrorMessage());
        }
        save(row);
    }

    private String capabilityReason(DeviceReportReqDTO.WakeWordInfo report, String chipModel,
            long assetsPartitionSize) {
        if (!Boolean.TRUE.equals(report.getSupported()) && report.getErrorMessage() != null
                && !report.getErrorMessage().isBlank()) {
            return report.getErrorMessage();
        }
        if (!"esp32s3".equalsIgnoreCase(chipModel)) return "仅支持 ESP32-S3";
        if (assetsPartitionSize < 0x800000L) return "assets 分区小于 8 MiB";
        if (!Integer.valueOf(2).equals(report.getLayoutVersion())) return "需要动态唤醒词布局 2";
        if (!Long.valueOf(0x300000L).equals(report.getSlotSize())) return "唤醒词槽位大小不匹配";
        return "设备固件暂不支持动态唤醒词";
    }

    static String normalizeWord(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (!SUPPORTED_WORD.matcher(value).matches()) {
            throw new RenException("唤醒词只支持二到八个中文汉字");
        }
        return value;
    }

    private void requireOwnedForUpdate(Long userId, String deviceId) {
        if (userId == null || deviceDao.selectOwnedByIdForUpdate(deviceId, userId) == null) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
    }

    private DeviceWakeWordEntity requireRowForUpdate(String deviceId) {
        DeviceWakeWordEntity row = wakeWordDao.selectByDeviceIdForUpdate(deviceId);
        if (row == null) {
            row = new DeviceWakeWordEntity();
            row.setDeviceId(deviceId);
            row.setDesiredVersion(0L);
            row.setActiveVersion(0L);
            row.setStatus(DeviceWakeWordEntity.IDLE);
            row.setCapable(false);
            row.setCapabilityReason("设备尚未上报动态唤醒词能力");
            Date now = new Date();
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            if (wakeWordDao.insert(row) != 1) {
                throw new RenException(ErrorCode.ADD_DATA_FAILED);
            }
        }
        return row;
    }

    private void requireCapable(DeviceWakeWordEntity row) {
        if (!Boolean.TRUE.equals(row.getCapable())) {
            throw new RenException("设备固件暂不支持动态唤醒词");
        }
    }

    private boolean hasCompleteCandidate(DeviceWakeWordEntity row) {
        return row.getCandidatePath() != null && !row.getCandidatePath().isBlank()
                && row.getCandidateSha256() != null && !row.getCandidateSha256().isBlank()
                && row.getCandidateSize() != null && row.getCandidateSize() > 0;
    }

    private void clearCandidate(DeviceWakeWordEntity row) {
        row.setCandidatePath(null);
        row.setCandidateToken(null);
        row.setCandidateSha256(null);
        row.setCandidateSize(null);
    }

    private void clearErrorAndLease(DeviceWakeWordEntity row) {
        row.setLastErrorCode(null);
        row.setLastErrorMessage(null);
        row.setLockToken(null);
        row.setLockUntil(null);
    }

    private void save(DeviceWakeWordEntity row) {
        row.setUpdatedAt(new Date());
        if (wakeWordDao.updateById(row) != 1) {
            throw new RenException(ErrorCode.UPDATE_DATA_FAILED);
        }
    }

    private DeviceWakeWordVO toVO(DeviceWakeWordEntity row) {
        DeviceWakeWordVO vo = new DeviceWakeWordVO();
        if (row == null) {
            vo.setStatus(DeviceWakeWordEntity.IDLE);
            vo.setUnsupportedReason("设备尚未上报动态唤醒词能力");
            return vo;
        }
        vo.setDesiredWord(row.getDesiredWord());
        vo.setDesiredVersion(value(row.getDesiredVersion()));
        vo.setActiveWord(row.getActiveWord());
        vo.setActiveVersion(value(row.getActiveVersion()));
        vo.setStatus(row.getStatus());
        vo.setLastErrorCode(row.getLastErrorCode());
        vo.setLastErrorMessage(row.getLastErrorMessage());
        vo.setSupported(Boolean.TRUE.equals(row.getCapable()));
        vo.setUnsupportedReason(row.getCapabilityReason());
        vo.setUpdatedAt(row.getUpdatedAt());
        return vo;
    }

    private long value(Long number) {
        return number == null ? 0 : number;
    }
}
