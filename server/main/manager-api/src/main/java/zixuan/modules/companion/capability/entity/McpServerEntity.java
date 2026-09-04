package zixuan.modules.companion.capability.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_mcp_server")
public class McpServerEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String capabilityId;
    private String transport;
    private String connectionConfigJson;
    private String secretRefsJson;
    private String approvedCommandTemplateJson;
    private String healthStatus;
    private String lastError;
    private Date lastCheckedAt;
    private Date createdAt;
    private Date updatedAt;
}
