package xiaozhi.modules.companion.vo;

import java.util.Date;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import xiaozhi.modules.companion.model.vo.CompanionEffectiveModelVO;

@Data
public class CompanionDeviceVO {
    private String id;
    private String macAddress;
    private String alias;
    private String board;
    private Boolean online;
    private Date lastConnectedAt;
    private String appVersion;
    private Boolean hasDisplay;
    private Boolean hasCamera;
    @Schema(description = "是否记录设备调试日志(0关闭/1开启)")
    private Boolean debugLogEnabled;
    private String activeProfileId;
    private List<CompanionEffectiveModelVO> effectiveModels;
}
