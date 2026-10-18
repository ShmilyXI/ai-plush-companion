package zixuan.modules.security.oauth2;

import java.util.HashSet;
import java.util.Set;

import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.DisabledAccountException;
import org.apache.shiro.authc.IncorrectCredentialsException;
import org.apache.shiro.authc.LockedAccountException;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.subject.PrincipalCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import zixuan.common.exception.ErrorCode;
import zixuan.common.user.UserDetail;
import zixuan.common.utils.ConvertUtils;
import zixuan.common.utils.MessageUtils;
import zixuan.modules.appauth.service.AppUserTokenService;
import zixuan.modules.conversation.security.PublicConversationApiKeyToken;
import zixuan.modules.conversation.security.PublicConversationUserDetail;
import zixuan.modules.conversation.service.PublicConversationApiKeyService;
import zixuan.modules.security.entity.SysUserTokenEntity;
import zixuan.modules.security.service.ShiroService;
import zixuan.modules.sys.entity.SysUserEntity;
import zixuan.modules.sys.enums.SuperAdminEnum;
import zixuan.modules.websession.service.WebSessionBootstrapService;

/**
 * 认证
 * Copyright (c) 人人开源 All rights reserved.
 * Website: https://www.renren.io
 */
@Component
public class Oauth2Realm extends AuthorizingRealm {
    @Lazy
    @Resource
    private ShiroService shiroService;

    @Lazy
    @Resource
    private PublicConversationApiKeyService publicConversationApiKeys;

    @Lazy
    @Resource
    private WebSessionBootstrapService webSessions;

    @Lazy
    @Resource
    private AppUserTokenService appUserTokens;

    private static final Logger logger = LoggerFactory.getLogger(Oauth2Realm.class);

    @Override
    public boolean supports(AuthenticationToken token) {
        return token instanceof Oauth2Token || token instanceof PublicConversationApiKeyToken;
    }

    /**
     * 授权(验证权限时调用)
     */
    @Override
    protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
        UserDetail user = (UserDetail) principals.getPrimaryPrincipal();

        // 用户权限列表
        Set<String> permsSet = new HashSet<>();

        if (user.getSuperAdmin() == SuperAdminEnum.YES.value()) {
            permsSet.add("sys:role:superAdmin");
            permsSet.add("sys:role:normal");
        } else {
            permsSet.add("sys:role:normal");
        }

        SimpleAuthorizationInfo info = new SimpleAuthorizationInfo();
        info.setStringPermissions(permsSet);
        return info;
    }

    /**
     * 认证(登录时调用)
     */
    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) throws AuthenticationException {
        if (token instanceof PublicConversationApiKeyToken apiKeyToken) {
            PublicConversationApiKeyService.ResolvedApiKey resolved = publicConversationApiKeys.resolve(
                    apiKeyToken.secret(), apiKeyToken.source());
            PublicConversationUserDetail user = new PublicConversationUserDetail();
            user.setId(resolved.userId());
            user.setStatus(1);
            user.setSuperAdmin(SuperAdminEnum.NO.value());
            user.setScopes(resolved.scopes());
            user.setAgentIds(resolved.agentIds());
            user.setApiKeyId(resolved.id());
            return new SimpleAuthenticationInfo(user, apiKeyToken.secret(), getName());
        }
        String accessToken = (String) token.getPrincipal();
        if (accessToken != null && accessToken.startsWith("app_")) {
            Long appUserId = appUserTokens == null ? null : appUserTokens.resolveAccessToken(accessToken);
            if (appUserId == null) {
                throw new IncorrectCredentialsException(MessageUtils.getMessage(ErrorCode.TOKEN_INVALID));
            }
            SysUserEntity appUserEntity = shiroService.getUser(appUserId);
            if (appUserEntity == null) {
                throw new IncorrectCredentialsException(MessageUtils.getMessage(ErrorCode.TOKEN_INVALID));
            }
            UserDetail appUserDetail = ConvertUtils.sourceToTarget(appUserEntity, UserDetail.class);
            appUserDetail.setToken(accessToken);
            // App 通道永远按普通用户授权，即使底层账号是管理台超管
            appUserDetail.setSuperAdmin(SuperAdminEnum.NO.value());
            if (appUserDetail.getStatus() == null || appUserDetail.getStatus() == 0) {
                throw new LockedAccountException(MessageUtils.getMessage(ErrorCode.ACCOUNT_LOCK));
            }
            return new SimpleAuthenticationInfo(appUserDetail, accessToken, getName());
        }
        Long webUserId = webSessions == null ? null : webSessions.resolve(accessToken);
        SysUserEntity userEntity;
        if (webUserId != null) {
            userEntity = shiroService.getUser(webUserId);
        } else {
            // 根据 accessToken 查询长期管理台 token
            SysUserTokenEntity tokenEntity = shiroService.getByToken(accessToken);
            if (tokenEntity == null || tokenEntity.getExpireDate().getTime() < System.currentTimeMillis()) {
                throw new IncorrectCredentialsException(MessageUtils.getMessage(ErrorCode.TOKEN_INVALID));
            }
            userEntity = shiroService.getUser(tokenEntity.getUserId());
        }

        // 转换成UserDetail对象
        UserDetail userDetail = ConvertUtils.sourceToTarget(userEntity, UserDetail.class);

        userDetail.setToken(accessToken);

        // 账号锁定
        if (userDetail.getStatus() == null) {
            logger.error("账号状态异常，status 不能为空");
            throw new DisabledAccountException(MessageUtils.getMessage(ErrorCode.ACCOUNT_DISABLE));
        }

        if (userDetail.getStatus() == 0) {
            throw new LockedAccountException(MessageUtils.getMessage(ErrorCode.ACCOUNT_LOCK));
        }

        SimpleAuthenticationInfo info = new SimpleAuthenticationInfo(userDetail, accessToken, getName());
        return info;
    }

}
