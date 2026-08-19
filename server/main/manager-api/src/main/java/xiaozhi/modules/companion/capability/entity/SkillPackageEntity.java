package xiaozhi.modules.companion.capability.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_skill_package")
public class SkillPackageEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String capabilityId;
    private Integer versionNo;
    private String packageSha256;
    private Long packageSize;
    private String storageKey;
    private String manifestJson;
    private String skillMarkdown;
    private String sourceType;
    private String validationStatus;
    private String validationReportJson;
    private Integer published;
    private Long creator;
    private Date createdAt;
    private Date publishedAt;
}
