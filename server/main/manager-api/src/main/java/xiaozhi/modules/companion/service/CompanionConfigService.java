package xiaozhi.modules.companion.service;

import java.util.Map;

import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.device.entity.DeviceEntity;

public interface CompanionConfigService {
    Map<String, Object> build(DeviceEntity device, AgentEntity agent);
}
