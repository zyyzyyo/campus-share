package com.campus.share;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest extends RedisTestSupport {
    private static final String PASSWORD = "CampusPass123!";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;

    @BeforeEach
    void cleanAccounts() {
        jdbc.update("DELETE FROM auth_session");
        jdbc.update("DELETE FROM app_user");
    }

    @Test
    void migrationCreatesBusinessTablesAndSeedsCategories() throws Exception {
        for (String table : new String[]{"app_user", "auth_session", "category", "item", "item_image", "borrow_order", "order_operation_log"}) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)).isNotNull();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM category", Integer.class)).isEqualTo(5);
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("UP"));
    }

    @Test
    void registrationHashesPasswordAndDoesNotExposeIt() throws Exception {
        register("Alice_01").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.username").value("alice_01"))
                .andExpect(jsonPath("$.data.nickname").value("林同学"))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
        String stored = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE username = 'alice_01'", String.class);
        assertThat(stored).isNotEqualTo(PASSWORD).startsWith("$2");
        assertThat(passwords.matches(PASSWORD, stored)).isTrue();
    }

    @Test
    void duplicateUsernameIsRejectedRegardlessOfCase() throws Exception {
        register("Alice_01").andExpect(status().isCreated());
        register("ALICE_01").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USERNAME_EXISTS"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class)).isEqualTo(1);
    }

    @Test
    void rejectsInvalidFieldsMalformedJsonAndExtraIdentityFields() throws Exception {
        postJson("/api/auth/register", Map.of("username", "a", "password", "short", "nickname", "林"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        postJson("/api/auth/register", Map.of("username", "alice_01", "password", PASSWORD, "nickname", " "))
                .andExpect(status().isBadRequest());
        postJson("/api/auth/register", Map.of("username", "alice_01", "password", PASSWORD, "nickname", "林", "id", 100))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class)).isZero();
    }

    @Test
    void rejectsPasswordsAboveBcryptByteLimit() throws Exception {
        postJson("/api/auth/register", Map.of("username", "alice_01", "password", "密".repeat(25), "nickname", "林"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void loginIssuesOpaqueTokenAndOnlyStoresItsHash() throws Exception {
        register("alice_01").andExpect(status().isCreated());
        JsonNode login = login("ALICE_01", PASSWORD);
        String token = login.path("accessToken").asText();
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(login.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(login.path("expiresAt").asText()).endsWith("Z");
        String storedHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        String key = redisPrefix + ":session:" + storedHash;
        assertThat(redis.opsForValue().get(key)).startsWith(login.path("user").path("id").asText() + ":");
        assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isBetween(28790L, 28800L);
        assertThat(redis.hasKey(redisPrefix + ":session:" + token)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_session", Integer.class)).isZero();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value("alice_01"))
                .andExpect(jsonPath("$.data.tokenHash").doesNotExist())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void incorrectPasswordAndUnknownAccountUseSameError() throws Exception {
        register("alice_01").andExpect(status().isCreated());
        for (String username : new String[]{"alice_01", "unknown_user"}) {
            postJson("/api/auth/login", Map.of("username", username, "password", "wrongPassword!"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_session", Integer.class)).isZero();
    }

    @Test
    void missingAndInvalidTokensCannotAccessCurrentUser() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        for (String token : new String[]{"garbage", "x".repeat(43)}) {
            mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void logoutRevokesOnlyTheCurrentSession() throws Exception {
        register("alice_01").andExpect(status().isCreated());
        String first = login("alice_01", PASSWORD).path("accessToken").asText();
        String second = login("alice_01", PASSWORD).path("accessToken").asText();
        assertThat(second).isNotEqualTo(first);
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + first)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + first)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + second)).andExpect(status().isOk());
        assertThat(redis.keys(redisPrefix + ":session:*")).hasSize(1);
    }

    @Test
    void expiredTokensCannotAuthenticate() throws Exception {
        register("alice_01").andExpect(status().isCreated());
        String token = login("alice_01", PASSWORD).path("accessToken").asText();
        String tokenHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
        String key = redisPrefix + ":session:" + tokenHash;
        redis.expire(key, Duration.ofMillis(100));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3))
                .until(() -> !Boolean.TRUE.equals(redis.hasKey(key)));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test
    void disablingAccountBlocksLoginAndExistingTokens() throws Exception {
        register("alice_01").andExpect(status().isCreated());
        String token = login("alice_01", PASSWORD).path("accessToken").asText();
        jdbc.update("UPDATE app_user SET status = 'DISABLED'");
        postJson("/api/auth/login", Map.of("username", "alice_01", "password", PASSWORD))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    private ResultActions register(String username) throws Exception {
        return postJson("/api/auth/register", Map.of("username", username, "password", PASSWORD, "nickname", " 林同学 "));
    }

    private JsonNode login(String username, String password) throws Exception {
        String result = postJson("/api/auth/login", Map.of("username", username, "password", password))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(result).path("data");
    }

    private ResultActions postJson(String path, Object body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)));
    }
}
