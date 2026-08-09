package xiaozhi.modules.companion.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@TableName("ai_companion_plan")
@Schema(description = "陪伴订阅套餐")
public class CompanionPlanEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String planCode;

    private String planName;

    private Integer maxDevices;

    private Integer maxProfiles;

    private Integer longTermMemory;

    private Integer advancedVoice;

    private Integer status;

    private Date createdAt;

    private Date updatedAt;
}
