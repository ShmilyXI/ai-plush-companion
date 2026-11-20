package zixuan.modules.invite.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 邀请码。封闭内测期间，新手机号必须凭邀请码才能创建账号。
 */
@Data
@TableName("ai_invite_code")
public class InviteCodeEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    private String code;
    private Integer maxUses;
    private Integer usedCount;
    private Date expireDate;
    private Integer status;
    private String remark;
    private Long creator;
    private Date createDate;
}
