package com.campus.share;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import redis.embedded.RedisServer;

// A real, private Redis process. Never connects to or flushes a developer's Redis.
abstract class RedisTestSupport {
    private static final RedisServer server;
    private static final int port;
    protected static final String redisPrefix = "campus-test:" + UUID.randomUUID();
    static {
        try {
            try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
                port = socket.getLocalPort();
            }
            server = RedisServer.newRedisServer().port(port).setting("bind 127.0.0.1")
                    .setting("save \"\"").setting("appendonly no").build();
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { server.stop(); } catch (IOException ignored) { }
            }));
        } catch (IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> port);
        registry.add("spring.data.redis.database", () -> 0);
        registry.add("spring.data.redis.username", () -> "");
        registry.add("spring.data.redis.password", () -> "");
        registry.add("app.redis.key-prefix", () -> redisPrefix);
    }
    @Autowired protected StringRedisTemplate redis;
    @BeforeEach
    void clearPrivateRedisKeys() {
        var keys = redis.keys(redisPrefix + ":*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }
}
