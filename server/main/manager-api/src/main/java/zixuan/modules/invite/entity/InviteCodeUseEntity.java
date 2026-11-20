package zixuan.modules.invite.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_invite_code_use")
public class InviteCodeUseEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    private String codeId;
    private Long userId;
    private Date usedAt;
}
