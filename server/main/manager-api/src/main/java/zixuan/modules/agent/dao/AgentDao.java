package zixuan.modules.agent.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import zixuan.common.dao.BaseDao;
import zixuan.modules.agent.entity.AgentEntity;
import zixuan.modules.agent.vo.AgentInfoVO;

@Mapper
public interface AgentDao extends BaseDao<AgentEntity> {

    @Update("UPDATE ai_agent SET active_version_no=#{versionNo}, updater=#{userId}, updated_at=NOW() WHERE id=#{agentId}")
    int updateActiveVersion(@Param("agentId") String agentId, @Param("versionNo") Integer versionNo,
            @Param("userId") Long userId);
    /**
     * 获取智能体的设备数量
     * 
     * @param agentId 智能体ID
     * @return 设备数量
     */
    Integer getDeviceCountByAgentId(@Param("agentId") String agentId);

    /**
     * 根据设备MAC地址查询对应设备的默认智能体信息
     *
     * @param macAddress 设备MAC地址
     * @return 默认智能体信息
     */
    @Select(" SELECT a.* FROM ai_device d " +
            " LEFT JOIN ai_agent a ON d.agent_id = a.id " +
            " WHERE d.mac_address = #{macAddress} " +
            " ORDER BY d.id DESC LIMIT 1")
    AgentEntity getDefaultAgentByMacAddress(@Param("macAddress") String macAddress);

    /**
     * 根据id查询agent信息，包括插件信息
     *
     * @param agentId 智能体ID
     */
    AgentInfoVO selectAgentInfoById(@Param("agentId") String agentId);

    /**
     * 锁定智能体主记录，用于串行化同一智能体的配置写入
     *
     * @param agentId 智能体ID
     */
    AgentEntity selectByIdForUpdate(@Param("agentId") String agentId);

    @Select("""
            SELECT COUNT(DISTINCT a.id)
            FROM ai_agent a
            LEFT JOIN ai_companion_profile_model pm
              ON pm.agent_id = a.id
             AND pm.source_type = 'global'
             AND pm.resource_id = #{modelId}
            WHERE pm.id IS NOT NULL
               OR a.asr_model_id = #{modelId}
               OR a.vad_model_id = #{modelId}
               OR a.llm_model_id = #{modelId}
               OR a.vllm_model_id = #{modelId}
               OR a.tts_model_id = #{modelId}
               OR a.mem_model_id = #{modelId}
            """)
    long countProfilesUsingModel(@Param("modelId") String modelId);

    @Select("""
            SELECT COUNT(DISTINCT d.id)
            FROM ai_device d
            JOIN ai_agent a ON a.id = d.agent_id
            LEFT JOIN ai_companion_profile_model pm
              ON pm.agent_id = a.id
             AND pm.source_type = 'global'
             AND pm.resource_id = #{modelId}
            WHERE pm.id IS NOT NULL
               OR a.asr_model_id = #{modelId}
               OR a.vad_model_id = #{modelId}
               OR a.llm_model_id = #{modelId}
               OR a.vllm_model_id = #{modelId}
               OR a.tts_model_id = #{modelId}
               OR a.mem_model_id = #{modelId}
            """)
    long countDevicesUsingModel(@Param("modelId") String modelId);

    /**
     * 精确写入快照覆盖的智能体字段，包括目标快照中的 null 值。
     * 不更新所属用户、创建信息等不属于快照的字段。
     *
     * @param agent 已应用目标快照的智能体
     * @return 受影响行数
     */
    int updateSnapshotFields(@Param("agent") AgentEntity agent);
}
