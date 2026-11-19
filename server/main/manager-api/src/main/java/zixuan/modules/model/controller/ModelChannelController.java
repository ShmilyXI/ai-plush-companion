package zixuan.modules.model.controller;

import java.util.List;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import zixuan.common.utils.Result;
import zixuan.modules.companion.model.vo.CompanionModelTestVO;
import zixuan.modules.config.service.ConfigService;
import zixuan.modules.model.dto.ModelChannelAddModelsDTO;
import zixuan.modules.model.dto.ModelChannelDTO;
import zixuan.modules.model.dto.ModelChannelModelDTO;
import zixuan.modules.model.dto.ModelChannelModelUpdateDTO;
import zixuan.modules.model.dto.ModelChannelSaveDTO;
import zixuan.modules.model.dto.ModelChannelUpstreamModelDTO;
import zixuan.modules.model.service.ModelChannelService;

/**
 * 模型连接。路径不放在 /models 下，避免和 /models/{id} 这类带变量的旧接口混淆。
 * 启用、设为默认和删除模型继续使用 /models 下的原有接口。
 */
@AllArgsConstructor
@RestController
@RequestMapping("/model-channels")
@Tag(name = "模型连接")
public class ModelChannelController {

    private final ModelChannelService channelService;
    private final ConfigService configService;

    @GetMapping
    @Operation(summary = "获取模型连接列表")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<List<ModelChannelDTO>> list() {
        return new Result<List<ModelChannelDTO>>().ok(channelService.list());
    }

    @PostMapping
    @Operation(summary = "新增模型连接")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<ModelChannelDTO> create(@RequestBody ModelChannelSaveDTO dto) {
        return new Result<ModelChannelDTO>().ok(channelService.create(dto));
    }

    @PutMapping("/{id}")
    @Operation(summary = "修改模型连接，并同步到连接下的模型")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<ModelChannelDTO> update(@PathVariable String id, @RequestBody ModelChannelSaveDTO dto) {
        ModelChannelDTO result = channelService.update(id, dto);
        configService.getConfig(false);
        return new Result<ModelChannelDTO>().ok(result);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除模型连接")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Void> delete(@PathVariable String id) {
        channelService.delete(id);
        return new Result<>();
    }

    @PostMapping("/test")
    @Operation(summary = "测试未保存的模型连接")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<CompanionModelTestVO> testNew(@RequestBody ModelChannelSaveDTO dto) {
        return new Result<CompanionModelTestVO>().ok(channelService.test(null, dto));
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "测试已保存的模型连接")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<CompanionModelTestVO> testSaved(@PathVariable String id,
            @RequestBody(required = false) ModelChannelSaveDTO dto) {
        return new Result<CompanionModelTestVO>().ok(channelService.test(id, dto));
    }

    @GetMapping("/{id}/upstream-models")
    @Operation(summary = "从服务商拉取模型列表")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<List<ModelChannelUpstreamModelDTO>> upstreamModels(@PathVariable String id) {
        return new Result<List<ModelChannelUpstreamModelDTO>>().ok(channelService.upstreamModels(id));
    }

    @GetMapping("/models")
    @Operation(summary = "获取所有连接下的模型")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<List<ModelChannelModelDTO>> models() {
        return new Result<List<ModelChannelModelDTO>>().ok(channelService.models());
    }

    @PostMapping("/{id}/models")
    @Operation(summary = "把模型添加到连接下")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<List<ModelChannelModelDTO>> addModels(@PathVariable String id,
            @RequestBody ModelChannelAddModelsDTO dto) {
        List<ModelChannelModelDTO> result = channelService.addModels(id, dto);
        configService.getConfig(false);
        return new Result<List<ModelChannelModelDTO>>().ok(result);
    }

    @PutMapping("/models/{modelId}")
    @Operation(summary = "修改连接下模型的显示名称和参数")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<ModelChannelModelDTO> updateModel(@PathVariable String modelId,
            @RequestBody ModelChannelModelUpdateDTO dto) {
        ModelChannelModelDTO result = channelService.updateModel(modelId, dto);
        configService.getConfig(false);
        return new Result<ModelChannelModelDTO>().ok(result);
    }
}
