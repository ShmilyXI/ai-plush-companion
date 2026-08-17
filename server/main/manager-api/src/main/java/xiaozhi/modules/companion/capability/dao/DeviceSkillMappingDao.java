package xiaozhi.modules.companion.capability.dao;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Update;
import java.util.Date;

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

    @Update("""
            UPDATE ai_device d
            JOIN (SELECT DISTINCT device_id FROM ai_device_skill_mapping
                  WHERE skill_id=#{skillId} AND enabled=1 AND version_mode='LATEST') m ON m.device_id=d.id
            SET d.capability_config_version=COALESCE(d.capability_config_version,0)+1,
                d.update_date=#{now}
            WHERE m.device_id IS NOT NULL
            """)
    int bumpLatestDeviceConfigVersions(@Param("skillId") String skillId, @Param("now") Date now);

    @Update("""
            UPDATE ai_device d
            JOIN (SELECT DISTINCT device_id FROM ai_device_skill_mapping
                  WHERE skill_id=#{skillId} AND enabled=1) m ON m.device_id=d.id
            SET d.capability_config_version=COALESCE(d.capability_config_version,0)+1,
                d.update_date=#{now}
            WHERE m.device_id IS NOT NULL
            """)
    int bumpAllDeviceConfigVersions(@Param("skillId") String skillId, @Param("now") Date now);

    @Update("""
            UPDATE ai_device d
            JOIN (SELECT DISTINCT device_id FROM ai_device_skill_mapping WHERE enabled=1) m ON m.device_id=d.id
            SET d.capability_config_version=COALESCE(d.capability_config_version,0)+1,
                d.update_date=#{now}
            WHERE m.device_id IS NOT NULL
            """)
    int bumpEveryEnabledDeviceConfigVersion(@Param("now") Date now);
}
