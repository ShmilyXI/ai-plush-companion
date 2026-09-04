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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import zixuan.common.page.PageData;
import zixuan.common.utils.ConvertUtils;
import zixuan.common.utils.Result;
import zixuan.modules.agent.service.AgentTemplateService;
import zixuan.modules.companion.model.vo.CompanionModelTestVO;
import zixuan.modules.config.service.ConfigService;
import zixuan.modules.model.dto.LlmModelBasicInfoDTO;
import zixuan.modules.model.dto.ModelBasicInfoDTO;
import zixuan.modules.model.dto.ModelConfigBodyDTO;
import zixuan.modules.model.dto.ModelConfigDTO;
import zixuan.modules.model.dto.ModelProviderDTO;
import zixuan.modules.model.dto.VoiceDTO;
import zixuan.modules.model.entity.ModelConfigEntity;
import zixuan.modules.model.service.ModelConfigService;
import zixuan.modules.model.service.ModelConnectionTestService;
import zixuan.modules.model.service.ModelProviderService;
import zixuan.modules.model.tencentdb.TencentDbMemoryModelCatalogService;
import zixuan.modules.timbre.service.TimbreService;

@AllArgsConstructor
@RestController
@RequestMapping("/models")
@Tag(name = "模型配置")
public class ModelController {

    private final ModelProviderService modelProviderService;
    private final TimbreService timbreService;
    private final ModelConfigService modelConfigService;
    private final ConfigService configService;
    private final AgentTemplateService agentTemplateService;
    private final ModelConnectionTestService modelConnectionTestService;
    private final TencentDbMemoryModelCatalogService memoryModelCatalogService;

    @GetMapping("/names")
    @Operation(summary = "获取所有模型名称")
    @RequiresPermissions("sys:role:normal")
    public Result<List<ModelBasicInfoDTO>> getModelNames(@RequestParam String modelType,
            @RequestParam(required = false) String modelName) {
        List<ModelBasicInfoDTO> modelList = modelConfigService.getModelCodeList(modelType, modelName);
        return new Result<List<ModelBasicInfoDTO>>().ok(modelList);
    }

    @GetMapping("/llm/names")
    @Operation(summary = "获取LLM模型信息")
    @RequiresPermissions("sys:role:normal")
    public Result<List<LlmModelBasicInfoDTO>> getLlmModelCodeList(@RequestParam(required = false) String modelName) {
        List<LlmModelBasicInfoDTO> llmModelCodeList = modelConfigService.getLlmModelCodeList(modelName);
        return new Result<List<LlmModelBasicInfoDTO>>().ok(llmModelCodeList);
    }

    @GetMapping("/{modelType}/provideTypes")
    @Operation(summary = "获取模型供应器列表")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<List<ModelProviderDTO>> getModelProviderList(@PathVariable String modelType) {
        List<ModelProviderDTO> modelProviderDTOS = modelProviderService.getListByModelType(modelType);
        memoryModelCatalogService.enrich(modelProviderDTOS);
        return new Result<List<ModelProviderDTO>>().ok(modelProviderDTOS);
    }

    @GetMapping("/list")
    @Operation(summary = "获取模型配置列表")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<PageData<ModelConfigDTO>> getModelConfigList(
            @RequestParam(required = true) String modelType,
            @RequestParam(required = false) String modelName,
            @RequestParam(required = true, defaultValue = "0") String page,
            @RequestParam(required = true, defaultValue = "10") String limit) {
        PageData<ModelConfigDTO> pageList = modelConfigService.getPageList(modelType, modelName, page, limit);
        return new Result<PageData<ModelConfigDTO>>().ok(pageList);
    }

    @PostMapping("/{modelType}/{provideCode}")
    @Operation(summary = "新增模型配置")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<ModelConfigDTO> addModelConfig(@PathVariable String modelType,
            @PathVariable String provideCode,
            @RequestBody ModelConfigBodyDTO modelConfigBodyDTO) {
        ModelConfigDTO modelConfigDTO = modelConfigService.add(modelType, provideCode, modelConfigBodyDTO);
        configService.getConfig(false);
        return new Result<ModelConfigDTO>().ok(modelConfigDTO);
    }

