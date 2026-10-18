package zixuan.modules.appauth.vo;

import java.util.Date;

import lombok.Data;

/**
 * App 当前登录用户信息
 */
@Data
public class AppProfileVO {
    private Long id;
    private String username;
    private String phone;
    private Date createDate;
}
