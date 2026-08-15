package xiaozhi.modules.companion.capability.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_capability")
public class CapabilityEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String capabilityCode;
    private String type;
    private String name;
    private String description;
    private String status;
    private Integer draftVersion;
    private Integer publishedVersion;
    private Long creator;
    private Long updater;
    private Date createdAt;
    private Date updatedAt;
    private Integer deleted;
}
