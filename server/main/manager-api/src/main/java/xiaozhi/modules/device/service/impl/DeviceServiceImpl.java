package xiaozhi.modules.device.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.TimeZone;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.lang3.StringUtils;
import org.springframework.aop.framework.AopContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.client.RestTemplate;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.page.PageData;
import xiaozhi.common.redis.RedisKeys;
import xiaozhi.common.redis.RedisUtils;
import xiaozhi.common.service.impl.BaseServiceImpl;
import xiaozhi.common.user.UserDetail;
import xiaozhi.common.utils.ConvertUtils;
import xiaozhi.common.utils.DateUtils;
import xiaozhi.common.utils.JsonUtils;
import xiaozhi.common.utils.SpringContextUtils;
import xiaozhi.common.utils.ToolUtil;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.companion.service.CompanionSubscriptionService;
import xiaozhi.modules.companion.wakeword.service.DeviceWakeWordService;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.dto.DeviceManualAddDTO;
import xiaozhi.modules.device.dto.DevicePageUserDTO;
import xiaozhi.modules.device.dto.DeviceReportReqDTO;
import xiaozhi.modules.device.dto.DeviceReportRespDTO;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.entity.OtaEntity;
import xiaozhi.modules.device.service.DeviceAddressBookService;
import xiaozhi.modules.device.service.DeviceService;
import xiaozhi.modules.device.service.DeviceOnlineStatus;
import xiaozhi.modules.device.service.OtaService;
import xiaozhi.modules.device.vo.UserShowDeviceListVO;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.sys.dao.SysUserDao;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.sys.service.SysUserUtilService;

@Slf4j
@Service
@AllArgsConstructor
public class DeviceServiceImpl extends BaseServiceImpl<DeviceDao, DeviceEntity> implements DeviceService {

    private static final Set<String> DISPLAY_BOARDS = Set.of(
            "bread-compact-wifi-lcd", "bread-compact-esp32-lcd", "bread-compact-wifi-s3cam", "df-k10", "esp-box-3", "esp-box",
            "kevin-box-1", "kevin-box-2", "kevin-yuying-313lcd", "magiclick-2p4", "magiclick-2p5",
            "m5stack-core-s3", "atoms3-echo-base", "atoms3r-echo-base", "atoms3r-cam-m12-echo-base",
            "atommatrix-echo-base", "esp-sparkbot", "esp-spot-s3", "esp32-s3-touch-amoled-1.8",
            "esp32-s3-touch-lcd-1.85c", "esp32-s3-touch-lcd-1.85", "esp32-s3-touch-lcd-1.46",
            "esp32-s3-touch-lcd-3.5", "tudouzi", "lilygo-t-circle-s3", "lilygo-t-cameraplus-s3",
            "movecall-moji-esp32s3", "atk-dnesp32s3-box", "du-chatx", "xingzhi-cube-0.85tft-wifi",
            "xingzhi-cube-0.85tft-ml307", "xingzhi-cube-0.96oled-wifi", "xingzhi-cube-0.96oled-ml307",
            "xingzhi-cube-1.54tft-wifi", "xingzhi-cube-1.54tft-ml307", "sensecap-watcher", "doit-s3-aibox",
            "mixgo-nova");
    private static final Set<String> CAMERA_BOARDS = Set.of(
            "atoms3r-cam-m12-echo-base", "bread-compact-wifi-s3cam", "lilygo-t-cameraplus-s3", "sensecap-watcher");

    private final DeviceDao deviceDao;
    private final SysUserUtilService sysUserUtilService;
    private final SysParamsService sysParamsService;
    private final RedisUtils redisUtils;
    private final OtaService otaService;
    private final DeviceAddressBookService deviceAddressBookService;
    private final AgentDao agentDao;
    private final CompanionSubscriptionService companionSubscriptionService;
    private final SysUserDao sysUserDao;

    @Async
    public void updateDeviceConnectionInfo(String agentId, String deviceId, String appVersion) {
        updateDeviceConnectionInfo(agentId, deviceId, appVersion, null, null);
    }

    @Override
    public boolean touchHeartbeat(String deviceId) {
        DeviceEntity heartbeat = new DeviceEntity();
        heartbeat.setLastConnectedAt(new Date());
        UpdateWrapper<DeviceEntity> boundDevice = new UpdateWrapper<DeviceEntity>()
                .eq("id", deviceId)
                .isNotNull("user_id");
        return deviceDao.update(heartbeat, boundDevice) > 0;
    }

    @Override
    public void reportWakeWordState(String deviceId, DeviceReportReqDTO report) {
        if (report == null || report.getWakeWord() == null || StringUtils.isBlank(deviceId)) {
            return;
        }
        DeviceEntity device = deviceDao.selectById(deviceId);
        if (device == null || device.getUserId() == null) {
            return;
        }
        long assetsPartitionSize = report.getPartitionTable() == null ? 0L
                : report.getPartitionTable().stream()
                        .filter(partition -> "assets".equals(partition.getLabel()))
                        .map(DeviceReportReqDTO.Partition::getSize)
                        .filter(Objects::nonNull)
                        .mapToLong(Integer::longValue)
                        .findFirst().orElse(0L);
        SpringContextUtils.getBean(DeviceWakeWordService.class).report(
                device.getId(), report.getChipModelName(), assetsPartitionSize, report.getWakeWord());
    }

