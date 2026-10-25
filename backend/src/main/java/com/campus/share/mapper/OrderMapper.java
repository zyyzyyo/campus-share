package com.campus.share.mapper;

import com.campus.share.entity.*;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface OrderMapper {
    OrderRow findById(Long id);
    List<OrderRow> findPage(@Param("userId") Long userId, @Param("role") String role, @Param("offset") int offset, @Param("size") int size);
    long count(@Param("userId") Long userId, @Param("role") String role);

    @Insert("""
            INSERT INTO borrow_order (item_id, borrower_id, start_time, end_time, status, review_expires_at, created_at, updated_at)
            VALUES (#{itemId}, #{borrowerId}, #{startTime}, #{endTime}, 'PENDING_REVIEW', #{reviewExpiresAt}, #{now}, #{now})
            """)
    @Options(useGeneratedKeys=true, keyProperty="id")
    int insert(BorrowOrderEntity order);

    @Select("""
            SELECT COUNT(*) FROM borrow_order WHERE item_id=#{itemId} AND id != #{excludedId}
              AND status IN ('AWAITING_PICKUP', 'BORROWING', 'RETURN_REQUESTED')
              AND start_time < #{end} AND end_time > #{start}
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
}
