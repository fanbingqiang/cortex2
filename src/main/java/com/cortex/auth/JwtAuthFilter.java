package com.cortex.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class JwtAuthFilter implements Filter {
    // JWT 认证过滤器，拦截需要登录的 API 路径

    private final JwtService jwtService;

    private static final String GUEST_MSG = "{\"code\":401,\"message\":\"请先注册账号后再使用AI助手功能\",\"data\":null}";

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String path = request.getRequestURI();

        // 需要登录的路径
        boolean needsAuth = "/api/auth/me".equals(path)
                || path.startsWith("/api/lab/assistant/")
                || path.startsWith("/api/lab/admin/")
                || path.equals("/api/lab/chat");

        if (needsAuth) {
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                Map<String, String> claims = jwtService.verifyToken(token);
                if (claims != null) {
                    request.setAttribute("userId", claims.get("userId"));
                    request.setAttribute("role", claims.get("role"));
                    request.setAttribute("orgId", claims.get("orgId"));
                    chain.doFilter(request, response);
                    return;
                }
            }
            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(401);
            response.getWriter().write(GUEST_MSG);
            return;
        }

        // 非受保护路径：尝试解析 token，但不强制
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            Map<String, String> claims = jwtService.verifyToken(authHeader.substring(7));
            if (claims != null) {
                request.setAttribute("userId", claims.get("userId"));
                request.setAttribute("role", claims.get("role"));
                request.setAttribute("orgId", claims.get("orgId"));
            }
        }
        chain.doFilter(request, response);
    }
}
