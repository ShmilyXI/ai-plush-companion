package xiaozhi.modules.companion.vo;

import lombok.Data;

@Data
public class CompanionBoundDeviceVO {
    private String id;
    private String alias;
    private String macAddress;
    private String board;
    private String appVersion;
    private Boolean online;
}
