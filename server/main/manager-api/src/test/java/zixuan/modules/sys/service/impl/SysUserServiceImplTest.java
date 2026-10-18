package zixuan.modules.sys.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import zixuan.modules.agent.service.AgentService;
import zixuan.modules.device.service.DeviceService;
import zixuan.modules.sys.dao.SysUserDao;
import zixuan.modules.sys.dto.AdminPageUserDTO;
import zixuan.modules.sys.entity.SysUserEntity;
import zixuan.modules.sys.service.SysParamsService;

class SysUserServiceImplTest {
    @Test
    void adminPageLoadsDeviceCountsInOneBatch() {
        SysUserDao userDao = mock(SysUserDao.class);
        DeviceService deviceService = mock(DeviceService.class);
        SysUserEntity first = user(1L, "alice");
        SysUserEntity second = user(2L, "bob");
        Page<SysUserEntity> page = new Page<>(1, 20, 2);
        page.setRecords(List.of(first, second));
        when(userDao.selectPage(any(), any())).thenReturn(page);
        when(deviceService.countByUserIds(List.of(1L, 2L))).thenReturn(Map.of(1L, 2L, 2L, 3L));
        SysUserServiceImpl service = new SysUserServiceImpl(userDao, deviceService,
                mock(AgentService.class), mock(SysParamsService.class));
        AdminPageUserDTO dto = new AdminPageUserDTO(); dto.setPage("1"); dto.setLimit("20");

        var result = service.page(dto);

        assertEquals("2", result.getList().get(0).getDeviceCount());
        assertEquals("3", result.getList().get(1).getDeviceCount());
        verify(deviceService).countByUserIds(List.of(1L, 2L));
    }

    private SysUserEntity user(Long id, String username) {
        SysUserEntity user = new SysUserEntity();
        user.setId(id); user.setUsername(username); user.setStatus(1);
        return user;
    }
}
