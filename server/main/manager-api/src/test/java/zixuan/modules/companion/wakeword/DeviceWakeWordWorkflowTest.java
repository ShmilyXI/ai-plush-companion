package zixuan.modules.companion.wakeword;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import zixuan.modules.companion.wakeword.dao.DeviceWakeWordDao;
import zixuan.modules.companion.wakeword.entity.DeviceWakeWordEntity;
import zixuan.modules.companion.wakeword.service.WakeWordAssetStore;
import zixuan.modules.companion.wakeword.service.WakeWordGenerationClient;
import zixuan.modules.companion.wakeword.service.WakeWordGenerationClient.GeneratedAsset;
import zixuan.modules.companion.wakeword.service.WakeWordUpdateWorker;
import zixuan.modules.companion.wakeword.service.impl.DeviceWakeWordServiceImpl;
import zixuan.modules.device.dao.DeviceDao;
import zixuan.modules.device.dto.DeviceReportReqDTO;
import zixuan.modules.device.entity.DeviceEntity;
import zixuan.modules.device.service.DeviceService;
import zixuan.modules.sys.service.SysParamsService;

class DeviceWakeWordWorkflowTest {
    @Test
    void updateBecomesActiveOnlyAfterMatchingDeviceReport() {
        Fixture fixture = new Fixture();
        fixture.service.update(7L, "device-1", "小布小布");
        fixture.online = false;
        fixture.worker.runOnce();
        assertEquals(DeviceWakeWordEntity.WAITING_DEVICE, fixture.row.getStatus());

        fixture.online = true;
        fixture.worker.runOnce();
        assertEquals(DeviceWakeWordEntity.WAITING_REBOOT, fixture.row.getStatus());

        fixture.service.report("device-1", "esp32s3", 0x800000L,
                report(fixture.row.getDesiredVersion(), "小布小布", 0, "active", null, null));

        var state = fixture.service.get(7L, "device-1");
        assertEquals(DeviceWakeWordEntity.ACTIVE, state.getStatus());
        assertEquals("小布小布", state.getActiveWord());
        assertEquals(state.getDesiredVersion(), state.getActiveVersion());
        assertNull(state.getLastErrorMessage());
    }

    @Test
    void failedDownloadKeepsOldWordAndRetryReusesCandidate() {
        Fixture fixture = new Fixture();
        fixture.row.setActiveWord("你好小智");
        fixture.row.setActiveVersion(3L);
        fixture.service.update(7L, "device-1", "小布小布");
        fixture.worker.runOnce();
        String candidatePath = fixture.row.getCandidatePath();
        fixture.online = true;
        fixture.worker.runOnce();

        fixture.service.report("device-1", "esp32s3", 0x800000L,
                report(3, "你好小智", fixture.row.getDesiredVersion(), "failed", "DOWNLOAD_FAILED", "network lost"));
        var failed = fixture.service.get(7L, "device-1");
        assertEquals(DeviceWakeWordEntity.FAILED, failed.getStatus());
        assertEquals("你好小智", failed.getActiveWord());
        assertEquals(3L, failed.getActiveVersion());

        fixture.service.retry(7L, "device-1");
        assertEquals(candidatePath, fixture.row.getCandidatePath());
        fixture.worker.runOnce();
        fixture.service.report("device-1", "esp32s3", 0x800000L,
                report(fixture.row.getDesiredVersion(), "小布小布", 0, "active", null, null));
        assertEquals(DeviceWakeWordEntity.ACTIVE, fixture.service.get(7L, "device-1").getStatus());
    }

    private static DeviceReportReqDTO.WakeWordInfo report(long activeVersion, String activeWord,
            long pendingVersion, String status, String errorCode, String errorMessage) {
        DeviceReportReqDTO.WakeWordInfo report = new DeviceReportReqDTO.WakeWordInfo();
        report.setSupported(true);
        report.setLayoutVersion(2);
        report.setSlotSize(0x300000L);
        report.setActiveVersion(activeVersion);
        report.setActiveWord(activeWord);
        report.setPendingVersion(pendingVersion);
        report.setStatus(status);
        report.setErrorCode(errorCode);
        report.setErrorMessage(errorMessage);
        return report;
    }

