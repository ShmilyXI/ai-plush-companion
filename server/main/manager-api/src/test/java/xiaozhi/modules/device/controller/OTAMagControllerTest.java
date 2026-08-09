package xiaozhi.modules.device.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import xiaozhi.common.exception.RenException;
import xiaozhi.common.redis.RedisKeys;
import xiaozhi.common.redis.RedisUtils;
import xiaozhi.modules.companion.service.AdminFirmwareService;
import xiaozhi.modules.device.entity.OtaEntity;
import xiaozhi.modules.device.service.OtaService;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.common.user.UserDetail;

class OTAMagControllerTest {
    private OtaService otaService;
    private AdminFirmwareService firmwareService;
    private RedisUtils redisUtils;
    private OTAMagController controller;

    @BeforeEach
    void setUp() throws Exception {
        otaService = mock(OtaService.class);
        firmwareService = mock(AdminFirmwareService.class);
        redisUtils = mock(RedisUtils.class);
        controller = controller(otaService, firmwareService, redisUtils, mock(SysParamsService.class));

        Subject subject = mock(Subject.class);
        UserDetail user = new UserDetail();
        user.setId(7L);
        when(subject.getPrincipal()).thenReturn(user);
        ThreadContext.bind(subject);
    }

    @AfterEach
    void tearDown() {
        ThreadContext.unbindSubject();
    }

    @Test
    void jsonCreationAndBareFirmwareUploadRemainCompatibleButDoNotWrite() {
        OtaEntity entity = firmware("f1", "/etc/hosts");
        when(otaService.storeFirmware(any())).thenReturn("uploadfile/orphan.bin");

        assertNotEquals(0, controller.save(entity).getCode());
        assertNotEquals(0, controller.uploadFirmware(file()).getCode());

        verify(otaService, never()).save(any());
        verify(otaService, never()).storeFirmware(any());
        verify(firmwareService, never()).upload(any(), any(), any(), any(), any(), any());
    }

    @Test
    void legacyMetadataUpdateAndDeleteDelegateOnceToTheAuditedService() {
        OtaEntity update = firmware(null, "/etc/hosts");

        assertEquals(0, controller.update("f1", update).getCode());
        assertEquals(0, controller.delete(new String[] { "f1" }).getCode());

        verify(firmwareService).update(7L, "f1", update);
        verify(firmwareService).deleteAll(7L, new String[] { "f1" });
        verify(otaService, never()).update(any());
        verify(otaService, never()).delete(any());
    }

    @Test
    void downloadRejectsAnUnmanagedDatabasePath() {
        when(redisUtils.get(RedisKeys.getOtaIdKey("token"))).thenReturn("f1");
        when(redisUtils.get(RedisKeys.getOtaDownloadCountKey("token"))).thenReturn(0);
        when(otaService.selectById("f1")).thenReturn(firmware("f1", "/etc/hosts"));
        when(otaService.resolveManagedFirmwareFile("/etc/hosts"))
                .thenThrow(new RenException("固件路径不合法"));

        assertEquals(HttpStatus.NOT_FOUND, controller.downloadFirmware("token").getStatusCode());

        verify(otaService).resolveManagedFirmwareFile("/etc/hosts");
    }

    @Test
    void compatibilityPermissionsKeepFirmwareAdminAndAssetUserBoundaries() throws Exception {
        assertPermission("save", new Class<?>[] { OtaEntity.class }, "sys:role:superAdmin");
        assertPermission("update", new Class<?>[] { String.class, OtaEntity.class }, "sys:role:superAdmin");
        assertPermission("delete", new Class<?>[] { String[].class }, "sys:role:superAdmin");
        assertPermission("uploadFirmware", new Class<?>[] { org.springframework.web.multipart.MultipartFile.class },
                "sys:role:superAdmin");
        assertPermission("uploadAssetsBin", new Class<?>[] { org.springframework.web.multipart.MultipartFile.class },
                "sys:role:normal");
    }

    private void assertPermission(String name, Class<?>[] parameters, String expected) throws Exception {
        Method method = OTAMagController.class.getMethod(name, parameters);
        assertEquals(expected, method.getAnnotation(RequiresPermissions.class).value()[0]);
    }

    private OTAMagController controller(OtaService ota, AdminFirmwareService firmware, RedisUtils redis,
            SysParamsService params) throws Exception {
        Constructor<?> constructor = OTAMagController.class.getDeclaredConstructors()[0];
        Object[] arguments = java.util.Arrays.stream(constructor.getParameterTypes()).map(type -> {
            if (type == OtaService.class) return ota;
            if (type == AdminFirmwareService.class) return firmware;
            if (type == RedisUtils.class) return redis;
            if (type == SysParamsService.class) return params;
            throw new IllegalStateException(type.getName());
        }).toArray();
        return (OTAMagController) constructor.newInstance(arguments);
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "firmware.bin", "application/octet-stream", new byte[] { 1 });
    }

    private OtaEntity firmware(String id, String path) {
        OtaEntity entity = new OtaEntity();
        entity.setId(id);
        entity.setFirmwareName("固件");
        entity.setType("esp32");
        entity.setVersion("1.0.0");
        entity.setFirmwarePath(path);
        return entity;
    }
}
