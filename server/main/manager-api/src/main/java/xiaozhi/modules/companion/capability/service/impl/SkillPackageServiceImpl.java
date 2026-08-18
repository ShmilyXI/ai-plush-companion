package xiaozhi.modules.companion.capability.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.io.IOException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import cn.hutool.core.util.IdUtil;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.utils.JsonUtils;
import xiaozhi.modules.companion.capability.dao.SkillPackageDao;
import xiaozhi.modules.companion.capability.dto.CapabilitySaveDTO;
import xiaozhi.modules.companion.capability.dto.SkillPackageDraftDTO;
import xiaozhi.modules.companion.capability.dto.SkillToolDTO;
import xiaozhi.modules.companion.capability.dto.SkillTriggerDTO;
import xiaozhi.modules.companion.capability.entity.SkillPackageEntity;
import xiaozhi.modules.companion.capability.packagefile.SkillPackageBuilder;
import xiaozhi.modules.companion.capability.packagefile.SkillPackageDocument;
import xiaozhi.modules.companion.capability.packagefile.SkillPackageParser;
import xiaozhi.modules.companion.capability.packagefile.SkillPackageStore;
import xiaozhi.modules.companion.capability.packagefile.SkillPackageValidator;
import xiaozhi.modules.companion.capability.vo.SkillPackageValidationVO;
import xiaozhi.modules.companion.capability.vo.SkillPackageValidationVO.Issue;
import xiaozhi.modules.companion.capability.vo.SkillPackageVO;
import xiaozhi.modules.companion.capability.vo.SkillPackageImportVO;

@Service
@RequiredArgsConstructor
public class SkillPackageServiceImpl implements xiaozhi.modules.companion.capability.service.SkillPackageService {
    private final SkillPackageDao packageDao;
    private final SkillPackageStore packageStore;
    private final SkillPackageBuilder builder;
    private final SkillPackageParser parser;
    private final SkillPackageValidator validator;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SkillPackageVO saveOnlineDraft(Long operatorId, String capabilityId, int version,
            SkillPackageDraftDTO draft) {
        if (draft == null || draft.getManifest() == null || draft.getSkillMarkdown() == null) {
            throw new RenException("Skill 包草稿不能为空");
        }
        byte[] archive = builder.build(draft.getManifest(), draft.getSkillMarkdown(), draft.getAssets());
        SkillPackageDocument document = parser.parse(archive);
        return persist(operatorId, capabilityId, version, document, archive, "ONLINE");
    }

    @Override
    public SkillPackageImportVO inspect(MultipartFile file) {
        byte[] archive = bytes(file);
        try {
            SkillPackageDocument document = parser.parse(archive);
            SkillPackageValidationVO report = validator.validate(document, null);
            return importVO(document, report);
        } catch (RenException canonicalFailure) {
            String markdown;
            try {
                markdown = parser.parseCompatibleMarkdown(archive);
            } catch (RenException compatibilityFailure) {
                throw canonicalFailure;
            }
            SkillPackageValidationVO report = new SkillPackageValidationVO();
            report.setStatus("INCOMPLETE");
            report.getIssues().add(new Issue("WARNING", "MANIFEST_REQUIRED", "需要补齐 skill.yaml 配置"));
            SkillPackageImportVO result = new SkillPackageImportVO();
            result.setName(markdownTitle(markdown));
            result.setSkillMarkdown(markdown);
            result.setValidation(report);
            return result;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SkillPackageVO saveUploadedDraft(Long operatorId, String capabilityId, MultipartFile file) {
        byte[] archive = bytes(file);
        SkillPackageDocument document = parser.parse(archive);
        Object rawVersion = document.manifest().get("version");
        if (!(rawVersion instanceof Number version) || version.intValue() < 1) {
            throw new RenException("Skill 包版本无效");
        }
        return persist(operatorId, capabilityId, version.intValue(), document, archive, "UPLOAD");
    }

    @Override
    public byte[] download(String capabilityId, int version) {
        SkillPackageEntity row = packageDao.selectByVersion(capabilityId, version);
        if (row == null) throw new RenException("Skill 包版本不存在");
        byte[] bytes = packageStore.get(row.getStorageKey());
        SkillPackageDocument document = parser.parse(bytes);
        if (!document.sha256().equals(row.getPackageSha256()) || document.archiveSize() != row.getPackageSize()) {
            throw new RenException("Skill 包文件摘要不一致");
        }
        return bytes;
    }

    @Override
    public SkillPackageValidationVO draftValidation(String capabilityId) {
        SkillPackageEntity row = packageDao.selectDraft(capabilityId);
        if (row == null) throw new RenException("Skill 包草稿不存在");
        return JsonUtils.parseObject(row.getValidationReportJson(), SkillPackageValidationVO.class);
    }

    private SkillPackageVO persist(Long operatorId, String capabilityId, int version,
            SkillPackageDocument document, byte[] archive, String source) {
        SkillPackageValidationVO report = validator.validate(document, capabilityId);
        if ("INVALID".equals(report.getStatus())) {
            throw new RenException("Skill 包校验失败: " + report.errorCodes());
        }
        String sha256 = document.sha256();
        String storageKey = packageStore.put(capabilityId, version, sha256, archive);
        SkillPackageEntity row = new SkillPackageEntity();
        row.setId(IdUtil.fastSimpleUUID());
        row.setCapabilityId(capabilityId);
        row.setVersionNo(version);
        row.setPackageSha256(sha256);
        row.setPackageSize((long) archive.length);
        row.setStorageKey(storageKey);
        row.setManifestJson(JsonUtils.toJsonString(document.manifest()));
        row.setSkillMarkdown(document.markdown());
        row.setSourceType(source);
        row.setValidationStatus(report.getStatus());
        row.setValidationReportJson(JsonUtils.toJsonString(report));
        row.setPublished(0);
        row.setCreator(operatorId);
        row.setCreatedAt(new Date());
        try {
            if (packageDao.insert(row) != 1) throw new RenException("Skill 包草稿保存失败");
        } catch (RuntimeException exception) {
            packageStore.delete(storageKey);
            throw exception;
        }
        return toVO(row, report);
    }

    private SkillPackageImportVO importVO(SkillPackageDocument document, SkillPackageValidationVO report) {
        SkillPackageImportVO result = new SkillPackageImportVO();
        result.setCapabilityId(text(document.manifest().get("id")));
        result.setName(text(document.manifest().get("name")));
        Object version = document.manifest().get("version");
        if (version instanceof Number number) result.setVersion(number.intValue());
        result.setManifest(document.manifest());
        result.setSkillMarkdown(document.markdown());
        result.setValidation(report);
        return result;
    }

    private byte[] bytes(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new RenException("Skill 包文件不能为空");
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new RenException("Skill 包读取失败", exception);
        }
    }

    private String markdownTitle(String markdown) {
        return markdown.lines().map(String::trim).filter(line -> line.startsWith("# "))
                .map(line -> line.substring(2).trim()).filter(line -> !line.isBlank()).findFirst()
                .orElse("未命名 Skill");
    }

    private String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text.trim() : null;
    }

