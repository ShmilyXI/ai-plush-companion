package zixuan.modules.companion.capability.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_capability_secret")
public class CapabilitySecretEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String capabilityId;
    private String secretName;
    private String secretCiphertext;
    private Date createdAt;
    private Date updatedAt;
}
