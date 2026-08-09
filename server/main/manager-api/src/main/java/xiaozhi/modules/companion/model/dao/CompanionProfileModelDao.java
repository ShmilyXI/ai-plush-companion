package xiaozhi.modules.companion.model.dao;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.companion.model.entity.CompanionProfileModelEntity;

@Mapper
public interface CompanionProfileModelDao extends BaseMapper<CompanionProfileModelEntity> {
    @Select("SELECT * FROM ai_companion_profile_model WHERE agent_id=#{agentId} ORDER BY model_type")
    List<CompanionProfileModelEntity> selectByAgentId(@Param("agentId") String agentId);

    @Select("SELECT * FROM ai_companion_profile_model WHERE agent_id=#{agentId} ORDER BY model_type FOR UPDATE")
    List<CompanionProfileModelEntity> selectByAgentIdForUpdate(@Param("agentId") String agentId);

    @Select("SELECT COUNT(*) FROM ai_companion_profile_model WHERE source_type=#{sourceType} AND resource_id=#{resourceId}")
    long countResourceUsage(@Param("sourceType") String sourceType, @Param("resourceId") String resourceId);

    @Delete("DELETE FROM ai_companion_profile_model WHERE agent_id=#{agentId}")
    int deleteByAgentId(@Param("agentId") String agentId);
}
