package zixuan.modules.sys.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import zixuan.common.redis.RedisKeys;
import zixuan.common.redis.RedisUtils;
import zixuan.common.service.impl.BaseServiceImpl;
import zixuan.modules.sys.dao.SysUserDao;
import zixuan.modules.sys.entity.SysUserEntity;
import zixuan.modules.sys.service.SysUserUtilService;

import java.util.function.Consumer;

@Service
@AllArgsConstructor
public class SysUserUtilServiceImpl extends BaseServiceImpl<SysUserDao, SysUserEntity> implements SysUserUtilService {

    private RedisUtils redisUtils;

    @Override
    public void assignUsername(Long userId, Consumer<String> setter) {
        String userIdKey = RedisKeys.getUserIdKey(userId);

        Object value = redisUtils.get(userIdKey);
        String username = (value != null) ? value.toString() : null;
        if(username != null){
            setter.accept(username);
        }else {
            SysUserEntity entity = baseDao.selectById(userId);
            if (entity != null) {
                username = entity.getUsername();
                redisUtils.set(userIdKey,username,10);
                setter.accept(username);
            }
        }
    }
}
