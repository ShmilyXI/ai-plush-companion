package zixuan.modules.companion.capability.service;

import java.util.Map;

public interface CapabilitySecretService {
    boolean save(Long operatorId, String capabilityId, String secretName, String value);

    Map<String, Boolean> status(String capabilityId);
}
