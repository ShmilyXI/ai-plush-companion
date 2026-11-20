package zixuan.modules.conversation.dao;

import org.apache.ibatis.annotations.Mapper;

import zixuan.common.dao.BaseDao;
import zixuan.modules.conversation.entity.ConversationTurnEntity;

@Mapper
public interface ConversationTurnDao extends BaseDao<ConversationTurnEntity> {
}
