package xiaozhi.modules.companion.capability.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.companion.capability.entity.McpServerEntity;

@Mapper
public interface McpServerDao extends BaseMapper<McpServerEntity> {
    @Select("SELECT * FROM ai_mcp_server WHERE capability_id=#{capabilityId} LIMIT 1")
    McpServerEntity selectByCapabilityId(@Param("capabilityId") String capabilityId);
}
