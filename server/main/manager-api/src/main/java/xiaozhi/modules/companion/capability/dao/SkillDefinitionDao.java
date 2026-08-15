package xiaozhi.modules.companion.capability.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.companion.capability.entity.SkillDefinitionEntity;

@Mapper
public interface SkillDefinitionDao extends BaseMapper<SkillDefinitionEntity> {
    @Select("SELECT * FROM ai_skill_definition WHERE capability_id=#{capabilityId} LIMIT 1")
    SkillDefinitionEntity selectByCapabilityId(@Param("capabilityId") String capabilityId);
}
