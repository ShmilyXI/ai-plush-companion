package zixuan.modules.companion.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;

import zixuan.common.constant.Constant;
import zixuan.modules.companion.model.service.impl.CompanionModelSecretServiceImpl;
import zixuan.modules.sys.service.SysParamsService;

class CompanionModelSecretServiceImplTest {

    @Test
    void encryptedSecretMapRoundTripsWithoutPlaintext() {
        SysParamsService params = mock(SysParamsService.class);
        when(params.getValue(Constant.SERVER_SECRET, true))
                .thenReturn("0123456789abcdef0123456789abcdef");
        CompanionModelSecretServiceImpl service = new CompanionModelSecretServiceImpl(params);
        Map<String, Object> value = Map.of(
                "api_key", "key-123",
                "api_secret", "secret-456");

        String ciphertext = service.encryptMap(value);

        assertFalse(ciphertext.contains("key-123"));
        assertFalse(ciphertext.contains("secret-456"));
        assertEquals(value, service.decryptMap(ciphertext));
    }
}
