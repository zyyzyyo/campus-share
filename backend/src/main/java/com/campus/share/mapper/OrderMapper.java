package com.campus.share.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.share.entity.*;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface OrderMapper extends BaseMapper<BorrowOrderEntity> {
    @Select("""
            SELECT COALESCE(picked_up_at,start_time) AS start_time, end_time, status FROM borrow_order
            WHERE item_id=#{itemId} AND end_time > #{now}
              AND status IN ('AWAITING_PICKUP', 'BORROWING', 'RETURN_REQUESTED')
            ORDER BY start_time, end_time, id
            """)
    List<ReservationSlot> reservations(@Param("itemId") Long itemId, @Param("now") LocalDateTime now);

    OrderRow findById(Long id);
    List<OrderRow> findPage(@Param("userId") Long userId, @Param("role") String role, @Param("offset") int offset, @Param("size") int size);
    long count(@Param("userId") Long userId, @Param("role") String role);

    @Select("""
            SELECT COUNT(*) FROM borrow_order WHERE item_id=#{itemId} AND id != #{excludedId}
              AND status IN ('AWAITING_PICKUP', 'BORROWING', 'RETURN_REQUESTED')
              AND COALESCE(picked_up_at,start_time) < #{end} AND end_time > #{start}
            """)
    int countConflicts(@Param("itemId") Long itemId, @Param("excludedId") Long excludedId,
                       @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Select("""
            SELECT COUNT(*) FROM borrow_order WHERE item_id=#{itemId} AND borrower_id=#{userId} AND status='PENDING_REVIEW'
              AND start_time < #{end} AND end_time > #{start}
            """)
    int countOwnPending(@Param("itemId") Long itemId, @Param("userId") Long userId,
                        @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Select("SELECT COUNT(*) FROM borrow_order WHERE item_id=#{itemId} AND id != #{id} AND status IN ('BORROWING','RETURN_REQUESTED')")
    int countHolding(@Param("itemId") Long itemId, @Param("id") Long id);

    @Update("""
            UPDATE borrow_order SET status=#{to}, pickup_expires_at=#{pickupExpiry}, updated_at=#{now}
            WHERE id=#{id} AND status=#{from}
            """)
    int changeStatus(@Param("id") Long id, @Param("from") String from, @Param("to") String to,
                     @Param("pickupExpiry") LocalDateTime pickupExpiry, @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO order_operation_log (order_id, operator_id, operator_type, from_status, to_status, remark, created_at)
            VALUES (#{orderId}, #{userId}, #{operatorType}, #{from}, #{to}, #{remark}, #{now})
            """)
    int log(@Param("orderId") Long orderId, @Param("userId") Long userId, @Param("operatorType") String operatorType,
            @Param("from") String from, @Param("to") String to, @Param("remark") String remark, @Param("now") LocalDateTime now);

    @Select("""
            SELECT id FROM borrow_order WHERE (status='PENDING_REVIEW' AND review_expires_at <= #{now})
              OR (status='AWAITING_PICKUP' AND pickup_expires_at <= #{now}) ORDER BY item_id, id LIMIT 100
            """)
    List<Long> overdueIds(LocalDateTime now);

    @Select("SELECT COUNT(*) FROM borrow_order WHERE borrower_id=#{userId} AND status IN ('BORROWING','RETURN_REQUESTED') AND end_time <= #{now}")
    int countOverdue(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Update("UPDATE borrow_order SET picked_up_at=#{now} WHERE id=#{id}")
    int recordPickup(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Update("UPDATE borrow_order SET cancel_reason=#{reason} WHERE id=#{id}")
    int cancellation(@Param("id") Long id, @Param("reason") String reason);

    @Update("UPDATE borrow_order SET renewal_status=#{status}, renewal_end_time=#{end}, updated_at=#{now} WHERE id=#{id}")
    int renewal(@Param("id") Long id, @Param("status") String status, @Param("end") LocalDateTime end, @Param("now") LocalDateTime now);

    @Update("UPDATE borrow_order SET end_time=#{end}, updated_at=#{now} WHERE id=#{id}")
    int extend(@Param("id") Long id, @Param("end") LocalDateTime end, @Param("now") LocalDateTime now);

    @Select("""
        SELECT o.id FROM borrow_order o WHERE o.status IN ('BORROWING','RETURN_REQUESTED') AND o.end_time <= #{until}
        AND NOT EXISTS (SELECT 1 FROM notification n WHERE n.order_id=o.id AND n.user_id=o.borrower_id
          AND n.event_key=CONCAT(o.id, ':', CASE WHEN o.end_time <= #{now} THEN 'overdue:' ELSE 'due:' END, CONCAT('',o.end_time)))
        ORDER BY o.item_id,o.id LIMIT 100
        """)
    List<Long> reminderIds(@Param("now") LocalDateTime now, @Param("until") LocalDateTime until);

    @Select("SELECT CONCAT('',end_time) FROM borrow_order WHERE id=#{id}")
    String endKey(Long id);

    @Update("UPDATE borrow_order SET renewal_sequence=renewal_sequence+1 WHERE id=#{id}")
    int nextRenewal(Long id);
}
