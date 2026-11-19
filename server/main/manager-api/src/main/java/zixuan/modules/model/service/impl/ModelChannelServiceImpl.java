package zixuan.modules.model.service.impl;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import zixuan.common.exception.RenException;
import zixuan.common.utils.SensitiveDataUtils;
import zixuan.modules.companion.model.service.CompanionModelSecretService;
import zixuan.modules.companion.model.vo.CompanionModelTestVO;
import zixuan.modules.model.dao.ModelChannelDao;
import zixuan.modules.model.dao.ModelConfigDao;
import zixuan.modules.model.dto.ModelChannelAddModelsDTO;
import zixuan.modules.model.dto.ModelChannelDTO;
import zixuan.modules.model.dto.ModelChannelModelDTO;
import zixuan.modules.model.dto.ModelChannelModelUpdateDTO;
import zixuan.modules.model.dto.ModelChannelSaveDTO;
import zixuan.modules.model.dto.ModelChannelUpstreamModelDTO;
import zixuan.modules.model.dto.ModelProviderDTO;
import zixuan.modules.model.entity.ModelChannelEntity;
import zixuan.modules.model.entity.ModelConfigEntity;
import zixuan.modules.model.service.ModelChannelService;
import zixuan.modules.model.service.ModelChannelUpstreamClient;
import zixuan.modules.model.service.ModelConfigService;
import zixuan.modules.model.service.ModelProviderService;

@Service
@RequiredArgsConstructor
public class ModelChannelServiceImpl implements ModelChannelService {
    /** 连接目前只管理对话类模型。语音识别和合成没有统一的模型列表接口，仍按单个模型配置。 */
    private static final List<String> MODEL_TYPES = List.of("LLM", "VLLM", "Embedding");
    private static final Set<String> PROVIDERS = Set.of("openai", "ollama");
    private static final String TYPE_KEY = "type";
    private static final String BASE_URL_KEY = "base_url";
    private static final String API_KEY = "api_key";
    private static final String MODEL_NAME_KEY = "model_name";
    /** 由连接和模型代号决定、不能当作参数修改的配置项。 */
    private static final Set<String> MANAGED_KEYS = Set.of(TYPE_KEY, BASE_URL_KEY, API_KEY, MODEL_NAME_KEY);
    /** OpenAI 客户端要求密钥非空，本地服务没有密钥时用这个占位值。 */
    private static final String KEYLESS_API_KEY = "EMPTY";
    private static final int MAX_NAME_LENGTH = 64;
    private static final int MAX_MODEL_NAME_LENGTH = 50;
    private static final int MAX_MODEL_ID_LENGTH = 200;
    private static final int MAX_MODELS_PER_REQUEST = 100;

    private final ModelChannelDao channelDao;
    private final ModelConfigDao modelConfigDao;
    private final ModelConfigService modelConfigService;
    private final ModelProviderService modelProviderService;
    private final CompanionModelSecretService secrets;
    private final ModelChannelUpstreamClient upstream;

