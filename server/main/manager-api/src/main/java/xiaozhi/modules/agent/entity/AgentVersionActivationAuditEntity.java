package xiaozhi.modules.agent.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_agent_version_activation_audit")
public class AgentVersionActivationAuditEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String agentId;

    private Long userId;

    private Integer previousVersionNo;

    private Integer activatedVersionNo;

    private String action;

    private Long operatorId;

    private Date createdAt;
}
