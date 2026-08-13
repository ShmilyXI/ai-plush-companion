package xiaozhi.modules.companion.wakeword.service;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.companion.wakeword.dao.DeviceWakeWordDao;
import xiaozhi.modules.companion.wakeword.entity.DeviceWakeWordEntity;
import xiaozhi.modules.companion.wakeword.service.WakeWordGenerationClient.GeneratedAsset;
import xiaozhi.modules.device.service.DeviceService;
import xiaozhi.modules.sys.service.SysParamsService;

@Service
public class WakeWordUpdateWorker {
    private final DeviceWakeWordDao dao;
    private final WakeWordGenerationClient generator;
    private final WakeWordAssetStore store;
    private final DeviceService deviceService;
    private final SysParamsService params;

    public WakeWordUpdateWorker(DeviceWakeWordDao dao, WakeWordGenerationClient generator,
            WakeWordAssetStore store, DeviceService deviceService, SysParamsService params) {
        this.dao = dao;
        this.generator = generator;
        this.store = store;
        this.deviceService = deviceService;
        this.params = params;
    }

    @Scheduled(fixedDelayString = "${xiaozhi.wake-word.worker-delay-ms:5000}")
    public void runOnce() {
        for (DeviceWakeWordEntity pending : dao.selectPending(10)) {
            process(pending);
        }
    }

    private void process(DeviceWakeWordEntity pending) {
        String lockToken = UUID.randomUUID().toString().replace("-", "");
        long version = pending.getDesiredVersion();
        Date lockUntil = new Date(System.currentTimeMillis() + 60_000L);
        if (dao.claim(pending.getDeviceId(), version, lockToken, lockUntil) != 1) {
            return;
        }
        try {
            DeviceWakeWordEntity row = dao.selectById(pending.getDeviceId());
            if (row == null || row.getDesiredVersion() != version) {
                return;
            }
            if (DeviceWakeWordEntity.GENERATING.equals(row.getStatus())) {
                generate(row, lockToken);
                return;
            }
            if (DeviceWakeWordEntity.WAITING_DEVICE.equals(row.getStatus())) {
                dispatch(row, lockToken);
            }
        } catch (RuntimeException exception) {
            Map<String, Object> values = new HashMap<>();
            values.put("lastErrorCode", "WAKE_WORD_UPDATE_FAILED");
            values.put("lastErrorMessage", StringUtils.abbreviate(exception.getMessage(), 512));
            values.put("lockToken", lockToken);
            dao.updateIfVersion(pending.getDeviceId(), version, DeviceWakeWordEntity.FAILED, values);
        } finally {
            dao.release(pending.getDeviceId(), version, lockToken);
        }
    }

    private void generate(DeviceWakeWordEntity row, String lockToken) {
        GeneratedAsset generated = generator.generate(row);
        String path = store.store(row.getDeviceId(), row.getDesiredVersion(),
                generated.content(), generated.sha256());
        String candidateToken = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> values = new HashMap<>();
        values.put("candidatePath", path);
        values.put("candidateToken", candidateToken);
        values.put("candidateSha256", generated.sha256());
        values.put("candidateSize", generated.size());
        values.put("lastErrorCode", null);
        values.put("lastErrorMessage", null);
        values.put("lockToken", lockToken);
        dao.updateIfVersion(row.getDeviceId(), row.getDesiredVersion(),
                DeviceWakeWordEntity.WAITING_DEVICE, values);
    }

    private void dispatch(DeviceWakeWordEntity row, String lockToken) {
        if (!deviceService.isOnline(row.getDeviceId())) {
            return;
        }
        String ota = params.getValue(Constant.SERVER_OTA, true);
        if (StringUtils.isBlank(ota) || StringUtils.isBlank(row.getCandidateToken())) {
            throw new RenException("唤醒词下载地址未配置");
        }
        String downloadUrl = ota.replace("/ota/", "/wake-word-assets/") + row.getCandidateToken();
        Map<String, Object> download = Map.of(
                "url", downloadUrl,
                "sha256", row.getCandidateSha256(),
                "size", row.getCandidateSize(),
                "version", row.getDesiredVersion(),
                "word", row.getDesiredWord());
        requireSuccess(deviceService.callDeviceToolInternal(
                row.getDeviceId(), "self.assets.set_download_url", download));
        dao.updateIfVersion(row.getDeviceId(), row.getDesiredVersion(),
                DeviceWakeWordEntity.DOWNLOADING, Map.of("lockToken", lockToken));
        requireSuccess(deviceService.callDeviceToolInternal(row.getDeviceId(), "self.reboot", Map.of()));
        dao.updateIfVersion(row.getDeviceId(), row.getDesiredVersion(),
                DeviceWakeWordEntity.WAITING_REBOOT, Map.of("lockToken", lockToken));
    }

    private void requireSuccess(Object result) {
        if (result == null || Boolean.FALSE.equals(result)) {
            throw new RenException("设备唤醒词指令执行失败");
        }
    }
}
