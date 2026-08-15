package xiaozhi.modules.companion.capability.dao;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.companion.capability.entity.SkillToolMappingEntity;

@Mapper
public interface SkillToolMappingDao extends BaseMapper<SkillToolMappingEntity> {
    @Select("SELECT * FROM ai_skill_tool_mapping WHERE skill_id=#{skillId} ORDER BY sort_order ASC,id ASC")
    List<SkillToolMappingEntity> selectBySkillId(@Param("skillId") String skillId);

    @Delete("DELETE FROM ai_skill_tool_mapping WHERE skill_id=#{skillId}")
    int deleteBySkillId(@Param("skillId") String skillId);

    @Select("SELECT COUNT(*) FROM ai_skill_tool_mapping WHERE tool_type=#{toolType} AND tool_ref_id=#{toolRefId}")
    long countByToolRef(@Param("toolType") String toolType, @Param("toolRefId") String toolRefId);
}
