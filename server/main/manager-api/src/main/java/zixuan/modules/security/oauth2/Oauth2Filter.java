package zixuan.modules.security.oauth2;

import java.io.IOException;

import org.apache.commons.lang3.StringUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.web.filter.authc.AuthenticatingFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.RequestMethod;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import zixuan.common.constant.Constant;
import zixuan.common.constant.ProductIdentity;
import zixuan.common.exception.ErrorCode;
import zixuan.common.utils.HttpContextUtils;
import zixuan.common.utils.JsonUtils;
import zixuan.common.utils.MessageUtils;
import zixuan.common.utils.Result;
import zixuan.modules.conversation.security.PublicConversationApiKeyToken;

/**
 * oauth2过滤器
 * Copyright (c) 人人开源 All rights reserved.
 * Website: https://www.renren.io
 */
public class Oauth2Filter extends AuthenticatingFilter {

    private static final Logger logger = LoggerFactory.getLogger(Oauth2Filter.class);

    @Override
    protected AuthenticationToken createToken(ServletRequest request, ServletResponse response) throws Exception {
        // 获取请求token
        String token = getRequestCredential((HttpServletRequest) request);

        if (StringUtils.isBlank(token)) {
            logger.warn("createToken:token is empty");
            return null;
        }

        String authorization = ((HttpServletRequest) request).getHeader(Constant.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, "ApiKey ", 0, 7)) {
            if (!isPublicApiKeyPath((HttpServletRequest) request)) return null;
            return new PublicConversationApiKeyToken(token, ((HttpServletRequest) request).getRemoteAddr());
        }
        if (token.startsWith("web_") && !isWebSessionPath((HttpServletRequest) request)) return null;
        return new Oauth2Token(token);
    }

    @Override
    protected boolean isAccessAllowed(ServletRequest request, ServletResponse response, Object mappedValue) {
        if (((HttpServletRequest) request).getMethod().equals(RequestMethod.OPTIONS.name())) {
            return true;
        }

        return false;
    }

    @Override
    protected boolean onAccessDenied(ServletRequest request, ServletResponse response) throws Exception {
        // 获取请求token，如果token不存在，直接返回401
        String token = getRequestCredential((HttpServletRequest) request);

        if (StringUtils.isBlank(token)) {
            logger.warn("onAccessDenied:token is empty");

            HttpServletResponse httpResponse = (HttpServletResponse) response;
            httpResponse.setContentType("application/json;charset=utf-8");
            httpResponse.setHeader("Access-Control-Allow-Credentials", "true");
            httpResponse.setHeader("Access-Control-Allow-Origin", HttpContextUtils.getOrigin());

            String json = JsonUtils.toJsonString(new Result<Void>().error(ErrorCode.UNAUTHORIZED));

            httpResponse.getWriter().print(json);

            return false;
        }

        return executeLogin(request, response);
    }

    @Override
    protected boolean onLoginFailure(AuthenticationToken token, AuthenticationException e, ServletRequest request,
            ServletResponse response) {
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        httpResponse.setContentType("application/json;charset=utf-8");
        httpResponse.setHeader("Access-Control-Allow-Credentials", "true");
        httpResponse.setHeader("Access-Control-Allow-Origin", HttpContextUtils.getOrigin());
        try {
            // 使用国际化消息替代直接使用异常消息
            Result<Void> r = new Result<Void>().error(ErrorCode.UNAUTHORIZED);

            String json = JsonUtils.toJsonString(r);
            httpResponse.getWriter().print(json);
        } catch (IOException e1) {
        }

        return false;
    }

    /**
     * 获取请求的token
     */
    private String getRequestCredential(HttpServletRequest httpRequest) {
        String token = null;
        // 从header中获取token
        String authorization = httpRequest.getHeader(Constant.AUTHORIZATION);
        if (StringUtils.isNotBlank(authorization) && authorization.startsWith("Bearer ")) {
            token = authorization.replace("Bearer ", "");
        } else if (StringUtils.isNotBlank(authorization) && authorization.regionMatches(true, 0, "ApiKey ", 0, 7)
                && isPublicApiKeyPath(httpRequest)) {
            token = authorization.substring("ApiKey ".length()).trim();
        }
        return token;
    }

    private boolean isPublicApiKeyPath(HttpServletRequest request) {
        String path = normalizedPath(request);
        return path.equals("/api/v1/conversations")
                || path.startsWith("/api/v1/conversations/")
                || path.equals("/api/v1/agents")
                || path.equals("/api/v1/models")
                || path.equals("/api/v1/voices")
                || path.equals("/api/v1/devices");
    }

    private boolean isWebSessionPath(HttpServletRequest request) {
        String path = normalizedPath(request);
        return path.equals("/user/info")
                || isPublicApiKeyPath(request);
    }

    private String normalizedPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StringUtils.isNotBlank(contextPath) && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        } else if (StringUtils.isBlank(contextPath) && path.startsWith(ProductIdentity.ROUTE_PREFIX + "/")) {
            path = path.substring(ProductIdentity.ROUTE_PREFIX.length());
        }
        return path;
    }
}
