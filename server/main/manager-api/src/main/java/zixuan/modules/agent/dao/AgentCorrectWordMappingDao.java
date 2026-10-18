package zixuan.modules.agent.dao;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import zixuan.common.dao.BaseDao;
import zixuan.modules.agent.entity.AgentCorrectWordMappingEntity;

@Mapper
public interface AgentCorrectWordMappingDao extends BaseDao<AgentCorrectWordMappingEntity> {

    int deleteByAgentId(@Param("agentId") String agentId);

    int deleteByFileId(@Param("fileId") String fileId);

    int batchInsertMapping(@Param("list") List<AgentCorrectWordMappingEntity> mappings);

    List<AgentCorrectWordMappingEntity> selectByAgentId(@Param("agentId") String agentId);
}
