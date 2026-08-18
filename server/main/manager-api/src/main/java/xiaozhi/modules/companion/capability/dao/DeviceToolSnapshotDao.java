package xiaozhi.modules.companion.capability.dao;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.companion.capability.entity.DeviceToolSnapshotEntity;

@Mapper
public interface DeviceToolSnapshotDao extends BaseMapper<DeviceToolSnapshotEntity> {
    @Select("SELECT * FROM ai_device_tool_snapshot WHERE device_id=#{deviceId} AND tool_name=#{toolName} LIMIT 1")
    DeviceToolSnapshotEntity selectByDeviceAndTool(@Param("deviceId") String deviceId,
            @Param("toolName") String toolName);

    @Select("SELECT * FROM ai_device_tool_snapshot WHERE tool_name=#{toolName} AND available=1 "
            + "ORDER BY last_seen_at DESC")
    List<DeviceToolSnapshotEntity> selectAvailableByToolName(@Param("toolName") String toolName);
}
