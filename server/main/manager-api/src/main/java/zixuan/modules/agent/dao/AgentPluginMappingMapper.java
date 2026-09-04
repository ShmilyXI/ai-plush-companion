package zixuan.modules.agent.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import zixuan.modules.agent.entity.AgentPluginMapping;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

/**
* @description 针对表【ai_agent_plugin_mapping(Agent与插件的唯一映射表)】的数据库操作Mapper
* @createDate 2025-05-25 22:33:17
* @Entity zixuan.modules.agent.entity.AgentPluginMapping
*/
@Mapper
public interface AgentPluginMappingMapper extends BaseMapper<AgentPluginMapping> {
    List<AgentPluginMapping> selectPluginsByAgentId(@Param("agentId") String agentId);

    @Select("SELECT m.id,m.agent_id AS agentId,m.plugin_id AS pluginId,m.param_info AS paramInfo,"
            + "p.provider_code AS providerCode FROM ai_agent_plugin_mapping m "
            + "LEFT JOIN ai_model_provider p ON p.id=m.plugin_id ORDER BY m.id ASC")
    List<AgentPluginMapping> selectAllWithProviderCode();
}



