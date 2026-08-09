package xiaozhi.modules.device.dao;

import java.util.Date;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.dto.UserDeviceCountDTO;

@Mapper
public interface DeviceDao extends BaseMapper<DeviceEntity> {
    /**
     * 获取此智能体全部设备的最后连接时间
     * 
     * @param agentId 智能体id
     * @return
     */
    Date getAllLastConnectedAtByAgentId(String agentId);

    DeviceEntity selectOwnedByIdForUpdate(@Param("deviceId") String deviceId, @Param("userId") Long userId);

    @Select("SELECT * FROM ai_device WHERE normalized_mac_address = #{normalizedMac} FOR UPDATE")
    DeviceEntity selectByNormalizedMacForUpdate(@Param("normalizedMac") String normalizedMac);

    @Select("SELECT * FROM ai_device WHERE normalized_mac_address = #{normalizedMac}")
    DeviceEntity selectByNormalizedMac(@Param("normalizedMac") String normalizedMac);

    List<UserDeviceCountDTO> countByUserIds(@Param("userIds") List<Long> userIds);

}
