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
class BusinessIntegrationTest extends RedisTestSupport {
    private static final Instant NOW=Instant.parse("2030-05-01T00:00:00Z");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired OrderService orderService;
    @Autowired com.campus.share.mapper.ItemMapper itemMapper;
    @Autowired com.campus.share.mapper.OrderMapper orderMapper;
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
        for (String table: List.of("notification","order_operation_log","borrow_order","favorite","item_image","item","uploaded_image","auth_session","app_user"))
            jdbc.update("DELETE FROM " + table);
        owner=user("owner_01"); borrower=user("borrower_01");
    }

    @Test
    void plusCrudPreservesItemStatusOwnershipAndCreationTime() throws Exception {
        long item=createItem(owner,"相机");
        var created=itemMapper.selectById(item);
        assertThat(created.getOwnerId()).isEqualTo(owner.id());
        assertThat(created.getStatus()).isEqualTo("ON_SHELF");
        assertThat(created.getCreatedAt()).isEqualTo(LocalDateTime.ofInstant(NOW,ZoneOffset.UTC));
        send("PATCH","/api/items/"+item+"/status",owner,Map.of("status","OFF_SHELF"))
                .andExpect(status().isOk());
        clock.value=NOW.plusSeconds(60);
        send("PUT","/api/items/"+item,owner,itemBody("修改后的相机")).andExpect(status().isOk());
        var updated=itemMapper.selectById(item);
        assertThat(updated.getTitle()).isEqualTo("修改后的相机");
        assertThat(updated.getStatus()).isEqualTo("OFF_SHELF");
        assertThat(updated.getOwnerId()).isEqualTo(owner.id());
        assertThat(updated.getCreatedAt()).isEqualTo(created.getCreatedAt());
        assertThat(updated.getUpdatedAt()).isEqualTo(LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));
        send("PATCH","/api/items/"+item+"/status",owner,Map.of("status","ON_SHELF"))
                .andExpect(status().isOk());
        long order=data(apply(item,borrower,NOW.plusSeconds(3600),NOW.plusSeconds(7200))).path("id").asLong();
        var saved=orderMapper.selectById(order);
        assertThat(saved.getItemId()).isEqualTo(item);
        assertThat(saved.getBorrowerId()).isEqualTo(borrower.id());
        assertThat(saved.getStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(saved.getStartTime()).isEqualTo(LocalDateTime.ofInstant(NOW.plusSeconds(3600),ZoneOffset.UTC));
        assertThat(saved.getCreatedAt()).isEqualTo(LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));
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
    void publicReservationsShowOnlyActiveIntervalsWithoutPersonalInformation() throws Exception {
        long item=createItem(owner,"相机"), other=createItem(owner,"台灯");
        long first=data(apply(item,borrower,NOW.plusSeconds(3600),NOW.plusSeconds(7200))).path("id").asLong();
        long second=data(apply(item,borrower,NOW.plusSeconds(10800),NOW.plusSeconds(14400))).path("id").asLong();
        mvc.perform(get("/api/items/"+item+"/reservations"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        action(second,"approve",owner).andExpect(status().isOk());
        action(first,"approve",owner).andExpect(status().isOk());
        JsonNode slots=data(mvc.perform(get("/api/items/"+item+"/reservations"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2)));
        assertThat(slots.get(0).path("startTime").asText()).isEqualTo(NOW.plusSeconds(3600).toString());
        assertThat(slots.get(0).path("endTime").asText()).isEqualTo(NOW.plusSeconds(7200).toString());
        assertThat(slots.get(0).size()).isEqualTo(3);
        assertThat(slots.get(0).path("status").asText()).isEqualTo("AWAITING_PICKUP");
        mvc.perform(get("/api/items/"+other+"/reservations")).andExpect(jsonPath("$.data.length()").value(0));
        for (String state: List.of("BORROWING", "RETURN_REQUESTED")) {
            jdbc.update("UPDATE borrow_order SET status=? WHERE id=?",state,first);
            mvc.perform(get("/api/items/"+item+"/reservations")).andExpect(jsonPath("$.data.length()").value(2));
        }
        for (String state: List.of("COMPLETED", "CANCELLED", "REJECTED", "EXPIRED", "PENDING_REVIEW")) {
            jdbc.update("UPDATE borrow_order SET status=? WHERE id=?",state,first);
            mvc.perform(get("/api/items/"+item+"/reservations")).andExpect(jsonPath("$.data.length()").value(1));
        }
        clock.value=NOW.plusSeconds(14400);
        mvc.perform(get("/api/items/"+item+"/reservations")).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/items/9223372036854775807/reservations")).andExpect(status().isNotFound());
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
        send("PATCH","/api/items/"+item+"/status",owner,Map.of("status","OFF_SHELF")).andExpect(status().isOk());
        action(id,"pickup",borrower).andExpect(status().isForbidden());
        action(id,"pickup",owner).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("BORROWING"));
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
    void earlyPickupRequiresApprovalAndNoUnreturnedItemAndRespectsDeadline() throws Exception {
        long item=createItem(owner,"相机");
        long first=data(apply(item,borrower,NOW.plusSeconds(3600),NOW.plusSeconds(7200))).path("id").asLong();
        long second=data(apply(item,borrower,NOW.plusSeconds(7200),NOW.plusSeconds(14400))).path("id").asLong();
        action(first,"pickup",owner).andExpect(status().isConflict());
        action(first,"approve",owner).andExpect(status().isOk());
        action(second,"approve",owner).andExpect(status().isOk());
        action(first,"pickup",owner).andExpect(status().isOk());
        action(second,"pickup",owner).andExpect(status().isConflict());
        action(first,"request-return",borrower).andExpect(status().isOk());
        action(second,"pickup",owner).andExpect(status().isConflict());
        action(first,"complete",owner).andExpect(status().isOk());
        action(second,"pickup",owner).andExpect(status().isOk());

        long other=createItem(owner,"台灯");
        long expired=data(apply(other,borrower,NOW.plusSeconds(3600),NOW.plusSeconds(7200))).path("id").asLong();
        action(expired,"approve",owner).andExpect(status().isOk());
        clock.value=NOW.plusSeconds(7200);
        action(expired,"pickup",owner).andExpect(status().isConflict());
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

    @Test
    void notificationsArePrivateAndRepeatedActionsDoNotSendTwice() throws Exception {
        long item=createItem(owner,"相机");
        long id=data(apply(item,borrower,NOW.plusSeconds(3600),NOW.plusSeconds(7200))).path("id").asLong();
        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notifications/unread-count").header("Authorization",bearer(owner))).andExpect(jsonPath("$.data").value(1));
        long notification=data(mvc.perform(get("/api/notifications").header("Authorization",bearer(owner))))
                .path("items").get(0).path("id").asLong();
        send("PATCH","/api/notifications/"+notification+"/read",borrower,null).andExpect(status().isOk());
        mvc.perform(get("/api/notifications/unread-count").header("Authorization",bearer(owner))).andExpect(jsonPath("$.data").value(1));
        send("PATCH","/api/notifications/"+notification+"/read",owner,null).andExpect(status().isOk());
        send("PATCH","/api/notifications/read-all",owner,null).andExpect(status().isOk());
        mvc.perform(get("/api/notifications/unread-count").header("Authorization",bearer(owner))).andExpect(jsonPath("$.data").value(0));
        action(id,"approve",owner).andExpect(status().isOk()); action(id,"approve",owner).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id=?",Integer.class,borrower.id())).isEqualTo(1);
        User third=user("third_01");
        mvc.perform(get("/api/orders/"+id).header("Authorization",bearer(third))).andExpect(status().isForbidden());
        mvc.perform(get("/api/notifications").param("page","0").header("Authorization",bearer(owner))).andExpect(status().isBadRequest());
    }

    @Test
    void renewalRequiresApprovalAndCanBeRejectedOrRetried() throws Exception {
        long id=borrowingOrder();
        Map<String,String> request=Map.of("endTime",NOW.plusSeconds(10800).toString());
        send("POST","/api/orders/"+id+"/renewal",owner,request).andExpect(status().isForbidden());
        send("POST","/api/orders/"+id+"/renewal",borrower,request).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.renewalStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.endTime").value(NOW.plusSeconds(7200).toString()));
        send("POST","/api/orders/"+id+"/renewal",borrower,request).andExpect(status().isOk());
        send("PATCH","/api/orders/"+id+"/renewal/approve",borrower,null).andExpect(status().isForbidden());
        send("PATCH","/api/orders/"+id+"/renewal/reject",owner,null).andExpect(status().isOk());
        send("POST","/api/orders/"+id+"/renewal",borrower,request).andExpect(status().isOk());
        send("PATCH","/api/orders/"+id+"/renewal/approve",owner,null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.endTime").value(NOW.plusSeconds(10800).toString()));
        send("PATCH","/api/orders/"+id+"/renewal/approve",owner,null).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE event_key LIKE '%renewal-request:%'",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE event_key LIKE '%renewal-review:%'",Integer.class)).isEqualTo(2);
    }

    @Test
    void renewalRechecksConflictsAtApprovalAndReturnInvalidatesPendingRequest() throws Exception {
        long id=borrowingOrder();
        long item=orderMapper.selectById(id).getItemId();
        send("POST","/api/orders/"+id+"/renewal",borrower,Map.of("endTime",NOW.plusSeconds(10800).toString())).andExpect(status().isOk());
        User third=user("third_01");
        long next=data(apply(item,third,NOW.plusSeconds(7200),NOW.plusSeconds(14400))).path("id").asLong();
        action(next,"approve",owner).andExpect(status().isOk());
        send("PATCH","/api/orders/"+id+"/renewal/approve",owner,null).andExpect(status().isConflict());
        action(id,"request-return",borrower).andExpect(status().isOk()).andExpect(jsonPath("$.data.renewalStatus").value("CANCELLED"));
        send("PATCH","/api/orders/"+id+"/renewal/approve",owner,null).andExpect(status().isConflict());
    }

    @Test
    void overdueRestrictsBookingAndRemindersAreDeduplicatedUntilReturn() throws Exception {
        long id=borrowingOrder(); long other=createItem(owner,"台灯");
        orderService.expireOverdue(); orderService.expireOverdue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE event_key LIKE '%:due:%'",Integer.class)).isEqualTo(1);
        clock.value=NOW.plusSeconds(7260);
        mvc.perform(get("/api/orders/"+id).header("Authorization",bearer(borrower))).andExpect(jsonPath("$.data.overdue").value(true)).andExpect(jsonPath("$.data.overdueMinutes").value(1));
        apply(other,borrower,NOW.plusSeconds(10800),NOW.plusSeconds(14400)).andExpect(status().isConflict());
        send("POST","/api/orders/"+id+"/renewal",borrower,Map.of("endTime",NOW.plusSeconds(10800).toString())).andExpect(status().isConflict());
        orderService.expireOverdue(); orderService.expireOverdue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE event_key LIKE '%:overdue:%'",Integer.class)).isEqualTo(2);
        action(id,"remind",borrower).andExpect(status().isForbidden());
        action(id,"remind",owner).andExpect(status().isOk()); action(id,"remind",owner).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE event_key LIKE '%:urge:%'",Integer.class)).isEqualTo(1);
        action(id,"request-return",borrower).andExpect(status().isOk()).andExpect(jsonPath("$.data.overdue").value(true));
        action(id,"complete",owner).andExpect(status().isOk()).andExpect(jsonPath("$.data.overdue").value(false));
        apply(other,borrower,NOW.plusSeconds(10800),NOW.plusSeconds(14400)).andExpect(status().isCreated());
    }

    @Test
    void ownerCancellationRequiresReasonAndCannotCancelAfterPickup() throws Exception {
        long item=createItem(owner,"相机");
        long id=data(apply(item,borrower,NOW.plusSeconds(3600),NOW.plusSeconds(7200))).path("id").asLong();
        action(id,"owner-cancel",owner).andExpect(status().isBadRequest());
        send("PATCH","/api/orders/"+id+"/actions/owner-cancel",borrower,Map.of("reason","不能交付")).andExpect(status().isForbidden());
        action(id,"approve",owner).andExpect(status().isOk());
        send("PATCH","/api/orders/"+id+"/actions/owner-cancel",owner,Map.of("reason","相机需要维修")).andExpect(status().isOk()).andExpect(jsonPath("$.data.cancelReason").value("相机需要维修"));
        send("PATCH","/api/orders/"+id+"/actions/owner-cancel",owner,Map.of("reason","相机需要维修")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE event_key=?",Integer.class,id+":owner-cancel")).isEqualTo(1);
        long held=borrowingOrder();
        send("PATCH","/api/orders/"+held+"/actions/owner-cancel",owner,Map.of("reason","不能交付")).andExpect(status().isConflict());
    }

    @Test
    void earlyPickupCannotSkipEarlierApprovedBookingAndActualIntervalIsReserved() throws Exception {
        long item=createItem(owner,"相机"); User third=user("third_01");
        long first=data(apply(item,borrower,NOW.plusSeconds(3600),NOW.plusSeconds(7200))).path("id").asLong();
        long later=data(apply(item,third,NOW.plusSeconds(7200),NOW.plusSeconds(10800))).path("id").asLong();
        action(first,"approve",owner).andExpect(status().isOk()); action(later,"approve",owner).andExpect(status().isOk());
        action(later,"pickup",owner).andExpect(status().isConflict());
        action(first,"pickup",owner).andExpect(status().isOk()).andExpect(jsonPath("$.data.pickedUpAt").value(NOW.toString()));
        mvc.perform(get("/api/items/"+item+"/reservations")).andExpect(jsonPath("$.data[0].startTime").value(NOW.toString()));
        apply(item,third,NOW.plusSeconds(60),NOW.plusSeconds(1800)).andExpect(status().isConflict());
    }

    @Test
    void notificationFailureRollsBackStateAndOperationLog() throws Exception {
        long item=createItem(owner,"相机");
        long id=data(apply(item,borrower,NOW.plusSeconds(3600),NOW.plusSeconds(7200))).path("id").asLong();
        jdbc.execute("ALTER TABLE notification ADD CONSTRAINT test_fail_notification CHECK (event_key NOT LIKE '%:approve')");
        try {
            action(id,"approve",owner).andExpect(status().isServiceUnavailable());
            assertThat(orderMapper.selectById(id).getStatus()).isEqualTo("PENDING_REVIEW");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_operation_log WHERE order_id=?",Integer.class,id)).isEqualTo(1);
        } finally { jdbc.execute("ALTER TABLE notification DROP CONSTRAINT test_fail_notification"); }
    }

    @Test
    void concurrentRenewalAndNextBookingApprovalCannotBothReserveSameTime() throws Exception {
        long id=borrowingOrder(); long item=orderMapper.selectById(id).getItemId(); User third=user("third_01");
        long next=data(apply(item,third,NOW.plusSeconds(7200),NOW.plusSeconds(14400))).path("id").asLong();
        send("POST","/api/orders/"+id+"/renewal",borrower,Map.of("endTime",NOW.plusSeconds(10800).toString())).andExpect(status().isOk());
        ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch start=new CountDownLatch(1);
        try {
            Future<Integer> renewal=pool.submit(() -> { start.await(); return send("PATCH","/api/orders/"+id+"/renewal/approve",owner,null).andReturn().getResponse().getStatus(); });
            Future<Integer> booking=pool.submit(() -> { start.await(); return action(next,"approve",owner).andReturn().getResponse().getStatus(); });
            start.countDown();
            assertThat(List.of(renewal.get(15,TimeUnit.SECONDS),booking.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void approvedRenewalCreatesNewDueReminderAndExpiredRequestCannotBeApproved() throws Exception {
        long id=borrowingOrder();
        orderService.expireOverdue();
        send("POST","/api/orders/"+id+"/renewal",borrower,Map.of("endTime",NOW.plusSeconds(10800).toString())).andExpect(status().isOk());
        send("PATCH","/api/orders/"+id+"/renewal/approve",owner,null).andExpect(status().isOk());
        orderService.expireOverdue(); orderService.expireOverdue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE event_key LIKE '%:due:%'",Integer.class)).isEqualTo(2);
        send("POST","/api/orders/"+id+"/renewal",borrower,Map.of("endTime",NOW.plusSeconds(14400).toString())).andExpect(status().isOk());
        clock.value=NOW.plusSeconds(10800);
        send("PATCH","/api/orders/"+id+"/renewal/approve",owner,null).andExpect(status().isConflict());
        orderService.expireOverdue();
        mvc.perform(get("/api/orders/"+id).header("Authorization",bearer(borrower)))
                .andExpect(jsonPath("$.data.renewalStatus").value("CANCELLED")).andExpect(jsonPath("$.data.overdue").value(true));
    }

    private long borrowingOrder() throws Exception {
        long item=createItem(owner,"相机");
        long id=data(apply(item,borrower,NOW.plusSeconds(3600),NOW.plusSeconds(7200))).path("id").asLong();
        action(id,"approve",owner).andExpect(status().isOk()); action(id,"pickup",owner).andExpect(status().isOk());
        return id;
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
