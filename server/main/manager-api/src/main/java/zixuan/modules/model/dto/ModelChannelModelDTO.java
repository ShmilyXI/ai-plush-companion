package zixuan.modules.model.dto;

import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "连接下的模型，不包含地址和凭据")
public class ModelChannelModelDTO {
    private String id;
    private String channelId;
    private String modelType;

    @Schema(description = "服务商给出的模型代号")
    private String modelId;

    @Schema(description = "显示名称")
    private String modelName;

    private Integer isEnabled;
    private Integer isDefault;

    @Schema(description = "模型参数，如温度和最大输出长度")
    private Map<String, Object> params;
}
