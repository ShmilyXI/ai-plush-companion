package xiaozhi.modules.model.tencentdb;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.dto.ModelProviderDTO;
import xiaozhi.modules.model.entity.ModelConfigEntity;

@Service
@RequiredArgsConstructor
public class TencentDbMemoryModelCatalogService {
    private final ModelConfigDao modelConfigDao;

    public void enrich(List<ModelProviderDTO> providers) {
        if (providers == null || providers.isEmpty()) {
            return;
        }
        JSONArray llmOptions = options("LLM");
        JSONArray embeddingOptions = options("Embedding");
        for (ModelProviderDTO provider : providers) {
            if (!"Memory".equalsIgnoreCase(provider.getModelType())
                    || !"tencentdb".equalsIgnoreCase(provider.getProviderCode())
                    || StringUtils.isBlank(provider.getFields())) {
                continue;
            }
            JSONArray fields = JSONUtil.parseArray(provider.getFields());
            replaceOptions(fields, "llm_model_id", llmOptions);
            replaceOptions(fields, "embedding_model_id", embeddingOptions);
            provider.setFields(fields.toString());
        }
    }

    private JSONArray options(String modelType) {
        List<ModelConfigEntity> models = modelConfigDao.selectList(
                new LambdaQueryWrapper<ModelConfigEntity>()
                        .eq(ModelConfigEntity::getModelType, modelType)
                        .eq(ModelConfigEntity::getIsEnabled, 1)
                        .orderByAsc(ModelConfigEntity::getSort));
        JSONArray options = new JSONArray();
        for (ModelConfigEntity model : models) {
            JSONObject config = model.getConfigJson();
            if (model.getIsEnabled() == null || model.getIsEnabled() != 1
                    || config == null
                    || !"openai".equalsIgnoreCase(config.getStr("type"))
                    || StringUtils.isAnyBlank(
                            config.getStr("base_url"),
                            config.getStr("api_key"),
                            config.getStr("model_name"))) {
                continue;
            }
            options.add(new JSONObject()
                    .set("label", model.getModelName())
                    .set("value", model.getId()));
        }
        return options;
    }

    private void replaceOptions(JSONArray fields, String key, JSONArray options) {
        for (int index = 0; index < fields.size(); index++) {
            Object item = fields.get(index);
            JSONObject field = item instanceof JSONObject json ? json : JSONUtil.parseObj(item);
            if (key.equals(field.getStr("key"))) {
                field.set("options", options);
                fields.set(index, field);
            }
        }
    }
}
