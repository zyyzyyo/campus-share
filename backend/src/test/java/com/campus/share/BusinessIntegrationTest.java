package com.campus.share;

import com.campus.share.service.OrderService;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:campus_business_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "app.orders.expiry-enabled=false", "app.uploads.directory=./target/test-uploads"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(BusinessIntegrationTest.TimeConfiguration.class)
class BusinessIntegrationTest {
    private static final Instant NOW=Instant.parse("2030-05-01T00:00:00Z");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired OrderService orderService;
    private User owner, borrower;

    @TestConfiguration
    static class TimeConfiguration {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
    }
    static class MutableClock extends Clock {
        volatile Instant value=NOW;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return value; }
    }
    record User(long id, String token) { }

    @BeforeEach
    void setup() throws Exception {
        clock.value=NOW;
        for (String table: List.of("order_operation_log","borrow_order","favorite","item_image","item","uploaded_image","auth_session","app_user"))
            jdbc.update("DELETE FROM " + table);
        owner=user("owner_01"); borrower=user("borrower_01");
    }

    @Test
    void publicListSupportsPaginationAndFiltersAndPrivateScopeRequiresLogin() throws Exception {
        createItem(owner, "相机"); createItem(owner, "台灯");
        mvc.perform(get("/api/items").param("q","相机").param("campus","东校区").param("categoryId","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].title").value("相机"));
        mvc.perform(get("/api/items").param("size","1").param("page","2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(get("/api/items").param("scope","mine")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/items").param("page","0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/items").param("scope","mine").header("Authorization",bearer(borrower)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void onlyOwnerCanEditAndUnlistEvenWhenNicknamesMatch() throws Exception {
        long item=createItem(owner,"相机");
        send("PUT","/api/items/"+item,borrower,itemBody("偷改的标题"))
                .andExpect(status().isForbidden());
        send("PATCH","/api/items/"+item+"/status",borrower,Map.of("status","OFF_SHELF"))
                .andExpect(status().isForbidden());
        send("PUT","/api/items/"+item,owner,itemBody("新标题")).andExpect(status().isOk());
        send("PATCH","/api/items/"+item+"/status",owner,Map.of("status","OFF_SHELF")).andExpect(status().isOk());
        mvc.perform(get("/api/items").param("availableOnly","true")).andExpect(jsonPath("$.data.total").value(0));
        apply(item,borrower,NOW.plusSeconds(3600),NOW.plusSeconds(7200)).andExpect(status().isConflict());
    }

    @Test
    void favoritesAreIsolatedAndPutIsIdempotent() throws Exception {
        long item=createItem(owner,"相机");
        send("PUT","/api/favorites/"+item,borrower,null).andExpect(status().isOk());
        send("PUT","/api/favorites/"+item,borrower,null).andExpect(status().isOk());
        mvc.perform(get("/api/favorites").header("Authorization",bearer(borrower))).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get("/api/favorites").header("Authorization",bearer(owner))).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/items").param("scope","favorites").header("Authorization",bearer(borrower)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        send("DELETE","/api/favorites/"+item,borrower,null).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM favorite",Integer.class)).isZero();
    }

    @Test
    void uploadedImageCanOnlyBeAttachedByItsUploader() throws Exception {
        byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");
        MockMultipartFile file=new MockMultipartFile("file","photo.png","image/png",png);
        String url=data(mvc.perform(multipart("/api/media").file(file).header("Authorization",bearer(owner)))
                .andExpect(status().isCreated())).path("url").asText();
        mvc.perform(get(url)).andExpect(status().isOk()).andExpect(content().bytes(png));
        Map<String,Object> body=new HashMap<>(itemBody("带图相机")); body.put("imageUrl",url);
        send("POST","/api/items",borrower,body).andExpect(status().isBadRequest());
        send("POST","/api/items",owner,body).andExpect(status().isCreated()).andExpect(jsonPath("$.data.image").value(url));
        mvc.perform(multipart("/api/media").file(new MockMultipartFile("file","bad.png","image/png","not an image".getBytes()))
                .header("Authorization",bearer(owner))).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/media").file(new MockMultipartFile("file","huge.png","image/png",new byte[1024*1024+1]))
                .header("Authorization",bearer(owner))).andExpect(status().isBadRequest());
    }

    @Test
    void pendingDoesNotReserveAndDuplicateOwnApplicationIsRejected() throws Exception {
        long item=createItem(owner,"相机");
        apply(item,owner,NOW.plusSeconds(3600),NOW.plusSeconds(7200)).andExpect(status().isBadRequest());
        apply(item,borrower,NOW.minusSeconds(1),NOW.plusSeconds(7200)).andExpect(status().isBadRequest());
        long first=data(apply(item,borrower,NOW.plusSeconds(3600),NOW.plusSeconds(7200)).andExpect(status().isCreated())).path("id").asLong();
        apply(item,borrower,NOW.plusSeconds(3600),NOW.plusSeconds(7200)).andExpect(status().isConflict());
        User third=user("third_01");
        apply(item,third,NOW.plusSeconds(3600),NOW.plusSeconds(7200)).andExpect(status().isCreated());
        action(first,"approve",borrower).andExpect(status().isForbidden());
        action(first,"cancel",third).andExpect(status().isForbidden());
        action(first,"cancel",borrower).andExpect(status().isOk());
        action(first,"cancel",borrower).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_operation_log WHERE order_id=?",Integer.class,first)).isEqualTo(2);
    }

    @Test
    void approvalConflictAndAdjacentIntervalsFollowHalfOpenRule() throws Exception {
        long item=createItem(owner,"相机"); User third=user("third_01");
        long first=data(apply(item,borrower,NOW.plusSeconds(3600),NOW.plusSeconds(7200))).path("id").asLong();
        long overlap=data(apply(item,third,NOW.plusSeconds(5400),NOW.plusSeconds(9000))).path("id").asLong();
        long adjacent=data(apply(item,borrower,NOW.plusSeconds(7200),NOW.plusSeconds(10800))).path("id").asLong();
        action(first,"approve",owner).andExpect(status().isOk());
        action(first,"approve",owner).andExpect(status().isOk());
        action(overlap,"approve",owner).andExpect(status().isConflict());
        action(adjacent,"approve",owner).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_operation_log WHERE order_id=? AND to_status='AWAITING_PICKUP'",Integer.class,first)).isEqualTo(1);
    }

    @Test
    void concurrentApprovalsOnlyApproveOneConflictingApplication() throws Exception {
        long item=createItem(owner,"相机"); User third=user("third_01");
        long first=data(apply(item,borrower,NOW.plusSeconds(3600),NOW.plusSeconds(7200))).path("id").asLong();
        long second=data(apply(item,third,NOW.plusSeconds(3600),NOW.plusSeconds(7200))).path("id").asLong();
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<Integer>> results=new ArrayList<>();
            for (long id: new long[]{first,second}) results.add(pool.submit(() -> { start.await(); return action(id,"approve",owner).andReturn().getResponse().getStatus(); }));
            start.countDown();
            List<Integer> statuses=new ArrayList<>();
            for (Future<Integer> result: results) statuses.add(result.get(15,TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(200,409);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM borrow_order WHERE status='AWAITING_PICKUP'",Integer.class)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void fullBorrowingLifecycleWorksAfterUnlistingAndLogsEachChangeOnce() throws Exception {
        long item=createItem(owner,"相机");
        long id=data(apply(item,borrower,NOW.plusSeconds(3600),NOW.plusSeconds(10800))).path("id").asLong();
        action(id,"approve",owner).andExpect(status().isOk());
        action(id,"pickup",owner).andExpect(status().isConflict());
        send("PATCH","/api/items/"+item+"/status",owner,Map.of("status","OFF_SHELF")).andExpect(status().isOk());
        clock.value=NOW.plusSeconds(3660);
        action(id,"pickup",owner).andExpect(status().isOk());
        action(id,"pickup",owner).andExpect(status().isOk());
        action(id,"request-return",borrower).andExpect(status().isOk());
        action(id,"complete",owner).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMPLETED"));
        action(id,"complete",owner).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_operation_log WHERE order_id=?",Integer.class,id)).isEqualTo(5);
        mvc.perform(get("/api/orders").param("role","lent").header("Authorization",bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/orders").header("Authorization",bearer(owner))).andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void failedOperationLogRollsBackOrderUpdate() throws Exception {
        long item=createItem(owner,"相机");
        long id=data(apply(item,borrower,NOW.plusSeconds(3600),NOW.plusSeconds(7200))).path("id").asLong();
        jdbc.execute("ALTER TABLE order_operation_log ADD CONSTRAINT test_fail_approval_log CHECK (to_status <> 'AWAITING_PICKUP')");
        try {
            action(id,"approve",owner).andExpect(status().isServiceUnavailable());
            assertThat(jdbc.queryForObject("SELECT status FROM borrow_order WHERE id=?",String.class,id)).isEqualTo("PENDING_REVIEW");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_operation_log WHERE order_id=?",Integer.class,id)).isEqualTo(1);
        } finally { jdbc.execute("ALTER TABLE order_operation_log DROP CONSTRAINT test_fail_approval_log"); }
    }

    @Test
    void overdueReviewAndPickupExpireWithoutAffectingBorrowingOrders() throws Exception {
        long a=createItem(owner,"相机"), b=createItem(owner,"台灯"), c=createItem(owner,"帐篷");
        long pending=data(apply(a,borrower,NOW.plusSeconds(30*3600),NOW.plusSeconds(32*3600))).path("id").asLong();
        long pickup=data(apply(b,borrower,NOW.plusSeconds(3600),NOW.plusSeconds(10*3600))).path("id").asLong();
        action(pickup,"approve",owner).andExpect(status().isOk());
        long borrowing=data(apply(c,borrower,NOW.plusSeconds(3600),NOW.plusSeconds(32*3600))).path("id").asLong();
        action(borrowing,"approve",owner).andExpect(status().isOk());
        clock.value=NOW.plusSeconds(3660); action(borrowing,"pickup",owner).andExpect(status().isOk());
        clock.value=NOW.plusSeconds(25*3600); orderService.expireOverdue(); orderService.expireOverdue();
        assertThat(jdbc.queryForObject("SELECT status FROM borrow_order WHERE id=?",String.class,pending)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM borrow_order WHERE id=?",String.class,pickup)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM borrow_order WHERE id=?",String.class,borrowing)).isEqualTo("BORROWING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_operation_log WHERE operator_type='SYSTEM'",Integer.class)).isEqualTo(2);
    }

    private User user(String username) throws Exception {
        JsonNode registered=data(send("POST","/api/auth/register",null,Map.of("username",username,"password","CampusPass123!","nickname","同名同学")).andExpect(status().isCreated()));
        JsonNode login=data(send("POST","/api/auth/login",null,Map.of("username",username,"password","CampusPass123!")).andExpect(status().isOk()));
        return new User(registered.path("id").asLong(),login.path("accessToken").asText());
    }
    private String bearer(User user) { return "Bearer " + user.token(); }
    private Map<String,Object> itemBody(String title) { return Map.of("title",title,"description","功能正常，附完整配件","categoryId",1,"campus","东校区","pickupLocation","图书馆"); }
    private long createItem(User user,String title) throws Exception { return data(send("POST","/api/items",user,itemBody(title)).andExpect(status().isCreated())).path("id").asLong(); }
    private ResultActions apply(long id,User user,Instant start,Instant end) throws Exception {
        return send("POST","/api/orders",user,Map.of("itemId",id,"startTime",start.toString(),"endTime",end.toString()));
    }
    private ResultActions action(long id,String action,User user) throws Exception { return send("PATCH","/api/orders/"+id+"/actions/"+action,user,null); }
    private JsonNode data(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsByteArray()).path("data"); }
    private ResultActions send(String method,String path,User user,Object body) throws Exception {
        var request=request(org.springframework.http.HttpMethod.valueOf(method),path);
        if (user!=null) request.header("Authorization",bearer(user));
        if (body!=null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        return mvc.perform(request);
    }
}
