package xiaozhi.modules.companion.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.companion.entity.CompanionPlanEntity;

@Mapper
public interface CompanionPlanDao extends BaseMapper<CompanionPlanEntity> {

    @Select("SELECT * FROM ai_companion_plan WHERE id = #{id} FOR UPDATE")
    CompanionPlanEntity selectByIdForUpdate(@Param("id") String id);
}
