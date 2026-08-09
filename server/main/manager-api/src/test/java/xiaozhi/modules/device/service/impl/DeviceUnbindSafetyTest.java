package xiaozhi.modules.device.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.redis.RedisUtils;
import xiaozhi.common.utils.MessageUtils;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.companion.service.CompanionSubscriptionService;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.service.DeviceAddressBookService;
import xiaozhi.modules.device.service.DeviceService;
import xiaozhi.modules.sys.dao.SysUserDao;

class DeviceUnbindSafetyTest {

    @Test
    void foreignUserCannotDeleteAddressBookEntries() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        DeviceAddressBookService addressBook = mock(DeviceAddressBookService.class);
        when(deviceDao.selectOwnedByIdForUpdate("device-a", 7L)).thenReturn(null);
        DeviceServiceImpl service = service(deviceDao, addressBook);

        try (MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            messages.when(() -> MessageUtils.getMessage(ErrorCode.DEVICE_NOT_EXIST)).thenReturn("not found");
            assertThrows(RenException.class, () -> service.unbindDevice(7L, "device-a"));
        }

        verify(deviceDao, never()).delete(any());
        verify(addressBook, never()).deleteByMacAddresses(any());
    }

    @Test
    void zeroRowDeleteDoesNotCleanAddressBook() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        DeviceAddressBookService addressBook = mock(DeviceAddressBookService.class);
        when(deviceDao.selectOwnedByIdForUpdate("device-a", 7L)).thenReturn(ownedDevice());
        when(deviceDao.delete(any())).thenReturn(0);
        DeviceServiceImpl service = service(deviceDao, addressBook);

        try (MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            messages.when(() -> MessageUtils.getMessage(ErrorCode.DEVICE_NOT_EXIST)).thenReturn("not found");
            assertThrows(RenException.class, () -> service.unbindDevice(7L, "device-a"));
        }

        verify(addressBook, never()).deleteByMacAddresses(any());
    }

    @Test
    void addressBookFailureRollsBackDeviceDeleteThroughSpringProxy() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:unbind_rollback_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE ai_device (id VARCHAR(64) PRIMARY KEY, user_id BIGINT, mac_address VARCHAR(64), agent_id VARCHAR(64))");
        jdbc.update("INSERT INTO ai_device(id, user_id, mac_address, agent_id) VALUES ('device-a', 7, 'AA:BB', 'agent-a')");
        DeviceDao deviceDao = mock(DeviceDao.class);
        when(deviceDao.selectOwnedByIdForUpdate("device-a", 7L)).thenAnswer(invocation -> jdbc.query(
                "SELECT id, user_id, mac_address, agent_id FROM ai_device WHERE id = 'device-a' AND user_id = 7 FOR UPDATE",
                result -> result.next() ? device(result.getString(1), result.getLong(2), result.getString(3),
                        result.getString(4)) : null));
        when(deviceDao.delete(any())).thenAnswer(invocation -> jdbc.update(
                "DELETE FROM ai_device WHERE id = 'device-a' AND user_id = 7"));
        DeviceAddressBookService addressBook = mock(DeviceAddressBookService.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("cleanup failed"))
                .when(addressBook).deleteByMacAddresses(List.of("AA:BB"));
        DeviceService target = service(deviceDao, addressBook);
        DeviceService proxy = transactionalProxy(target, new DataSourceTransactionManager(dataSource));

        assertThrows(IllegalStateException.class, () -> proxy.unbindDevice(7L, "device-a"));

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_device WHERE id = 'device-a'", Integer.class));
    }

    private DeviceServiceImpl service(DeviceDao deviceDao, DeviceAddressBookService addressBook) {
        DeviceServiceImpl service = new DeviceServiceImpl(
                deviceDao, null, null, mock(RedisUtils.class), null, addressBook, mock(AgentDao.class),
                mock(CompanionSubscriptionService.class), mock(SysUserDao.class));
        ReflectionTestUtils.setField(service, "baseDao", deviceDao);
        return service;
    }

    private DeviceService transactionalProxy(DeviceService target, DataSourceTransactionManager transactionManager) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvice(interceptor);
        return (DeviceService) proxyFactory.getProxy();
    }

    private DeviceEntity ownedDevice() {
        return device("device-a", 7L, "AA:BB", "agent-a");
    }

    private DeviceEntity device(String id, Long userId, String macAddress, String agentId) {
        DeviceEntity device = new DeviceEntity();
        device.setId(id);
        device.setUserId(userId);
        device.setMacAddress(macAddress);
        device.setAgentId(agentId);
        return device;
    }
}
