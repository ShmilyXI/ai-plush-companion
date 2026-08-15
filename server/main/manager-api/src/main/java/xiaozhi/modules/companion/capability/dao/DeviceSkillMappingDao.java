package xiaozhi.modules.companion.capability.dao;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.companion.capability.entity.DeviceSkillMappingEntity;

@Mapper
public interface DeviceSkillMappingDao extends BaseMapper<DeviceSkillMappingEntity> {
    @Select("SELECT COUNT(*) FROM ai_device_skill_mapping WHERE skill_id=#{skillId}")
    long countBySkillId(@Param("skillId") String skillId);

    @Select("SELECT * FROM ai_device_skill_mapping WHERE device_id=#{deviceId} AND enabled=1 ORDER BY trigger_priority DESC,id ASC")
    List<DeviceSkillMappingEntity> selectEnabledByDevice(@Param("deviceId") String deviceId);
}
