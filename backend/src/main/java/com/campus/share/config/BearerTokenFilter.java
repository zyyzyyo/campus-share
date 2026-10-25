package com.campus.share.config;

import com.campus.share.service.AuthService;
import com.campus.share.security.AuthenticatedUser;
import com.campus.share.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class BearerTokenFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(BearerTokenFilter.class);
    private final AuthService auth;
    private final ObjectMapper json;

    BearerTokenFilter(AuthService auth, ObjectMapper json) {
        this.auth = auth;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            AuthenticatedUser user;
            try {
                user = auth.authenticate(header.substring(7));
            } catch (DataAccessException exception) {
                log.error("Session lookup failed", exception);
                response.setStatus(503);
                response.setContentType("application/json;charset=UTF-8");
                json.writeValue(response.getOutputStream(), ApiResponse.failure("SERVICE_UNAVAILABLE", "服务暂不可用，请稍后重试"));
                return;
            }
            if (user != null) {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(user, null, List.of()));
            }
        }
        chain.doFilter(request, response);
    }
}
