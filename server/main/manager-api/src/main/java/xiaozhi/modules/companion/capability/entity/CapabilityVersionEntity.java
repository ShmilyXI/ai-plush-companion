package xiaozhi.modules.companion.capability.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_capability_version")
public class CapabilityVersionEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String capabilityId;
    private Integer versionNo;
    private String contentJson;
    private String contentSha256;
    private Long publisher;
    private Date publishedAt;
}
