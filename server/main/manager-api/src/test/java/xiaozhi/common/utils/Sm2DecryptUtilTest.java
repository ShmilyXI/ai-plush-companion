package xiaozhi.common.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.security.service.CaptchaService;
import xiaozhi.modules.sys.service.SysParamsService;

class Sm2DecryptUtilTest {

    @Test
    void decryptsTheWholePasswordWithoutCaptcha() {
        Map<String, String> keys = SM2Utils.createKey();
        SysParamsService params = mock(SysParamsService.class);
        when(params.getValue(Constant.SM2_PRIVATE_KEY, true)).thenReturn(keys.get(SM2Utils.KEY_PRIVATE_KEY));
        String encryptedPassword = SM2Utils.encrypt(keys.get(SM2Utils.KEY_PUBLIC_KEY), "password-only");

        assertEquals("password-only", Sm2DecryptUtil.decryptPassword(encryptedPassword, params));
    }

    @Test
    void rejectsBlankPrivateKey() {
        SysParamsService params = mock(SysParamsService.class);
        when(params.getValue(Constant.SM2_PRIVATE_KEY, true)).thenReturn("  ");

        try (MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            RenException error = assertThrows(RenException.class,
                    () -> Sm2DecryptUtil.decryptPassword("encrypted", params));

            assertEquals(ErrorCode.SM2_KEY_NOT_CONFIGURED, error.getCode());
        }
    }

    @Test
    void mapsSm2FailureToDecryptError() {
        SysParamsService params = paramsWithPrivateKey();
        try (MockedStatic<SM2Utils> sm2 = mockStatic(SM2Utils.class);
                MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            sm2.when(() -> SM2Utils.decrypt("private-key", "encrypted"))
                    .thenThrow(new RuntimeException("broken ciphertext"));

            RenException error = assertThrows(RenException.class,
                    () -> Sm2DecryptUtil.decryptPassword("encrypted", params));

            assertEquals(ErrorCode.SM2_DECRYPT_ERROR, error.getCode());
        }
    }

    @Test
    void rejectsEmptyDecryptedPassword() {
        SysParamsService params = paramsWithPrivateKey();
        try (MockedStatic<SM2Utils> sm2 = mockStatic(SM2Utils.class);
                MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            sm2.when(() -> SM2Utils.decrypt("private-key", "encrypted")).thenReturn("");

            RenException error = assertThrows(RenException.class,
                    () -> Sm2DecryptUtil.decryptPassword("encrypted", params));

            assertEquals(ErrorCode.SM2_DECRYPT_ERROR, error.getCode());
        }
    }

    @Test
    void rejectsNullDecryptedPassword() {
        SysParamsService params = paramsWithPrivateKey();
        try (MockedStatic<SM2Utils> sm2 = mockStatic(SM2Utils.class);
                MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            sm2.when(() -> SM2Utils.decrypt("private-key", "encrypted")).thenReturn(null);

            RenException error = assertThrows(RenException.class,
                    () -> Sm2DecryptUtil.decryptPassword("encrypted", params));

            assertEquals(ErrorCode.SM2_DECRYPT_ERROR, error.getCode());
        }
    }

    @Test
    void rejectsBlankDecryptedPassword() {
        SysParamsService params = paramsWithPrivateKey();
        try (MockedStatic<SM2Utils> sm2 = mockStatic(SM2Utils.class);
                MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            sm2.when(() -> SM2Utils.decrypt("private-key", "encrypted")).thenReturn("  ");

            RenException error = assertThrows(RenException.class,
                    () -> Sm2DecryptUtil.decryptPassword("encrypted", params));

            assertEquals(ErrorCode.SM2_DECRYPT_ERROR, error.getCode());
        }
    }

    @Test
    void mapsParameterServiceRenExceptionToDecryptError() {
        SysParamsService params = mock(SysParamsService.class);
        try (MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            RenException expected = new RenException(ErrorCode.INTERNAL_SERVER_ERROR);
            when(params.getValue(Constant.SM2_PRIVATE_KEY, true)).thenThrow(expected);

            RenException error = assertThrows(RenException.class,
                    () -> Sm2DecryptUtil.decryptPassword("encrypted", params));

            assertEquals(ErrorCode.SM2_DECRYPT_ERROR, error.getCode());
        }
    }

