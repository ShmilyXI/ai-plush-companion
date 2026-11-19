package zixuan.modules.model.service;

import java.util.List;

import zixuan.modules.companion.model.vo.CompanionModelTestVO;
import zixuan.modules.model.dto.ModelChannelAddModelsDTO;
import zixuan.modules.model.dto.ModelChannelDTO;
import zixuan.modules.model.dto.ModelChannelModelDTO;
import zixuan.modules.model.dto.ModelChannelModelUpdateDTO;
import zixuan.modules.model.dto.ModelChannelSaveDTO;
import zixuan.modules.model.dto.ModelChannelUpstreamModelDTO;

/**
 * 模型连接。连接是地址和密钥的唯一来源，保存时会同步到连接下所有模型的配置。
 */
public interface ModelChannelService {

    List<ModelChannelDTO> list();

    ModelChannelDTO create(ModelChannelSaveDTO dto);

    ModelChannelDTO update(String id, ModelChannelSaveDTO dto);

    void delete(String id);

    /**
     * 测试连接。id 为空时测试未保存的连接；不为空时用提交的值覆盖已保存的值。
     */
    CompanionModelTestVO test(String id, ModelChannelSaveDTO dto);

    List<ModelChannelUpstreamModelDTO> upstreamModels(String id);

    /**
     * 所有挂在连接下的模型。
     */
    List<ModelChannelModelDTO> models();

    /**
     * 把模型添加到连接下，已经添加过的同用途模型会被跳过。
     *
     * @return 本次新建的模型
     */
    List<ModelChannelModelDTO> addModels(String id, ModelChannelAddModelsDTO dto);

    ModelChannelModelDTO updateModel(String modelId, ModelChannelModelUpdateDTO dto);
}
