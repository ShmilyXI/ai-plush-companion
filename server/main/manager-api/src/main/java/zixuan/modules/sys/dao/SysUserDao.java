package zixuan.modules.sys.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import zixuan.common.dao.BaseDao;
import zixuan.modules.sys.entity.SysUserEntity;

/**
 * 系统用户
 */
@Mapper
public interface SysUserDao extends BaseDao<SysUserEntity> {

    @Select("SELECT * FROM sys_user WHERE id = #{userId} FOR UPDATE")
    SysUserEntity selectByIdForUpdate(@Param("userId") Long userId);

}
