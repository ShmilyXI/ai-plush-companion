package xiaozhi.modules.companion.service.impl;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

import cn.hutool.json.JSONObject;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.companion.debug.model.DeviceDebugLogDraft;
import xiaozhi.modules.companion.debug.service.DeviceDebugLogService;
import xiaozhi.modules.companion.dto.CompanionDeviceBindDTO;
import xiaozhi.modules.companion.dto.CompanionDeviceCommandDTO;
import xiaozhi.modules.companion.service.CompanionDeviceService;
import xiaozhi.modules.companion.service.CompanionProfileService;
import xiaozhi.modules.companion.vo.CompanionDeviceVO;
import xiaozhi.modules.device.dto.DeviceUpdateDTO;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.service.DeviceService;
import xiaozhi.modules.device.service.DeviceOnlineStatus;

@Service
@AllArgsConstructor
@Slf4j
public class CompanionDeviceServiceImpl implements CompanionDeviceService {
    private static final String DEFAULT_TEMPLATE_ID = "template-xiaozhi";
    private static final String DEFAULT_PROFILE_NAME = "小智";

    private final DeviceService deviceService;
    private final CompanionProfileService profileService;
    private final DeviceDebugLogService debugLogService;

    @Override
    public List<CompanionDeviceVO> list(Long userId) {
        return deviceService.getUserDevices(userId)
                .stream()
                .map(this::inspectAndConvert)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bind(Long userId, CompanionDeviceBindDTO dto) {
        String profileId = profileService.resolveForDeviceBinding(
                userId, dto.getProfileId(), DEFAULT_TEMPLATE_ID, DEFAULT_PROFILE_NAME);
        deviceService.deviceActivationWithLockedUser(userId, profileId, dto.getActivationCode());
    }

    @Override
    public CompanionDeviceVO get(Long userId, String deviceId) {
        DeviceEntity device = requireOwned(userId, deviceId);
        return inspectAndConvert(device);
    }

    @Override
    public void update(Long userId, String deviceId, DeviceUpdateDTO dto) {
        requireOwned(userId, deviceId);
        DeviceEntity device = new DeviceEntity();
        device.setId(deviceId);
        if (dto.getAlias() != null) {
            device.setAlias(dto.getAlias());
        }
        if (dto.getAutoUpdate() != null) {
            device.setAutoUpdate(dto.getAutoUpdate());
        }
        UpdateWrapper<DeviceEntity> ownedDevice = new UpdateWrapper<DeviceEntity>()
                .eq("id", deviceId)
                .eq("user_id", userId);
        if (!deviceService.update(device, ownedDevice)) {
            throw new RenException(ErrorCode.UPDATE_DATA_FAILED);
        }
    }

    @Override
    public void setDebugLogEnabled(Long userId, String deviceId, boolean enabled) {
        requireOwned(userId, deviceId);
        DeviceEntity device = new DeviceEntity();
        device.setId(deviceId);
        device.setDebugLogEnabled(enabled ? 1 : 0);
        UpdateWrapper<DeviceEntity> ownedDevice = new UpdateWrapper<DeviceEntity>()
                .eq("id", deviceId)
                .eq("user_id", userId);
        if (!deviceService.update(device, ownedDevice)) {
            throw new RenException(ErrorCode.UPDATE_DATA_FAILED);
        }
    }

    @Override
    public void switchProfile(Long userId, String deviceId, String profileId) {
        profileService.get(userId, profileId);
        deviceService.switchCompanionProfile(userId, deviceId, profileId);
    }

    @Override
    public void unbind(Long userId, String deviceId) {
        requireOwned(userId, deviceId);
        deviceService.unbindDevice(userId, deviceId);
    }

    @Override
    public Object command(Long userId, String deviceId, CompanionDeviceCommandDTO dto) {
        requireOwned(userId, deviceId);
        String toolName;
        String argumentName;
        if ("volume".equals(dto.getCommand())) {
            toolName = "self.audio_speaker.set_volume";
            argumentName = "volume";
        } else if ("brightness".equals(dto.getCommand())) {
            toolName = "self.screen.set_brightness";
            argumentName = "brightness";
        } else {
            throw new RenException(ErrorCode.PARAM_TYPE_INVALID);
        }
        emitCommandEvent(deviceId, dto, "command.started", "info", "设备命令开始执行", null);
        long startedAt = System.nanoTime();
        Object result;
        try {
            result = deviceService.callDeviceTool(deviceId, toolName, Map.of(argumentName, dto.getValue()));
        } catch (RuntimeException exception) {
            emitCommandEvent(deviceId, dto, "command.failed", "error", "设备命令执行失败",
                    elapsedSince(startedAt));
            throw new RenException(ErrorCode.DEVICE_OFFLINE, exception);
        }
        try {
            validateCommandResult(result);
        } catch (RenException exception) {
            emitCommandEvent(deviceId, dto, "command.failed", "error", "设备命令执行失败",
                    elapsedSince(startedAt));
            throw exception;
        }
        emitCommandEvent(deviceId, dto, "command.completed", "info", "设备命令执行完成",
                elapsedSince(startedAt));
        return result;
    }

    private void validateCommandResult(Object result) {
        if (result == null) {
            throw new RenException(ErrorCode.DEVICE_OFFLINE);
        }
        if (Boolean.FALSE.equals(result)
                || (result instanceof Map<?, ?> map && (Boolean.FALSE.equals(map.get("success"))
                        || Boolean.TRUE.equals(map.get("isError"))))) {
            throw new RenException(ErrorCode.DEVICE_COMMAND_FAILED);
        }
    }

    private long elapsedSince(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedAt));
    }

    private void emitCommandEvent(String deviceId, CompanionDeviceCommandDTO dto, String eventType, String level,
            String summary, Long durationMs) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("command", dto.getCommand());
        if (dto.getValue() != null) {
            details.put("value", dto.getValue());
        }
        DeviceDebugLogDraft event = new DeviceDebugLogDraft(
                null,
                null,
                "device",
                eventType,
                level,
                summary,
                details,
                null,
                durationMs);
        try {
            debugLogService.ingest(deviceId, event);
        } catch (RuntimeException exception) {
            log.debug("设备命令调试日志写入失败，设备ID: {}, 异常类型: {}",
                    deviceId, exception.getClass().getSimpleName());
        }
    }

    private DeviceEntity requireOwned(Long userId, String deviceId) {
        DeviceEntity device = deviceService.selectById(deviceId);
        if (device == null || userId == null || !userId.equals(device.getUserId())) {
            throw new RenException(ErrorCode.DEVICE_NOT_EXIST);
        }
        return device;
    }

    private Object inspectTools(DeviceEntity device) {
        Object inventory;
        try {
            inventory = deviceService.getDeviceTools(device.getId());
        } catch (RuntimeException exception) {
            log.warn("获取设备工具清单失败，设备ID: {}, 原因: {}", device.getId(), exception.getMessage());
            return null;
        }
        if (inventory == null) {
            return null;
        }
        boolean display = hasTool(inventory, "self.screen.");
        boolean camera = hasTool(inventory, "self.camera.");
        if ((display && !Integer.valueOf(1).equals(device.getHasDisplay()))
                || (camera && !Integer.valueOf(1).equals(device.getHasCamera()))) {
            DeviceEntity capabilities = new DeviceEntity();
            capabilities.setId(device.getId());
            capabilities.setHasDisplay(display || Integer.valueOf(1).equals(device.getHasDisplay()) ? 1 : 0);
            capabilities.setHasCamera(camera || Integer.valueOf(1).equals(device.getHasCamera()) ? 1 : 0);
            deviceService.updateById(capabilities);
            device.setHasDisplay(capabilities.getHasDisplay());
            device.setHasCamera(capabilities.getHasCamera());
        }
        return inventory;
    }

    private boolean hasTool(Object inventory, String prefix) {
        if (!(inventory instanceof Map<?, ?> inventoryMap)) {
            return false;
        }
        Object tools = inventoryMap.get("tools");
        if (!(tools instanceof Iterable<?> iterable)) {
            return false;
        }
        for (Object tool : iterable) {
            Object name = tool instanceof Map<?, ?> map ? map.get("name")
                    : tool instanceof JSONObject json ? json.get("name") : null;
            if (name instanceof String text && text.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private CompanionDeviceVO inspectAndConvert(DeviceEntity device) {
        inspectTools(device);
        return toVO(device);
    }

    private CompanionDeviceVO toVO(DeviceEntity device) {
        CompanionDeviceVO vo = new CompanionDeviceVO();
        vo.setId(device.getId());
        vo.setMacAddress(device.getMacAddress());
        vo.setAlias(device.getAlias());
        vo.setBoard(device.getBoard());
        vo.setOnline(isOnline(device));
        vo.setLastConnectedAt(device.getLastConnectedAt());
        vo.setAppVersion(device.getAppVersion());
        vo.setHasDisplay(Integer.valueOf(1).equals(device.getHasDisplay()));
        vo.setHasCamera(Integer.valueOf(1).equals(device.getHasCamera()));
        vo.setDebugLogEnabled(Integer.valueOf(1).equals(device.getDebugLogEnabled()));
        vo.setActiveProfileId(device.getAgentId());
        vo.setCompanionMode("proactive".equals(device.getCompanionMode()) ? "proactive" : "turn_based");
        if (device.getAgentId() != null && !device.getAgentId().isBlank()) {
            vo.setEffectiveModels(profileService.get(device.getUserId(), device.getAgentId()).getEffectiveModels());
        } else {
            vo.setEffectiveModels(List.of());
        }
        return vo;
    }

    private boolean isOnline(DeviceEntity device) {
        return DeviceOnlineStatus.isOnline(device.getLastConnectedAt());
    }
}
