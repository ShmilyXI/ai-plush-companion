package xiaozhi.modules.companion.capability.controller;

import java.util.List;
import java.util.Map;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import xiaozhi.common.page.PageData;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.companion.capability.dto.CapabilitySaveDTO;
import xiaozhi.modules.companion.capability.dto.CapabilitySecretSaveDTO;
import xiaozhi.modules.companion.capability.dto.DeviceSkillBindingDTO;
import xiaozhi.modules.companion.capability.service.CapabilityRoutePreviewService;
import xiaozhi.modules.companion.capability.service.CapabilityMigrationAuditService;
import xiaozhi.modules.companion.capability.service.CapabilitySecretService;
import xiaozhi.modules.companion.capability.service.CapabilityService;
import xiaozhi.modules.companion.capability.service.DeviceCapabilityService;
import xiaozhi.modules.companion.capability.service.McpCapabilityService;
import xiaozhi.modules.companion.capability.service.McpLocalConfigImportService;
import xiaozhi.modules.companion.capability.service.CapabilityRuntimeClient;
import xiaozhi.modules.companion.capability.service.SkillPackageService;
import xiaozhi.modules.companion.capability.entity.McpToolSnapshotEntity;
import xiaozhi.modules.companion.capability.vo.CapabilityRoutePreviewVO;
import xiaozhi.modules.companion.capability.vo.CapabilityMigrationAuditVO;
import xiaozhi.modules.companion.capability.vo.CapabilityVO;
import xiaozhi.modules.companion.capability.vo.DeviceSkillBindingVO;
import xiaozhi.modules.companion.capability.vo.DeviceSkillCatalogVO;
import xiaozhi.modules.companion.capability.vo.McpLocalConfigImportVO;
import xiaozhi.modules.companion.capability.vo.McpOperationVO;
import xiaozhi.modules.companion.capability.vo.SkillPackageImportVO;
import xiaozhi.modules.companion.capability.vo.SkillPackageValidationVO;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
@RequestMapping("/admin/companion/capabilities")
public class AdminCapabilityController {
    private final CapabilityService capabilities;
    private final CapabilitySecretService secrets;
    private final CapabilityRoutePreviewService routePreview;
    private final DeviceCapabilityService deviceCapabilities;
    private final McpCapabilityService mcpCapabilities;
    private final CapabilityMigrationAuditService migrationAudit;
    private final McpLocalConfigImportService mcpImport;
    private final CapabilityRuntimeClient runtimeCapabilities;
    private final SkillPackageService skillPackages;

    public AdminCapabilityController(CapabilityService capabilities, CapabilitySecretService secrets,
            CapabilityRoutePreviewService routePreview) {
        this(capabilities, secrets, routePreview, null, null, null, null, null, null);
    }

    public AdminCapabilityController(CapabilityService capabilities, CapabilitySecretService secrets,
            CapabilityRoutePreviewService routePreview, DeviceCapabilityService deviceCapabilities) {
        this(capabilities, secrets, routePreview, deviceCapabilities, null, null, null, null, null);
    }

    public AdminCapabilityController(CapabilityService capabilities, CapabilitySecretService secrets,
            CapabilityRoutePreviewService routePreview, DeviceCapabilityService deviceCapabilities,
            McpCapabilityService mcpCapabilities) {
        this(capabilities, secrets, routePreview, deviceCapabilities, mcpCapabilities, null, null, null, null);
    }

    public AdminCapabilityController(CapabilityService capabilities, CapabilitySecretService secrets,
            CapabilityRoutePreviewService routePreview, DeviceCapabilityService deviceCapabilities,
            McpCapabilityService mcpCapabilities, CapabilityMigrationAuditService migrationAudit,
            McpLocalConfigImportService mcpImport) {
        this(capabilities, secrets, routePreview, deviceCapabilities, mcpCapabilities,
                migrationAudit, mcpImport, null, null);
    }

    public AdminCapabilityController(CapabilityService capabilities, CapabilitySecretService secrets,
            CapabilityRoutePreviewService routePreview, DeviceCapabilityService deviceCapabilities,
            McpCapabilityService mcpCapabilities, CapabilityMigrationAuditService migrationAudit,
            McpLocalConfigImportService mcpImport, CapabilityRuntimeClient runtimeCapabilities) {
        this(capabilities, secrets, routePreview, deviceCapabilities, mcpCapabilities,
                migrationAudit, mcpImport, runtimeCapabilities, null);
    }

