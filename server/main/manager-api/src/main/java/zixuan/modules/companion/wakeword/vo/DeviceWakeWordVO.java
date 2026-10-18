package zixuan.modules.companion.wakeword.vo;

import java.util.Date;

import lombok.Data;

@Data
public class DeviceWakeWordVO {
    private String desiredWord;
    private long desiredVersion;
    private String activeWord;
    private long activeVersion;
    private String status;
    private String lastErrorCode;
    private String lastErrorMessage;
    private boolean supported;
    private String unsupportedReason;
    private Date updatedAt;
}