    @Test
    void mapsRenExceptionFromSm2DecryptToDecryptError() {
        SysParamsService params = paramsWithPrivateKey();
        RenException expected = mock(RenException.class);
        try (MockedStatic<SM2Utils> sm2 = mockStatic(SM2Utils.class);
                MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            sm2.when(() -> SM2Utils.decrypt("private-key", "encrypted")).thenThrow(expected);

            RenException error = assertThrows(RenException.class,
                    () -> Sm2DecryptUtil.decryptPassword("encrypted", params));

            assertEquals(ErrorCode.SM2_DECRYPT_ERROR, error.getCode());
        }
    }

    @Test
    void mapsNullCiphertextToDecryptError() {
        SysParamsService params = paramsWithPrivateKey();
        try (MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            RenException error = assertThrows(RenException.class,
                    () -> Sm2DecryptUtil.decryptPassword(null, params));

            assertEquals(ErrorCode.SM2_DECRYPT_ERROR, error.getCode());
        }
    }

    @Test
    void decryptsNewLoginProtocolAsTheWholePassword() {
        Map<String, String> keys = SM2Utils.createKey();
        SysParamsService params = mock(SysParamsService.class);
        when(params.getValue(Constant.SM2_PRIVATE_KEY, true)).thenReturn(keys.get(SM2Utils.KEY_PRIVATE_KEY));
        String encrypted = SM2Utils.encrypt(keys.get(SM2Utils.KEY_PUBLIC_KEY), "password-only");

        assertEquals("password-only", Sm2DecryptUtil.decryptLoginPassword(encrypted, null, params));
    }

    @Test
    void stripsLegacyLoginCaptchaPrefixWithoutValidatingItsValue() {
        Map<String, String> keys = SM2Utils.createKey();
        SysParamsService params = mock(SysParamsService.class);
        when(params.getValue(Constant.SM2_PRIVATE_KEY, true)).thenReturn(keys.get(SM2Utils.KEY_PRIVATE_KEY));
        String encrypted = SM2Utils.encrypt(keys.get(SM2Utils.KEY_PUBLIC_KEY), "wrongpassword");

        assertEquals("password", Sm2DecryptUtil.decryptLoginPassword(encrypted, "legacy-captcha-id", params));
    }

    @ParameterizedTest
    @ValueSource(strings = { "abcd", "abcde", "abcde  " })
    void rejectsLegacyLoginPlaintextWithoutNonBlankPasswordAfterPrefix(String plaintext) {
        Map<String, String> keys = SM2Utils.createKey();
        SysParamsService params = mock(SysParamsService.class);
        when(params.getValue(Constant.SM2_PRIVATE_KEY, true)).thenReturn(keys.get(SM2Utils.KEY_PRIVATE_KEY));
        String encrypted = SM2Utils.encrypt(keys.get(SM2Utils.KEY_PUBLIC_KEY), plaintext);

        try (MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            RenException error = assertThrows(RenException.class,
                    () -> Sm2DecryptUtil.decryptLoginPassword(encrypted, "legacy-captcha-id", params));

            assertEquals(ErrorCode.SM2_DECRYPT_ERROR, error.getCode());
        }
    }

    @Test
    void captchaAwareDecryptionStillValidatesTheEmbeddedCaptcha() {
        Map<String, String> keys = SM2Utils.createKey();
        SysParamsService params = mock(SysParamsService.class);
        CaptchaService captcha = mock(CaptchaService.class);
        when(params.getValue(Constant.SM2_PRIVATE_KEY, true)).thenReturn(keys.get(SM2Utils.KEY_PRIVATE_KEY));
        when(captcha.validate("captcha-id", "abcde", true)).thenReturn(true);
        String encrypted = SM2Utils.encrypt(keys.get(SM2Utils.KEY_PUBLIC_KEY), "abcdepassword");

        String password = Sm2DecryptUtil.decryptAndValidateCaptcha(encrypted, "captcha-id", captcha, params);

        assertEquals("password", password);
        verify(captcha).validate("captcha-id", "abcde", true);
    }

    private SysParamsService paramsWithPrivateKey() {
        SysParamsService params = mock(SysParamsService.class);
        when(params.getValue(Constant.SM2_PRIVATE_KEY, true)).thenReturn("private-key");
        return params;
    }
}
