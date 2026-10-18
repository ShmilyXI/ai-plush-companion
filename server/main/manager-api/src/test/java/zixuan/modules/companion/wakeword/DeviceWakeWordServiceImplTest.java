package zixuan.modules.companion.wakeword;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import zixuan.common.exception.ErrorCode;
import zixuan.common.exception.RenException;
import zixuan.common.utils.MessageUtils;
import zixuan.modules.companion.wakeword.dao.DeviceWakeWordDao;
import zixuan.modules.companion.wakeword.entity.DeviceWakeWordEntity;
import zixuan.modules.companion.wakeword.service.impl.DeviceWakeWordServiceImpl;
import zixuan.modules.device.dao.DeviceDao;
import zixuan.modules.device.entity.DeviceEntity;

class DeviceWakeWordServiceImplTest {
    @ParameterizedTest
    @ValueSource(strings = { "小", "一二三四五六七八九", "hello", "小布hello" })
    void rejectsUnsupportedWords(String word) {
        DeviceWakeWordServiceImpl service = service(mock(DeviceDao.class), mock(DeviceWakeWordDao.class));

        RenException failure = assertThrows(RenException.class, () -> service.update(7L, "device-1", word));

        assertEquals("唤醒词只支持二到八个中文汉字", failure.getMsg());
    }

    @Test
    void rejectsDevicesNotOwnedByTheUser() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        DeviceWakeWordDao wakeWordDao = mock(DeviceWakeWordDao.class);
        when(deviceDao.selectOwnedByIdForUpdate("device-1", 7L)).thenReturn(null);
        DeviceWakeWordServiceImpl service = service(deviceDao, wakeWordDao);

        RenException failure;
        try (var messages = mockStatic(MessageUtils.class)) {
            messages.when(() -> MessageUtils.getMessage(ErrorCode.NO_PERMISSION)).thenReturn("no permission");
            failure = assertThrows(RenException.class,
                    () -> service.update(7L, "device-1", "小布小布"));
        }