    @Async
    public void updateDeviceConnectionInfo(String agentId, String deviceId, String appVersion,
            Boolean hasDisplay, Boolean hasCamera) {
        try {
            DeviceEntity device = new DeviceEntity();
            device.setId(deviceId);
            device.setLastConnectedAt(new Date());
            if (StringUtils.isNotBlank(appVersion)) {
                device.setAppVersion(appVersion);
            }
            if (hasDisplay != null) {
                device.setHasDisplay(hasDisplay ? 1 : 0);
            }
            if (hasCamera != null) {
                device.setHasCamera(hasCamera ? 1 : 0);
            }
            deviceDao.updateById(device);
            if (StringUtils.isNotBlank(agentId)) {
                redisUtils.set(RedisKeys.getAgentDeviceLastConnectedAtById(agentId), new Date());
            }
        } catch (Exception e) {
            log.error("异步更新设备连接信息失败", e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deviceActivation(String agentId, String activationCode) {
        UserDetail user = requireCurrentUser();
        return activateDevice(user.getId(), agentId, activationCode, user, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.MANDATORY)
    public Boolean deviceActivationWithLockedUser(Long userId, String agentId, String activationCode) {
        UserDetail user = requireCurrentUser();
        if (userId == null || !userId.equals(user.getId())) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
        return activateDevice(userId, agentId, activationCode, user, true);
    }

    private Boolean activateDevice(Long userId, String agentId, String activationCode, UserDetail user,
            boolean userAlreadyLocked) {
        if (StringUtils.isBlank(activationCode)) {
            throw new RenException(ErrorCode.ACTIVATION_CODE_EMPTY);
        }
        String deviceKey = RedisKeys.getOtaActivationCode(activationCode);
        Object cacheDeviceId = redisUtils.get(deviceKey);
        if (ToolUtil.isEmpty(cacheDeviceId)) {
            throw new RenException(ErrorCode.ACTIVATION_CODE_ERROR);
        }
        String deviceId = (String) cacheDeviceId;
        String safeDeviceId = deviceId.replace(":", "_").toLowerCase();
        String cacheDeviceKey = RedisKeys.getOtaDeviceActivationInfo(safeDeviceId);
        Map<String, Object> cacheMap = JsonUtils.toStringObjectMap(redisUtils.get(cacheDeviceKey));
        if (ToolUtil.isEmpty(cacheMap)) {
            throw new RenException(ErrorCode.ACTIVATION_CODE_ERROR);
        }
        String cachedCode = (String) cacheMap.get("activation_code");
        if (!activationCode.equals(cachedCode)) {
            throw new RenException(ErrorCode.ACTIVATION_CODE_ERROR);
        }
        if (!userAlreadyLocked) {
            lockUserForEntitlement(userId);
        }
        lockAgentForDeviceReference(agentId, userId, user);
        // 检查设备有没有被激活
        if (selectById(deviceId) != null) {
            throw new RenException(ErrorCode.DEVICE_ALREADY_ACTIVATED);
        }
        companionSubscriptionService.requireDeviceSlot(userId, countUserDevices(userId));

        String macAddress = (String) cacheMap.get("mac_address");
        String board = (String) cacheMap.get("board");
        String appVersion = (String) cacheMap.get("app_version");
        Date currentTime = new Date();
        DeviceEntity deviceEntity = new DeviceEntity();
        deviceEntity.setId(deviceId);
        deviceEntity.setBoard(board);
        deviceEntity.setAgentId(agentId);
        deviceEntity.setAppVersion(appVersion);
        deviceEntity.setHasDisplay(resolveCapability(cacheMap.get("has_display"),
                DISPLAY_BOARDS.contains(normalizeBoard(board))));
        deviceEntity.setHasCamera(resolveCapability(cacheMap.get("has_camera"),
                CAMERA_BOARDS.contains(normalizeBoard(board))));
        deviceEntity.setMacAddress(macAddress);
        deviceEntity.setUserId(userId);
        deviceEntity.setCreator(userId);
        deviceEntity.setAutoUpdate(1);
        deviceEntity.setCreateDate(currentTime);
        deviceEntity.setUpdater(userId);
        deviceEntity.setUpdateDate(currentTime);
        deviceEntity.setLastConnectedAt(currentTime);
        try {
            deviceDao.insert(deviceEntity);
        } catch (DuplicateKeyException exception) {
            throw new RenException(ErrorCode.DEVICE_ALREADY_ACTIVATED, exception);
        }

        afterCommit(() -> redisUtils.delete(
                List.of(cacheDeviceKey, deviceKey, RedisKeys.getAgentDeviceCountById(agentId))));
        return true;
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    action.run();
                } catch (RuntimeException exception) {
                    log.warn("事务提交后的设备缓存清理失败", exception);
                }
            }
        });
    }

