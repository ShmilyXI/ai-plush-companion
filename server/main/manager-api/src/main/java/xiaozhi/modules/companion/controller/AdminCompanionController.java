package xiaozhi.modules.companion.controller;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.page.PageData;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.companion.dao.CompanionPlanDao;
import xiaozhi.modules.companion.dao.CompanionSubscriptionDao;
import xiaozhi.modules.companion.dto.CompanionGrantDTO;
import xiaozhi.modules.companion.dto.AdminCompanionDeviceUpdateDTO;
import xiaozhi.modules.companion.dto.AdminCompanionDeviceModeUpdateDTO;
import xiaozhi.modules.companion.dto.CompanionPlanSaveDTO;
import xiaozhi.modules.companion.entity.CompanionAuditEntity;
import xiaozhi.modules.companion.entity.CompanionPlanEntity;
import xiaozhi.modules.companion.entity.CompanionSubscriptionEntity;
import xiaozhi.modules.companion.service.CompanionAuditService;
import xiaozhi.modules.companion.service.CompanionSubscriptionService;
import xiaozhi.modules.companion.service.AdminFirmwareService;
import xiaozhi.modules.companion.service.AdminResourceService;
import xiaozhi.modules.companion.service.AdminCompanionDeviceService;
import xiaozhi.modules.companion.model.service.CompanionModelMigrationAuditService;
import xiaozhi.modules.companion.model.vo.CompanionModelMigrationAuditReportVO;
import xiaozhi.modules.agent.entity.AgentTemplateEntity;
import xiaozhi.modules.device.entity.OtaEntity;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.service.CompanionMemoryService;
import xiaozhi.modules.device.service.CompanionMemoryService.MemoryItem;
import xiaozhi.modules.device.service.DeviceService;
import xiaozhi.modules.model.dto.ModelConfigBodyDTO;
import xiaozhi.modules.model.dto.ModelConfigDTO;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.model.service.ModelProviderService;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.sys.service.SysUserService;
import xiaozhi.modules.timbre.service.TimbreService;
import xiaozhi.modules.timbre.dto.TimbreDataDTO;
import xiaozhi.modules.timbre.dto.TimbrePageDTO;
import xiaozhi.modules.timbre.vo.TimbreDetailsVO;
import xiaozhi.modules.sys.dto.AdminPageUserDTO;
import xiaozhi.modules.sys.vo.AdminPageUserVO;
import cn.hutool.json.JSONObject;
import lombok.Data;

@RestController
@RequestMapping("/admin/companion")
@AllArgsConstructor
public class AdminCompanionController {
    private static final String BASIC_PLAN_CODE = "basic";

    private final CompanionSubscriptionService subscriptionService;
    private final CompanionAuditService auditService;
    private final CompanionPlanDao planDao;
    private final CompanionSubscriptionDao subscriptionDao;
    private final SysUserService userService;
    private final ModelConfigService modelService;
    private final AdminFirmwareService firmwareService;
    private final AdminResourceService resourceService;
    private final TimbreService timbreService;
    private final ModelProviderService modelProviderService;
    private final CompanionMemoryService memoryService;
    private final DeviceService deviceService;
    private final AdminCompanionDeviceService deviceAdminService;
    private final CompanionModelMigrationAuditService modelMigrationAuditService;

    private static final Set<String> MODEL_TYPES = Set.of("Memory", "ASR", "VAD", "LLM", "VLLM", "TTS");

    @GetMapping("/plans")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<List<CompanionPlanEntity>> plans() {
        return new Result<List<CompanionPlanEntity>>().ok(
                planDao.selectList(new QueryWrapper<CompanionPlanEntity>().orderByAsc("plan_code")));
    }