    @PutMapping("/{modelType}/{provideCode}/{id}")
    @Operation(summary = "编辑模型配置")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<ModelConfigDTO> editModelConfig(@PathVariable String modelType,
            @PathVariable String provideCode,
            @PathVariable String id,
            @RequestBody ModelConfigBodyDTO modelConfigBodyDTO) {
        ModelConfigDTO modelConfigDTO = modelConfigService.edit(modelType, provideCode, id, modelConfigBodyDTO);
        configService.getConfig(false);
        return new Result<ModelConfigDTO>().ok(modelConfigDTO);
    }

    @PostMapping("/{modelType}/{provideCode}/test")
    @Operation(summary = "测试未保存模型配置")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<CompanionModelTestVO> testNewModel(@PathVariable String modelType,
            @PathVariable String provideCode,
            @RequestBody ModelConfigBodyDTO modelConfigBodyDTO) {
        return new Result<CompanionModelTestVO>().ok(
                modelConnectionTestService.test(modelType, provideCode, null, modelConfigBodyDTO));
    }

    @PostMapping("/{modelType}/{provideCode}/{id}/test")
    @Operation(summary = "测试已保存模型配置")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<CompanionModelTestVO> testSavedModel(@PathVariable String modelType,
            @PathVariable String provideCode,
            @PathVariable String id,
            @RequestBody ModelConfigBodyDTO modelConfigBodyDTO) {
        return new Result<CompanionModelTestVO>().ok(
                modelConnectionTestService.test(modelType, provideCode, id, modelConfigBodyDTO));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除模型配置")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Void> deleteModelConfig(@PathVariable String id) {
        modelConfigService.delete(id);
        return new Result<>();
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取模型配置")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<ModelConfigDTO> getModelConfig(@PathVariable String id) {
        ModelConfigEntity item = modelConfigService.selectById(id);
        ModelConfigDTO modelConfigDTO = ConvertUtils.sourceToTarget(item, ModelConfigDTO.class);
        return new Result<ModelConfigDTO>().ok(modelConfigDTO);
    }

    @PutMapping("/enable/{id}/{status}")
    @Operation(summary = "启用/关闭模型配置")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Void> enableModelConfig(@PathVariable String id, @PathVariable Integer status) {
        ModelConfigEntity entity = modelConfigService.selectById(id);
        if (entity == null) {
            return new Result<Void>().error("模型配置不存在");
        }
        // 不能关闭默认模型
        if (status == 0 && entity.getIsDefault() > 0) {
            return new Result<Void>().error("默认模型配置不允许关闭");
        }
        // 不更新ConfigJson字段
        entity.setConfigJson(null);
        entity.setIsEnabled(status);
        modelConfigService.updateById(entity);
        modelConfigService.evictModelCache(id);
        return new Result<Void>();
    }

    @PutMapping("/default/{id}")
    @Operation(summary = "设置默认模型")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Void> setDefaultModel(@PathVariable String id) {
        ModelConfigEntity entity = modelConfigService.selectById(id);
        if (entity == null) {
            return new Result<Void>().error("模型配置不存在");
        }
        // 将其他模型设置为非默认
        modelConfigService.setDefaultModel(entity.getModelType(), 0);
        entity.setIsEnabled(1);
        entity.setIsDefault(1);
        // 不更新ConfigJson字段
        entity.setConfigJson(null);
        modelConfigService.updateById(entity);

        // 更新模板表中对应的模型ID
        agentTemplateService.updateDefaultTemplateModelId(entity.getModelType(), entity.getId());

        configService.getConfig(false);
        return new Result<Void>();
    }

    @GetMapping("/{modelId}/voices")
    @Operation(summary = "获取模型音色")
    @RequiresPermissions("sys:role:normal")
    public Result<List<VoiceDTO>> getVoiceList(@PathVariable String modelId,
            @RequestParam(required = false) String voiceName) {
        List<VoiceDTO> voiceList = timbreService.getVoiceNames(modelId, voiceName);
        return new Result<List<VoiceDTO>>().ok(voiceList);
    }
}