    @Override
    public List<ModelChannelDTO> list() {
        List<ModelChannelEntity> channels = channelDao.selectList(
                new QueryWrapper<ModelChannelEntity>().orderByAsc("sort", "create_date"));
        Map<String, Integer> counts = new HashMap<>();
        for (ModelConfigEntity model : linkedModels(null)) {
            counts.merge(model.getChannelId(), 1, Integer::sum);
        }
        return channels.stream()
                .map(channel -> toDTO(channel, counts.getOrDefault(channel.getId(), 0)))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModelChannelDTO create(ModelChannelSaveDTO dto) {
        requireBody(dto);
        ModelChannelEntity channel = new ModelChannelEntity();
        channel.setId(newId());
        channel.setName(requireName(dto.getName(), null));
        channel.setVendorCode(StringUtils.trimToNull(dto.getVendorCode()));
        channel.setProviderCode(requireProvider(dto.getProviderCode()));
        channel.setBaseUrl(requireBaseUrl(dto.getBaseUrl()));
        channel.setSecretConfigCiphertext(encryptKey(StringUtils.trimToNull(dto.getApiKey())));
        channel.setRemark(StringUtils.trimToNull(dto.getRemark()));
        channel.setSort(requireSort(dto.getSort(), 0));
        if (channelDao.insert(channel) != 1) {
            throw new RenException("连接保存失败");
        }
        return toDTO(channel, 0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModelChannelDTO update(String id, ModelChannelSaveDTO dto) {
        requireBody(dto);
        ModelChannelEntity channel = requireChannel(id);
        if (StringUtils.isNotBlank(dto.getProviderCode())
                && !channel.getProviderCode().equalsIgnoreCase(dto.getProviderCode().trim())) {
            throw new RenException("连接的接口协议创建后不能修改");
        }
        String apiKey = apiKey(channel);
        if (Boolean.TRUE.equals(dto.getClearApiKey())) {
            apiKey = null;
        } else if (StringUtils.isNotBlank(dto.getApiKey())) {
            apiKey = dto.getApiKey().trim();
        }
        channel.setName(requireName(dto.getName(), id));
        channel.setVendorCode(StringUtils.trimToNull(dto.getVendorCode()));
        channel.setBaseUrl(requireBaseUrl(dto.getBaseUrl()));
        channel.setSecretConfigCiphertext(encryptKey(apiKey));
        channel.setRemark(StringUtils.trimToNull(dto.getRemark()));
        channel.setSort(requireSort(dto.getSort(), channel.getSort() == null ? 0 : channel.getSort()));
        if (channelDao.updateById(channel) != 1) {
            throw new RenException("连接保存失败");
        }
        // 连接是地址和密钥的唯一来源：改完连接后把新值写进它名下每个模型的配置，
        // 这样运行时和其他读取 config_json 的地方都不用知道连接的存在。
        List<ModelConfigEntity> linked = linkedModels(id);
        for (ModelConfigEntity model : linked) {
            JSONObject config = copyConfig(model);
            applyChannel(config, channel, apiKey);
            saveConfig(model.getId(), null, config);
        }
        return toDTO(channel, linked.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        requireChannel(id);
        if (!linkedModels(id).isEmpty()) {
            throw new RenException("请先移除这个连接下的模型");
        }
        channelDao.deleteById(id);
    }

    @Override
    public CompanionModelTestVO test(String id, ModelChannelSaveDTO dto) {
        ModelChannelEntity saved = StringUtils.isBlank(id) ? null : requireChannel(id);
        ModelChannelSaveDTO body = dto == null ? new ModelChannelSaveDTO() : dto;
        String providerCode = saved == null ? requireProvider(body.getProviderCode()) : saved.getProviderCode();
        String baseUrl = StringUtils.isNotBlank(body.getBaseUrl()) || saved == null
                ? requireBaseUrl(body.getBaseUrl())
                : saved.getBaseUrl();
        String apiKey = saved == null ? null : apiKey(saved);
        if (Boolean.TRUE.equals(body.getClearApiKey())) {
            apiKey = null;
        } else if (StringUtils.isNotBlank(body.getApiKey())) {
            apiKey = body.getApiKey().trim();
        }
        long started = System.nanoTime();
        try {
            int count = upstream.listModels(providerCode, baseUrl, apiKey).size();
            return new CompanionModelTestVO(true, elapsed(started), "连接成功，发现 " + count + " 个模型");
        } catch (RenException exception) {
            return new CompanionModelTestVO(false, elapsed(started), exception.getMsg());
        }
    }

    @Override
    public List<ModelChannelUpstreamModelDTO> upstreamModels(String id) {
        ModelChannelEntity channel = requireChannel(id);
        List<String> ids = upstream.listModels(channel.getProviderCode(), channel.getBaseUrl(), apiKey(channel));
        Map<String, List<String>> added = new HashMap<>();
        for (ModelConfigEntity model : linkedModels(id)) {
            String modelId = upstreamModelId(model);
            if (modelId != null) {
                added.computeIfAbsent(modelId, key -> new ArrayList<>()).add(model.getModelType());
            }
        }
        return ids.stream()
                .map(modelId -> new ModelChannelUpstreamModelDTO(modelId, added.getOrDefault(modelId, List.of())))
                .toList();
    }

    @Override
    public List<ModelChannelModelDTO> models() {
        return linkedModels(null).stream()
                .sorted(Comparator
                        .comparingInt((ModelConfigEntity model) -> typeOrder(model.getModelType()))
                        .thenComparing(model -> StringUtils.defaultString(model.getModelName())))
                .map(this::toModelDTO)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<ModelChannelModelDTO> addModels(String id, ModelChannelAddModelsDTO dto) {
        ModelChannelEntity channel = requireChannel(id);
        List<ModelChannelAddModelsDTO.Item> items = dto == null ? null : dto.getModels();
        if (items == null || items.isEmpty()) {
            throw new RenException("请选择要添加的模型");
        }
        if (items.size() > MAX_MODELS_PER_REQUEST) {
            throw new RenException("一次最多添加 " + MAX_MODELS_PER_REQUEST + " 个模型");
        }
        String apiKey = apiKey(channel);
        Set<String> existing = new HashSet<>();
        for (ModelConfigEntity model : linkedModels(id)) {
            existing.add(modelKey(model.getModelType(), upstreamModelId(model)));
        }
        List<ModelChannelModelDTO> created = new ArrayList<>();
        for (ModelChannelAddModelsDTO.Item item : items) {
            if (item == null) {
                throw new RenException("请选择要添加的模型");
            }
            String modelType = requireModelType(item.getModelType());
            String modelId = StringUtils.trimToNull(item.getModelId());
            if (modelId == null) {
                throw new RenException("模型代号不能为空");
            }
            if (modelId.length() > MAX_MODEL_ID_LENGTH) {
                throw new RenException("模型代号不能超过 " + MAX_MODEL_ID_LENGTH + " 个字符");
            }
            JSONObject config = providerDefaults(modelType, channel.getProviderCode());
            if (!existing.add(modelKey(modelType, modelId))) {
                continue;
            }
            config.set(MODEL_NAME_KEY, modelId);
            applyChannel(config, channel, apiKey);

            ModelConfigEntity model = new ModelConfigEntity();
            model.setId(newId());
            model.setModelType(modelType);
            model.setModelCode(StringUtils.abbreviate(modelId, MAX_MODEL_NAME_LENGTH));
            model.setModelName(displayName(item.getModelName(), channel.getName(), modelId));
            model.setChannelId(channel.getId());
            model.setIsDefault(0);
            model.setIsEnabled(1);
            model.setSort(0);
            model.setConfigJson(config);
            if (modelConfigDao.insert(model) != 1) {
                throw new RenException("模型添加失败");
            }
            created.add(toModelDTO(model));
        }
        return created;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModelChannelModelDTO updateModel(String modelId, ModelChannelModelUpdateDTO dto) {
        if (dto == null) {
            throw new RenException("缺少模型内容");
        }
        ModelConfigEntity model = StringUtils.isBlank(modelId) ? null : modelConfigDao.selectById(modelId);
        if (model == null || StringUtils.isBlank(model.getChannelId())) {
            throw new RenException("模型不存在");
        }
        String name = StringUtils.trimToNull(dto.getModelName());
        if (name == null) {
            throw new RenException("显示名称不能为空");
        }
        if (name.length() > MAX_MODEL_NAME_LENGTH) {
            throw new RenException("显示名称不能超过 " + MAX_MODEL_NAME_LENGTH + " 个字");
        }
        JSONObject config = copyConfig(model);
        if (dto.getParams() != null && !dto.getParams().isEmpty()) {
            Set<String> allowed = parameterKeys(model.getModelType(), config.getStr(TYPE_KEY));
            for (Map.Entry<String, Object> entry : dto.getParams().entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (!allowed.contains(key)) {
                    throw new RenException("不支持的模型参数：" + key);
                }
                if (value == null) {
                    config.remove(key);
                } else if (value instanceof Number || value instanceof Boolean || value instanceof String) {
                    config.set(key, value);
                } else {
                    throw new RenException("模型参数格式无效：" + key);
                }
            }
        }
        saveConfig(model.getId(), name, config);
        model.setModelName(name);
        model.setConfigJson(config);
        return toModelDTO(model);
    }

    private void saveConfig(String modelId, String modelName, JSONObject config) {
        ModelConfigEntity patch = new ModelConfigEntity();
        patch.setId(modelId);
        patch.setModelName(modelName);
        patch.setConfigJson(config);
        if (modelConfigDao.updateById(patch) != 1) {
            throw new RenException("模型保存失败");
        }
        modelConfigService.evictModelCache(modelId);
    }

    private void applyChannel(JSONObject config, ModelChannelEntity channel, String apiKey) {
        config.set(TYPE_KEY, channel.getProviderCode());
        config.set(BASE_URL_KEY, channel.getBaseUrl());
        if ("ollama".equals(channel.getProviderCode())) {
            config.remove(API_KEY);
        } else {
            config.set(API_KEY, apiKey == null ? KEYLESS_API_KEY : apiKey);
        }
    }

    private List<ModelConfigEntity> linkedModels(String channelId) {
        QueryWrapper<ModelConfigEntity> query = new QueryWrapper<>();
        if (channelId == null) {
            query.isNotNull("channel_id");
        } else {
            query.eq("channel_id", channelId);
        }
        List<ModelConfigEntity> models = modelConfigDao.selectList(query);
        return models == null ? List.of() : models;
    }

    private JSONObject providerDefaults(String modelType, String providerCode) {
        JSONObject config = new JSONObject();
        for (Object value : providerFields(modelType, providerCode)) {
            JSONObject field = value instanceof JSONObject object ? object : new JSONObject(value);
            String key = field.getStr("key");
            Object defaultValue = field.get("default");
            if (isParameterKey(key) && defaultValue != null) {
                config.set(key, defaultValue);
            }
        }
        return config;
    }

    private Set<String> parameterKeys(String modelType, String providerCode) {
        Set<String> keys = new LinkedHashSet<>();
        for (Object value : providerFields(modelType, providerCode)) {
            JSONObject field = value instanceof JSONObject object ? object : new JSONObject(value);
            String key = field.getStr("key");
            if (isParameterKey(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private JSONArray providerFields(String modelType, String providerCode) {
        List<ModelProviderDTO> providers = StringUtils.isBlank(providerCode)
                ? List.of()
                : modelProviderService.getList(modelType, providerCode);
        if (providers == null || providers.isEmpty()) {
            throw new RenException("这个连接的接口协议不支持添加" + typeLabel(modelType));
        }
        return JSONUtil.parseArray(StringUtils.defaultIfBlank(providers.get(0).getFields(), "[]"));
    }

    private boolean isParameterKey(String key) {
        return StringUtils.isNotBlank(key) && !MANAGED_KEYS.contains(key)
                && !SensitiveDataUtils.isSensitiveField(key);
    }

    private ModelChannelDTO toDTO(ModelChannelEntity channel, int modelCount) {
        ModelChannelDTO dto = new ModelChannelDTO();
        dto.setId(channel.getId());
        dto.setName(channel.getName());
        dto.setVendorCode(channel.getVendorCode());
        dto.setProviderCode(channel.getProviderCode());
        dto.setBaseUrl(channel.getBaseUrl());
        dto.setCredentialConfigured(apiKey(channel) != null);
        dto.setModelCount(modelCount);
        dto.setRemark(channel.getRemark());
        dto.setSort(channel.getSort());
        return dto;
    }

    private ModelChannelModelDTO toModelDTO(ModelConfigEntity model) {
        ModelChannelModelDTO dto = new ModelChannelModelDTO();
        dto.setId(model.getId());
        dto.setChannelId(model.getChannelId());
        dto.setModelType(model.getModelType());
        dto.setModelId(upstreamModelId(model));
        dto.setModelName(model.getModelName());
        dto.setIsEnabled(model.getIsEnabled());
        dto.setIsDefault(model.getIsDefault());
        Map<String, Object> params = new LinkedHashMap<>();
        if (model.getConfigJson() != null) {
            model.getConfigJson().forEach((key, value) -> {
                if (isParameterKey(key) && value != null) {
                    params.put(key, value);
                }
            });
        }
        dto.setParams(params);
        return dto;
    }

    private String upstreamModelId(ModelConfigEntity model) {
        return model.getConfigJson() == null ? null
                : StringUtils.trimToNull(model.getConfigJson().getStr(MODEL_NAME_KEY));
    }

    private JSONObject copyConfig(ModelConfigEntity model) {
        return model.getConfigJson() == null ? new JSONObject() : new JSONObject(model.getConfigJson());
    }

    private String displayName(String requested, String channelName, String modelId) {
        String name = StringUtils.trimToNull(requested);
        if (name != null) {
            if (name.length() > MAX_MODEL_NAME_LENGTH) {
                throw new RenException("显示名称不能超过 " + MAX_MODEL_NAME_LENGTH + " 个字");
            }
            return name;
        }
        String combined = channelName + " / " + modelId;
        if (combined.length() <= MAX_MODEL_NAME_LENGTH) {
            return combined;
        }
        return StringUtils.abbreviate(modelId, MAX_MODEL_NAME_LENGTH);
    }

    private String apiKey(ModelChannelEntity channel) {
        Object value = secrets.decryptMap(channel.getSecretConfigCiphertext()).get(API_KEY);
        return value == null ? null : StringUtils.trimToNull(value.toString());
    }

    private String encryptKey(String apiKey) {
        return apiKey == null ? null : secrets.encryptMap(Map.of(API_KEY, apiKey));
    }

    private ModelChannelEntity requireChannel(String id) {
        ModelChannelEntity channel = StringUtils.isBlank(id) ? null : channelDao.selectById(id);
        if (channel == null) {
            throw new RenException("连接不存在");
        }
        return channel;
    }

    private void requireBody(ModelChannelSaveDTO dto) {
        if (dto == null) {
            throw new RenException("缺少连接内容");
        }
    }

    private String requireName(String value, String excludeId) {
        String name = StringUtils.trimToNull(value);
        if (name == null) {
            throw new RenException("连接名称不能为空");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new RenException("连接名称不能超过 " + MAX_NAME_LENGTH + " 个字");
        }
        QueryWrapper<ModelChannelEntity> query = new QueryWrapper<ModelChannelEntity>().eq("name", name);
        if (excludeId != null) {
            query.ne("id", excludeId);
        }
        Long count = channelDao.selectCount(query);
        if (count != null && count > 0) {
            throw new RenException("已经有同名的连接");
        }
        return name;
    }

    private String requireProvider(String value) {
        String provider = StringUtils.lowerCase(StringUtils.trimToNull(value));
        if (provider == null || !PROVIDERS.contains(provider)) {
            throw new RenException("不支持的接口协议");
        }
        return provider;
    }

    private String requireBaseUrl(String value) {
        String url = StringUtils.trimToEmpty(value);
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.isEmpty()) {
            throw new RenException("接口地址不能为空");
        }
        if (url.length() > 512) {
            throw new RenException("接口地址过长");
        }
        try {
            URI uri = URI.create(url);
            String scheme = StringUtils.lowerCase(uri.getScheme());
            if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new RenException("接口地址无效，应为 http 或 https 开头的地址");
            }
        } catch (IllegalArgumentException exception) {
            throw new RenException("接口地址无效，应为 http 或 https 开头的地址");
        }
        return url;
    }

    private int requireSort(Integer value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value < 0) {
            throw new RenException("排序不能为负数");
        }
        return value;
    }

    private String requireModelType(String value) {
        for (String type : MODEL_TYPES) {
            if (type.equalsIgnoreCase(StringUtils.trimToEmpty(value))) {
                return type;
            }
        }
        throw new RenException("连接下只能添加对话、视觉或向量模型");
    }

    private int typeOrder(String modelType) {
        int index = MODEL_TYPES.indexOf(modelType);
        return index < 0 ? MODEL_TYPES.size() : index;
    }

    private String typeLabel(String modelType) {
        return switch (modelType) {
            case "LLM" -> "对话模型";
            case "VLLM" -> "视觉模型";
            case "Embedding" -> "向量模型";
            default -> "这类模型";
        };
    }

    private String modelKey(String modelType, String modelId) {
        return modelType + "\n" + modelId;
    }

    private String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private int elapsed(long started) {
        return Math.toIntExact(Duration.ofNanos(System.nanoTime() - started).toMillis());
    }
}
