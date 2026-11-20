package zixuan.modules.model.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "把上游模型添加到连接下")
public class ModelChannelAddModelsDTO {
    private List<Item> models;

    @Data
    public static class Item {
        @Schema(description = "用途：LLM、VLLM 或 Embedding")
        private String modelType;

        @Schema(description = "服务商给出的模型代号")
        private String modelId;

        @Schema(description = "显示名称，留空时用“连接名 / 模型代号”")
        private String modelName;
    }
}
