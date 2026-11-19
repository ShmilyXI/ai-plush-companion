package zixuan.modules.model.dto;

import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "修改连接下模型的显示名称和参数")
public class ModelChannelModelUpdateDTO {
    private String modelName;

    @Schema(description = "要修改的参数。值为 null 表示删除该参数")
    private Map<String, Object> params;
}
