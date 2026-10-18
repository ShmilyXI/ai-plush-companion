package zixuan.modules.companion.wakeword.dao;

import java.util.Date;
import java.util.Map;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import zixuan.modules.companion.wakeword.entity.DeviceWakeWordEntity;

@Mapper
public interface DeviceWakeWordDao extends BaseMapper<DeviceWakeWordEntity> {
    @Select("SELECT * FROM ai_device_wake_word WHERE device_id = #{deviceId} FOR UPDATE")
    DeviceWakeWordEntity selectByDeviceIdForUpdate(@Param("deviceId") String deviceId);

    @Select("SELECT * FROM ai_device_wake_word WHERE status IN ('GENERATING','WAITING_DEVICE') ORDER BY updated_at LIMIT #{limit}")
    List<DeviceWakeWordEntity> selectPending(@Param("limit") int limit);

    @Select("SELECT * FROM ai_device_wake_word WHERE candidate_token = #{token} LIMIT 1")
    DeviceWakeWordEntity selectByCandidateToken(@Param("token") String token);

    int updateIfVersion(@Param("deviceId") String deviceId,
            @Param("desiredVersion") long desiredVersion,
            @Param("status") String status,
            @Param("values") Map<String, Object> values);

    int claim(@Param("deviceId") String deviceId,
            @Param("desiredVersion") long desiredVersion,
            @Param("lockToken") String lockToken,
            @Param("lockUntil") Date lockUntil);

    int release(@Param("deviceId") String deviceId,
            @Param("desiredVersion") long desiredVersion,
            @Param("lockToken") String lockToken);
}
