package com.campus.share;

import com.campus.share.security.RedisSessionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:redis_rate_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.rate-limit.login.max-requests=3", "app.rate-limit.login.window=10s",
        "app.rate-limit.register.max-requests=2", "app.rate-limit.register.window=10s",
        "app.rate-limit.booking.max-requests=2", "app.rate-limit.booking.window=10s"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RedisRateLimitIntegrationTest extends RedisTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean RedisSessionStore sessions;
    @MockitoSpyBean StringRedisTemplate redisSpy;
    @BeforeEach
    void cleanAccounts() { jdbc.update("DELETE FROM app_user"); }

    @Test
    void loginCountsFailuresIgnoresForgedForwardedHeadersAndRecoversAfterExpiry() throws Exception {
        for (int i=0; i<3; i++) login("unknown", "wrongPassword!").andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").header("X-Forwarded-For", "8.8.8.8")
                        .header("X-Real-IP", "8.8.4.4").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("username","unknown", "password","wrongPassword!"))))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().string("Retry-After", org.hamcrest.Matchers.matchesPattern("[1-9][0-9]*")));
        mvc.perform(post("/api/auth/login").with(r -> { r.setRemoteAddr("192.0.2.2"); return r; })
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        var keys=redis.keys(redisPrefix+":rate:login:*");
        assertThat(keys).hasSize(2);
        for (String key: keys) redis.expire(key, Duration.ofMillis(100));
        await().atMost(Duration.ofSeconds(3)).until(() -> redis.keys(redisPrefix+":rate:login:*").isEmpty());
        login("unknown", "wrongPassword!").andExpect(status().isUnauthorized());
    }

    @Test
    void registrationAndLoginHaveIndependentLimitsAndPublicReadsRemainAvailable() throws Exception {
        for (int i=0; i<2; i++) send(post("/api/auth/register"), Map.of()).andExpect(status().isBadRequest());
        send(post("/api/auth/register"), Map.of()).andExpect(status().isTooManyRequests());
        send(post("/api/auth/login"), Map.of()).andExpect(status().isBadRequest());
        mvc.perform(get("/api/items")).andExpect(status().isOk());
    }

    @Test
    void concurrentRequestsCannotExceedAtomicLimit() throws Exception {
        var pool=Executors.newFixedThreadPool(12);
        var start=new CountDownLatch(1);
        try {
            List<Future<Integer>> tasks=new ArrayList<>();
            for (int i=0; i<12; i++) tasks.add(pool.submit(() -> {
                start.await();
                return send(post("/api/auth/login"), Map.of()).andReturn().getResponse().getStatus();
            }));
            start.countDown();
            List<Integer> statuses=new ArrayList<>();
            for (var task: tasks) statuses.add(task.get(5,TimeUnit.SECONDS));
            assertThat(statuses.stream().filter(s -> s==400).count()).isEqualTo(3);
            assertThat(statuses.stream().filter(s -> s==429).count()).isEqualTo(9);
            for (String key: redis.keys(redisPrefix+":rate:login:*")) {
                assertThat(redis.getExpire(key)).isPositive();
                assertThat(redis.opsForValue().get(key)).isEqualTo("3");
            }
        } finally { pool.shutdownNow(); }
    }

    @Test
    void bookingQuotaIsSharedBySameUsersSessionsAndIsolatedBetweenUsers() throws Exception {
        register("alice_01"); register("bob_01");
        String first=token("alice_01"), second=token("alice_01"), other=token("bob_01");
        send(post("/api/orders").header("Authorization","Bearer "+first),Map.of()).andExpect(status().isBadRequest());
        send(post("/api/orders").header("Authorization","Bearer "+second),Map.of()).andExpect(status().isBadRequest());
        send(post("/api/orders").header("Authorization","Bearer "+first),Map.of()).andExpect(status().isTooManyRequests());
        send(post("/api/orders").header("Authorization","Bearer "+other),Map.of()).andExpect(status().isBadRequest());
        send(post("/api/orders"),Map.of()).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/orders").header("Authorization","Bearer "+first)).andExpect(status().isOk());
    }

    @Test
    void authenticationRedisFailureIs503AndDoesNotInvalidateClientIdentity() throws Exception {
        doThrow(new RedisConnectionFailureException("test outage")).when(sessions).findUserId(anyString());
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+"a".repeat(43)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void failedSessionWriteCannotIssueToken() throws Exception {
        register("alice_01");
        doThrow(new RedisConnectionFailureException("test outage")).when(sessions).save(anyString(), anyLong(), any(Instant.class));
        login("alice_01", "CampusPass123!").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data").isEmpty());
        assertThat(redis.keys(redisPrefix+":session:*")).isEmpty();
    }

    @Test
    void failedLogoutDoesNotPretendToRevokeSession() throws Exception {
        register("alice_01"); String token=token("alice_01");
        doThrow(new RedisConnectionFailureException("test outage")).when(sessions).delete(anyString());
        mvc.perform(post("/api/auth/logout").header("Authorization","Bearer "+token))
                .andExpect(status().isServiceUnavailable());
        assertThat(redis.keys(redisPrefix+":session:*")).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void limiterRedisFailureReturns503WithoutExecutingRegistration() throws Exception {
        doThrow(new RedisConnectionFailureException("test outage")).when(redisSpy)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));
        send(post("/api/auth/register"),Map.of("username","alice_01","password","CampusPass123!","nickname","林"))
                .andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user",Integer.class)).isZero();
    }

    private void register(String name) throws Exception {
        send(post("/api/auth/register"),Map.of("username",name,"password","CampusPass123!","nickname","林"))
                .andExpect(status().isCreated());
    }
    private String token(String name) throws Exception {
        return json.readTree(login(name,"CampusPass123!").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).path("data").path("accessToken").asText();
    }
    private ResultActions login(String name,String password) throws Exception {
        return send(post("/api/auth/login"),Map.of("username",name,"password",password));
    }
    private ResultActions send(MockHttpServletRequestBuilder request,Object body) throws Exception {
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)));
    }
}