    @Override
    public SkillPackageVO saveOnlineDraft(Long operatorId, String capabilityId, int version,
            CapabilitySaveDTO request) {
        return saveOnlineDraft(operatorId, capabilityId, version, toDraft(capabilityId, version, request));
    }

    @Override
    public SkillPackageEntity selectDraft(String capabilityId) {
        return packageDao.selectDraft(capabilityId);
    }

    @Override
    public SkillPackageEntity selectVersion(String capabilityId, int version) {
        return packageDao.selectByVersion(capabilityId, version);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SkillPackageEntity publishDraft(Long operatorId, String capabilityId) {
        SkillPackageEntity draft = packageDao.selectDraft(capabilityId);
        if (draft == null || !"VALID".equals(draft.getValidationStatus())) {
            throw new RenException("没有可发布的有效 Skill 包草稿");
        }
        if (Integer.valueOf(1).equals(draft.getPublished())) {
            throw new RenException("已发布 Skill 包不可覆盖");
        }
        draft.setPublished(1);
        draft.setPublishedAt(new Date());
        if (packageDao.updateById(draft) != 1) throw new RenException("Skill 包发布失败");
        return draft;
    }

    private SkillPackageDraftDTO toDraft(String capabilityId, int version, CapabilitySaveDTO request) {
        SkillPackageDraftDTO draft = new SkillPackageDraftDTO();
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("id", capabilityId);
        manifest.put("name", request.getName());
        manifest.put("version", version);
        if (request.getDescription() != null) manifest.put("description", request.getDescription());
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("responseMode", request.getResponseMode());
        runtime.put("timeoutMs", request.getTimeoutMs());
        runtime.put("semanticThreshold", request.getSemanticThreshold());
        if (request.getFailureMessage() != null) runtime.put("failureMessage", request.getFailureMessage());
        manifest.put("runtime", runtime);
        List<Map<String, Object>> triggers = new ArrayList<>();
        for (SkillTriggerDTO trigger : request.getTriggers()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("type", trigger.getType());
            value.put("value", trigger.getValue());
            value.put("priority", trigger.getPriority());
            value.put("caseSensitive", trigger.getCaseSensitive());
            value.put("enabled", trigger.getEnabled());
            triggers.add(value);
        }
        manifest.put("triggers", triggers);
        List<Map<String, Object>> tools = new ArrayList<>();
        for (SkillToolDTO tool : request.getTools()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("type", tool.getToolType());
            value.put("ref", tool.getToolRefId());
            value.put("name", tool.getToolName());
            value.put("required", Boolean.TRUE.equals(tool.getRequired()));
            if (tool.getAlias() != null) value.put("alias", tool.getAlias());
            if (tool.getPurpose() != null) value.put("purpose", tool.getPurpose());
            if (tool.getDefaultParams() != null) value.put("defaults", tool.getDefaultParams());
            tools.add(value);
        }
        manifest.put("tools", tools);
        manifest.put("secretRefs", List.of());
        draft.setManifest(manifest);
        draft.setSkillMarkdown(request.getExecutionPrompt());
        return draft;
    }

    private SkillPackageVO toVO(SkillPackageEntity row, SkillPackageValidationVO report) {
        SkillPackageVO result = new SkillPackageVO();
        result.setId(row.getId());
        result.setCapabilityId(row.getCapabilityId());
        result.setVersion(row.getVersionNo());
        result.setPackageSha256(row.getPackageSha256());
        result.setPackageSize(row.getPackageSize());
        result.setSource(row.getSourceType());
        result.setValidationStatus(row.getValidationStatus());
        result.setValidationIssues(report.getIssues().stream().map(this::toIssue).toList());
        result.setPublished(Integer.valueOf(1).equals(row.getPublished()));
        result.setCreatedAt(row.getCreatedAt());
        result.setPublishedAt(row.getPublishedAt());
        return result;
    }

    private SkillPackageVO.ValidationIssueVO toIssue(Issue issue) {
        SkillPackageVO.ValidationIssueVO result = new SkillPackageVO.ValidationIssueVO();
        result.setLevel(issue.getLevel());
        result.setCode(issue.getCode());
        result.setMessage(issue.getMessage());
        return result;
    }
}