    @Autowired
    public AdminCapabilityController(CapabilityService capabilities, CapabilitySecretService secrets,
            CapabilityRoutePreviewService routePreview, DeviceCapabilityService deviceCapabilities,
            McpCapabilityService mcpCapabilities, CapabilityMigrationAuditService migrationAudit,
            McpLocalConfigImportService mcpImport, CapabilityRuntimeClient runtimeCapabilities,
            SkillPackageService skillPackages) {
        this.capabilities = capabilities;
        this.secrets = secrets;
        this.routePreview = routePreview;
        this.deviceCapabilities = deviceCapabilities;
        this.mcpCapabilities = mcpCapabilities;
        this.migrationAudit = migrationAudit;
        this.mcpImport = mcpImport;
        this.runtimeCapabilities = runtimeCapabilities;
        this.skillPackages = skillPackages;
    }

    @GetMapping
    @RequiresPermissions("sys:role:superAdmin")
    public Result<PageData<CapabilityVO>> page(@RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit) {
        return new Result<PageData<CapabilityVO>>().ok(capabilities.page(type, status, keyword, page, limit));
    }

    @GetMapping("/{id}")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<CapabilityVO> get(@PathVariable String id) {
        return new Result<CapabilityVO>().ok(capabilities.get(id));
    }

    @PostMapping
    @RequiresPermissions("sys:role:superAdmin")
    public Result<CapabilityVO> create(@RequestBody @Valid CapabilitySaveDTO request) {
        validatePluginExecutor(request);
        return new Result<CapabilityVO>().ok(capabilities.create(SecurityUser.getUserId(), request));
    }

    @PutMapping("/{id}")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<CapabilityVO> update(@PathVariable String id, @RequestBody @Valid CapabilitySaveDTO request) {
        validatePluginExecutor(request);
        return new Result<CapabilityVO>().ok(capabilities.update(SecurityUser.getUserId(), id, request));
    }

    @PostMapping("/{id}/publish")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<CapabilityVO> publish(@PathVariable String id) {
        return new Result<CapabilityVO>().ok(capabilities.publish(SecurityUser.getUserId(), id));
    }

