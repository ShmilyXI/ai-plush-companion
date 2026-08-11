package xiaozhi.modules.model.service.impl;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.companion.model.service.CompanionModelConnectionTester;
import xiaozhi.modules.companion.model.vo.CompanionModelTestVO;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.dto.ModelConfigBodyDTO;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConnectionTestService;
import xiaozhi.modules.model.service.ModelProviderService;

@Service
@RequiredArgsConstructor
public class ModelConnectionTestServiceImpl implements ModelConnectionTestService {
    private final ModelConfigDao modelConfigDao;
    private final ModelProviderService modelProviderService;
    private final CompanionModelConnectionTester tester;

    @Override
    public CompanionModelTestVO test(String modelType, String providerCode, String id, ModelConfigBodyDTO body) {
        if (StringUtils.isAnyBlank(modelType, providerCode) || body == null) {
            throw new RenException(ErrorCode.PARAMS_GET_ERROR);
        }
        if (CollectionUtil.isEmpty(modelProviderService.getList(modelType, providerCode))) {
            throw new RenException(ErrorCode.MODEL_PROVIDER_NOT_EXIST);
        }
        if (!isConversationModel(modelType)) {
            return new CompanionModelTestVO(false, 0, "当前模型不支持自动测试");
        }
        if (!"openai".equalsIgnoreCase(providerCode)) {
            return new CompanionModelTestVO(false, 0, "当前供应器不支持自动测试");
        }

        JSONObject runtime = new JSONObject();
        if (StringUtils.isNotBlank(id)) {
            ModelConfigEntity saved = modelConfigDao.selectById(id);
            if (saved == null || !modelType.equalsIgnoreCase(saved.getModelType())) {
                throw new RenException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            if (saved.getConfigJson() != null) {
                runtime.putAll(saved.getConfigJson());
            }
        }
        merge(runtime, body.getConfigJson());
        String runtimeProvider = runtime.getStr("type", providerCode);
        if (!"openai".equalsIgnoreCase(runtimeProvider)) {
            return new CompanionModelTestVO(false, 0, "当前供应器不支持自动测试");
        }
        return tester.test(runtimeProvider, runtime);
    }

    private boolean isConversationModel(String modelType) {
        return "LLM".equalsIgnoreCase(modelType) || "VLLM".equalsIgnoreCase(modelType);
    }

    private void merge(JSONObject target, JSONObject submitted) {
        if (submitted == null) {
            return;
        }
        for (String key : submitted.keySet()) {
            Object value = submitted.get(key);
            if (value instanceof JSONObject nested) {
                Object currentValue = target.get(key);
                JSONObject current = currentValue instanceof JSONObject json ? json : new JSONObject();
                merge(current, nested);
                target.set(key, current);
            } else if (value != null && !(value instanceof String text && text.isBlank())) {
                target.set(key, value);
            }
        }
    }
}
