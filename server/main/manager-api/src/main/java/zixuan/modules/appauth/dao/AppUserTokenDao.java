package zixuan.modules.appauth.dao;

import org.apache.ibatis.annotations.Mapper;

import zixuan.common.dao.BaseDao;
import zixuan.modules.appauth.entity.AppUserTokenEntity;

/**
 * App 用户 Token
 */
@Mapper
public interface AppUserTokenDao extends BaseDao<AppUserTokenEntity> {

}
