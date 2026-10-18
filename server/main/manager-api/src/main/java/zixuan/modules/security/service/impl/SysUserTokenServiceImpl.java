package zixuan.modules.security.service.impl;

import java.util.Date;

import org.springframework.stereotype.Service;

import cn.hutool.core.date.DateUtil;
import lombok.AllArgsConstructor;
import zixuan.common.exception.ErrorCode;
import zixuan.common.exception.RenException;
import zixuan.common.page.TokenDTO;
import zixuan.common.service.impl.BaseServiceImpl;
import zixuan.common.utils.HttpContextUtils;
import zixuan.common.utils.Result;
import zixuan.modules.security.dao.SysUserTokenDao;
import zixuan.modules.security.entity.SysUserTokenEntity;
import zixuan.modules.security.oauth2.TokenGenerator;
import zixuan.modules.security.service.SysUserTokenService;
import zixuan.modules.sys.dto.PasswordDTO;
import zixuan.modules.sys.dto.SysUserDTO;
import zixuan.modules.sys.service.SysUserService;

@AllArgsConstructor
@Service
public class SysUserTokenServiceImpl extends BaseServiceImpl<SysUserTokenDao, SysUserTokenEntity>
        implements SysUserTokenService {

    private final SysUserService sysUserService;
    /**
     * 12小时后过期
     */
    private final static int EXPIRE = 3600 * 12;

    @Override
    public Result<TokenDTO> createToken(Long userId) {
        // 用户token
        String token;

        // 当前时间
        Date now = new Date();
        // 过期时间
        Date expireTime = new Date(now.getTime() + EXPIRE * 1000);

        // 判断是否生成过token
        SysUserTokenEntity tokenEntity = baseDao.getByUserId(userId);
        if (tokenEntity == null) {
            // 生成一个token
            token = TokenGenerator.generateValue();

            tokenEntity = new SysUserTokenEntity();
            tokenEntity.setUserId(userId);
            tokenEntity.setToken(token);
            tokenEntity.setUpdateDate(now);
            tokenEntity.setExpireDate(expireTime);

            // 保存token
            this.insert(tokenEntity);
        } else {
            // 判断token是否过期
            if (tokenEntity.getExpireDate().getTime() < System.currentTimeMillis()) {
                // token过期，重新生成token
                token = TokenGenerator.generateValue();
            } else {
                token = tokenEntity.getToken();
            }

            tokenEntity.setToken(token);
            tokenEntity.setUpdateDate(now);
            tokenEntity.setExpireDate(expireTime);

            // 更新token
            this.updateById(tokenEntity);
        }

        String clientHash = HttpContextUtils.getClientCode();

        TokenDTO tokenDTO = new TokenDTO();
        tokenDTO.setToken(token);
        tokenDTO.setExpire(EXPIRE);
        tokenDTO.setClientHash(clientHash);
        return new Result<TokenDTO>().ok(tokenDTO);
    }

    @Override
    public SysUserDTO getUserByToken(String token) {
        SysUserTokenEntity userToken = baseDao.getByToken(token);
        if (null == userToken) {
            throw new RenException(ErrorCode.TOKEN_INVALID);
        }

        Date now = new Date();
        if (userToken.getExpireDate().before(now)) {
            throw new RenException(ErrorCode.UNAUTHORIZED);
        }

        SysUserDTO userDTO = sysUserService.getByUserId(userToken.getUserId());
        userDTO.setPassword("");
        return userDTO;
    }

    @Override
    public void logout(Long userId) {
        Date expireDate = DateUtil.offsetMinute(new Date(), -1);
        baseDao.logout(userId, expireDate);
    }

    @Override
    public void changePassword(Long userId, PasswordDTO passwordDTO) {
        // 修改密码
        sysUserService.changePassword(userId, passwordDTO);

        // 使 token 失效，后需要重新登录
        Date expireDate = DateUtil.offsetMinute(new Date(), -1);
        baseDao.logout(userId, expireDate);
    }
}