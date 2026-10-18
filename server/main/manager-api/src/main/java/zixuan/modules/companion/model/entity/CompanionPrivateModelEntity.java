package zixuan.modules.companion.model.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;

import cn.hutool.json.JSONObject;
import lombok.Data;

@Data
@TableName(value = "ai_companion_private_model", autoResultMap = true)
public class CompanionPrivateModelEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private Long userId;
    private String modelType;
    private String name;
    private String providerCode;
    private String vendorName;
    private String protocol;
    private Integer credentialRequired;
    private String providerTemplateId;
    private String apiUrl;
    private String apiKeyCiphertext;
    private String secretConfigCiphertext;
    private String modelId;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private JSONObject configJson;
    private Integer enabled;
    private Long creator;
    private Date createdAt;
    private Long updater;
    private Date updatedAt;
}
