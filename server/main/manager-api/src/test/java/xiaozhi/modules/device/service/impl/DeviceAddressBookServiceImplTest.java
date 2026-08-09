package xiaozhi.modules.device.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import xiaozhi.common.redis.RedisUtils;
import xiaozhi.modules.device.dao.DeviceAddressBookDao;
import xiaozhi.modules.device.entity.DeviceAddressBookEntity;
import xiaozhi.modules.device.service.DeviceService;
import xiaozhi.modules.sys.service.SysParamsService;

class DeviceAddressBookServiceImplTest {

    @Test
    void newEntryUsesTheDedicatedAddressBookInsertMapping() {
        DeviceAddressBookDao addressBookDao = mock(DeviceAddressBookDao.class);
        RedisUtils redisUtils = mock(RedisUtils.class);
        DeviceService deviceService = mock(DeviceService.class);
        SysParamsService sysParamsService = mock(SysParamsService.class);
        DeviceAddressBookServiceImpl service = new DeviceAddressBookServiceImpl(
                addressBookDao, redisUtils, deviceService, sysParamsService);
        when(addressBookDao.selectOne(any())).thenReturn(null);
        when(addressBookDao.selectList(null)).thenReturn(List.of());

        service.saveOrUpdate("00:11:22:33:44:55", "00:11:22:33:44:66", "living-room", true);

        ArgumentCaptor<DeviceAddressBookEntity> entityCaptor = ArgumentCaptor.forClass(DeviceAddressBookEntity.class);
        verify(addressBookDao).insertAddressBook(entityCaptor.capture());
        DeviceAddressBookEntity entity = entityCaptor.getValue();
        assertEquals("00:11:22:33:44:55", entity.getMacAddress());
        assertEquals("00:11:22:33:44:66", entity.getTargetMac());
        assertEquals("living-room", entity.getAlias());
        assertTrue(entity.getHasPermission());
        verify(addressBookDao, never()).insert(any(DeviceAddressBookEntity.class));
    }

    @Test
    void rolledBackDeleteDoesNotPublishAddressBookCache() {
        DeviceAddressBookDao addressBookDao = mock(DeviceAddressBookDao.class);
        RedisUtils redisUtils = mock(RedisUtils.class);
        DeviceAddressBookServiceImpl service = new DeviceAddressBookServiceImpl(
                addressBookDao, redisUtils, mock(DeviceService.class), mock(SysParamsService.class));
        when(addressBookDao.selectList(null)).thenReturn(List.of());
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:address_book_cache_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        transaction.executeWithoutResult(status -> {
            service.deleteByMacAddresses(List.of("AA:BB"));
            status.setRollbackOnly();
        });

        verify(redisUtils, never()).set(any(), any());
    }
}