    @GetMapping("/plans/page")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<PageData<CompanionPlanEntity>> plansPage(@RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int limit) {
        QueryWrapper<CompanionPlanEntity> query = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) query.and(value -> value.like("plan_code", keyword.trim()).or().like("plan_name", keyword.trim()));
        query.orderByAsc("plan_code");
        IPage<CompanionPlanEntity> result = planDao.selectPage(new Page<>(Math.max(page, 1), Math.min(Math.max(limit, 1), 100)), query);
        return new Result<PageData<CompanionPlanEntity>>().ok(new PageData<>(result.getRecords(), result.getTotal()));
    }

    @GetMapping("/plans/{id}")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<CompanionPlanEntity> plan(@PathVariable String id) {
        return new Result<CompanionPlanEntity>().ok(requirePlan(id));
    }

    @PostMapping("/plans")
    @RequiresPermissions("sys:role:superAdmin")
    @Transactional(rollbackFor = Exception.class)
    public Result<String> createPlan(@RequestBody @Valid CompanionPlanSaveDTO dto) {
        CompanionPlanEntity plan = toEntity(dto);
        Date now = new Date();
        plan.setCreatedAt(now);
        plan.setUpdatedAt(now);
        if (planDao.insert(plan) != 1) {
            throw new RenException("套餐创建失败");
        }
        auditService.record(SecurityUser.getUserId(), null, "plan.create", "plan", plan.getId(),
                Map.of("planCode", plan.getPlanCode(), "planName", plan.getPlanName()));
        return new Result<String>().ok(plan.getId());
    }

    @PutMapping("/plans/{id}")
    @RequiresPermissions("sys:role:superAdmin")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> updatePlan(@PathVariable String id, @RequestBody @Valid CompanionPlanSaveDTO dto) {
        CompanionPlanEntity existing = requirePlan(id);
        CompanionPlanEntity plan = toEntity(dto);
        if (BASIC_PLAN_CODE.equals(existing.getPlanCode())
                && (!BASIC_PLAN_CODE.equals(plan.getPlanCode()) || !Integer.valueOf(1).equals(plan.getStatus()))) {
            throw new RenException("基础套餐不能改名或停用");
        }
        plan.setId(id);
        plan.setUpdatedAt(new Date());
        if (planDao.updateById(plan) != 1) {
            throw new RenException("套餐不存在");
        }
        auditService.record(SecurityUser.getUserId(), null, "plan.update", "plan", id,
                Map.of("planCode", plan.getPlanCode(), "status", plan.getStatus()));
        return new Result<Void>().ok(null);
    }

    @DeleteMapping("/plans/{id}")
    @RequiresPermissions("sys:role:superAdmin")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> deletePlan(@PathVariable String id) {
        CompanionPlanEntity existing = planDao.selectByIdForUpdate(id);
        if (existing != null && BASIC_PLAN_CODE.equals(existing.getPlanCode())) {
            throw new RenException("基础套餐不能删除");
        }
        if (subscriptionDao.selectCount(new QueryWrapper<CompanionSubscriptionEntity>().eq("plan_id", id)) > 0) {
            throw new RenException("套餐已被订阅引用，不能删除");
        }
        if (planDao.deleteById(id) != 1) {
            throw new RenException("套餐不存在");
        }
        auditService.record(SecurityUser.getUserId(), null, "plan.delete", "plan", id,
                Map.of("planCode", existing == null ? "unknown" : existing.getPlanCode()));
        return new Result<Void>().ok(null);
    }

    @PutMapping("/subscriptions/{userId}")
    @RequiresPermissions("sys:role:superAdmin")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> grant(@PathVariable Long userId, @RequestBody @Valid CompanionGrantDTO request) {
        Long operatorId = SecurityUser.getUserId();
        subscriptionService.grant(operatorId, userId, request.getPlanId(), request.getExpiresAt());
        auditService.record(operatorId, userId, "subscription.grant", "subscription", String.valueOf(userId),
                Map.of("planId", request.getPlanId(), "expiresAt", request.getExpiresAt().toString()));
        return new Result<Void>().ok(null);
    }

    @PutMapping("/subscriptions/{userId}/pause")
    @RequiresPermissions("sys:role:superAdmin")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> pauseSubscription(@PathVariable Long userId) {
        Long operatorId = SecurityUser.getUserId();
        subscriptionService.pause(operatorId, userId);
        auditService.record(operatorId, userId, "subscription.pause", "subscription", String.valueOf(userId),
                Map.of("status", CompanionSubscriptionEntity.STATUS_PAUSED));
        return new Result<Void>().ok(null);
    }

    @PutMapping("/subscriptions/{userId}/cancel")
    @RequiresPermissions("sys:role:superAdmin")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> cancelSubscription(@PathVariable Long userId) {
        Long operatorId = SecurityUser.getUserId();
        subscriptionService.cancel(operatorId, userId);
        auditService.record(operatorId, userId, "subscription.cancel", "subscription", String.valueOf(userId),
                Map.of("status", CompanionSubscriptionEntity.STATUS_CANCELLED));
        return new Result<Void>().ok(null);
    }

    @GetMapping("/audit")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<PageData<CompanionAuditEntity>> audit(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String keyword) {
        return new Result<PageData<CompanionAuditEntity>>().ok(auditService.page(page, limit, keyword));
    }

    @GetMapping("/model-migration-audit")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<CompanionModelMigrationAuditReportVO> modelMigrationAudit() {
        return new Result<CompanionModelMigrationAuditReportVO>().ok(modelMigrationAuditService.audit());
    }

    @GetMapping("/devices/{deviceId}/memories")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<List<MemoryItem>> adminMemories(@PathVariable String deviceId) {
        Long operatorId = SecurityUser.getUserId();
        Long ownerId = requireDeviceOwner(deviceId);
        return new Result<List<MemoryItem>>().ok(memoryService.list(operatorId, ownerId, deviceId));
    }

    @PutMapping("/devices/{deviceId}/memories/{memoryId}")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Void> updateAdminMemory(@PathVariable String deviceId, @PathVariable String memoryId,
            @RequestBody @Valid CompanionMemoryController.MemoryUpdateRequest request) {
        Long operatorId = SecurityUser.getUserId();
        memoryService.update(operatorId, requireDeviceOwner(deviceId), deviceId, memoryId, request.content());
        return new Result<Void>().ok(null);
    }

    @DeleteMapping("/devices/{deviceId}/memories/{memoryId}")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Void> deleteAdminMemory(@PathVariable String deviceId, @PathVariable String memoryId) {
        Long operatorId = SecurityUser.getUserId();
        memoryService.delete(operatorId, requireDeviceOwner(deviceId), deviceId, memoryId);
        return new Result<Void>().ok(null);
    }

    @DeleteMapping("/devices/{deviceId}/memories")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Void> clearAdminMemories(@PathVariable String deviceId) {
        Long operatorId = SecurityUser.getUserId();
        memoryService.clear(operatorId, requireDeviceOwner(deviceId), deviceId);
        return new Result<Void>().ok(null);
    }

    @PutMapping("/devices/{deviceId}")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Void> updateAdminDevice(@PathVariable String deviceId,
            @RequestBody @Valid AdminCompanionDeviceUpdateDTO request) {
        deviceAdminService.rename(SecurityUser.getUserId(), deviceId, request.getAlias());
        return new Result<Void>().ok(null);
    }

    @PutMapping("/devices/{deviceId}/mode")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Void> updateAdminDeviceMode(@PathVariable String deviceId,
            @RequestBody @Valid AdminCompanionDeviceModeUpdateDTO request) {
        deviceAdminService.updateMode(SecurityUser.getUserId(), deviceId, request.getMode());
        return new Result<Void>().ok(null);
    }

    @DeleteMapping("/devices/{deviceId}")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Void> unbindAdminDevice(@PathVariable String deviceId) {
        deviceAdminService.unbind(SecurityUser.getUserId(), deviceId);
        return new Result<Void>().ok(null);
    }

    @GetMapping("/users")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<PageData<AdminPageUserVO>> users(@RequestParam(defaultValue = "1") String page,
            @RequestParam(defaultValue = "20") String limit,
            @RequestParam(required = false) String keyword) {
        int pageNumber = requirePositivePage(page);
        int pageLimit = requirePageLimit(limit);
        AdminPageUserDTO dto = new AdminPageUserDTO();
        dto.setPage(String.valueOf(pageNumber));
        dto.setLimit(String.valueOf(pageLimit));
        dto.setMobile(keyword);
        return new Result<PageData<AdminPageUserVO>>().ok(userService.page(dto));
    }

    @GetMapping("/resources/models")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<PageData<SafeModelVO>> models(@RequestParam String modelType,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") String page,
            @RequestParam(defaultValue = "20") String limit) {
        requireModelType(modelType);
        PageData<ModelConfigDTO> data = modelService.getPageList(modelType, keyword, page, limit);
        return new Result<PageData<SafeModelVO>>().ok(new PageData<>(
                data.getList().stream().map(this::safeModel).toList(), data.getTotal()));
    }

    @GetMapping("/resources/models/{id}")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<SafeModelVO> model(@PathVariable String id) {
        ModelConfigEntity entity = modelService.selectById(id);
        if (entity == null) throw new RenException("模型不存在");
        ModelConfigDTO dto = new ModelConfigDTO();
        org.springframework.beans.BeanUtils.copyProperties(entity, dto);
        return new Result<SafeModelVO>().ok(safeModel(dto));
    }

    @GetMapping("/resources/models/options")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<List<SafeModelVO>> modelOptions(@RequestParam(defaultValue = "TTS") String modelType) {
        requireModelType(modelType);
        return new Result<List<SafeModelVO>>().ok(modelService.getEnabledModelsByType(modelType).stream().map(entity -> {
            ModelConfigDTO dto = new ModelConfigDTO();
            org.springframework.beans.BeanUtils.copyProperties(entity, dto);
            return safeModel(dto);
        }).toList());
    }

    @PostMapping("/resources/models")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<String> createModel(@RequestBody @Valid AdminModelCreateDTO input) {
        requireModelType(input.getModelType());
        return new Result<String>().ok(resourceService.createModel(SecurityUser.getUserId(), input.getModelType(),
                input.getProviderCode(), createModelBody(input)));
    }

    @PutMapping("/resources/models/{id}")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Void> updateModel(@PathVariable String id, @RequestBody @Valid AdminModelUpdateDTO input) {
        resourceService.updateModel(SecurityUser.getUserId(), id, input.getModelName(), input.getIsEnabled(),
                input.getRemark(), input.getSort(),
                input.getConfig() == null ? null : new JSONObject(input.getConfig()));
        return new Result<Void>().ok(null);
    }

    @DeleteMapping("/resources/models/{id}")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Void> deleteModel(@PathVariable String id) {
        resourceService.deleteModel(SecurityUser.getUserId(), id);
        return new Result<Void>().ok(null);
    }

    @PutMapping("/resources/models/{id}/default")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Void> defaultModel(@PathVariable String id) {
        resourceService.setDefaultModel(SecurityUser.getUserId(), id);
        return new Result<Void>().ok(null);
    }

    @GetMapping("/resources/providers")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<List<ProviderOptionVO>> providers(@RequestParam String modelType) {
        requireModelType(modelType);
        return new Result<List<ProviderOptionVO>>().ok(modelProviderService.getListByModelType(modelType).stream()
                .map(value -> new ProviderOptionVO(value.getProviderCode(), value.getName())).toList());
    }

    @GetMapping("/resources/timbres")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<PageData<TimbreDetailsVO>> timbres(@RequestParam String ttsModelId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") String page,
            @RequestParam(defaultValue = "20") String limit) {
        TimbrePageDTO dto = new TimbrePageDTO(); dto.setTtsModelId(ttsModelId); dto.setName(keyword); dto.setPage(page); dto.setLimit(limit);
        return new Result<PageData<TimbreDetailsVO>>().ok(timbreService.page(dto));
    }

    @PostMapping("/resources/timbres")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Void> createTimbre(@RequestBody @Valid TimbreDataDTO dto) {
        resourceService.createTimbre(SecurityUser.getUserId(), dto);
        return new Result<Void>().ok(null);
    }

    @PutMapping("/resources/timbres/{id}")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Void> updateTimbre(@PathVariable String id, @RequestBody @Valid TimbreDataDTO dto) {
        resourceService.updateTimbre(SecurityUser.getUserId(), id, dto);
        return new Result<Void>().ok(null);
    }

    @PutMapping("/users/{userId}/status/{status}")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Void> changeUserStatus(@PathVariable Long userId, @PathVariable Integer status) {
        resourceService.changeUserStatus(SecurityUser.getUserId(), userId, status);
        return new Result<Void>().ok(null);
    }

    @DeleteMapping("/templates/{id}")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Void> deleteTemplate(@PathVariable String id) {
        resourceService.deleteTemplate(SecurityUser.getUserId(), id);
        return new Result<Void>().ok(null);
    }

    @PostMapping("/templates")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<String> createTemplate(@RequestBody AgentTemplateEntity template) {
        return new Result<String>().ok(resourceService.createTemplate(SecurityUser.getUserId(), template));
    }

    @PutMapping("/templates/{id}")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Void> updateTemplate(@PathVariable String id, @RequestBody AgentTemplateEntity template) {
        resourceService.updateTemplate(SecurityUser.getUserId(), id, template);
        return new Result<Void>().ok(null);
    }

    @PutMapping("/models/{id}/enabled/{status}")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Void> setModelEnabled(@PathVariable String id, @PathVariable Integer status) {
        resourceService.setModelEnabled(SecurityUser.getUserId(), id, status);
        return new Result<Void>().ok(null);
    }

    @DeleteMapping("/voice-resources/{id}")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Void> deleteVoiceResource(@PathVariable String id) {
        resourceService.deleteVoiceResource(SecurityUser.getUserId(), id);
        return new Result<Void>().ok(null);
    }

    @DeleteMapping("/timbres/{id}")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Void> deleteTimbre(@PathVariable String id) {
        resourceService.deleteTimbre(SecurityUser.getUserId(), id);
        return new Result<Void>().ok(null);
    }

    @DeleteMapping("/firmware/{id}")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Void> deleteFirmware(@PathVariable String id) {
        firmwareService.delete(SecurityUser.getUserId(), id);
        return new Result<Void>().ok(null);
    }

    @PutMapping("/firmware/{id}")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Void> updateFirmware(@PathVariable String id, @RequestBody OtaEntity firmware) {
        firmwareService.update(SecurityUser.getUserId(), id, firmware);
        return new Result<Void>().ok(null);
    }

    @PostMapping(value = "/firmware/upload", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermissions("sys:role:superAdmin")
    public Result<String> uploadFirmware(@RequestParam("file") MultipartFile file,
            @RequestParam String firmwareName, @RequestParam String type,
            @RequestParam String version, @RequestParam(required = false) String remark) {
        return new Result<String>().ok(firmwareService.upload(SecurityUser.getUserId(), file,
                firmwareName, type, version, remark));
    }

    private CompanionPlanEntity requirePlan(String id) {
        CompanionPlanEntity plan = planDao.selectById(id);
        if (plan == null) {
            throw new RenException("套餐不存在");
        }
        return plan;
    }

    private void requireModelType(String modelType) {
        if (!MODEL_TYPES.contains(modelType)) throw new RenException("模型类别不合法");
    }

    private int requirePositivePage(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) {
                throw new RenException("page必须是正整数");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new RenException("page必须是正整数");
        }
    }

    private int requirePageLimit(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > 100) {
                throw new RenException("limit必须在1到100之间");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new RenException("limit必须是1到100之间的整数");
        }
    }

    private SafeModelVO safeModel(ModelConfigDTO value) {
        AdminResourceService.ModelUsage usage = resourceService.modelUsage(value.getId());
        return new SafeModelVO(value.getId(), value.getModelName(), value.getModelType(), value.getModelCode(),
                value.getConfigJson() == null ? null : value.getConfigJson().getStr("type"), value.getIsEnabled(),
                value.getIsDefault(), value.getDocLink(), value.getRemark(), value.getSort(),
                usage.profileUsageCount(), usage.deviceUsageCount());
    }

    private ModelConfigBodyDTO createModelBody(AdminModelCreateDTO input) {
        ModelConfigBodyDTO body = new ModelConfigBodyDTO(); body.setModelCode(input.getModelCode()); body.setModelName(input.getModelName());
        body.setIsEnabled(input.getIsEnabled()); body.setIsDefault(input.getIsDefault()); body.setDocLink(input.getDocLink());
        body.setRemark(input.getRemark()); body.setSort(input.getSort());
        if (input.getConfig() != null) body.setConfigJson(new JSONObject(input.getConfig()));
        return body;
    }

    public record SafeModelVO(String id, String name, String type, String modelCode, String providerCode, Integer enabled,
            Integer isDefault, String docLink, String remark, Integer sort, long profileUsageCount,
            long deviceUsageCount) {}
    public record ProviderOptionVO(String code, String name) {}
    @Data
    public static class AdminModelCreateDTO {
        @NotBlank private String modelType; @NotBlank private String providerCode; @NotBlank private String modelCode; @NotBlank private String modelName;
        @NotNull private Integer isEnabled; @NotNull private Integer isDefault; private String docLink; private String remark; private Integer sort;
        private Map<String, Object> config;
    }
    @Data
    public static class AdminModelUpdateDTO {
        @NotBlank private String modelName; @NotNull private Integer isEnabled; private String remark; private Integer sort;
        private Map<String, Object> config;
    }

    private CompanionPlanEntity toEntity(CompanionPlanSaveDTO dto) {
        CompanionPlanEntity plan = new CompanionPlanEntity();
        plan.setPlanCode(dto.getPlanCode());
        plan.setPlanName(dto.getPlanName());
        plan.setMaxDevices(dto.getMaxDevices());
        plan.setMaxProfiles(dto.getMaxProfiles());
        plan.setLongTermMemory(dto.getLongTermMemory());
        plan.setAdvancedVoice(dto.getAdvancedVoice());
        plan.setStatus(dto.getStatus());
        return plan;
    }

    private Long requireDeviceOwner(String deviceId) {
        DeviceEntity device = deviceService.selectById(deviceId);
        if (device == null || device.getUserId() == null) {
            throw new RenException("设备不存在或尚未绑定用户");
        }
        return device.getUserId();
    }
}
