package com.campus.share;

import com.campus.share.dto.ItemRequest;
import com.campus.share.mapper.ItemMapper;
import com.campus.share.service.*;
import com.campus.share.vo.ItemView;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:campus_item_redis;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "app.orders.expiry-enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ItemRedisIntegrationTest.TimeConfiguration.class)
class ItemRedisIntegrationTest extends RedisTestSupport {
    private static final Instant NOW=Instant.parse("2030-05-01T15:59:00Z");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ItemService service;
    @Autowired ItemPopularityService popularity;
    @Autowired MutableClock clock;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean ItemMapper mapper;
    private User owner,viewer;
    record User(long id,String token) { }
    @TestConfiguration
    static class TimeConfiguration {
        @Bean @Primary MutableClock itemClock() { return new MutableClock(); }
    }
    static class MutableClock extends Clock {
        volatile Instant value=NOW;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return value; }
    }
    @BeforeEach
    void setup() throws Exception {
        clock.value=NOW;
        for (String table:List.of("notification","order_operation_log","borrow_order","favorite","item_image","item","uploaded_image","auth_session","app_user")) jdbc.update("DELETE FROM "+table);
        owner=account("item_owner"); viewer=account("item_viewer");
    }

    @Test
    void detailCacheHitsAvoidRepeatedDatabaseLoadsAndExpire() throws Exception {
        long id=item("相机"); clearInvocations(mapper);
        service.detail(id); service.detail(id);
        verify(mapper,times(1)).findById(id);
        assertThat(redis.getExpire(detailKey(id),TimeUnit.MILLISECONDS)).isBetween(1L,120000L);
        redis.expire(detailKey(id),Duration.ofMillis(20));
        await().atMost(Duration.ofSeconds(2)).until(() -> !Boolean.TRUE.equals(redis.hasKey(detailKey(id))));
        service.detail(id);
        verify(mapper,times(2)).findById(id);
    }

    @Test
    void committedEditsAndStatusChangesInvalidateAndReturnFreshData() throws Exception {
        long id=item("相机"); service.detail(id);
        send("PUT","/api/items/"+id,owner,body("新相机")).andExpect(status().isOk()).andExpect(jsonPath("$.data.title").value("新相机"));
        assertThat(redis.hasKey(detailKey(id))).isFalse();
        assertThat(service.detail(id).title()).isEqualTo("新相机");
        send("PATCH","/api/items/"+id+"/status",owner,Map.of("status","OFF_SHELF")).andExpect(status().isOk());
        assertThat(redis.hasKey(detailKey(id))).isFalse();
        assertThat(service.detail(id).status()).isEqualTo("OFF_SHELF");
    }

    @Test
    void rollbackNeverPublishesUncommittedDataOrInvalidatesCommittedCache() throws Exception {
        long id=item("相机"); service.detail(id);
        String original=redis.opsForValue().get(detailKey(id));
        TransactionTemplate tx=new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.execute(status -> {
            service.update(id,new ItemRequest("回滚标题","描述",1L,"东校区","图书馆",null),owner.id());
            assertThat(service.detail(id).title()).isEqualTo("回滚标题");
            assertThat(redis.opsForValue().get(detailKey(id))).isEqualTo(original);
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(service.detail(id).title()).isEqualTo("相机");
        assertThat(redis.opsForValue().get(detailKey(id))).isEqualTo(original);
    }

    @Test
    void oldInFlightLoadCannotRefillCacheAfterCommittedEdit() throws Exception {
        long id=item("相机"); ItemView old=mapper.findById(id);
        ItemDetailCache cache=new ItemDetailCache(redis,json,redisPrefix,Duration.ofMinutes(2));
        CountDownLatch loading=new CountDownLatch(1),edited=new CountDownLatch(1);
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try {
            Future<ItemView> stale=pool.submit(() -> cache.get(id,() -> {
                loading.countDown();
                try { if (!edited.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("edit timeout"); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
                return old;
            }));
            assertThat(loading.await(10,TimeUnit.SECONDS)).isTrue();
            service.update(id,new ItemRequest("新标题","描述",1L,"东校区","图书馆",null),owner.id());
            edited.countDown(); stale.get(10,TimeUnit.SECONDS);
            assertThat(redis.hasKey(detailKey(id))).isFalse();
            assertThat(service.detail(id).title()).isEqualTo("新标题");
        } finally { edited.countDown(); pool.shutdownNow(); }
    }

    @Test
    void corruptedCacheAndRedisFailureFallBackToDatabase() throws Exception {
        long id=item("相机");
        redis.opsForValue().set(detailKey(id),"broken-json",Duration.ofMinutes(2));
        ItemDetailCache isolated=new ItemDetailCache(redis,json,redisPrefix,Duration.ofMinutes(2));
        assertThat(isolated.get(id,() -> mapper.findById(id)).title()).isEqualTo("相机");
        assertThat(redis.hasKey(detailKey(id))).isFalse();
        StringRedisTemplate failed=mock(StringRedisTemplate.class);
        doThrow(new RedisConnectionFailureException("offline")).when(failed).execute(any(RedisScript.class),anyList(),any(Object[].class));
        ItemDetailCache offline=new ItemDetailCache(failed,json,redisPrefix,Duration.ofMinutes(2));
        AtomicInteger loads=new AtomicInteger();
        assertThat(offline.get(id,() -> { loads.incrementAndGet(); return mapper.findById(id); }).id()).isEqualTo(id);
        offline.get(id,() -> { loads.incrementAndGet(); return mapper.findById(id); });
        assertThat(loads).hasValue(2);
    }

    @Test
    void onlyRealDetailViewsCountAndOwnerAndRepeatedVisitorAreExcluded() throws Exception {
        long id=item("相机");
        mvc.perform(get("/api/items")).andExpect(status().isOk());
        mvc.perform(get("/api/items/"+id).header("Authorization",bearer(owner))).andExpect(status().isOk());
        send("PUT","/api/favorites/"+id,viewer,null).andExpect(status().isOk());
        assertThat(popularity.popular(6).items()).isEmpty();
        for (int i=0;i<3;i++) mvc.perform(get("/api/items/"+id).header("Authorization",bearer(viewer))).andExpect(status().isOk());
        assertThat(popularity.popular(6).items().get(0).views()).isEqualTo(1);
        // Multiple tokens for the same user share the same deduplication identity.
        String token=data(send("POST","/api/auth/login",null,Map.of("username","item_viewer","password","CampusPass123!"))).path("accessToken").asText();
        mvc.perform(get("/api/items/"+id).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        assertThat(popularity.popular(6).items().get(0).views()).isEqualTo(1);
        mvc.perform(get("/api/items/"+id).header("X-Forwarded-For","1.2.3.4")).andExpect(status().isOk());
        mvc.perform(get("/api/items/"+id).header("X-Forwarded-For","5.6.7.8")).andExpect(status().isOk());
        assertThat(popularity.popular(6).items().get(0).views()).isEqualTo(2);
        var seen=redis.keys(redisPrefix+":popular:*:seen:*");
        assertThat(seen).hasSize(2);
        for (String key:seen) assertThat(redis.getExpire(key,TimeUnit.MILLISECONDS)).isBetween(1L,600000L);
    }

    @Test
    void concurrentViewsOnlyCountOnceAndDeduplicationExpires() throws Exception {
        long id=item("相机"); ItemView item=mapper.findById(id);
        ExecutorService pool=Executors.newFixedThreadPool(8); CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<?>> jobs=new ArrayList<>();
            for(int i=0;i<12;i++) jobs.add(pool.submit(() -> { start.await(); popularity.record(item,viewer.id(),"127.0.0.1"); return null; }));
            start.countDown(); for(Future<?> job:jobs) job.get(10,TimeUnit.SECONDS);
            assertThat(popularity.popular(6).items().get(0).views()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
        for(String key:redis.keys(redisPrefix+":popular:*:seen:*")) redis.expire(key,Duration.ofMillis(20));
        await().atMost(Duration.ofSeconds(2)).until(() -> redis.keys(redisPrefix+":popular:*:seen:*").isEmpty());
        popularity.record(item,viewer.id(),"127.0.0.1");
        assertThat(popularity.popular(6).items().get(0).views()).isEqualTo(2);
    }

    @Test
    void leaderboardUsesCurrentMetadataFiltersOffShelfAndSkipsMissingMembers() throws Exception {
        long a=item("相机"),b=item("台灯");
        popularity.record(mapper.findById(a),null,"192.0.2.1");
        popularity.record(mapper.findById(b),null,"192.0.2.1"); popularity.record(mapper.findById(b),null,"192.0.2.2");
        assertThat(popularity.popular(1).items().get(0).item().id()).isEqualTo(b);
        service.update(b,new ItemRequest("新台灯","描述",1L,"东校区","新位置",null),owner.id());
        assertThat(popularity.popular(1).items().get(0).item().title()).isEqualTo("新台灯");
        service.changeStatus(b,"OFF_SHELF",owner.id());
        assertThat(popularity.popular(6).items()).hasSize(1);
        String key=rankingKey();
        for(int i=0;i<105;i++) redis.opsForZSet().add(key,String.valueOf(900000+i),100+i);
        assertThat(popularity.popular(1).items().get(0).item().id()).isEqualTo(a);
        mvc.perform(get("/api/items/popular").param("limit","0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/items/popular").param("limit","21")).andExpect(status().isBadRequest());
    }

    @Test
    void leaderboardSwitchesAtBeijingMidnightAndKeysExpire() throws Exception {
        long id=item("相机"); ItemView item=mapper.findById(id);
        popularity.record(item,null,"192.0.2.1"); popularity.record(item,null,"192.0.2.2");
        assertThat(redis.getExpire(rankingKey(),TimeUnit.SECONDS)).isBetween(1L,172800L);
        assertThat(popularity.popular(6).items().get(0).views()).isEqualTo(2);
        clock.value=Instant.parse("2030-05-01T16:00:00Z");
        assertThat(popularity.popular(6).items()).isEmpty();
        popularity.record(item,null,"192.0.2.1");
        assertThat(popularity.popular(6).items().get(0).views()).isEqualTo(1);
    }

    @Test
    void leaderboardFailureIsReportedWithoutBreakingDetail() throws Exception {
        long id=item("相机");
        StringRedisTemplate failed=mock(StringRedisTemplate.class);
        doThrow(new RedisConnectionFailureException("offline")).when(failed).opsForZSet();
        doThrow(new RedisConnectionFailureException("offline")).when(failed).execute(any(RedisScript.class),anyList(),any(Object[].class));
        ItemPopularityService offline=new ItemPopularityService(failed,mapper,clock,redisPrefix,Duration.ofMinutes(10));
        assertThatCode(() -> offline.record(mapper.findById(id),viewer.id(),"127.0.0.1")).doesNotThrowAnyException();
        assertThat(offline.popular(6).available()).isFalse();
        assertThat(offline.popular(6).items()).isEmpty();
        mvc.perform(get("/api/items/"+Long.MAX_VALUE)).andExpect(status().isNotFound());
        assertThat(popularity.popular(6).items()).isEmpty();
    }

    private String detailKey(long id) { return redisPrefix+":item:{"+id+"}:detail"; }
    private String rankingKey() { return redisPrefix+":popular:{"+clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()+"}:ranking"; }
    private String bearer(User user) { return "Bearer "+user.token(); }
    private User account(String username) throws Exception {
        long id=data(send("POST","/api/auth/register",null,Map.of("username",username,"password","CampusPass123!","nickname",username))).path("id").asLong();
        String token=data(send("POST","/api/auth/login",null,Map.of("username",username,"password","CampusPass123!"))).path("accessToken").asText();
        return new User(id,token);
    }
    private Map<String,Object> body(String title) { return Map.of("title",title,"description","功能正常","categoryId",1,"campus","东校区","pickupLocation","图书馆"); }
    private long item(String title) throws Exception { return data(send("POST","/api/items",owner,body(title)).andExpect(status().isCreated())).path("id").asLong(); }
    private JsonNode data(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsByteArray()).path("data"); }
    private ResultActions send(String method,String path,User user,Object body) throws Exception {
        var request=request(org.springframework.http.HttpMethod.valueOf(method),path);
        if(user!=null) request.header("Authorization",bearer(user));
        if(body!=null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        return mvc.perform(request);
    }
}
