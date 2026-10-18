package zixuan.modules.companion.model.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_companion_global_model_credential")
public class CompanionGlobalModelCredentialEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    private Long userId;
    private String globalModelId;
    private String apiUrlOverride;
    private String modelIdOverride;
    private String secretConfigCiphertext;
    private Date createdAt;
    private Date updatedAt;
}
