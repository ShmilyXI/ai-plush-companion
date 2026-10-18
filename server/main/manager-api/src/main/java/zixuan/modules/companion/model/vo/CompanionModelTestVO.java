package zixuan.modules.companion.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CompanionModelTestVO {
    private boolean success;
    private int elapsedMillis;
    private String message;
}
