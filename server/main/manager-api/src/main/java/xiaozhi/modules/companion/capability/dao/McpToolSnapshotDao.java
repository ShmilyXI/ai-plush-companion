package xiaozhi.modules.companion.capability.dao;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.companion.capability.entity.McpToolSnapshotEntity;

@Mapper
public interface McpToolSnapshotDao extends BaseMapper<McpToolSnapshotEntity> {
    @Select("SELECT * FROM ai_mcp_tool_snapshot WHERE mcp_server_id=#{mcpServerId} ORDER BY tool_name ASC")
    List<McpToolSnapshotEntity> selectByMcpServerId(@Param("mcpServerId") String mcpServerId);
}
