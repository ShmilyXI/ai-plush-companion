package zixuan.modules.knowledge.dao;

import org.apache.ibatis.annotations.Mapper;
import zixuan.common.dao.BaseDao;
import zixuan.modules.knowledge.entity.DocumentEntity;

/**
 * 文档 DAO
 */
@Mapper
public interface DocumentDao extends BaseDao<DocumentEntity> {
}
