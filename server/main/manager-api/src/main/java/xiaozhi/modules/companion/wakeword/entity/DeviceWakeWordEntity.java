package xiaozhi.modules.companion.wakeword.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_device_wake_word")
public class DeviceWakeWordEntity {
    public static final String IDLE = "IDLE";
    public static final String GENERATING = "GENERATING";
    public static final String WAITING_DEVICE = "WAITING_DEVICE";
    public static final String DOWNLOADING = "DOWNLOADING";
    public static final String WAITING_REBOOT = "WAITING_REBOOT";
    public static final String ACTIVE = "ACTIVE";
    public static final String FAILED = "FAILED";

    @TableId(type = IdType.INPUT)
    private String deviceId;
    private String desiredWord;
    private Long desiredVersion;
    private String activeWord;
    private Long activeVersion;
    private String candidatePath;
    private String candidateToken;
    private String candidateSha256;
    private Long candidateSize;
    private String status;
    private String lastErrorCode;
    private String lastErrorMessage;
    private Boolean capable;
    private String capabilityReason;
    private String chipModel;
    private Long assetsPartitionSize;
    private Integer layoutVersion;
    private Long slotSize;
    private String lockToken;
    private Date lockUntil;
    private Date createdAt;
    private Date updatedAt;
}
