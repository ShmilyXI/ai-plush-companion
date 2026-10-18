package zixuan.modules.companion.model.service.impl;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.AllArgsConstructor;
import zixuan.common.exception.RenException;
import zixuan.modules.companion.model.dao.CompanionModelPresetMetaDao;
import zixuan.modules.companion.model.entity.CompanionModelPresetMetaEntity;
import zixuan.modules.companion.model.service.CompanionModelPresetService;
import zixuan.modules.companion.model.vo.CompanionModelPresetVO;
import zixuan.modules.companion.model.vo.CompanionModelProviderFieldVO;

@Service
@AllArgsConstructor
public class CompanionModelPresetServiceImpl implements CompanionModelPresetService {
    private static final Set<String> REQUIREMENTS = Set.of("required", "not_required");
    private final CompanionModelPresetMetaDao dao;

    @Override
    public CompanionModelPresetVO get(String globalModelId) {
        if (StringUtils.isBlank(globalModelId)) return fallback(globalModelId);
        CompanionModelPresetMetaEntity row = dao.selectById(globalModelId);
        if (row == null) return fallback(globalModelId);
        try {
            return parse(row);
        } catch (RuntimeException exception) {
            if (exception instanceof RenException renException) throw renException;
            throw new RenException("系统模型厂商元数据无效", exception);
        }
    }

    @Override
    public Map<String, CompanionModelPresetVO> getAll(Collection<String> globalModelIds) {
        Map<String, CompanionModelPresetVO> result = new LinkedHashMap<>();
        if (globalModelIds == null) return result;
        for (String id : new LinkedHashSet<>(globalModelIds)) {
            if (StringUtils.isNotBlank(id)) result.put(id, get(id));
        }
        return result;
    }

    @Override
    public Set<String> credentialKeys(String globalModelId) {
        Set<String> result = new LinkedHashSet<>();
        for (CompanionModelProviderFieldVO field : get(globalModelId).getCredentialFields()) {
            result.add(field.getKey());
        }
        return result;
    }

    private CompanionModelPresetVO parse(CompanionModelPresetMetaEntity row) {
        if (StringUtils.isAnyBlank(row.getGlobalModelId(), row.getVendorCode(), row.getVendorName(),
                row.getProtocol(), row.getCredentialRequirement())
                || !REQUIREMENTS.contains(row.getCredentialRequirement())) {
            throw new RenException("系统模型厂商元数据无效");
        }
        validateUrl(row.getDefaultApiUrl(), true);
        validateUrl(row.getKeyUrl(), false);
        validateUrl(row.getDocsUrl(), false);

        CompanionModelPresetVO result = new CompanionModelPresetVO();
        result.setGlobalModelId(row.getGlobalModelId());
        result.setVendorCode(row.getVendorCode());
        result.setVendorName(row.getVendorName());
        result.setProtocol(row.getProtocol());
        result.setDefaultApiUrl(StringUtils.trimToNull(row.getDefaultApiUrl()));
        result.setCredentialRequirement(row.getCredentialRequirement());
        result.setCredentialFields(parseFields(row.getCredentialFieldsJson()));
        result.setKeyUrl(StringUtils.trimToNull(row.getKeyUrl()));
        result.setDocsUrl(StringUtils.trimToNull(row.getDocsUrl()));
        result.setSetupGuide(parseGuide(row.getSetupGuideJson()));
        return result;
    }

    private List<CompanionModelProviderFieldVO> parseFields(String json) {
        JSONArray fields = JSONUtil.parseArray(StringUtils.defaultIfBlank(json, "[]"));
        List<CompanionModelProviderFieldVO> result = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (Object value : fields) {
            JSONObject field = value instanceof JSONObject object ? object : new JSONObject(value);
            String key = StringUtils.trimToNull(field.getStr("key"));
            String label = StringUtils.trimToNull(field.getStr("label"));
            String rawType = StringUtils.trimToNull(field.getStr("type"));
            if (key == null || label == null || rawType == null || !keys.add(key)) {
                throw new RenException("系统模型凭据字段无效");
            }
            CompanionModelProviderFieldVO parsed = new CompanionModelProviderFieldVO();
            parsed.setKey(key);
            parsed.setLabel(label);
            parsed.setType(normalizeType(rawType));
            parsed.setRequired(Boolean.TRUE.equals(field.getBool("required")));
            parsed.setSecret(Boolean.TRUE.equals(field.getBool("secret")) || "password".equalsIgnoreCase(rawType));
            JSONArray options = field.getJSONArray("options");
            parsed.setOptions(options == null ? List.of() : new ArrayList<>(options));
            parsed.setDefaultValue(field.get("default"));
            result.add(parsed);
        }
        return result;
    }

    private List<String> parseGuide(String json) {
        JSONArray guide = JSONUtil.parseArray(StringUtils.defaultIfBlank(json, "[]"));
        List<String> result = new ArrayList<>();
        for (Object value : guide) {
            if (!(value instanceof String text) || StringUtils.isBlank(text)) {
                throw new RenException("系统模型配置教程无效");
            }
            result.add(text.trim());
        }
        return result;
    }

    private String normalizeType(String rawType) {
        return switch (rawType.toLowerCase(Locale.ROOT)) {
            case "string", "password" -> "string";
            case "int", "float", "number" -> "number";
            case "bool", "boolean" -> "boolean";
            case "dict" -> "dict";
            default -> throw new RenException("系统模型凭据字段类型无效");
        };
    }

    private void validateUrl(String value, boolean allowWebSocket) {
        if (StringUtils.isBlank(value)) return;
        try {
            URI uri = URI.create(value.trim());
            String scheme = StringUtils.lowerCase(uri.getScheme());
            boolean allowed = "http".equals(scheme) || "https".equals(scheme)
                    || (allowWebSocket && ("ws".equals(scheme) || "wss".equals(scheme)));
            if (!allowed || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new RenException("系统模型链接地址无效");
            }
        } catch (IllegalArgumentException exception) {
            throw new RenException("系统模型链接地址无效", exception);
        }
    }

    private CompanionModelPresetVO fallback(String globalModelId) {
        CompanionModelPresetVO result = new CompanionModelPresetVO();
        result.setGlobalModelId(globalModelId);
        result.setCredentialRequirement("unknown");
        result.setCredentialFields(List.of());
        result.setSetupGuide(List.of());
        return result;
    }
}
