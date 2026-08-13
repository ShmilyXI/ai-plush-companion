package xiaozhi.modules.companion.wakeword.dao;

import java.util.Date;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.companion.wakeword.entity.DeviceWakeWordEntity;

@Mapper
public interface DeviceWakeWordDao extends BaseMapper<DeviceWakeWordEntity> {
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
