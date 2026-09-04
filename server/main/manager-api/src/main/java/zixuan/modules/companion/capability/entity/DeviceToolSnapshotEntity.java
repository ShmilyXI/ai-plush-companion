package zixuan.modules.companion.capability.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_device_tool_snapshot")
public class DeviceToolSnapshotEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String deviceId;
    private String toolName;
    private String inputSchemaJson;
    private String schemaSha256;
    private String deviceModel;
    private String firmwareVersion;
    private Integer available;
    private Date lastSeenAt;
    private Date createdAt;
    private Date updatedAt;
}
