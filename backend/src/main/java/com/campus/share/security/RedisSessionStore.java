package com.campus.share.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisSessionStore {
    private final StringRedisTemplate redis;
    private final Clock clock;
    private final String prefix;
    public RedisSessionStore(StringRedisTemplate redis, Clock clock,
                             @Value("${app.redis.key-prefix}") String prefix) {
        this.redis = redis; this.clock = clock; this.prefix = prefix + ":session:";
    }
    public void save(String tokenHash, Long userId, Instant expiresAt) {
        Duration ttl = Duration.between(clock.instant(), expiresAt);
        if (ttl.toMillis() <= 0) throw new IllegalArgumentException("Session TTL must be at least 1 ms");
        // SET with expiration is atomic: no session key can be left without a TTL.
        redis.opsForValue().set(prefix + tokenHash, userId + ":" + expiresAt.toEpochMilli(), ttl);
    }
    public Long findUserId(String tokenHash) {
        String value = redis.opsForValue().get(prefix + tokenHash);
        if (value == null) return null;
        try {
            String[] fields = value.split(":", -1);
            if (fields.length != 2) throw new IllegalArgumentException("Invalid session format");
            long id = Long.parseLong(fields[0]);
            Instant expiresAt = Instant.ofEpochMilli(Long.parseLong(fields[1]));
            if (id <= 0) throw new IllegalArgumentException("Invalid user ID");
            if (!expiresAt.isAfter(clock.instant())) { delete(tokenHash); return null; }
            return id;
        } catch (IllegalArgumentException exception) {
            throw new DataRetrievalFailureException("Invalid Redis session data", exception);
        }
    }
    public void delete(String tokenHash) { redis.delete(prefix + tokenHash); }
}
