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
@TableName(value = "ai_companion_profile_model", autoResultMap = true)
public class CompanionProfileModelEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String agentId;
    private String modelType;
    private String sourceType;
    private String resourceId;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private JSONObject overrideJson;
    private Date createdAt;
    private Date updatedAt;
}