    @PostMapping(path = "/skill-packages/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermissions("sys:role:superAdmin")
    public Result<SkillPackageImportVO> importPackage(@RequestParam("file") MultipartFile file) {
        return new Result<SkillPackageImportVO>().ok(skillPackages.inspect(file));
    }

    @PostMapping(path = "/{id}/packages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermissions("sys:role:superAdmin")
    public Result<CapabilityVO> savePackage(@PathVariable String id,
            @RequestParam("file") MultipartFile file) {
        return new Result<CapabilityVO>().ok(capabilities.savePackage(SecurityUser.getUserId(), id, file));
    }

    @GetMapping("/{id}/packages/{version}/download")
    @RequiresPermissions("sys:role:superAdmin")
    public ResponseEntity<Resource> downloadPackage(@PathVariable String id, @PathVariable int version) {
        byte[] bytes = skillPackages.download(id, version);
        String fileName = id.replaceAll("[^A-Za-z0-9._-]", "_") + "-" + version + ".skill.zip";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .body(new ByteArrayResource(bytes));
    }

    @GetMapping("/{id}/packages/draft/validation")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<SkillPackageValidationVO> packageValidation(@PathVariable String id) {
        return new Result<SkillPackageValidationVO>().ok(skillPackages.draftValidation(id));
    }

    @PutMapping("/{id}/status")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Void> status(@PathVariable String id, @RequestBody @Valid StatusRequest request) {
        capabilities.updateStatus(SecurityUser.getUserId(), id, request.status());
        return new Result<Void>().ok(null);
    }

    @DeleteMapping("/{id}")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Void> delete(@PathVariable String id) {
        capabilities.delete(SecurityUser.getUserId(), id);
        return new Result<Void>().ok(null);
    }

    @GetMapping("/{id}/secrets")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Map<String, Boolean>> secretStatus(@PathVariable String id) {
        return new Result<Map<String, Boolean>>().ok(secrets.status(id));
    }

    @PutMapping("/{id}/secrets/{name}")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Map<String, Boolean>> saveSecret(@PathVariable String id, @PathVariable String name,
            @RequestBody @Valid CapabilitySecretSaveDTO request) {
        Long operatorId = SecurityUser.getUserId();
        boolean configured = secrets.save(operatorId, id, name, request.getValue());
        if (configured && mcpCapabilities != null && name.contains(".")) {
            mcpCapabilities.bindSecretReference(operatorId, id, name);
        }
        return new Result<Map<String, Boolean>>().ok(Map.of("configured", configured));
    }

    @PostMapping("/route-preview")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<CapabilityRoutePreviewVO> routePreview(@RequestBody @Valid RoutePreviewRequest request) {
        return new Result<CapabilityRoutePreviewVO>().ok(routePreview.preview(request.deviceId(), request.utterance()));
    }

    @GetMapping("/devices/{deviceId}/skills")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<List<DeviceSkillBindingVO>> deviceSkills(@PathVariable String deviceId) {
        return new Result<List<DeviceSkillBindingVO>>().ok(
                deviceCapabilities.list(SecurityUser.getUserId(), deviceId, true));
    }

    @GetMapping("/devices/{deviceId}/skills/catalog")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<List<DeviceSkillCatalogVO>> deviceSkillCatalog(@PathVariable String deviceId) {
        return new Result<List<DeviceSkillCatalogVO>>().ok(
                deviceCapabilities.catalog(SecurityUser.getUserId(), deviceId, true));
    }

    @PutMapping("/devices/{deviceId}/skills")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<List<DeviceSkillBindingVO>> saveDeviceSkills(@PathVariable String deviceId,
            @RequestBody List<@Valid DeviceSkillBindingDTO> request) {
        return new Result<List<DeviceSkillBindingVO>>().ok(
                deviceCapabilities.save(SecurityUser.getUserId(), deviceId, request, true));
    }

    @GetMapping("/{id}/mcp/tools")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<List<McpToolSnapshotEntity>> mcpTools(@PathVariable String id) {
        return new Result<List<McpToolSnapshotEntity>>().ok(mcpCapabilities.list(id));
    }

    @PutMapping("/{id}/mcp/tools")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<List<McpToolSnapshotEntity>> approveMcpTools(@PathVariable String id,
            @RequestBody @Valid McpApprovalRequest request) {
        return new Result<List<McpToolSnapshotEntity>>().ok(
                mcpCapabilities.approve(SecurityUser.getUserId(), id, request.approvedToolIds()));
    }

    @GetMapping("/plugin-executors")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<List<CapabilityRuntimeClient.PluginExecutor>> pluginExecutors() {
        return new Result<List<CapabilityRuntimeClient.PluginExecutor>>().ok(runtimeCapabilities.pluginExecutors());
    }

    @PostMapping("/{id}/mcp/test")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<McpOperationVO> testMcp(@PathVariable String id) {
        return new Result<McpOperationVO>().ok(mcpCapabilities.testConnection(SecurityUser.getUserId(), id));
    }

    @PostMapping("/{id}/mcp/sync")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<McpOperationVO> syncMcp(@PathVariable String id) {
        return new Result<McpOperationVO>().ok(mcpCapabilities.syncFromRuntime(SecurityUser.getUserId(), id));
    }

    @GetMapping("/migration-audit")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<CapabilityMigrationAuditVO> migrationAudit() {
        return new Result<CapabilityMigrationAuditVO>().ok(migrationAudit.report());
    }

    @PostMapping("/mcp/import-local")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<McpLocalConfigImportVO> importLocalMcp(@RequestBody Map<String, Object> document) {
        return new Result<McpLocalConfigImportVO>().ok(
                mcpImport.importDocument(SecurityUser.getUserId(), document));
    }

    public record StatusRequest(@NotBlank String status) {
    }

    public record RoutePreviewRequest(@NotBlank String deviceId, @NotBlank String utterance) {
    }

    public record McpApprovalRequest(@NotNull List<String> approvedToolIds) {
    }

    private void validatePluginExecutor(CapabilitySaveDTO request) {
        if (request == null || !"PLUGIN".equalsIgnoreCase(request.getType())) return;
        if (request.getPlugin() == null || runtimeCapabilities == null) {
            throw new RenException("Plugin 执行器目录不可用");
        }
        String name = request.getPlugin().getExecutorName();
        CapabilityRuntimeClient.PluginExecutor executor = runtimeCapabilities.pluginExecutors().stream()
                .filter(item -> item.name().equals(name)).findFirst()
                .orElseThrow(() -> new RenException("Plugin 执行器未在运行时登记"));
        if (!executor.inputSchema().equals(request.getPlugin().getInputSchema())) {
            throw new RenException("Plugin 输入 Schema 与运行时登记不一致");
        }
    }
}
