package com.tylab.biliweb.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** 全局访问口令拦截器（可选） */
@Component
public class AccessAuthInterceptor implements HandlerInterceptor {

    private final String accessToken;

    public AccessAuthInterceptor(@Value("${app.access-token:}") String accessToken) {
        this.accessToken = accessToken == null ? "" : accessToken.trim();
    }

    public boolean isAuthRequired() {
        return !accessToken.isEmpty();
    }

    public boolean verify(String token) {
        if (!isAuthRequired()) return true;
        return com.tylab.biliweb.util.SecurityUtil.verifyToken(token, accessToken);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 放行 OPTIONS 请求
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // 如果未配置访问口令，直接放行
        if (!isAuthRequired()) {
            return true;
        }
        // 公开端点（检查口令是否配置/校验口令）
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/system/access-info") || uri.startsWith("/api/system/verify-token")) {
            return true;
        }
        // 从请求头获取 token
        String token = request.getHeader("X-Access-Token");
        if (token == null || !verify(token)) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"未授权访问：请在页面输入正确的访问口令\"}");
            return false;
        }
        return true;
    }
}
