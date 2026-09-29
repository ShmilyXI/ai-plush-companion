package zixuan.modules.companion.vo;

import java.util.Date;
import java.util.List;

import lombok.Data;
import zixuan.modules.companion.model.vo.CompanionEffectiveModelVO;
import zixuan.modules.companion.model.vo.CompanionProfileModelVO;

@Data
public class CompanionProfileVO {
    private String id;
    private String name;
    private String relationMode;
    private String userAddress;
    private String personality;
    private String systemPrompt;
    private Integer screenExpressionEnabled;
    private Integer cameraPreferenceEnabled;
    private String templateId;
    private String llmModelId;
    private String llmModelName;
    private String ttsModelId;
    private String ttsModelName;
    private String ttsVoiceId;
    private String ttsVoiceName;
    private String ttsLanguage;
    private Date createdAt;
    private Date updatedAt;
    private List<CompanionProfileModelVO> models;
    private List<CompanionEffectiveModelVO> effectiveModels;
    private Integer activeVersionNo;
    private List<CompanionBoundDeviceVO> boundDevices;
    private java.util.Map<String, Object> memoryPolicy;
    private List<CompanionSkillBindingVO> skills;
}
