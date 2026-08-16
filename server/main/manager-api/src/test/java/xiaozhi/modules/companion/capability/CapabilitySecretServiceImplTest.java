package xiaozhi.modules.companion.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import xiaozhi.modules.companion.capability.dao.CapabilityDao;
import xiaozhi.modules.companion.capability.dao.CapabilitySecretDao;
import xiaozhi.modules.companion.capability.dao.DeviceSkillMappingDao;
import xiaozhi.modules.companion.capability.entity.CapabilityEntity;
import xiaozhi.modules.companion.capability.entity.CapabilitySecretEntity;
import xiaozhi.modules.companion.capability.service.impl.CapabilitySecretServiceImpl;
import xiaozhi.modules.companion.model.service.CompanionModelSecretService;
import xiaozhi.modules.companion.service.CompanionAuditService;

class CapabilitySecretServiceImplTest {
    private final CapabilityDao capabilityDao = mock(CapabilityDao.class);
    private final CapabilitySecretDao secretDao = mock(CapabilitySecretDao.class);
    private final CompanionModelSecretService cipher = mock(CompanionModelSecretService.class);
    private final CompanionAuditService audit = mock(CompanionAuditService.class);
    private final DeviceSkillMappingDao mappings = mock(DeviceSkillMappingDao.class);
    private final CapabilitySecretServiceImpl service = new CapabilitySecretServiceImpl(
            capabilityDao, secretDao, cipher, audit);

    @Test
    void encryptsNewValuesAndReturnsOnlyConfiguredMarkers() {
        CapabilityEntity capability = new CapabilityEntity();
        capability.setId("plugin-weather");
        capability.setDeleted(0);
        when(capabilityDao.selectById("plugin-weather")).thenReturn(capability);
        when(cipher.encrypt("secret-api-key")).thenReturn("v1:encrypted-value");
        when(secretDao.insert(any(CapabilitySecretEntity.class))).thenReturn(1);
        service.setDeviceSkillMappingDao(mappings);

        assertEquals(true, service.save(7L, "plugin-weather", "api_key", "secret-api-key"));

        ArgumentCaptor<CapabilitySecretEntity> captured = ArgumentCaptor.forClass(CapabilitySecretEntity.class);
        verify(secretDao).insert(captured.capture());
        assertEquals("v1:encrypted-value", captured.getValue().getSecretCiphertext());
        assertFalse(captured.getValue().getSecretCiphertext().contains("secret-api-key"));
        verify(mappings).bumpEveryEnabledDeviceConfigVersion(any());

        when(secretDao.selectByCapabilityId("plugin-weather")).thenReturn(List.of(captured.getValue()));
        assertEquals(java.util.Map.of("api_key", true), service.status("plugin-weather"));
    }

    @Test
    void blankReplacementPreservesExistingSecret() {
        CapabilityEntity capability = new CapabilityEntity();
        capability.setId("plugin-weather");
        capability.setDeleted(0);
        when(capabilityDao.selectById("plugin-weather")).thenReturn(capability);
        CapabilitySecretEntity existing = new CapabilitySecretEntity();
        existing.setId("secret-1");
        existing.setCapabilityId("plugin-weather");
        existing.setSecretName("api_key");
        existing.setSecretCiphertext("v1:existing");
        when(secretDao.selectByCapabilityAndName("plugin-weather", "api_key")).thenReturn(existing);

        assertEquals(true, service.save(7L, "plugin-weather", "api_key", "   "));

        verify(cipher, never()).encrypt(any());
        verify(secretDao, never()).updateById(any(CapabilitySecretEntity.class));
    }
}
