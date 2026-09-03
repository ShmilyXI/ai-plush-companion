package xiaozhi.modules.companion.wakeword;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;

import xiaozhi.modules.companion.wakeword.dao.DeviceWakeWordDao;
import xiaozhi.modules.companion.wakeword.entity.DeviceWakeWordEntity;
import xiaozhi.modules.companion.wakeword.service.impl.DeviceWakeWordServiceImpl;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.dto.DeviceReportReqDTO;

class DeviceWakeWordReportTest {
    @Test
    void successfulReportPersistsClearedErrorFields() throws NoSuchFieldException {
        TableField code = DeviceWakeWordEntity.class.getDeclaredField("lastErrorCode")
                .getAnnotation(TableField.class);
        TableField message = DeviceWakeWordEntity.class.getDeclaredField("lastErrorMessage")
                .getAnnotation(TableField.class);

        assertEquals(FieldStrategy.ALWAYS, code.updateStrategy());
        assertEquals(FieldStrategy.ALWAYS, message.updateStrategy());
    }

    @Test
    void supportedLayoutUpdatesCapabilityAndMatchingActiveVersion() {
        DeviceWakeWordDao dao = mock(DeviceWakeWordDao.class);
        DeviceWakeWordEntity row = pendingRow();
        when(dao.selectByDeviceIdForUpdate("device-1")).thenReturn(row);
        when(dao.updateById(row)).thenReturn(1);

        service(dao).report("device-1", "esp32s3", 0x800000L,
                report(true, 2, 0x300000L, 7, "小布小布", 0, "active", null, null));

        assertTrue(row.getCapable());
        assertEquals(DeviceWakeWordEntity.ACTIVE, row.getStatus());
        assertEquals("小布小布", row.getActiveWord());
        assertEquals(7L, row.getActiveVersion());
        assertEquals(null, row.getLastErrorCode());
        verify(dao).updateById(row);
    }

    @Test
    void legacyLayoutIsReportedAsUnsupported() {
        DeviceWakeWordDao dao = mock(DeviceWakeWordDao.class);
        DeviceWakeWordEntity row = pendingRow();
        when(dao.selectByDeviceIdForUpdate("device-1")).thenReturn(row);
        when(dao.updateById(row)).thenReturn(1);

        service(dao).report("device-1", "esp32s3", 0x800000L,
                report(false, 1, 0L, 0, "", 0, "idle", "UNSUPPORTED", "layout 1"));

        assertFalse(row.getCapable());
        assertEquals("layout 1", row.getCapabilityReason());
    }

    @Test
    void staleReportsCannotActivateTheDesiredVersion() {
        DeviceWakeWordDao dao = mock(DeviceWakeWordDao.class);
        DeviceWakeWordEntity row = pendingRow();
        when(dao.selectByDeviceIdForUpdate("device-1")).thenReturn(row);
        when(dao.updateById(row)).thenReturn(1);

        service(dao).report("device-1", "esp32s3", 0x800000L,
                report(true, 2, 0x300000L, 6, "旧词旧词", 0, "active", null, null));

        assertEquals(DeviceWakeWordEntity.WAITING_REBOOT, row.getStatus());
        assertEquals("你好小智", row.getActiveWord());
        assertEquals(3L, row.getActiveVersion());
        verify(dao, never()).selectByDeviceIdForUpdate("body-device-id");
    }

    @Test
    void factoryReflashRequeuesTheDesiredCandidateFromTheReportedActiveWord() {
        DeviceWakeWordDao dao = mock(DeviceWakeWordDao.class);
        DeviceWakeWordEntity row = pendingRow();
        row.setStatus(DeviceWakeWordEntity.ACTIVE);
        row.setCandidatePath("candidate.bin");
        row.setCandidateToken("candidate-token");
        row.setCandidateSha256("a".repeat(64));
        row.setCandidateSize(123L);
        when(dao.selectByDeviceIdForUpdate("device-1")).thenReturn(row);
        when(dao.updateById(row)).thenReturn(1);

        service(dao).report("device-1", "esp32s3", 0x800000L,
                report(true, 2, 0x300000L, 1, "你好小智", 0, "active", null, null));

        assertTrue(row.getCapable());
        assertEquals("你好小智", row.getActiveWord());
        assertEquals(1L, row.getActiveVersion());
        assertEquals(DeviceWakeWordEntity.WAITING_DEVICE, row.getStatus());
        verify(dao).updateById(row);
    }

    @Test
    void matchingFailureKeepsThePreviousActiveWord() {
        DeviceWakeWordDao dao = mock(DeviceWakeWordDao.class);
        DeviceWakeWordEntity row = pendingRow();
        when(dao.selectByDeviceIdForUpdate("device-1")).thenReturn(row);
        when(dao.updateById(row)).thenReturn(1);

        service(dao).report("device-1", "esp32s3", 0x800000L,
                report(true, 2, 0x300000L, 3, "你好小智", 7, "failed", "SHA256_MISMATCH", "bad hash"));

        assertEquals(DeviceWakeWordEntity.FAILED, row.getStatus());
        assertEquals("你好小智", row.getActiveWord());
        assertEquals(3L, row.getActiveVersion());
        assertEquals("SHA256_MISMATCH", row.getLastErrorCode());
    }

    private DeviceWakeWordServiceImpl service(DeviceWakeWordDao dao) {
        return new DeviceWakeWordServiceImpl(mock(DeviceDao.class), dao);
    }

    private DeviceWakeWordEntity pendingRow() {
        DeviceWakeWordEntity row = new DeviceWakeWordEntity();
        row.setDeviceId("device-1");
        row.setDesiredWord("小布小布");
        row.setDesiredVersion(7L);
        row.setActiveWord("你好小智");
        row.setActiveVersion(3L);
        row.setStatus(DeviceWakeWordEntity.WAITING_REBOOT);
        return row;
    }

    private DeviceReportReqDTO.WakeWordInfo report(boolean supported, int layoutVersion, long slotSize,
            long activeVersion, String activeWord, long pendingVersion, String status,
            String errorCode, String errorMessage) {
        DeviceReportReqDTO.WakeWordInfo report = new DeviceReportReqDTO.WakeWordInfo();
        report.setSupported(supported);
        report.setLayoutVersion(layoutVersion);
        report.setSlotSize(slotSize);
        report.setActiveVersion(activeVersion);
        report.setActiveWord(activeWord);
        report.setPendingVersion(pendingVersion);
        report.setStatus(status);
        report.setErrorCode(errorCode);
        report.setErrorMessage(errorMessage);
        return report;
    }
}
