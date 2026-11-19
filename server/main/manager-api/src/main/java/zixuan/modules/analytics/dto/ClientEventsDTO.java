package zixuan.modules.analytics.dto;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "客户端上报的一批事件")
public class ClientEventsDTO {
    private List<Item> events;

    @Data
    public static class Item {
        @Schema(description = "事件名，小写字母和下划线，例如 mic_failed")
        private String name;

        @Schema(description = "少量属性，值只能是字符串、数字或布尔")
        private Map<String, Object> props;
    }
}
