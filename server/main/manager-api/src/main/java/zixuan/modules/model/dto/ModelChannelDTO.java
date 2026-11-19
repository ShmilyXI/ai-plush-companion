package zixuan.modules.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "模型连接，不包含任何凭据内容")
public class ModelChannelDTO {
    private String id;
    private String name;
    private String vendorCode;
    private String providerCode;
    private String baseUrl;

    @Schema(description = "是否已保存密钥")
    private boolean credentialConfigured;

    @Schema(description = "连接下的模型数量")
    private int modelCount;

    private String remark;
    private Integer sort;
}
