package zixuan.modules.companion.vo;

import java.util.Date;

import lombok.Data;

@Data
public class CompanionEntitlementVO {
    private String planId;
    private String planCode;
    private String planName;
    private int maxDevices;
    private int maxProfiles;
    private boolean longTermMemory;
    private boolean advancedVoice;
    private Date expiresAt;
}
