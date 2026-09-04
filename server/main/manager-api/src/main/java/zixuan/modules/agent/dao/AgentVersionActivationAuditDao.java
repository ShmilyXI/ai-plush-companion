package zixuan.modules.agent.dao;

import org.apache.ibatis.annotations.Mapper;

import zixuan.common.dao.BaseDao;
import zixuan.modules.agent.entity.AgentVersionActivationAuditEntity;

@Mapper
public interface AgentVersionActivationAuditDao extends BaseDao<AgentVersionActivationAuditEntity> {
}
