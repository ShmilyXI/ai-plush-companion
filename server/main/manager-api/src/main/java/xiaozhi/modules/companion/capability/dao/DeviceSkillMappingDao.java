package xiaozhi.modules.companion.capability.dao;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Delete;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.companion.capability.entity.DeviceSkillMappingEntity;

@Mapper
public interface DeviceSkillMappingDao extends BaseMapper<DeviceSkillMappingEntity> {
    @Select("SELECT COUNT(*) FROM ai_device_skill_mapping WHERE skill_id=#{skillId}")
    long countBySkillId(@Param("skillId") String skillId);

    @Select("SELECT * FROM ai_device_skill_mapping WHERE device_id=#{deviceId} AND enabled=1 ORDER BY trigger_priority DESC,id ASC")
    List<DeviceSkillMappingEntity> selectEnabledByDevice(@Param("deviceId") String deviceId);

    @Select("SELECT * FROM ai_device_skill_mapping WHERE device_id=#{deviceId} ORDER BY trigger_priority DESC,id ASC")
    List<DeviceSkillMappingEntity> selectByDeviceId(@Param("deviceId") String deviceId);

    @Select("SELECT DISTINCT device_id FROM ai_device_skill_mapping WHERE enabled=1 ORDER BY device_id")
    List<String> selectEnabledDeviceIds();

    @Delete("DELETE FROM ai_device_skill_mapping WHERE device_id=#{deviceId}")
    int deleteByDeviceId(@Param("deviceId") String deviceId);
}
