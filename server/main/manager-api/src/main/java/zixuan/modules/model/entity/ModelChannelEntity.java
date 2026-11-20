package zixuan.modules.model.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 模型连接：一家服务商的接口地址和凭据。对话类模型挂在连接下，
 * 连接的地址和密钥会写入各模型的 config_json，运行时仍然只读模型配置。
 */
@Data
@TableName("ai_model_channel")
@Schema(description = "模型连接表")
public class ModelChannelEntity {

    @TableId(type = IdType.INPUT)
    @Schema(description = "主键")
    private String id;

    @Schema(description = "连接名称")
    private String name;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    @Schema(description = "厂商预设编码")
    private String vendorCode;

    @Schema(description = "接口协议(openai/ollama)")
    private String providerCode;

    @Schema(description = "接口地址")
    private String baseUrl;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    @Schema(description = "加密后的凭据")
    private String secretConfigCiphertext;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    @Schema(description = "备注")
    private String remark;

    @Schema(description = "排序")
    private Integer sort;

    @Schema(description = "创建者")
    @TableField(fill = FieldFill.INSERT)
    private Long creator;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private Date createDate;

    @Schema(description = "更新者")
    @TableField(fill = FieldFill.UPDATE)
    private Long updater;

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.UPDATE)
    private Date updateDate;
}
