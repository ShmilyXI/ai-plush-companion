package zixuan.modules.appauth.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * App 用户多端会话 token，只保存哈希，明文仅在签发响应中返回一次
 */
@Data
@TableName("app_user_token")
public class AppUserTokenEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /**
     * 用户id
     */
    private Long userId;
    /**
     * 访问令牌SHA-256哈希
     */
    private String tokenHash;
    /**
     * 刷新令牌SHA-256哈希
     */
    private String refreshHash;
    /**
     * 客户端设备标识
     */
    private String deviceLabel;
    /**
     * 签发时来源IP
     */
    private String ip;
    /**
     * 访问令牌过期时间
     */
    private Date expireDate;
    /**
     * 刷新令牌过期时间
     */
    private Date refreshExpireDate;
    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private Date createDate;
    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateDate;
}
