package com.campus.share.config;

import com.campus.share.common.ApiResponse;
import com.campus.share.security.AuthenticatedUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
            local count = tonumber(redis.call('GET', KEYS[1]) or '0')
            local ttl = redis.call('PTTL', KEYS[1])
            if count >= tonumber(ARGV[1]) and ttl > 0 then return ttl end
            if ttl <= 0 then redis.call('DEL', KEYS[1]) end
            local next = redis.call('INCR', KEYS[1])
            if next == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
            return 0
            """, Long.class);
    private record Policy(String name, int limit, Duration window) {
        Policy {
            if (limit <= 0 || window.toMillis() <= 0) throw new IllegalArgumentException("Invalid rate limit policy");
        }
    }
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final String prefix;
    private final Policy login, register, booking;
    public RateLimitInterceptor(StringRedisTemplate redis, ObjectMapper json,
            @Value("${app.redis.key-prefix}") String prefix,
            @Value("${app.rate-limit.login.max-requests}") int loginLimit,
            @Value("${app.rate-limit.login.window}") Duration loginWindow,
            @Value("${app.rate-limit.register.max-requests}") int registerLimit,
            @Value("${app.rate-limit.register.window}") Duration registerWindow,
            @Value("${app.rate-limit.booking.max-requests}") int bookingLimit,
            @Value("${app.rate-limit.booking.window}") Duration bookingWindow) {
        this.redis = redis; this.json = json; this.prefix = prefix + ":rate:";
        login = new Policy("login", loginLimit, loginWindow);
        register = new Policy("register", registerLimit, registerWindow);
        booking = new Policy("booking", bookingLimit, bookingWindow);
    }
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!"POST".equals(request.getMethod())) return true;
        String path = request.getServletPath();
        if (path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
        Policy policy = switch (path) {
            case "/api/auth/login" -> login;
            case "/api/auth/register" -> register;
            case "/api/orders" -> booking;
            default -> null;
        };
        if (policy == null) return true;
        String identity;
        if (policy == booking) {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedUser user)) return true;
            identity = "user:" + user.id();
        } else {
            // Forwarded headers are untrusted; deployment must configure a trusted proxy separately.
            identity = "ip:" + hash(request.getRemoteAddr());
        }
        Long retryMillis;
        try {
            retryMillis = redis.execute(SCRIPT, List.of(prefix + policy.name() + ":" + identity),
                    String.valueOf(policy.limit()), String.valueOf(policy.window().toMillis()));
            if (retryMillis == null) throw new IllegalStateException("Missing Redis script result");
        } catch (DataAccessException | IllegalStateException exception) {
            log.error("Redis rate limiter unavailable", exception);
            writeError(response, 503, "SERVICE_UNAVAILABLE", "服务暂不可用，请稍后重试");
            return false;
        }
        if (retryMillis > 0) {
            response.setHeader("Retry-After", String.valueOf((retryMillis + 999) / 1000));
            writeError(response, 429, "RATE_LIMITED", "操作过于频繁，请稍后重试");
            return false;
        }
        return true;
    }
    private void writeError(HttpServletResponse response, int status, String code, String message) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getOutputStream(), ApiResponse.failure(code, message));
    }
    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
