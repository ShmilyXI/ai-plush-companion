package zixuan.modules.companion.wakeword;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import zixuan.modules.device.service.DeviceService;
import zixuan.modules.sys.service.SysParamsService;

class WakeWordUpdateWorkerTest {
    @Test
    void generationStoresAnImmutableCandidateAndWaitsForAnOfflineDevice() {
        Fixture fixture = new Fixture(DeviceWakeWordEntity.GENERATING, false);
        when(fixture.generator.generate(fixture.row)).thenReturn(new GeneratedAsset(new byte[] { 1 }, "a".repeat(64), 1));
        when(fixture.store.store("device-1", 7, new byte[] { 1 }, "a".repeat(64))).thenReturn("candidate.bin");

        fixture.worker.runOnce();

        assertEquals(DeviceWakeWordEntity.WAITING_DEVICE, fixture.row.getStatus());
        assertEquals("candidate.bin", fixture.row.getCandidatePath());
        verify(fixture.deviceService, never()).callDeviceToolInternal(any(), any(), any());
    }

    @Test
    void onlineCandidateIsDispatchedBeforeReboot() {
        Fixture fixture = new Fixture(DeviceWakeWordEntity.WAITING_DEVICE, true);
        fixture.row.setCandidatePath("candidate.bin");
        fixture.row.setCandidateToken("token");
        fixture.row.setCandidateSha256("a".repeat(64));
        fixture.row.setCandidateSize(123L);
        when(fixture.params.getValue("server.ota", true)).thenReturn("https://example.test/zixuan/ota/");
        when(fixture.deviceService.callDeviceToolInternal(eq("device-1"), any(), any())).thenReturn(true);

        fixture.worker.runOnce();

        var order = inOrder(fixture.deviceService);
        order.verify(fixture.deviceService).callDeviceToolInternal(
                "device-1",
                "self.assets.set_download_url",
                Map.of(
                        "url", "https://example.test/zixuan/wake-word-assets/token",
                        "sha256", "a".repeat(64),
                        "size", 123L,
                        "version", 7L,
                        "word", "小布小布"));
        order.verify(fixture.deviceService).callDeviceToolInternal("device-1", "self.reboot", Map.of());
        assertEquals(DeviceWakeWordEntity.WAITING_REBOOT, fixture.row.getStatus());
    }

    @Test
    void staleCandidateIsNotSentWhenVersionReservationFails() {
        Fixture fixture = new Fixture(DeviceWakeWordEntity.WAITING_DEVICE, true);
        fixture.row.setCandidateToken("token");
        fixture.row.setCandidateSha256("a".repeat(64));
        fixture.row.setCandidateSize(123L);
        when(fixture.params.getValue("server.ota", true)).thenReturn("https://example.test/zixuan/ota/");
        when(fixture.dao.updateIfVersion(eq("device-1"), eq(7L),
                eq(DeviceWakeWordEntity.DOWNLOADING), any())).thenReturn(0);

        fixture.worker.runOnce();

        verify(fixture.deviceService, never()).callDeviceToolInternal(any(), any(), any());
    }

    @Test
    void websocketFailurePayloadStopsBeforeReboot() {
        Fixture fixture = new Fixture(DeviceWakeWordEntity.WAITING_DEVICE, true);
        fixture.row.setCandidateToken("token");
        fixture.row.setCandidateSha256("a".repeat(64));
        fixture.row.setCandidateSize(123L);
        when(fixture.params.getValue("server.ota", true)).thenReturn("https://example.test/zixuan/ota/");
        when(fixture.deviceService.callDeviceToolInternal(
                "device-1", "self.assets.set_download_url", Map.of(
                        "url", "https://example.test/zixuan/wake-word-assets/token",
                        "sha256", "a".repeat(64), "size", 123L,
                        "version", 7L, "word", "小布小布")))
                .thenReturn(Map.of("success", false));

        fixture.worker.runOnce();

        verify(fixture.deviceService, never()).callDeviceToolInternal("device-1", "self.reboot", Map.of());
        assertEquals(DeviceWakeWordEntity.FAILED, fixture.row.getStatus());
    }

    private static class Fixture {
        final DeviceWakeWordDao dao = mock(DeviceWakeWordDao.class);
        final WakeWordGenerationClient generator = mock(WakeWordGenerationClient.class);
        final WakeWordAssetStore store = mock(WakeWordAssetStore.class);
        final DeviceService deviceService = mock(DeviceService.class);
        final SysParamsService params = mock(SysParamsService.class);
        final DeviceWakeWordEntity row = row();
        final WakeWordUpdateWorker worker;

        Fixture(String status, boolean online) {
            row.setStatus(status);
            when(dao.selectPending(10)).thenReturn(List.of(row));
            when(dao.claim(eq("device-1"), eq(7L), any(), any())).thenReturn(1);
            when(dao.selectById("device-1")).thenReturn(row);
            when(dao.updateIfVersion(eq("device-1"), eq(7L), any(), any())).thenAnswer(invocation -> {
                row.setStatus(invocation.getArgument(2));
                @SuppressWarnings("unchecked")
                Map<String, Object> values = invocation.getArgument(3);
                if (values.containsKey("candidatePath")) row.setCandidatePath((String) values.get("candidatePath"));
                if (values.containsKey("candidateToken")) row.setCandidateToken((String) values.get("candidateToken"));
                if (values.containsKey("candidateSha256")) row.setCandidateSha256((String) values.get("candidateSha256"));
                if (values.containsKey("candidateSize")) row.setCandidateSize((Long) values.get("candidateSize"));
                return 1;
            });
            when(deviceService.isOnline("device-1")).thenReturn(online);
            worker = new WakeWordUpdateWorker(dao, generator, store, deviceService, params);
        }

        private static DeviceWakeWordEntity row() {
            DeviceWakeWordEntity row = new DeviceWakeWordEntity();
            row.setDeviceId("device-1");
            row.setDesiredWord("小布小布");
            row.setDesiredVersion(7L);
            row.setChipModel("esp32s3");
            row.setSlotSize(0x300000L);
            row.setCapable(true);
            return row;
        }
    }
}
