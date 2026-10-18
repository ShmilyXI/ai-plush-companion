package zixuan.modules.companion.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import zixuan.modules.companion.debug.config.DeviceDebugLogExecutorConfig;
import zixuan.modules.companion.debug.controller.CompanionDeviceDebugLogController;
import zixuan.modules.companion.debug.controller.InternalDeviceDebugLogController;
import zixuan.modules.companion.debug.dto.DeviceDebugLogIngestDTO;
import zixuan.modules.companion.debug.model.DeviceDebugLogEvent;
import zixuan.modules.companion.debug.service.DeviceDebugLogService;
import zixuan.modules.companion.debug.vo.DeviceDebugLogHistoryVO;
import zixuan.modules.security.user.SecurityUser;

class CompanionDeviceDebugLogControllerTest {
    @Test
    void ownerRoutesUseCurrentUserAndTheNamedExecutor() {
        DeviceDebugLogService service = mock(DeviceDebugLogService.class);
        ExecutorService executor = mock(ExecutorService.class);
        DeviceDebugLogHistoryVO expected = new DeviceDebugLogHistoryVO(List.of(event()), "9-0");
        when(service.history(7L, "device-a")).thenReturn(expected);
        CompanionDeviceDebugLogController controller = new CompanionDeviceDebugLogController(service, executor);
        SseEmitter emitter;

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            assertEquals(expected, controller.history("device-a").getData());
            emitter = controller.stream("device-a", "8-0");
            assertEquals(0L, emitter.getTimeout());
        }

        verify(service).requireOwned(7L, "device-a");
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        task.getValue().run();
        verify(service).stream(eq("device-a"), eq("8-0"), same(emitter));
    }

    @Test
    void ownerRoutesExposeCompanionPathsPermissionsAndAuthenticatedStreamContract() throws Exception {
        RequestMapping root = CompanionDeviceDebugLogController.class.getAnnotation(RequestMapping.class);
        assertEquals(List.of("/companion/devices"), List.of(root.value()));

        Method history = CompanionDeviceDebugLogController.class.getMethod("history", String.class);
        GetMapping historyRoute = history.getAnnotation(GetMapping.class);
        assertEquals(List.of("/{id}/debug-logs"), List.of(historyRoute.value()));
        assertNormalPermission(history);

        Method stream = CompanionDeviceDebugLogController.class.getMethod("stream", String.class, String.class);
        GetMapping streamRoute = stream.getAnnotation(GetMapping.class);
        assertEquals(List.of("/{id}/debug-logs/stream"), List.of(streamRoute.value()));
        assertEquals(List.of(MediaType.TEXT_EVENT_STREAM_VALUE), List.of(streamRoute.produces()));
        RequestParam after = stream.getParameters()[1].getAnnotation(RequestParam.class);
        assertEquals("after", after.value());
        assertEquals("0-0", after.defaultValue());
        assertNormalPermission(stream);
    }

    @Test
    void internalRouteAcceptsValidatedDtoWithoutUserIdentity() throws Exception {
        DeviceDebugLogService service = mock(DeviceDebugLogService.class);
        InternalDeviceDebugLogController controller = new InternalDeviceDebugLogController(service);
        DeviceDebugLogIngestDTO dto = new DeviceDebugLogIngestDTO();
        dto.setDeviceRef("device-a");
        dto.setCategory("device");
        dto.setEventType("connection.opened");
        dto.setLevel("info");
        dto.setSummary("connected");
        dto.setDetails(Map.of("board", "esp32"));

        assertEquals(0, controller.ingest(dto).getCode());

        verify(service).ingest("device-a", dto.toDraft());
        RequestMapping root = InternalDeviceDebugLogController.class.getAnnotation(RequestMapping.class);
        assertEquals(List.of("/internal/device-debug-logs"), List.of(root.value()));
        Method ingest = InternalDeviceDebugLogController.class.getMethod("ingest", DeviceDebugLogIngestDTO.class);
        assertEquals(List.of("/events"), List.of(ingest.getAnnotation(PostMapping.class).value()));
        assertTrue(ingest.getParameters()[0].isAnnotationPresent(jakarta.validation.Valid.class));
    }

    @Test
    void streamExecutorIsNamedAndUsesVirtualThreads() throws Exception {
        Method factory = DeviceDebugLogExecutorConfig.class.getMethod("deviceDebugLogStreamExecutor");
        Bean bean = factory.getAnnotation(Bean.class);
        assertEquals(List.of("deviceDebugLogStreamExecutor"), List.of(bean.name()));
        assertEquals("close", bean.destroyMethod());

        try (ExecutorService executor = new DeviceDebugLogExecutorConfig().deviceDebugLogStreamExecutor()) {
            assertTrue(executor.submit(() -> Thread.currentThread().isVirtual()).get());
        }
    }

    private void assertNormalPermission(Method method) {
        RequiresPermissions permission = method.getAnnotation(RequiresPermissions.class);
        assertNotNull(permission);
        assertEquals(List.of("sys:role:normal"), List.of(permission.value()));
    }

    private DeviceDebugLogEvent event() {
        return new DeviceDebugLogEvent(
                "9-0", "device-a", 1L, 2L, null, null,
                "device", "connection.opened", "info", "connected", Map.of(), null);
    }
}
