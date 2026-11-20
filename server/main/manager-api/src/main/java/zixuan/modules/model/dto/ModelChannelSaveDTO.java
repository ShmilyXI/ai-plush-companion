package zixuan.modules.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "新增、修改或测试模型连接")
public class ModelChannelSaveDTO {
    private String name;
    private String vendorCode;

    @Schema(description = "接口协议，创建后不能修改")
    private String providerCode;

    private String baseUrl;

    @Schema(description = "密钥。修改时留空表示保留原值")
    private String apiKey;

    @Schema(description = "修改时传 true 表示清除已保存的密钥")
    private Boolean clearApiKey;

    private String remark;
    private Integer sort;
}
