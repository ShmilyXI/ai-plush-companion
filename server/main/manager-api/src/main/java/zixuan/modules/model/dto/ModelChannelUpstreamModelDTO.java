package zixuan.modules.model.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "从服务商拉取到的模型")
public class ModelChannelUpstreamModelDTO {
    @Schema(description = "服务商给出的模型代号")
    private String id;

    @Schema(description = "这个连接下已经添加过的用途")
    private List<String> addedTypes;
}
