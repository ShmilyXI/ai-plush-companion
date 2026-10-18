package zixuan.modules.companion.controller;

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

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import zixuan.common.utils.Result;
import zixuan.modules.companion.model.dto.CompanionPrivateModelSaveDTO;
import zixuan.modules.companion.model.dto.CompanionModelCopyDTO;
import zixuan.modules.companion.model.dto.CompanionGlobalModelCredentialSaveDTO;
import zixuan.modules.companion.model.service.CompanionGlobalModelCredentialService;
import zixuan.modules.companion.model.service.CompanionModelCatalogService;
import zixuan.modules.companion.model.service.CompanionModelTemplateService;
import zixuan.modules.companion.model.service.CompanionPrivateModelService;
import zixuan.modules.companion.model.vo.CompanionModelCatalogItemVO;
import zixuan.modules.companion.model.vo.CompanionGlobalModelCredentialVO;
import zixuan.modules.companion.model.vo.CompanionModelProviderTemplateVO;
import zixuan.modules.companion.model.vo.CompanionModelTestVO;
import zixuan.modules.companion.model.vo.CompanionPrivateModelVO;
import zixuan.modules.security.user.SecurityUser;

@RestController
@AllArgsConstructor
@RequestMapping("/companion/models")
@RequiresPermissions("sys:role:normal")
public class CompanionPrivateModelController {
    private final CompanionPrivateModelService service;
    private final CompanionModelCatalogService catalogService;
    private final CompanionModelTemplateService templateService;
    private final CompanionGlobalModelCredentialService globalCredentials;

    @GetMapping public Result<List<CompanionPrivateModelVO>> list(@RequestParam String modelType) { return new Result<List<CompanionPrivateModelVO>>().ok(service.list(SecurityUser.getUserId(), modelType)); }
    @GetMapping("/catalog") public Result<List<CompanionModelCatalogItemVO>> catalog(@RequestParam String modelType,
            @RequestParam(defaultValue = "management") String view) {
        List<CompanionModelCatalogItemVO> result = switch (view) {
            case "management" -> catalogService.management(SecurityUser.getUserId(), modelType);
            case "selection" -> catalogService.selection(SecurityUser.getUserId(), modelType);
            default -> throw new zixuan.common.exception.RenException("模型目录视图无效");
        };
        return new Result<List<CompanionModelCatalogItemVO>>().ok(result);
    }
    @GetMapping("/templates") public Result<List<CompanionModelProviderTemplateVO>> templates(@RequestParam String modelType) {
        return new Result<List<CompanionModelProviderTemplateVO>>().ok(templateService.list(modelType));
    }
    @PostMapping("/copy") public Result<CompanionPrivateModelVO> copy(@RequestBody @Valid CompanionModelCopyDTO dto) {
        return new Result<CompanionPrivateModelVO>().ok(catalogService.copy(SecurityUser.getUserId(), dto.getReference(), dto.getName()));
    }
    @GetMapping("/global/{id}/config")
    public Result<CompanionGlobalModelCredentialVO> getGlobalConfig(@PathVariable String id) {
        return new Result<CompanionGlobalModelCredentialVO>().ok(
                globalCredentials.get(SecurityUser.getUserId(), id));
    }
    @PutMapping("/global/{id}/config")
    public Result<CompanionGlobalModelCredentialVO> saveGlobalConfig(@PathVariable String id,
            @RequestBody @Valid CompanionGlobalModelCredentialSaveDTO dto) {
        return new Result<CompanionGlobalModelCredentialVO>().ok(
                globalCredentials.save(SecurityUser.getUserId(), id, dto));
    }
    @PostMapping("/global/{id}/test")
    public Result<CompanionModelTestVO> testGlobalConfig(@PathVariable String id,
            @RequestBody @Valid CompanionGlobalModelCredentialSaveDTO dto) {
        return new Result<CompanionModelTestVO>().ok(
                globalCredentials.test(SecurityUser.getUserId(), id, dto));
    }
    @GetMapping("/{id}") public Result<CompanionPrivateModelVO> get(@PathVariable String id) { return new Result<CompanionPrivateModelVO>().ok(service.get(SecurityUser.getUserId(), id)); }
    @PostMapping public Result<CompanionPrivateModelVO> create(@RequestBody @Valid CompanionPrivateModelSaveDTO dto) { return new Result<CompanionPrivateModelVO>().ok(service.create(SecurityUser.getUserId(), dto)); }
    @PutMapping("/{id}") public Result<CompanionPrivateModelVO> update(@PathVariable String id, @RequestBody @Valid CompanionPrivateModelSaveDTO dto) { return new Result<CompanionPrivateModelVO>().ok(service.update(SecurityUser.getUserId(), id, dto)); }
    @DeleteMapping("/{id}") public Result<Void> delete(@PathVariable String id) { service.delete(SecurityUser.getUserId(), id); return new Result<Void>().ok(null); }
    @PostMapping("/{id}/test") public Result<CompanionModelTestVO> testSaved(@PathVariable String id, @RequestBody @Valid CompanionPrivateModelSaveDTO dto) { return new Result<CompanionModelTestVO>().ok(service.test(SecurityUser.getUserId(), id, dto)); }
    @PostMapping("/test") public Result<CompanionModelTestVO> testNew(@RequestBody @Valid CompanionPrivateModelSaveDTO dto) { return new Result<CompanionModelTestVO>().ok(service.test(SecurityUser.getUserId(), null, dto)); }
}
