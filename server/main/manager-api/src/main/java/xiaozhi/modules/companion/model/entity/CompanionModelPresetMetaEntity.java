package xiaozhi.modules.companion.model.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_companion_model_preset_meta")
public class CompanionModelPresetMetaEntity {
    @TableId
    private String globalModelId;
    private String vendorCode;
    private String vendorName;
    private String protocol;
    private String defaultApiUrl;
    private String credentialRequirement;
    private String credentialFieldsJson;
    private String keyUrl;
    private String docsUrl;
    private String setupGuideJson;
    private Date createdAt;
    private Date updatedAt;
}
