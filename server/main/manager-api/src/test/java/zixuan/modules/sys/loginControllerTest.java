package zixuan.modules.sys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import lombok.extern.slf4j.Slf4j;
import zixuan.common.exception.ErrorCode;
import zixuan.common.exception.RenException;
import zixuan.modules.security.controller.LoginController;
import zixuan.modules.security.dto.LoginDTO;
import zixuan.modules.security.dto.SmsVerificationDTO;
import zixuan.modules.sys.dto.RetrievePasswordDTO;
import zixuan.modules.sys.service.SysUserService;

@Slf4j
@SpringBootTest
@ActiveProfiles("dev")
class loginControllerTest {

    @Autowired
    LoginController loginController;

    @MockitoBean
    SysUserService sysUserService;

    @Test
    public void testRegister() {
        when(sysUserService.getAllowUserRegister()).thenReturn(false);

        LoginDTO loginDTO = new LoginDTO();
        loginDTO.setUsername("手机号码");
        loginDTO.setPassword("密码");

        RenException exception = assertThrows(RenException.class, () -> loginController.register(loginDTO));
        assertEquals(ErrorCode.USER_REGISTER_DISABLED, exception.getCode());
    }

    @Test
    public void testSmsVerification() {
        try {
            SmsVerificationDTO smsVerificationDTO = new SmsVerificationDTO();
            smsVerificationDTO.setPhone("手机号码");
            smsVerificationDTO.setCaptchaId("123456");
            smsVerificationDTO.setCaptcha("123456");
            loginController.smsVerification(smsVerificationDTO);
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
    }

    @Test
    public void testRetrievePassword() {
        try {
            RetrievePasswordDTO retrievePasswordDTO = new RetrievePasswordDTO();
            retrievePasswordDTO.setCode("123456");
            retrievePasswordDTO.setPhone("手机号码");
            retrievePasswordDTO.setPassword("密码");
            loginController.retrievePassword(retrievePasswordDTO);
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }

    }

}
