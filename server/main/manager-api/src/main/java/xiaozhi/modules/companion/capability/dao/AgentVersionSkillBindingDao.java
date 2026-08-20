package xiaozhi.modules.companion.capability.dao;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.companion.capability.entity.AgentVersionSkillBindingEntity;

@Mapper
public interface AgentVersionSkillBindingDao extends BaseMapper<AgentVersionSkillBindingEntity> {
    @Select("SELECT * FROM ai_agent_version_skill_binding "
            + "WHERE agent_id=#{agentId} AND version_no=#{versionNo} AND enabled=1 "
            + "ORDER BY trigger_priority DESC,id ASC")
    List<AgentVersionSkillBindingEntity> selectEnabledByAgentVersion(@Param("agentId") String agentId,
            @Param("versionNo") Integer versionNo);

    @Select("SELECT * FROM ai_agent_version_skill_binding "
            + "WHERE agent_id=#{agentId} AND version_no=#{versionNo} AND skill_id=#{skillId} LIMIT 1")
    AgentVersionSkillBindingEntity selectByAgentVersionAndSkill(@Param("agentId") String agentId,
            @Param("versionNo") Integer versionNo, @Param("skillId") String skillId);
}
