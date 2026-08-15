package xiaozhi.modules.companion.capability.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_plugin_definition")
public class PluginDefinitionEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String capabilityId;
    private String executorName;
    private String inputSchemaJson;
    private String configSchemaJson;
    private String secretFieldsJson;
    private String defaultConfigJson;
    private Date createdAt;
    private Date updatedAt;
}
