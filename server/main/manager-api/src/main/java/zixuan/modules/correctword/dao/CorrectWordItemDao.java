package zixuan.modules.correctword.dao;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import zixuan.common.dao.BaseDao;
import zixuan.modules.correctword.entity.CorrectWordItemEntity;

@Mapper
public interface CorrectWordItemDao extends BaseDao<CorrectWordItemEntity> {

    int batchInsert(@Param("list") List<CorrectWordItemEntity> items);
}
