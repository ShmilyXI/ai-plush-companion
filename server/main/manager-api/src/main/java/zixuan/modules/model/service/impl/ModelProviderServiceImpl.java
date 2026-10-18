package zixuan.modules.model.service.impl;

import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;

import cn.hutool.json.JSONArray;
import lombok.AllArgsConstructor;
import zixuan.common.constant.Constant;
import zixuan.common.exception.ErrorCode;
import zixuan.common.exception.RenException;
import zixuan.common.page.PageData;
import zixuan.common.service.impl.BaseServiceImpl;
import zixuan.common.user.UserDetail;
import zixuan.common.utils.ConvertUtils;
import zixuan.common.utils.JsonUtils;
import zixuan.modules.companion.capability.dto.PluginDefinitionDTO;
import zixuan.modules.companion.capability.service.CapabilityService;
import zixuan.modules.companion.capability.vo.CapabilityVO;
import zixuan.modules.knowledge.dao.KnowledgeBaseDao;
import zixuan.modules.knowledge.entity.KnowledgeBaseEntity;
import zixuan.modules.model.dao.ModelProviderDao;
import zixuan.modules.model.dto.ModelProviderDTO;
import zixuan.modules.model.entity.ModelProviderEntity;
import zixuan.modules.model.service.ModelProviderService;
import zixuan.modules.security.user.SecurityUser;

