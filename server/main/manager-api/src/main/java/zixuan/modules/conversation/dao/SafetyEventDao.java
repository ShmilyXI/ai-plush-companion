package zixuan.modules.conversation.dao;

import org.apache.ibatis.annotations.Mapper;

import zixuan.common.dao.BaseDao;
import zixuan.modules.conversation.entity.SafetyEventEntity;

@Mapper
public interface SafetyEventDao extends BaseDao<SafetyEventEntity> {
}
