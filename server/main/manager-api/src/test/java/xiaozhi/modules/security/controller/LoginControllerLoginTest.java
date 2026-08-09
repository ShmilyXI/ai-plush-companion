package xiaozhi.modules.security.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.page.TokenDTO;
import xiaozhi.common.utils.MessageUtils;
import xiaozhi.common.utils.Result;
import xiaozhi.common.utils.SM2Utils;
import xiaozhi.modules.security.dto.PasswordLoginDTO;
import xiaozhi.modules.security.password.PasswordUtils;
import xiaozhi.modules.security.service.CaptchaService;
import xiaozhi.modules.security.service.SysUserTokenService;
import xiaozhi.modules.sys.dto.SysUserDTO;
import xiaozhi.modules.sys.service.SysDictDataService;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.sys.service.SysUserService;

class LoginControllerLoginTest {
    private static Map<String, String> keys;

    private SysUserService userService;
    private SysUserTokenService tokenService;
    private CaptchaService captchaService;
    private SysParamsService paramsService;
    private LoginController controller;

    @BeforeAll
    static void createSm2Keys() {
        keys = SM2Utils.createKey();
    }

    @BeforeEach
    void setUp() {
        userService = mock(SysUserService.class);
        tokenService = mock(SysUserTokenService.class);
        captchaService = mock(CaptchaService.class);
        paramsService = mock(SysParamsService.class);
        when(paramsService.getValue(Constant.SM2_PRIVATE_KEY, true))
                .thenReturn(keys.get(SM2Utils.KEY_PRIVATE_KEY));
        controller = new LoginController(
                userService,
                tokenService,
                captchaService,
                paramsService,
                mock(SysDictDataService.class));
    }

    @Test
    void logsInWithoutCaptcha() {
        PasswordLoginDTO login = login("alice", "correct-password", null);
        SysUserDTO user = user(7L, "alice", "correct-password");
        Result<TokenDTO> expected = new Result<TokenDTO>().ok(new TokenDTO());
        when(userService.getByUsername("alice")).thenReturn(user);
        when(tokenService.createToken(7L)).thenReturn(expected);

        Result<TokenDTO> actual = controller.login(login);

        assertSame(expected, actual);
        assertEquals("correct-password", login.getPassword());
        verifyNoInteractions(captchaService);
    }

    @ParameterizedTest
    @ValueSource(strings = { "abcde", "98765" })
    void legacyWebAndMobileCiphertextsStillLogInWithoutCaptchaValidation(String legacyCaptcha) {
        PasswordLoginDTO login = login("alice", legacyCaptcha + "correct-password", "legacy-captcha-id");
        SysUserDTO user = user(7L, "alice", "correct-password");
        Result<TokenDTO> expected = new Result<TokenDTO>().ok(new TokenDTO());
        when(userService.getByUsername("alice")).thenReturn(user);
        when(tokenService.createToken(7L)).thenReturn(expected);

        Result<TokenDTO> actual = controller.login(login);

        assertSame(expected, actual);
        assertEquals("correct-password", login.getPassword());
        verifyNoInteractions(captchaService);
    }

    @Test
    void rejectsWrongPasswordWithoutCaptchaValidation() {
        PasswordLoginDTO login = login("alice", "wrong-password", null);
        when(userService.getByUsername("alice")).thenReturn(user(7L, "alice", "correct-password"));

        RenException error;
        try (MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            error = assertThrows(RenException.class, () -> controller.login(login));
        }

        assertEquals(ErrorCode.ACCOUNT_PASSWORD_ERROR, error.getCode());
        verifyNoInteractions(captchaService);
    }

    @Test
    void rejectsUnknownAccountWithoutCaptchaValidation() {
        PasswordLoginDTO login = login("missing", "password", null);
        when(userService.getByUsername("missing")).thenReturn(null);

        RenException error;
        try (MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            error = assertThrows(RenException.class, () -> controller.login(login));
        }

        assertEquals(ErrorCode.ACCOUNT_PASSWORD_ERROR, error.getCode());
        verifyNoInteractions(captchaService);
    }

    private PasswordLoginDTO login(String username, String plaintext, String captchaId) {
        PasswordLoginDTO login = new PasswordLoginDTO();
        login.setUsername(username);
        login.setPassword(SM2Utils.encrypt(keys.get(SM2Utils.KEY_PUBLIC_KEY), plaintext));
        login.setCaptchaId(captchaId);
        return login;
    }

    private SysUserDTO user(Long id, String username, String password) {
        SysUserDTO user = new SysUserDTO();
        user.setId(id);
        user.setUsername(username);
        user.setPassword(PasswordUtils.encode(password));
        return user;
    }
}
