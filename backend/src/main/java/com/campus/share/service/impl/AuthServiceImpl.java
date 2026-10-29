package com.campus.share.service.impl;

import com.campus.share.dto.AuthRequests;
import com.campus.share.entity.UserAccount;
import com.campus.share.security.RedisSessionStore;
import com.campus.share.mapper.UserMapper;
import com.campus.share.security.AuthenticatedUser;
import com.campus.share.service.AuthService;
import com.campus.share.vo.LoginResult;
import com.campus.share.vo.UserView;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import com.campus.share.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthServiceImpl implements AuthService {
    private final UserMapper users;
    private final RedisSessionStore sessions;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final Duration tokenTtl;
    private final SecureRandom random = new SecureRandom();
    private final String dummyPasswordHash;

    public AuthServiceImpl(UserMapper users, RedisSessionStore sessions, PasswordEncoder passwords, Clock clock,
                       @Value("${app.auth.token-ttl}") Duration tokenTtl) {
        if (tokenTtl.toMillis() <= 0) throw new IllegalArgumentException("Token TTL must be at least 1 ms");
        this.users = users;
        this.sessions = sessions;
        this.passwords = passwords;
        this.clock = clock;
        this.tokenTtl = tokenTtl;
        this.dummyPasswordHash = passwords.encode("unavailable-account-dummy-password");
    }

    @Transactional
    public UserView register(AuthRequests.Register request) {
        validatePasswordBytes(request.password());
        String username = request.username().toLowerCase(Locale.ROOT);
        try {
            users.insert(username, passwords.encode(request.password()), request.nickname().strip(), now());
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "该账号已被注册");
        }
        return UserView.from(users.findByUsername(username));
    }

    public LoginResult login(AuthRequests.Login request) {
        validatePasswordBytes(request.password());
        UserAccount user = users.findByUsername(request.username().toLowerCase(Locale.ROOT));
        // Also run BCrypt for an unknown username to avoid an obvious timing shortcut.
        boolean passwordMatches = passwords.matches(request.password(), user == null ? dummyPasswordHash : user.passwordHash());
        if (user == null || !passwordMatches || !"ACTIVE".equals(user.status())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "账号或密码错误，或账号不可用");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = clock.instant().plus(tokenTtl);
        sessions.save(hash(token), user.id(), expiresAt);
        return new LoginResult(token, "Bearer", expiresAt, UserView.from(user));
    }

    public AuthenticatedUser authenticate(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        String tokenHash = hash(token);
        Long userId = sessions.findUserId(tokenHash);
        if (userId == null) return null;
        UserAccount user = users.findById(userId);
        if (user == null || !"ACTIVE".equals(user.status())) {
            sessions.delete(tokenHash);
            return null;
        }
        return new AuthenticatedUser(user.id(), user.username(), user.nickname(), tokenHash);
    }

    public void logout(AuthenticatedUser user) {
        sessions.delete(user.tokenHash());
    }

    private LocalDateTime now() { return LocalDateTime.now(clock); }

    private void validatePasswordBytes(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "密码的 UTF-8 编码不能超过 72 字节");
        }
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

}
