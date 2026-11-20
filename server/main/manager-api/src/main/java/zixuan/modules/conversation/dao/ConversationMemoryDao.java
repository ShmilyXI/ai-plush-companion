package zixuan.modules.conversation.dao;

import org.apache.ibatis.annotations.Mapper;

import zixuan.common.dao.BaseDao;
import zixuan.modules.conversation.entity.ConversationMemoryEntity;

@Mapper
public interface ConversationMemoryDao extends BaseDao<ConversationMemoryEntity> {
}
