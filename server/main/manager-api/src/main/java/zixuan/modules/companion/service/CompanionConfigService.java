package zixuan.modules.companion.service;

import java.util.Map;

import zixuan.modules.agent.entity.AgentEntity;
import zixuan.modules.device.entity.DeviceEntity;

public interface CompanionConfigService {
    Map<String, Object> build(DeviceEntity device, AgentEntity agent);
}