@Service
@AllArgsConstructor
public class ModelProviderServiceImpl extends BaseServiceImpl<ModelProviderDao, ModelProviderEntity>
        implements ModelProviderService {

    private static final List<String> VISIBLE_TTS_PROVIDER_CODES = List.of(
            "edge", "huoshan_double_stream", "alibl_stream");

    private final ModelProviderDao modelProviderDao;
    private final KnowledgeBaseDao knowledgeBaseDao;
    private final CapabilityService capabilityService;

    @Override
    public List<ModelProviderDTO> getPluginList() {
        List<ModelProviderDTO> resultList = new java.util.ArrayList<>(projectPlugins());

        // 2. 获取当前用户的知识库列表并追加到结果中
        UserDetail userDetail = SecurityUser.getUser();
        if (userDetail != null && userDetail.getId() != null) {
            // 查询当前用户的知识库
            LambdaQueryWrapper<KnowledgeBaseEntity> kbQueryWrapper = new LambdaQueryWrapper<>();
            kbQueryWrapper.eq(KnowledgeBaseEntity::getCreator, userDetail.getId());
            kbQueryWrapper.eq(KnowledgeBaseEntity::getStatus, 1); // 只获取启用状态的知识库
            List<KnowledgeBaseEntity> knowledgeBases = knowledgeBaseDao.selectList(kbQueryWrapper);

            // 将知识库转换为ModelProviderDTO格式并添加到结果列表
            for (KnowledgeBaseEntity kb : knowledgeBases) {
                ModelProviderDTO dto = new ModelProviderDTO();
                dto.setId(kb.getId());
                dto.setModelType("Rag");
                dto.setName("[知识库]" + kb.getName());
                dto.setProviderCode("ragflow"); // 假设所有RAG都使用ragflow
                dto.setFields("[]");
                dto.setSort(0);
                dto.setCreateDate(kb.getCreatedAt());
                dto.setUpdateDate(kb.getUpdatedAt());
                dto.setCreator(0L);
                dto.setUpdater(0L);
                resultList.add(dto);
            }
        }

        return resultList;
    }

    @Override
    public ModelProviderDTO getById(String id) {
        ModelProviderEntity entity = modelProviderDao.selectById(id);
        return ConvertUtils.sourceToTarget(entity, ModelProviderDTO.class);
    }

    @Override
    public List<ModelProviderDTO> getPluginListByIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return projectPlugins().stream().filter(item -> ids.contains(item.getId())).toList();
    }

    @Override
    public List<ModelProviderDTO> getListByModelType(String modelType) {

        QueryWrapper<ModelProviderEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("model_type", StringUtils.isBlank(modelType) ? "" : modelType);
        if ("TTS".equalsIgnoreCase(modelType)) {
            queryWrapper.in("provider_code", VISIBLE_TTS_PROVIDER_CODES);
        }
        queryWrapper.orderByAsc("sort");
        List<ModelProviderEntity> providerEntities = modelProviderDao.selectList(queryWrapper);
        return ConvertUtils.sourceToTarget(providerEntities, ModelProviderDTO.class);
    }

    @Override
    public PageData<ModelProviderDTO> getListPage(ModelProviderDTO modelProviderDTO, String page, String limit) {

        Map<String, Object> params = new HashMap<String, Object>();
        params.put(Constant.PAGE, page);
        params.put(Constant.LIMIT, limit);
        params.put(Constant.ORDER_FIELD, List.of("model_type", "sort"));
        params.put(Constant.ORDER, "asc");

        IPage<ModelProviderEntity> pageParam = getPage(params, null, true);

        QueryWrapper<ModelProviderEntity> wrapper = new QueryWrapper<ModelProviderEntity>();

        if (StringUtils.isNotBlank(modelProviderDTO.getModelType())) {
            wrapper.eq("model_type", modelProviderDTO.getModelType());
        }

        if (StringUtils.isNotBlank(modelProviderDTO.getName())) {
            wrapper.and(w -> w.like("name", modelProviderDTO.getName())
                    .or()
                    .like("provider_code", modelProviderDTO.getName()));
        }
        return getPageData(modelProviderDao.selectPage(pageParam, wrapper), ModelProviderDTO.class);
    }

    @Override
    public ModelProviderDTO add(ModelProviderDTO modelProviderDTO) {
        rejectPluginWrite(modelProviderDTO);
        UserDetail user = SecurityUser.getUser();
        modelProviderDTO.setCreator(user.getId());
        modelProviderDTO.setUpdater(user.getId());
        modelProviderDTO.setCreateDate(new Date());
        modelProviderDTO.setUpdateDate(new Date());
        // 去除Fields左右的双引号

        modelProviderDTO.setFields(modelProviderDTO.getFields());
        ModelProviderEntity entity = ConvertUtils.sourceToTarget(modelProviderDTO, ModelProviderEntity.class);
        if (modelProviderDao.insert(entity) == 0) {
            throw new RenException(ErrorCode.ADD_DATA_FAILED);
        }

        return ConvertUtils.sourceToTarget(modelProviderDTO, ModelProviderDTO.class);
    }

    @Override
    public ModelProviderDTO edit(ModelProviderDTO modelProviderDTO) {
        rejectPluginWrite(modelProviderDTO);
        UserDetail user = SecurityUser.getUser();
        modelProviderDTO.setUpdater(user.getId());
        modelProviderDTO.setUpdateDate(new Date());
        if (modelProviderDao
                .updateById(ConvertUtils.sourceToTarget(modelProviderDTO, ModelProviderEntity.class)) == 0) {
            throw new RenException(ErrorCode.UPDATE_DATA_FAILED);
        }
        return ConvertUtils.sourceToTarget(modelProviderDTO, ModelProviderDTO.class);
    }

    @Override
    public void delete(String id) {
        rejectStoredPluginWrite(id);
        if (modelProviderDao.deleteById(id) == 0) {
            throw new RenException(ErrorCode.DELETE_DATA_FAILED);
        }
    }

    @Override
    public void delete(List<String> ids) {
        if (ids != null) ids.forEach(this::rejectStoredPluginWrite);
        if (modelProviderDao.deleteByIds(ids) == 0) {
            throw new RenException(ErrorCode.DELETE_DATA_FAILED);
        }
    }

    @Override
    public List<ModelProviderDTO> getList(String modelType, String providerCode) {
        if ("Plugin".equalsIgnoreCase(modelType)) {
            return projectPlugins().stream()
                    .filter(item -> Objects.equals(item.getProviderCode(), providerCode))
                    .toList();
        }
        QueryWrapper<ModelProviderEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("model_type", StringUtils.isBlank(modelType) ? "" : modelType);
        queryWrapper.eq("provider_code", StringUtils.isBlank(providerCode) ? "" : providerCode);
        List<ModelProviderEntity> providerEntities = modelProviderDao.selectList(queryWrapper);
        return ConvertUtils.sourceToTarget(providerEntities, ModelProviderDTO.class);
    }

    private List<ModelProviderDTO> projectPlugins() {
        return capabilityService.page("PLUGIN", null, null, 1, 100).getList().stream()
                .filter(item -> item.getPlugin() != null)
                .map(this::projectPlugin)
                .toList();
    }

    private ModelProviderDTO projectPlugin(CapabilityVO capability) {
        PluginDefinitionDTO plugin = capability.getPlugin();
        ModelProviderDTO result = new ModelProviderDTO();
        result.setId(capability.getId());
        result.setModelType("Plugin");
        result.setProviderCode(plugin.getExecutorName());
        result.setName(capability.getName());
        result.setFields(JsonUtils.toJsonString(legacyFields(plugin)));
        result.setSort(0);
        result.setCreateDate(capability.getCreatedAt());
        result.setUpdateDate(capability.getUpdatedAt());
        result.setCreator(0L);
        result.setUpdater(0L);
        return result;
    }

    private List<Map<String, Object>> legacyFields(PluginDefinitionDTO plugin) {
        Map<String, Object> schema = plugin.getConfigSchema() == null ? Map.of() : plugin.getConfigSchema();
        Object rawProperties = schema.get("properties");
        if (!(rawProperties instanceof Map<?, ?> properties)) return List.of();
        Map<String, Object> defaults = plugin.getDefaultConfig() == null ? Map.of() : plugin.getDefaultConfig();
        return properties.entrySet().stream().map(entry -> {
            String key = String.valueOf(entry.getKey());
            Map<?, ?> property = entry.getValue() instanceof Map<?, ?> map ? map : Map.of();
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("key", key);
            Object rawType = property.get("type");
            field.put("type", rawType == null ? "string" : String.valueOf(rawType));
            field.put("label", property.get("title") == null ? key : property.get("title"));
            Object value = property.containsKey("default") ? property.get("default") : defaults.get(key);
            if (value != null) field.put("default", value);
            field.put("editing", false);
            field.put("selected", false);
            return Map.copyOf(field);
        }).toList();
    }

    private void rejectPluginWrite(ModelProviderDTO request) {
        if (request != null && "Plugin".equalsIgnoreCase(request.getModelType())) {
            throw new RenException("插件配置已迁移到新版能力中心，旧入口仅支持读取");
        }
    }

    private void rejectStoredPluginWrite(String id) {
        ModelProviderEntity existing = modelProviderDao.selectById(id);
        if (existing != null && "Plugin".equalsIgnoreCase(existing.getModelType())) {
            throw new RenException("插件配置已迁移到新版能力中心，旧入口仅支持读取");
        }
    }
}
