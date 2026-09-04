package zixuan.modules.companion.capability.dao;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import zixuan.modules.companion.capability.entity.SkillTriggerEntity;

@Mapper
public interface SkillTriggerDao extends BaseMapper<SkillTriggerEntity> {
    @Select("SELECT * FROM ai_skill_trigger WHERE skill_id=#{skillId} ORDER BY priority DESC,id ASC")
    List<SkillTriggerEntity> selectBySkillId(@Param("skillId") String skillId);

    @Delete("DELETE FROM ai_skill_trigger WHERE skill_id=#{skillId}")
    int deleteBySkillId(@Param("skillId") String skillId);
}