    private UserDetail requireCurrentUser() {
        UserDetail user = SecurityUser.getUser();
        if (user == null || user.getId() == null) {
            throw new RenException(ErrorCode.USER_NOT_LOGIN);
        }
        return user;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.MANDATORY)
    public void attachExistingDevice(Long userId, String agentId, String macAddress) {
        UserDetail user = requireCurrentUser();
        if (userId == null || !userId.equals(user.getId())) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
        String normalizedMac = normalizeMac(macAddress);
        DeviceEntity candidate = deviceDao.selectByNormalizedMac(normalizedMac);
        if (candidate == null) {
            throw new RenException(ErrorCode.DEVICE_NOT_EXIST);
        }
        lockUserForEntitlement(userId);
        Set<String> agentIds = new TreeSet<>();
        agentIds.add(agentId);
        if (StringUtils.isNotBlank(candidate.getAgentId())) {
            agentIds.add(candidate.getAgentId());
        }
        for (String lockedAgentId : agentIds) {
            lockCompanionAgentForDeviceReference(lockedAgentId, userId, user);
        }
        DeviceEntity device = deviceDao.selectByNormalizedMacForUpdate(normalizedMac);
        if (device == null) {
            throw new RenException(ErrorCode.DEVICE_NOT_EXIST);
        }
        if (device.getUserId() != null && !userId.equals(device.getUserId())) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
        if (!Objects.equals(candidate.getId(), device.getId())
                || !Objects.equals(candidate.getAgentId(), device.getAgentId())) {
            throw new RenException(ErrorCode.UPDATE_DATA_FAILED);
        }
        if (userId.equals(device.getUserId()) && StringUtils.isNotBlank(device.getAgentId())) {
            return;
        }
        if (device.getUserId() == null) {
            companionSubscriptionService.requireDeviceSlot(userId, countUserDevices(userId));
        }
        DeviceEntity update = new DeviceEntity();
        update.setId(device.getId());
        update.setUserId(userId);
        update.setAgentId(StringUtils.defaultIfBlank(device.getAgentId(), agentId));
        update.setUpdater(userId);
        update.setUpdateDate(new Date());
        if (deviceDao.updateById(update) != 1) {
            throw new RenException(ErrorCode.UPDATE_DATA_FAILED);
        }
        afterCommit(() -> redisUtils.delete(RedisKeys.getAgentDeviceCountById(agentId)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void switchCompanionProfile(Long userId, String deviceId, String agentId) {
        AgentEntity targetAgent = lockAgentForDeviceReference(agentId, userId, SecurityUser.getUser());
        if (!Integer.valueOf(1).equals(targetAgent.getCompanionEnabled())) {
            throw new RenException(ErrorCode.AGENT_NOT_FOUND);
        }
        DeviceEntity device = baseDao.selectById(deviceId);
        if (device == null || userId == null || !userId.equals(device.getUserId())) {
            throw new RenException(ErrorCode.DEVICE_NOT_EXIST);
        }
        UpdateWrapper<DeviceEntity> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", deviceId).eq("user_id", userId).set("agent_id", agentId);
        if (baseDao.update(null, wrapper) != 1) {
            throw new RenException(ErrorCode.UPDATE_DATA_FAILED);
        }
        if (StringUtils.isNotBlank(device.getAgentId())) {
            redisUtils.delete(RedisKeys.getAgentDeviceCountById(device.getAgentId()));
        }
        redisUtils.delete(RedisKeys.getAgentDeviceCountById(agentId));
    }

    private String normalizeBoard(String board) {
        return board == null ? "" : board.trim().toLowerCase();
    }

    private int resolveCapability(Object reportedValue, boolean inferredValue) {
        if (reportedValue instanceof Boolean value) {
            return value ? 1 : 0;
        }
        return inferredValue ? 1 : 0;
    }

    /**
     * 获取设备在线数据
     */
    @Override
    public String getDeviceOnlineData(String agentId) {
        // 从系统参数中获取MQTT网关地址
        String mqttGatewayUrl = sysParamsService.getValue("server.mqtt_manager_api", true);
        if (StringUtils.isBlank(mqttGatewayUrl) || "null".equals(mqttGatewayUrl)) {
            return "";
        }
        // 构建完整的URL
        String url = StrUtil.format("http://{}/api/devices/status", mqttGatewayUrl);

        // 获取当前用户的设备列表
        UserDetail user = SecurityUser.getUser();
        List<DeviceEntity> devices = getUserDevices(user.getId(), agentId);

        // 构建deviceIds数组
        Set<String> deviceIds = devices.stream().map(o -> {
            String macAddress = Optional.ofNullable(o.getMacAddress()).orElse("unknown").replace(":", "_");
            String groupId = Optional.ofNullable(o.getBoard()).orElse("GID_default").replace(":", "_");
            return StrUtil.format("{}@@@{}@@@{}", groupId, macAddress, macAddress);
        }).collect(Collectors.toSet());

        // 构建请求入参
        Map<String, Set<String>> params = MapUtil
                .builder(new HashMap<String, Set<String>>())
                .put("clientIds", deviceIds).build();

        if (ToolUtil.isNotEmpty(deviceIds)) {
            return postToMqttGateway(url, params);
        }
        // 返回响应
        return "";
    }

    @Override
    public DeviceReportRespDTO checkDeviceActive(String macAddress, String clientId, DeviceReportReqDTO deviceReport) {
        DeviceReportRespDTO response = new DeviceReportRespDTO();
        response.setServer_time(buildServerTime());

        DeviceEntity deviceById = getDeviceByMacAddress(macAddress);

        // 设备未绑定，则返回当前上传的固件信息（不更新）以此兼容旧固件版本
        if (deviceById == null) {
            DeviceReportRespDTO.Firmware firmware = new DeviceReportRespDTO.Firmware();
            firmware.setVersion(deviceReport.getApplication().getVersion());
            firmware.setUrl(Constant.INVALID_FIRMWARE_URL);
            response.setFirmware(firmware);
        } else {
            // 只有在设备已绑定且明确开启自动升级时才返回固件升级信息
            if (Integer.valueOf(1).equals(deviceById.getAutoUpdate())) {
                String type = deviceReport.getBoard() == null ? null : deviceReport.getBoard().getType();
                DeviceReportRespDTO.Firmware firmware = buildFirmwareInfo(type,
                        deviceReport.getApplication() == null ? null : deviceReport.getApplication().getVersion());
                response.setFirmware(firmware);
            }
        }

        // 添加WebSocket配置
        DeviceReportRespDTO.Websocket websocket = new DeviceReportRespDTO.Websocket();
        // 从系统参数获取WebSocket URL，如果未配置则使用默认值
        String wsUrl = sysParamsService.getValue(Constant.SERVER_WEBSOCKET, true);

        // 检查是否启用认证并生成token
        String authEnabled = sysParamsService.getValue(Constant.SERVER_AUTH_ENABLED, true);
        if ("true".equalsIgnoreCase(authEnabled)) {
            try {
                // 生成token
                String token = generateWebSocketToken(clientId, macAddress);
                websocket.setToken(token);
            } catch (Exception e) {
                log.error("生成WebSocket token失败: {}", e.getMessage());
                websocket.setToken("");
            }
        } else {
            websocket.setToken("");
        }

        if (StringUtils.isBlank(wsUrl) || wsUrl.equals("null")) {
            log.error("WebSocket地址未配置，请登录智控台，在参数管理找到【server.websocket】配置");
            wsUrl = "ws://xiaozhi.server.com:8000/xiaozhi/v1/";
            websocket.setUrl(wsUrl);
        } else {
            String[] wsUrls = wsUrl.split("\\;");
            if (wsUrls.length > 0) {
                // 随机选择一个WebSocket URL
                websocket.setUrl(wsUrls[RandomUtil.randomInt(0, wsUrls.length)]);
            } else {
                log.error("WebSocket地址未配置，请登录智控台，在参数管理找到【server.websocket】配置");
                websocket.setUrl("ws://xiaozhi.server.com:8000/xiaozhi/v1/");
            }
        }

        response.setWebsocket(websocket);

        // 添加MQTT UDP配置
        // 从系统参数获取MQTT Gateway地址，仅在配置有效时使用
        String mqttUdpConfig = sysParamsService.getValue(Constant.SERVER_MQTT_GATEWAY, true);
        if (mqttUdpConfig != null && !mqttUdpConfig.equals("null") && !mqttUdpConfig.isEmpty()) {
            try {
                String groupId = deviceById != null && deviceById.getBoard() != null ? deviceById.getBoard()
                        : "GID_default";
                DeviceReportRespDTO.MQTT mqtt = buildMqttConfig(macAddress, groupId);
                if (mqtt != null) {
                    mqtt.setEndpoint(mqttUdpConfig);
                    response.setMqtt(mqtt);
                }
            } catch (Exception e) {
                log.error("生成MQTT配置失败: {}", e.getMessage());
            }
        }

        if (deviceById != null) {
            if (deviceReport.getWakeWord() != null) {
                DeviceWakeWordService wakeWordService = SpringContextUtils.getBean(DeviceWakeWordService.class);
                long assetsPartitionSize = deviceReport.getPartitionTable() == null ? 0L
                        : deviceReport.getPartitionTable().stream()
                                .filter(partition -> "assets".equals(partition.getLabel()))
                                .map(DeviceReportReqDTO.Partition::getSize)
                                .filter(Objects::nonNull)
                                .mapToLong(Integer::longValue)
                                .findFirst().orElse(0L);
                wakeWordService.report(deviceById.getId(), deviceReport.getChipModelName(),
                        assetsPartitionSize, deviceReport.getWakeWord());
            }
            // 如果设备存在，则异步更新上次连接时间和版本信息
            String appVersion = deviceReport.getApplication() != null ? deviceReport.getApplication().getVersion()
                    : null;
            DeviceReportReqDTO.BoardInfo board = deviceReport.getBoard();
            // 通过Spring代理调用异步方法
            ((DeviceServiceImpl) AopContext.currentProxy()).updateDeviceConnectionInfo(deviceById.getAgentId(),
                    deviceById.getId(), appVersion,
                    board == null ? null : board.getHasDisplay(),
                    board == null ? null : board.getHasCamera());
        } else {
            // 如果设备不存在，则生成激活码
            DeviceReportRespDTO.Activation code = buildActivation(macAddress, deviceReport);
            response.setActivation(code);
        }

        return response;
    }

    @Override
    public List<DeviceEntity> getUserDevices(Long userId, String agentId) {
        QueryWrapper<DeviceEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId);
        wrapper.eq("agent_id", agentId);
        return baseDao.selectList(wrapper);
    }

    @Override
    public List<DeviceEntity> getUserDevices(Long userId) {
        return baseDao.selectList(new QueryWrapper<DeviceEntity>().eq("user_id", userId));
    }

    @Override
    public List<UserShowDeviceListVO> getUserDeviceList(Long userId, String agentId) {
        List<DeviceEntity> devices = getUserDevices(userId, agentId);
        return devices.stream().map(this::toUserShowDeviceListVO).toList();
    }

    private UserShowDeviceListVO toUserShowDeviceListVO(DeviceEntity device) {
        UserShowDeviceListVO vo = ConvertUtils.sourceToTarget(device, UserShowDeviceListVO.class);
        vo.setDeviceType(device.getBoard());
        vo.setBoard(device.getBoard());
        vo.setAutoUpdate(device.getAutoUpdate());
        vo.setCreateDateTimestamp(toTimestamp(device.getCreateDate()));
        vo.setLastConnectedAtTimestamp(toTimestamp(device.getLastConnectedAt()));
        return vo;
    }

    private Long toTimestamp(Date date) {
        return date == null ? null : date.getTime();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unbindDevice(Long userId, String deviceId) {
        DeviceEntity device = deviceDao.selectOwnedByIdForUpdate(deviceId, userId);
        if (device == null) {
            throw new RenException(ErrorCode.DEVICE_NOT_EXIST);
        }
        UpdateWrapper<DeviceEntity> wrapper = new UpdateWrapper<>();
        wrapper.eq("user_id", userId);
        wrapper.eq("id", deviceId);
        if (deviceDao.delete(wrapper) != 1) {
            throw new RenException(ErrorCode.DEVICE_NOT_EXIST);
        }

        deviceAddressBookService.deleteByMacAddresses(Collections.singletonList(device.getMacAddress()));
        if (StringUtils.isNotBlank(device.getAgentId())) {
            afterCommit(() -> redisUtils.delete(RedisKeys.getAgentDeviceCountById(device.getAgentId())));
        }
    }

    @Override
    public void deleteByUserId(Long userId) {
        UpdateWrapper<DeviceEntity> wrapper = new UpdateWrapper<>();
        wrapper.eq("user_id", userId);
        baseDao.delete(wrapper);
    }

    @Override
    public Long selectCountByUserId(Long userId) {
        UpdateWrapper<DeviceEntity> wrapper = new UpdateWrapper<>();
        wrapper.eq("user_id", userId);
        return baseDao.selectCount(wrapper);
    }

    @Override
    public Map<Long, Long> countByUserIds(java.util.Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<Long> distinctUserIds = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctUserIds.isEmpty()) {
            return Map.of();
        }
        return deviceDao.countByUserIds(distinctUserIds).stream()
                .collect(Collectors.toMap(value -> value.getUserId(), value -> value.getDeviceCount()));
    }

    @Override
    public void deleteByAgentId(String agentId) {
        // 先查询该智能体下的所有设备，获取mac地址用于删除通讯录记录
        QueryWrapper<DeviceEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("agent_id", agentId);
        List<DeviceEntity> devices = baseDao.selectList(queryWrapper);

        // 删除设备
        UpdateWrapper<DeviceEntity> wrapper = new UpdateWrapper<>();
        wrapper.eq("agent_id", agentId);
        baseDao.delete(wrapper);

        // 批量删除这些设备相关的所有通讯录权限记录
        if (!devices.isEmpty()) {
            List<String> macAddresses = devices.stream()
                    .map(DeviceEntity::getMacAddress)
                    .collect(Collectors.toList());
            deviceAddressBookService.deleteByMacAddresses(macAddresses);
        }
    }

    @Override
    public PageData<UserShowDeviceListVO> page(DevicePageUserDTO dto) {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put(Constant.PAGE, dto.getPage());
        params.put(Constant.LIMIT, dto.getLimit());
        IPage<DeviceEntity> page = baseDao.selectPage(
                getPage(params, "mac_address", true),
                // 定义查询条件
                new QueryWrapper<DeviceEntity>()
                        // 必须设备关键词查找
                        .like(StringUtils.isNotBlank(dto.getKeywords()), "alias", dto.getKeywords()));
        // 循环处理page获取回来的数据，返回需要的字段
        List<UserShowDeviceListVO> list = page.getRecords().stream().map(device -> {
            UserShowDeviceListVO vo = toUserShowDeviceListVO(device);
            // 把最后修改的时间，改为简短描述的时间
            vo.setRecentChatTime(DateUtils.getShortTime(device.getUpdateDate()));
            sysUserUtilService.assignUsername(device.getUserId(),
                    vo::setBindUserName);
            return vo;
        }).toList();
        // 计算页数
        return new PageData<>(list, page.getTotal());
    }

    @Override
    public DeviceEntity getDeviceByMacAddress(String macAddress) {
        if (StringUtils.isBlank(macAddress)) {
            return null;
        }
        QueryWrapper<DeviceEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("mac_address", macAddress);
        return baseDao.selectOne(wrapper);
    }

    private DeviceReportRespDTO.ServerTime buildServerTime() {
        DeviceReportRespDTO.ServerTime serverTime = new DeviceReportRespDTO.ServerTime();
        TimeZone tz = TimeZone.getDefault();
        serverTime.setTimestamp(Instant.now().toEpochMilli());
        serverTime.setTimeZone(tz.getID());
        serverTime.setTimezone_offset(tz.getOffset(System.currentTimeMillis()) / (60 * 1000));
        return serverTime;
    }

    @Override
    public String geCodeByDeviceId(String deviceId) {
        String dataKey = getDeviceCacheKey(deviceId);

        Map<String, Object> cacheMap = JsonUtils.toStringObjectMap(redisUtils.get(dataKey));
        if (cacheMap != null && cacheMap.containsKey("activation_code")) {
            String cachedCode = (String) cacheMap.get("activation_code");
            return cachedCode;
        }
        return null;
    }

    @Override
    public Date getLatestLastConnectionTime(String agentId) {
        // 查询是否有缓存时间，有则返回
        Date cachedDate = (Date) redisUtils.get(RedisKeys.getAgentDeviceLastConnectedAtById(agentId));
        if (cachedDate != null) {
            return cachedDate;
        }
        Date maxDate = deviceDao.getAllLastConnectedAtByAgentId(agentId);
        if (maxDate != null) {
            redisUtils.set(RedisKeys.getAgentDeviceLastConnectedAtById(agentId), maxDate);
        }
        return maxDate;
    }

    private String getDeviceCacheKey(String deviceId) {
        String safeDeviceId = deviceId.replace(":", "_").toLowerCase();
        return RedisKeys.getOtaDeviceActivationInfo(safeDeviceId);
    }

    public DeviceReportRespDTO.Activation buildActivation(String deviceId, DeviceReportReqDTO deviceReport) {
        DeviceReportRespDTO.Activation code = new DeviceReportRespDTO.Activation();

        String cachedCode = geCodeByDeviceId(deviceId);

        if (StringUtils.isNotBlank(cachedCode)) {
            code.setCode(cachedCode);
            String frontedUrl = sysParamsService.getValue(Constant.SERVER_FRONTED_URL, true);
            code.setMessage(frontedUrl + "\n" + cachedCode);
            code.setChallenge(deviceId);
        } else {
            String newCode = RandomUtil.randomNumbers(6);
            code.setCode(newCode);
            String frontedUrl = sysParamsService.getValue(Constant.SERVER_FRONTED_URL, true);
            code.setMessage(frontedUrl + "\n" + newCode);
            code.setChallenge(deviceId);

            Map<String, Object> dataMap = new HashMap<>();
            dataMap.put("id", deviceId);
            dataMap.put("mac_address", deviceId);

            dataMap.put("board", (deviceReport.getBoard() != null && deviceReport.getBoard().getType() != null)
                    ? deviceReport.getBoard().getType()
                    : (deviceReport.getChipModelName() != null ? deviceReport.getChipModelName() : "unknown"));
            dataMap.put("app_version", (deviceReport.getApplication() != null)
                    ? deviceReport.getApplication().getVersion()
                    : null);
            if (deviceReport.getBoard() != null && deviceReport.getBoard().getHasDisplay() != null) {
                dataMap.put("has_display", deviceReport.getBoard().getHasDisplay());
            }
            if (deviceReport.getBoard() != null && deviceReport.getBoard().getHasCamera() != null) {
                dataMap.put("has_camera", deviceReport.getBoard().getHasCamera());
            }
            dataMap.put("deviceId", deviceId);
            dataMap.put("activation_code", newCode);

            // 写入主数据 key
            String dataKey = getDeviceCacheKey(deviceId);
            redisUtils.set(dataKey, dataMap);

            // 写入反查激活码 key
            String codeKey = RedisKeys.getOtaActivationCode(newCode);
            redisUtils.set(codeKey, deviceId);
        }
        return code;
    }

    private DeviceReportRespDTO.Firmware buildFirmwareInfo(String type, String currentVersion) {
        if (StringUtils.isBlank(type)) {
            return null;
        }
        if (StringUtils.isBlank(currentVersion)) {
            currentVersion = "0.0.0";
        }

        OtaEntity ota = otaService.getLatestOta(type);
        DeviceReportRespDTO.Firmware firmware = new DeviceReportRespDTO.Firmware();
        String downloadUrl = null;

        if (ota != null) {
            // 如果设备没有版本信息，或者OTA版本比设备版本新，则返回下载地址
            if (compareVersions(ota.getVersion(), currentVersion) > 0) {
                String otaUrl = sysParamsService.getValue(Constant.SERVER_OTA, true);
                if (StringUtils.isBlank(otaUrl) || otaUrl.equals("null")) {
                    log.error("OTA地址未配置，请登录智控台，在参数管理找到【server.ota】配置");
                    // 尝试从请求中获取
                    HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder
                            .getRequestAttributes())
                            .getRequest();
                    otaUrl = request.getRequestURL().toString();
                }
                // 将URL中的/ota/替换为/otaMag/download/
                String uuid = UUID.randomUUID().toString();
                redisUtils.set(RedisKeys.getOtaIdKey(uuid), ota.getId());
                downloadUrl = otaUrl.replace("/ota/", "/otaMag/download/") + uuid;
            }
        }

        firmware.setVersion(ota == null ? currentVersion : ota.getVersion());
        firmware.setUrl(downloadUrl == null ? Constant.INVALID_FIRMWARE_URL : downloadUrl);
        return firmware;
    }

    /**
     * 比较两个版本号
     * 
     * @param version1 版本1
     * @param version2 版本2
     * @return 如果version1 > version2返回1，version1 < version2返回-1，相等返回0
     */
    private static int compareVersions(String version1, String version2) {
        if (version1 == null || version2 == null) {
            return 0;
        }

        String[] v1Parts = version1.split("\\.");
        String[] v2Parts = version2.split("\\.");

        int length = Math.max(v1Parts.length, v2Parts.length);
        for (int i = 0; i < length; i++) {
            int v1 = i < v1Parts.length ? Integer.parseInt(v1Parts[i]) : 0;
            int v2 = i < v2Parts.length ? Integer.parseInt(v2Parts[i]) : 0;

            if (v1 > v2) {
                return 1;
            } else if (v1 < v2) {
                return -1;
            }
        }
        return 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void manualAddDevice(Long userId, DeviceManualAddDTO dto) {
        lockUserForEntitlement(userId);
        lockAgentForDeviceReference(dto.getAgentId(), userId, SecurityUser.getUser());
        // 检查mac是否已存在
        QueryWrapper<DeviceEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("mac_address", dto.getMacAddress());
        DeviceEntity exist = baseDao.selectOne(wrapper);
        if (exist != null) {
            throw new RenException(ErrorCode.MAC_ADDRESS_ALREADY_EXISTS);
        }
        companionSubscriptionService.requireDeviceSlot(userId, countUserDevices(userId));
        Date now = new Date();
        DeviceEntity entity = new DeviceEntity();
        entity.setId(dto.getMacAddress());
        entity.setUserId(userId);
        entity.setAgentId(dto.getAgentId());
        entity.setBoard(dto.getBoard());
        entity.setAppVersion(dto.getAppVersion());
        entity.setMacAddress(dto.getMacAddress());
        entity.setCreateDate(now);
        entity.setUpdateDate(now);
        entity.setLastConnectedAt(now);
        entity.setCreator(userId);
        entity.setUpdater(userId);
        entity.setAutoUpdate(1);
        baseDao.insert(entity);

        // 添加：清除智能体设备数量缓存
        redisUtils.delete(RedisKeys.getAgentDeviceCountById(dto.getAgentId()));
    }

    private AgentEntity lockAgentForDeviceReference(String agentId, Long userId, UserDetail currentUser) {
        AgentEntity agent = agentDao.selectByIdForUpdate(agentId);
        if (agent == null) {
            throw new RenException(ErrorCode.AGENT_NOT_FOUND);
        }
        boolean sameUser = currentUser != null
                && userId != null
                && userId.equals(currentUser.getId())
                && userId.equals(agent.getUserId());
        if (!sameUser) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
        return agent;
    }

    private AgentEntity lockCompanionAgentForDeviceReference(String agentId, Long userId, UserDetail currentUser) {
        AgentEntity agent = lockAgentForDeviceReference(agentId, userId, currentUser);
        if (!Integer.valueOf(1).equals(agent.getCompanionEnabled())) {
            throw new RenException(ErrorCode.AGENT_NOT_FOUND);
        }
        return agent;
    }

    private String normalizeMac(String macAddress) {
        String value = macAddress == null ? "" : macAddress;
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == ' ') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == ' ') {
            end--;
        }
        return value.substring(start, end).toLowerCase(Locale.ROOT).replace(":", "").replace("-", "");
    }