    private static class Fixture {
        final DeviceWakeWordDao dao = mock(DeviceWakeWordDao.class);
        final DeviceDao deviceDao = mock(DeviceDao.class);
        final WakeWordGenerationClient generator = mock(WakeWordGenerationClient.class);
        final WakeWordAssetStore store = mock(WakeWordAssetStore.class);
        final DeviceService deviceService = mock(DeviceService.class);
        final SysParamsService params = mock(SysParamsService.class);
        final DeviceWakeWordEntity row = row();
        final DeviceWakeWordServiceImpl service = new DeviceWakeWordServiceImpl(deviceDao, dao);
        final WakeWordUpdateWorker worker = new WakeWordUpdateWorker(dao, generator, store, deviceService, params);
        boolean online;

        Fixture() {
            DeviceEntity device = new DeviceEntity();
            device.setId("device-1");
            device.setUserId(7L);
            when(deviceDao.selectOwnedByIdForUpdate("device-1", 7L)).thenReturn(device);
            when(deviceDao.selectById("device-1")).thenReturn(device);
            when(dao.selectByDeviceIdForUpdate("device-1")).thenReturn(row);
            when(dao.selectById("device-1")).thenReturn(row);
            when(dao.updateById(row)).thenReturn(1);
            when(dao.selectPending(10)).thenAnswer(invocation ->
                    List.of(DeviceWakeWordEntity.GENERATING, DeviceWakeWordEntity.WAITING_DEVICE).contains(row.getStatus())
                            ? List.of(row) : List.of());
            when(dao.claim(eq("device-1"), any(Long.class), any(), any())).thenReturn(1);
            when(generator.generate(row)).thenReturn(new GeneratedAsset(new byte[] { 1, 2 }, "a".repeat(64), 2));
            when(store.store(eq("device-1"), any(Long.class), any(), eq("a".repeat(64)))).thenReturn("candidate.bin");
            when(deviceService.isOnline("device-1")).thenAnswer(invocation -> online);
            when(params.getValue("server.ota", true)).thenReturn("https://example.test/zixuan/ota/");
            when(deviceService.callDeviceToolInternal(eq("device-1"), any(), any())).thenReturn(true);
            when(dao.updateIfVersion(eq("device-1"), any(Long.class), any(), any())).thenAnswer(invocation -> {
                row.setStatus(invocation.getArgument(2));
                @SuppressWarnings("unchecked")
                Map<String, Object> values = invocation.getArgument(3);
                if (values.containsKey("candidatePath")) row.setCandidatePath((String) values.get("candidatePath"));
                if (values.containsKey("candidateToken")) row.setCandidateToken((String) values.get("candidateToken"));
                if (values.containsKey("candidateSha256")) row.setCandidateSha256((String) values.get("candidateSha256"));
                if (values.containsKey("candidateSize")) row.setCandidateSize((Long) values.get("candidateSize"));
                return 1;
            });
        }

        private static DeviceWakeWordEntity row() {
            DeviceWakeWordEntity row = new DeviceWakeWordEntity();
            row.setDeviceId("device-1");
            row.setDesiredWord("你好小智");
            row.setDesiredVersion(3L);
            row.setActiveWord("你好小智");
            row.setActiveVersion(3L);
            row.setStatus(DeviceWakeWordEntity.ACTIVE);
            row.setCapable(true);
            row.setChipModel("esp32s3");
            row.setAssetsPartitionSize(0x800000L);
            row.setLayoutVersion(2);
            row.setSlotSize(0x300000L);
            return row;
        }
    }
}
