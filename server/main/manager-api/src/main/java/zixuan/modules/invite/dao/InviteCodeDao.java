package zixuan.modules.invite.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import zixuan.common.dao.BaseDao;
import zixuan.modules.invite.entity.InviteCodeEntity;

@Mapper
public interface InviteCodeDao extends BaseDao<InviteCodeEntity> {

    /**
     * 占用一次使用名额。条件都写在语句里，并发下不会超发。
     *
     * @return 1 表示占用成功，0 表示邀请码不可用
     */
    @Update("UPDATE ai_invite_code SET used_count = used_count + 1 "
            + "WHERE code = #{code} AND status = 1 AND used_count < max_uses "
            + "AND (expire_date IS NULL OR expire_date > NOW())")
    int consume(@Param("code") String code);
}
