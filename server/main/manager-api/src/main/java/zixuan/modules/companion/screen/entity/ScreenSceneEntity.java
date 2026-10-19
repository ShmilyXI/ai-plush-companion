package zixuan.modules.companion.screen.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_screen_scene")
public class ScreenSceneEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private Long userId;
    private String deviceId;
    private String sceneId;
    private Integer version;
    private String mode;
    private String sceneJson;
    private String status;
    private Date createdAt;
    private Date updatedAt;
}
