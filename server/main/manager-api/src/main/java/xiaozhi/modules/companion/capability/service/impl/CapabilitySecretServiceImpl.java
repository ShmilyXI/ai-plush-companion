package xiaozhi.modules.companion.capability.service.impl;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.hutool.core.util.IdUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.companion.capability.dao.CapabilityDao;
import xiaozhi.modules.companion.capability.dao.CapabilitySecretDao;
import xiaozhi.modules.companion.capability.dao.DeviceSkillMappingDao;
import xiaozhi.modules.companion.capability.entity.CapabilityEntity;
import xiaozhi.modules.companion.capability.entity.CapabilitySecretEntity;
import xiaozhi.modules.companion.capability.service.CapabilitySecretService;
import xiaozhi.modules.companion.model.service.CompanionModelSecretService;
import xiaozhi.modules.companion.service.CompanionAuditService;

@Service
@RequiredArgsConstructor
public class CapabilitySecretServiceImpl implements CapabilitySecretService {
    private final CapabilityDao capabilityDao;
    private final CapabilitySecretDao secretDao;
    private final CompanionModelSecretService cipher;
    private final CompanionAuditService audit;
    private DeviceSkillMappingDao deviceSkillMappingDao;

    @Autowired
    public void setDeviceSkillMappingDao(DeviceSkillMappingDao deviceSkillMappingDao) {
        this.deviceSkillMappingDao = deviceSkillMappingDao;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean save(Long operatorId, String capabilityId, String secretName, String value) {
        requireCapability(capabilityId);
        String normalizedName = normalizeName(secretName);
        CapabilitySecretEntity existing = secretDao.selectByCapabilityAndName(capabilityId, normalizedName);
        if (StringUtils.isBlank(value)) return existing != null;

        Date now = new Date();
        boolean create = existing == null;
        CapabilitySecretEntity entity = create ? new CapabilitySecretEntity() : existing;
        if (create) {
            entity.setId(IdUtil.fastSimpleUUID());
            entity.setCapabilityId(capabilityId);
            entity.setSecretName(normalizedName);
            entity.setCreatedAt(now);
        }
        entity.setSecretCiphertext(cipher.encrypt(value));
        entity.setUpdatedAt(now);
        int written = create ? secretDao.insert(entity) : secretDao.updateById(entity);
        if (written != 1) throw new RenException("能力密钥保存失败");
        if (deviceSkillMappingDao != null) {
            deviceSkillMappingDao.bumpEveryEnabledDeviceConfigVersion(now);
        }
        audit.record(operatorId, null, "capability.secret", "capability", capabilityId,
                Map.of("secretName", normalizedName, "configured", true));
        return true;
    }

    @Override
    public Map<String, Boolean> status(String capabilityId) {
        requireCapability(capabilityId);
        Map<String, Boolean> result = new LinkedHashMap<>();
        secretDao.selectByCapabilityId(capabilityId).forEach(secret -> result.put(secret.getSecretName(), true));
        return result;
    }

    private CapabilityEntity requireCapability(String capabilityId) {
        CapabilityEntity capability = capabilityDao.selectById(capabilityId);
        if (capability == null || Integer.valueOf(1).equals(capability.getDeleted())) throw new RenException("能力不存在");
        return capability;
    }

    private String normalizeName(String value) {
        String name = StringUtils.trimToEmpty(value).toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z][a-z0-9_.-]{0,127}")) throw new RenException("密钥名称无效");
        return name;
    }
}
