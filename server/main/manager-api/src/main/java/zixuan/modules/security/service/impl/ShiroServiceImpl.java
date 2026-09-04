package zixuan.modules.security.service.impl;

import org.springframework.stereotype.Service;

import lombok.AllArgsConstructor;
import zixuan.modules.security.dao.SysUserTokenDao;
import zixuan.modules.security.entity.SysUserTokenEntity;
import zixuan.modules.security.service.ShiroService;
import zixuan.modules.sys.dao.SysUserDao;
import zixuan.modules.sys.entity.SysUserEntity;

@AllArgsConstructor
@Service
public class ShiroServiceImpl implements ShiroService {
    private final SysUserDao sysUserDao;
    private final SysUserTokenDao sysUserTokenDao;

    @Override
    public SysUserTokenEntity getByToken(String token) {
        return sysUserTokenDao.getByToken(token);
    }

    @Override
    public SysUserEntity getUser(Long userId) {
        return sysUserDao.selectById(userId);
    }
}