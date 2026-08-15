package xiaozhi.modules.companion.capability.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_mcp_tool_snapshot")
public class McpToolSnapshotEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String mcpServerId;
    private String toolName;
    private String inputSchemaJson;
    private String schemaSha256;
    private String status;
    private Integer approved;
    private Date syncedAt;
    private Date createdAt;
    private Date updatedAt;
}
