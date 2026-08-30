package xiaozhi.modules.appauth.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_app_contact")
public class AppContactEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private String channel;

    private String normalizedValue;

    private Date verifiedAt;

    private Date createdAt;

    private Date updatedAt;
}
