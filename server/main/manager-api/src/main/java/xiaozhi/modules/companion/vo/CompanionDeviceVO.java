package xiaozhi.modules.companion.vo;

import java.util.Date;
import java.util.List;

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
    private String activeProfileId;
    private List<CompanionEffectiveModelVO> effectiveModels;
}