        assertEquals(ErrorCode.NO_PERMISSION, failure.getCode());
        verify(wakeWordDao, never()).updateById(any(DeviceWakeWordEntity.class));
    }

    @Test
    void rejectsIncapableDevices() {
        DeviceDao deviceDao = ownedDeviceDao();
        DeviceWakeWordDao wakeWordDao = mock(DeviceWakeWordDao.class);
        DeviceWakeWordEntity row = row();
        row.setCapable(false);
        when(wakeWordDao.selectByDeviceIdForUpdate("device-1")).thenReturn(row);

        RenException failure = assertThrows(RenException.class,
                () -> service(deviceDao, wakeWordDao).update(7L, "device-1", "小布小布"));

        assertEquals("设备固件暂不支持动态唤醒词", failure.getMsg());
    }

    @Test
    void identicalDesiredWordIsIdempotent() {
        DeviceDao deviceDao = ownedDeviceDao();
        DeviceWakeWordDao wakeWordDao = mock(DeviceWakeWordDao.class);
        DeviceWakeWordEntity row = row();
        row.setDesiredWord("小布小布");
        row.setDesiredVersion(4L);
        when(wakeWordDao.selectByDeviceIdForUpdate("device-1")).thenReturn(row);

        var result = service(deviceDao, wakeWordDao).update(7L, "device-1", " 小布小布 ");

        assertEquals(4L, result.getDesiredVersion());
        assertSame(row.getUpdatedAt(), result.getUpdatedAt());
        verify(wakeWordDao, never()).updateById(any(DeviceWakeWordEntity.class));
    }

    @Test
    void newWordStartsOneFreshGenerationVersion() {
        DeviceDao deviceDao = ownedDeviceDao();
        DeviceWakeWordDao wakeWordDao = mock(DeviceWakeWordDao.class);
        DeviceWakeWordEntity row = row();
        row.setDesiredWord("你好紫萱");
        row.setDesiredVersion(4L);
        row.setCandidatePath("old.bin");
        row.setCandidateToken("old-token");
        row.setCandidateSha256("old-hash");
        row.setCandidateSize(12L);
        row.setLastErrorCode("old-error");
        row.setLastErrorMessage("old-message");
        row.setLockToken("old-lock");
        when(wakeWordDao.selectByDeviceIdForUpdate("device-1")).thenReturn(row);
        when(wakeWordDao.updateById(row)).thenReturn(1);

        var result = service(deviceDao, wakeWordDao).update(7L, "device-1", " 小布小布 ");

        assertEquals("小布小布", result.getDesiredWord());
        assertEquals(5L, result.getDesiredVersion());
        assertEquals(DeviceWakeWordEntity.GENERATING, result.getStatus());
        assertEquals(null, row.getCandidatePath());
        assertEquals(null, row.getCandidateToken());
        assertEquals(null, row.getCandidateSha256());
        assertEquals(null, row.getCandidateSize());
        assertEquals(null, row.getLastErrorCode());
        assertEquals(null, row.getLastErrorMessage());
        assertEquals(null, row.getLockToken());
        assertEquals(null, row.getLockUntil());
        verify(wakeWordDao).updateById(row);
    }

    @Test
    void retryRequiresFailedStateAndReusesTheVersion() {
        DeviceDao deviceDao = ownedDeviceDao();
        DeviceWakeWordDao wakeWordDao = mock(DeviceWakeWordDao.class);
        DeviceWakeWordEntity row = row();
        row.setStatus(DeviceWakeWordEntity.ACTIVE);
        when(wakeWordDao.selectByDeviceIdForUpdate("device-1")).thenReturn(row);

        assertThrows(RenException.class, () -> service(deviceDao, wakeWordDao).retry(7L, "device-1"));
        verify(wakeWordDao, never()).updateById(any(DeviceWakeWordEntity.class));
    }

    @Test
    void retryGeneratesOnlyWhenNoCompleteCandidateExists() {
        DeviceDao deviceDao = ownedDeviceDao();
        DeviceWakeWordDao wakeWordDao = mock(DeviceWakeWordDao.class);
        DeviceWakeWordEntity row = row();
        row.setDesiredVersion(9L);
        row.setStatus(DeviceWakeWordEntity.FAILED);
        when(wakeWordDao.selectByDeviceIdForUpdate("device-1")).thenReturn(row);
        when(wakeWordDao.updateById(row)).thenReturn(1);

        var result = service(deviceDao, wakeWordDao).retry(7L, "device-1");

        assertEquals(9L, result.getDesiredVersion());
        assertEquals(DeviceWakeWordEntity.GENERATING, result.getStatus());
    }

    @Test
    void retryDispatchesAnExistingCompleteCandidate() {
        DeviceDao deviceDao = ownedDeviceDao();
        DeviceWakeWordDao wakeWordDao = mock(DeviceWakeWordDao.class);
        DeviceWakeWordEntity row = row();
        row.setDesiredVersion(9L);
        row.setStatus(DeviceWakeWordEntity.FAILED);
        row.setCandidatePath("candidate.bin");
        row.setCandidateSha256("a".repeat(64));
        row.setCandidateSize(123L);
        when(wakeWordDao.selectByDeviceIdForUpdate("device-1")).thenReturn(row);
        when(wakeWordDao.updateById(row)).thenReturn(1);

        var result = service(deviceDao, wakeWordDao).retry(7L, "device-1");

        assertEquals(9L, result.getDesiredVersion());
        assertEquals(DeviceWakeWordEntity.WAITING_DEVICE, result.getStatus());
    }

    private DeviceWakeWordServiceImpl service(DeviceDao deviceDao, DeviceWakeWordDao wakeWordDao) {
        return new DeviceWakeWordServiceImpl(deviceDao, wakeWordDao);
    }

    private DeviceDao ownedDeviceDao() {
        DeviceDao dao = mock(DeviceDao.class);
        DeviceEntity device = new DeviceEntity();
        device.setId("device-1");
        device.setUserId(7L);
        when(dao.selectOwnedByIdForUpdate("device-1", 7L)).thenReturn(device);
        return dao;
    }

    private DeviceWakeWordEntity row() {
        DeviceWakeWordEntity row = new DeviceWakeWordEntity();
        row.setDeviceId("device-1");
        row.setDesiredWord("你好紫萱");
        row.setDesiredVersion(1L);
        row.setActiveVersion(0L);
        row.setStatus(DeviceWakeWordEntity.IDLE);
        row.setCapable(true);
        return row;
    }
}