    private long countUserDevices(Long userId) {
        return baseDao.selectCount(new QueryWrapper<DeviceEntity>().eq("user_id", userId));
    }

    private void lockUserForEntitlement(Long userId) {
        if (sysUserDao.selectByIdForUpdate(userId) == null) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
    }

    @Override
    public List<DeviceEntity> searchDevicesByMacAddress(String macAddress, Long userId) {
        QueryWrapper<DeviceEntity> wrapper = new QueryWrapper<>();
        wrapper.like("mac_address", macAddress);
        wrapper.eq("user_id", userId);
        return deviceDao.selectList(wrapper);
    }

    /**
     * 生成MQTT密码签名
     * 
     * @param content   签名内容 (clientId + '|' + username)
     * @param secretKey 密钥
     * @return Base64编码的HMAC-SHA256签名
     */
    private String generatePasswordSignature(String content, String secretKey) throws Exception {
        Mac hmac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        hmac.init(keySpec);
        byte[] signature = hmac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signature);
    }

    /**
     * 生成WebSocket认证token 遵循Python端AuthManager的实现逻辑：token = signature.timestamp
     * 
     * @param clientId 客户端ID
     * @param username 用户名 (通常为deviceId/macAddress)
     * @return 认证token字符串
     */
    public String generateWebSocketToken(String clientId, String username)
            throws NoSuchAlgorithmException, InvalidKeyException {
        // 从系统参数获取密钥
        String secretKey = sysParamsService.getValue(Constant.SERVER_SECRET, false);
        if (StringUtils.isBlank(secretKey)) {
            throw new IllegalStateException("WebSocket认证密钥未配置(server.secret)");
        }

        // 获取当前时间戳(秒)
        long timestamp = System.currentTimeMillis() / 1000;

        // 构建签名内容: clientId|username|timestamp
        String content = String.format("%s|%s|%d", clientId, username, timestamp);

        // 生成HMAC-SHA256签名
        Mac hmac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        hmac.init(keySpec);
        byte[] signature = hmac.doFinal(content.getBytes(StandardCharsets.UTF_8));

        // Base64 URL-safe编码签名(去除填充符=)
        String signatureBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(signature);

        // 返回格式: signature.timestamp
        return String.format("%s.%d", signatureBase64, timestamp);
    }

    @Override
    public boolean verifyDeviceToken(String token, String clientId, String username) {
        String authEnabled = sysParamsService.getValue(Constant.SERVER_AUTH_ENABLED, true);
        if (!"true".equalsIgnoreCase(authEnabled)) return false;
        if (StringUtils.isAnyBlank(token, clientId, username)) return false;
        try {
            int separator = token.lastIndexOf('.');
            if (separator <= 0 || separator == token.length() - 1) return false;
            String signature = token.substring(0, separator);
            long timestamp = Long.parseLong(token.substring(separator + 1));
            long age = Instant.now().getEpochSecond() - timestamp;
            if (age < -300 || age > 60L * 60 * 24 * 30) return false;
            String secretKey = sysParamsService.getValue(Constant.SERVER_SECRET, false);
            if (StringUtils.isBlank(secretKey)) return false;
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = hmac.doFinal(String.format("%s|%s|%d", clientId, username, timestamp)
                    .getBytes(StandardCharsets.UTF_8));
            byte[] actual = Base64.getUrlDecoder().decode(signature);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException | NoSuchAlgorithmException | InvalidKeyException exception) {
            return false;
        }
    }

    /**
     * 构建MQTT配置信息
     * 
     * @param macAddress MAC地址
     * @param groupId    分组ID
     * @return MQTT配置对象
     */
    private DeviceReportRespDTO.MQTT buildMqttConfig(String macAddress, String groupId)
            throws Exception {
        // 从环境变量或系统参数获取签名密钥
        String signatureKey = sysParamsService.getValue("server.mqtt_signature_key", true);
        if (StringUtils.isBlank(signatureKey)) {
            log.warn("缺少MQTT_SIGNATURE_KEY，跳过MQTT配置生成");
            return null;
        }

        // 构建客户端ID格式：groupId@@@macAddress@@@uuid
        String groupIdSafeStr = groupId.replace(":", "_");
        String deviceIdSafeStr = macAddress.replace(":", "_");
        String mqttClientId = String.format("%s@@@%s@@@%s", groupIdSafeStr, deviceIdSafeStr, deviceIdSafeStr);

        // 构建用户数据（包含IP等信息）
        Map<String, String> userData = new HashMap<>();
        // 尝试获取客户端IP
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder
                    .getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                String clientIp = request.getRemoteAddr();
                userData.put("ip", clientIp);
            }
        } catch (Exception e) {
            userData.put("ip", "unknown");
        }

        // 将用户数据编码为Base64 JSON
        String userDataJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(userData);
        String username = Base64.getEncoder().encodeToString(userDataJson.getBytes(StandardCharsets.UTF_8));

        // 生成密码签名
        String password = generatePasswordSignature(mqttClientId + "|" + username, signatureKey);

        // 构建MQTT配置
        DeviceReportRespDTO.MQTT mqtt = new DeviceReportRespDTO.MQTT();
        mqtt.setClient_id(mqttClientId);
        mqtt.setUsername(username);
        mqtt.setPassword(password);
        mqtt.setPublish_topic("device-server");
        mqtt.setSubscribe_topic("devices/p2p/" + deviceIdSafeStr);

        return mqtt;
    }

    private String postToMqttGateway(String url, Object requestBody) {
        String signatureKey = sysParamsService.getValue(Constant.SERVER_MQTT_SECRET, false);
        return MqttGatewayAuthorization.postJson(
                url,
                JSONUtil.toJsonStr(requestBody),
                signatureKey,
                Instant.now());
    }

    @Override
    public Object getDeviceTools(String deviceId) {
        DeviceEntity device = baseDao.selectById(deviceId);
        if (device == null) {
            return null;
        }

        // 检查设备是否属于当前用户
        UserDetail user = SecurityUser.getUser();
        if (!device.getUserId().equals(user.getId())) {
            return null;
        }

        String mqttGatewayUrl = sysParamsService.getValue("server.mqtt_manager_api", true);
        if (isConfigured(mqttGatewayUrl)) {
            try {
                Object inventory = getDeviceToolsFromMqtt(device, mqttGatewayUrl);
                if (inventory != null) {
                    return inventory;
                }
            } catch (RuntimeException exception) {
                log.warn("MQTT设备工具清单不可用，改用WebSocket通道，设备ID: {}, 原因: {}",
                        deviceId, exception.getMessage());
            }
        }
        return getDeviceToolsFromWebSocket(device);
    }

    private Object getDeviceToolsFromMqtt(DeviceEntity device, String mqttGatewayUrl) {

        // 构建clientId
        String macAddress = Optional.ofNullable(device.getMacAddress()).orElse("unknown").replace(":", "_");
        String groupId = Optional.ofNullable(device.getBoard()).orElse("GID_default").replace(":", "_");
        String clientId = StrUtil.format("{}@@@{}@@@{}", groupId, macAddress, macAddress);

        // 构建完整的URL
        String url = StrUtil.format("http://{}/api/commands/{}", mqttGatewayUrl, clientId);

        // 存储所有工具列表
        List<Object> allTools = new ArrayList<>();
        boolean inventoryComplete = false;
        String cursor = null;

        // 循环获取分页数据
        while (true) {
            // 构建params
            Map<String, Object> paramsMap = MapUtil.builder(new HashMap<String, Object>())
                    .put("withUserTools", true)
                    .build();
            // 如果有cursor，添加到请求参数中
            if (StringUtils.isNotBlank(cursor)) {
                paramsMap.put("cursor", cursor);
            }

            // 构建请求体
            Map<String, Object> payload = MapUtil
                    .builder(new HashMap<String, Object>())
                    .put("jsonrpc", "2.0")
                    .put("id", 2)
                    .put("method", "tools/list")
                    .put("params", paramsMap)
                    .build();

            Map<String, Object> requestBody = MapUtil
                    .builder(new HashMap<String, Object>())
                    .put("type", "mcp")
                    .put("payload", payload)
                    .build();

            String resultMessage = postToMqttGateway(url, requestBody);

            // 解析响应
            if (StringUtils.isBlank(resultMessage)) {
                break;
            }

            JSONObject jsonObject;
            try {
                jsonObject = JSONUtil.parseObj(resultMessage);
            } catch (RuntimeException exception) {
                break;
            }
            if (!jsonObject.getBool("success", false)) {
                break;
            }

            JSONObject data = jsonObject.getJSONObject("data");
            if (data == null) {
                break;
            }
            // 获取当前页的工具列表
            JSONArray tools = data.getJSONArray("tools");
            if (tools == null) {
                break;
            }
            if (!tools.isEmpty()) {
                allTools.addAll(tools);
            }

            // 获取下一页的cursor
            String nextCursor = data.getStr("nextCursor");
            if (StringUtils.isBlank(nextCursor)) {
                // 没有下一页了
                inventoryComplete = true;
                break;
            }
            cursor = nextCursor;
        }

        // 构建返回结果
        if (!inventoryComplete) {
            return null;
        }

        Map<String, Object> resultData = new HashMap<>();
        resultData.put("tools", allTools);
        return resultData;
    }

    @Override
    public Object callDeviceTool(String deviceId, String toolName, Map<String, Object> arguments) {
        DeviceEntity device = baseDao.selectById(deviceId);
        if (device == null) {
            return null;
        }

        // 检查设备是否属于当前用户
        UserDetail user = SecurityUser.getUser();
        if (!device.getUserId().equals(user.getId())) {
            return null;
        }

        String mqttGatewayUrl = sysParamsService.getValue("server.mqtt_manager_api", true);
        if (isConfigured(mqttGatewayUrl)) {
            try {
                Object result = callDeviceToolFromMqtt(device, mqttGatewayUrl, toolName, arguments);
                if (result != null) {
                    return result;
                }
            } catch (RuntimeException exception) {
                log.warn("MQTT设备控制不可用，改用WebSocket通道，设备ID: {}, 原因: {}",
                        deviceId, exception.getMessage());
            }
        }
        return callDeviceToolFromWebSocket(device, toolName, arguments);
    }

    @Override
    public Object callDeviceToolInternal(String deviceId, String toolName, Map<String, Object> arguments) {
        DeviceEntity device = baseDao.selectById(deviceId);
        if (device == null) {
            return null;
        }
        String mqttGatewayUrl = sysParamsService.getValue("server.mqtt_manager_api", true);
        if (isConfigured(mqttGatewayUrl)) {
            try {
                Object result = callDeviceToolFromMqtt(device, mqttGatewayUrl, toolName, arguments);
                if (result != null) {
                    return result;
                }
            } catch (RuntimeException exception) {
                log.warn("内部MQTT设备控制不可用，改用WebSocket通道，设备ID: {}, 原因: {}",
                        deviceId, exception.getMessage());
            }
        }
        return callDeviceToolFromWebSocket(device, toolName, arguments);
    }

    @Override
    public boolean isOnline(String deviceId) {
        DeviceEntity device = baseDao.selectById(deviceId);
        return device != null && DeviceOnlineStatus.isOnline(device.getLastConnectedAt());
    }

    private Object callDeviceToolFromMqtt(DeviceEntity device, String mqttGatewayUrl,
            String toolName, Map<String, Object> arguments) {

        // 构建clientId
        String macAddress = Optional.ofNullable(device.getMacAddress()).orElse("unknown").replace(":", "_");
        String groupId = Optional.ofNullable(device.getBoard()).orElse("GID_default").replace(":", "_");
        String clientId = StrUtil.format("{}@@@{}@@@{}", groupId, macAddress, macAddress);

        // 构建完整的URL
        String url = StrUtil.format("http://{}/api/commands/{}", mqttGatewayUrl, clientId);

        // 构建请求体
        Map<String, Object> params = MapUtil
                .builder(new HashMap<String, Object>())
                .put("name", toolName)
                .put("arguments", arguments)
                .build();

        Map<String, Object> payload = MapUtil
                .builder(new HashMap<String, Object>())
                .put("jsonrpc", "2.0")
                .put("id", 2)
                .put("method", "tools/call")
                .put("params", params)
                .build();

        Map<String, Object> requestBody = MapUtil
                .builder(new HashMap<String, Object>())
                .put("type", "mcp")
                .put("payload", payload)
                .build();

        String resultMessage = postToMqttGateway(url, requestBody);

        // 解析响应
        if (StringUtils.isNotBlank(resultMessage)) {
            cn.hutool.json.JSONObject jsonObject = JSONUtil.parseObj(resultMessage);
            if (jsonObject.getBool("success", false)) {
                cn.hutool.json.JSONObject data = jsonObject.getJSONObject("data");
                if (data != null) {
                    if (data.getBool("isError", false)) {
                        return false;
                    }
                    cn.hutool.json.JSONArray content = data.getJSONArray("content");
                    if (content != null && content.size() > 0) {
                        cn.hutool.json.JSONObject firstContent = content.getJSONObject(0);
                        if (firstContent != null && "text".equals(firstContent.getStr("type"))) {
                            String text = firstContent.getStr("text");
                            if (StringUtils.isNotBlank(text)) {
                                String trimmedText = text.trim();
                                if (trimmedText.startsWith("{") || trimmedText.startsWith("[")) {
                                    try {
                                        return JSONUtil.parseObj(trimmedText);
                                    } catch (Exception e) {
                                        return trimmedText;
                                    }
                                } else if ("true".equals(trimmedText)) {
                                    return true;
                                } else if ("false".equals(trimmedText)) {
                                    return false;
                                } else {
                                    return trimmedText;
                                }
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private Object getDeviceToolsFromWebSocket(DeviceEntity device) {
        JSONObject response = postToWebSocketControl(device, "tools/list", Map.of());
        if (response == null || !response.getBool("success", false)) {
            return null;
        }
        return normalizeJsonValue(response.get("data"));
    }

    private Object callDeviceToolFromWebSocket(DeviceEntity device, String toolName,
            Map<String, Object> arguments) {
        JSONObject response = postToWebSocketControl(
                device,
                "tools/call",
                Map.of("name", toolName, "arguments", arguments));
        if (response == null) {
            return null;
        }
        if (!response.getBool("success", false)) {
            return Map.of("success", false);
        }
        return normalizeJsonValue(response.get("data"));
    }

    private JSONObject postToWebSocketControl(DeviceEntity device, String method, Map<String, Object> params) {
        String serverHttp = sysParamsService.getValue(Constant.SERVER_HTTP, true);
        String secret = sysParamsService.getValue(Constant.SERVER_SECRET, false);
        if (!isConfigured(serverHttp) || !isConfigured(secret) || StringUtils.isBlank(device.getMacAddress())) {
            return null;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(secret);
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> requestBody = Map.of(
                    "mac_address", device.getMacAddress(),
                    "method", method,
                    "params", params);
            RestTemplate restTemplate = SpringContextUtils.getBean(RestTemplate.class);
            String controlBaseUrl = serverHttp.endsWith("/")
                    ? serverHttp.substring(0, serverHttp.length() - 1)
                    : serverHttp;
            ResponseEntity<String> response = restTemplate.exchange(
                    controlBaseUrl + "/internal/device-control",
                    HttpMethod.POST,
                    new HttpEntity<>(requestBody, headers),
                    String.class);
            if (response == null || !response.getStatusCode().is2xxSuccessful()
                    || StringUtils.isBlank(response.getBody())) {
                return null;
            }
            return JSONUtil.parseObj(response.getBody());
        } catch (RuntimeException exception) {
            log.warn("WebSocket设备控制请求失败，设备ID: {}, 原因: {}", device.getId(), exception.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Object normalizeJsonValue(Object value) {
        if (value instanceof JSONObject json) {
            return json.toBean(Map.class);
        }
        if (value instanceof JSONArray json) {
            return json.toList(Object.class);
        }
        return value;
    }

    private boolean isConfigured(String value) {
        return StringUtils.isNotBlank(value) && !"null".equalsIgnoreCase(value.trim());
    }
}
