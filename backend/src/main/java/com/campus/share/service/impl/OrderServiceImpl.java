package com.campus.share.service.impl;

import com.campus.share.common.BusinessException;
import com.campus.share.dto.OrderRequest;
import com.campus.share.entity.*;
import com.campus.share.mapper.*;
import com.campus.share.service.OrderService;
import com.campus.share.vo.*;
import java.time.*;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
public class OrderServiceImpl implements OrderService {
    private final ItemMapper items;
    private final OrderMapper orders;
    private final Clock clock;
    public OrderServiceImpl(ItemMapper items, OrderMapper orders, Clock clock) { this.items=items; this.orders=orders; this.clock=clock; }
    public PageResult<OrderView> list(Long userId, String role, int page, int size) {
        if (!Set.of("borrowed", "lent").contains(role) || page < 1 || page > 100000 || size < 1 || size > 50)
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "订单查询参数不合法");
        return new PageResult<>(orders.findPage(userId, role, (page-1)*size, size).stream().map(this::view).toList(),
                orders.count(userId, role), page, size);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderView create(OrderRequest request, Long userId) {
        LocalDateTime start=LocalDateTime.ofInstant(request.startTime(), ZoneOffset.UTC);
        LocalDateTime end=LocalDateTime.ofInstant(request.endTime(), ZoneOffset.UTC);
        LocalDateTime now=now();
        if (!start.isAfter(now) || !end.isAfter(start)) throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "开始时间必须在未来，结束时间必须晚于开始时间");
        ItemLock item=lockItem(request.itemId());
        if (item.ownerId().equals(userId)) throw new BusinessException(HttpStatus.BAD_REQUEST, "SELF_BOOKING", "不能借用自己发布的物品");
        requireOnShelf(item);
        if (orders.countConflicts(item.id(), 0L, start, end)>0) throw conflict("该时段已有有效预约，请选择其他时间");
        if (orders.countOwnPending(item.id(), userId, start, end)>0) throw conflict("你已提交过重叠时段的申请");
        BorrowOrderEntity order=new BorrowOrderEntity();
        order.setItemId(item.id()); order.setBorrowerId(userId); order.setStartTime(start); order.setEndTime(end);
        order.setReviewExpiresAt(earlier(start, now.plusHours(24))); order.setNow(now);
        orders.insert(order);
        orders.log(order.getId(), userId, "USER", null, "PENDING_REVIEW", "提交预约", now);
        return view(orders.findById(order.getId()));
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderView act(Long id, String action, Long userId) {
        OrderRow snapshot=requireOrder(id);
        ItemLock item=lockItem(snapshot.itemId());
        // Re-read after the item lock: every mutation for this item follows this order.
        OrderRow order=requireOrder(id);
        boolean borrowerAction=Set.of("cancel", "request-return").contains(action);
        if (!Set.of("cancel", "request-return", "approve", "reject", "pickup", "complete").contains(action))
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "不支持的订单操作");
        if (!(borrowerAction ? order.borrowerId() : order.ownerId()).equals(userId))
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权操作该订单");
        String next=switch(action) {
            case "approve" -> "AWAITING_PICKUP"; case "reject" -> "REJECTED"; case "cancel" -> "CANCELLED";
            case "pickup" -> "BORROWING"; case "request-return" -> "RETURN_REQUESTED"; default -> "COMPLETED";
        };
        if (order.status().equals(next)) return view(order);
        String expected=switch(action) {
            case "approve", "reject" -> "PENDING_REVIEW"; case "pickup" -> "AWAITING_PICKUP";
            case "request-return" -> "BORROWING"; case "complete" -> "RETURN_REQUESTED"; default -> order.status();
        };
        if (!order.status().equals(expected) || (action.equals("cancel") && !Set.of("PENDING_REVIEW", "AWAITING_PICKUP").contains(order.status())))
            throw conflict("订单状态已变化，请刷新后重试");
        LocalDateTime now=now(), pickupExpiry=order.pickupExpiresAt();
        if ((order.status().equals("PENDING_REVIEW") && !order.reviewExpiresAt().isAfter(now))
                || (order.status().equals("AWAITING_PICKUP") && !order.pickupExpiresAt().isAfter(now)))
            throw conflict("订单已超过处理时间，请刷新后重试");
        if (action.equals("approve")) {
            requireOnShelf(item);
            if (orders.countConflicts(item.id(), id, order.startTime(), order.endTime())>0) throw conflict("该时段与已通过的预约冲突");
            pickupExpiry=earlier(order.startTime().plusHours(2), order.endTime());
        }
        if (action.equals("pickup")) {
            if (now.isBefore(order.startTime())) throw conflict("尚未到预约开始时间");
            if (orders.countHolding(item.id(), id)>0) throw conflict("上一位同学尚未归还物品，暂时无法领取");
        }
        if (orders.changeStatus(id, order.status(), next, pickupExpiry, now)!=1) throw conflict("订单状态已变化，请刷新后重试");
        orders.log(id, userId, "USER", order.status(), next, action, now);
        return view(requireOrder(id));
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void expireOverdue() {
        LocalDateTime now=now();
        // Candidate rows are ordered by item_id so a batch never acquires item locks in reverse order.
        for (Long id: orders.overdueIds(now)) {
            OrderRow candidate=requireOrder(id); lockItem(candidate.itemId()); OrderRow order=requireOrder(id);
            boolean expired=(order.status().equals("PENDING_REVIEW") && !order.reviewExpiresAt().isAfter(now))
                    || (order.status().equals("AWAITING_PICKUP") && !order.pickupExpiresAt().isAfter(now));
            if (expired && orders.changeStatus(id, order.status(), "EXPIRED", order.pickupExpiresAt(), now)==1)
                orders.log(id, null, "SYSTEM", order.status(), "EXPIRED", "自动超时", now);
        }
    }
    private ItemLock lockItem(Long id) {
        ItemLock item=items.lockById(id);
        if (item==null) throw new BusinessException(HttpStatus.NOT_FOUND, "ITEM_NOT_FOUND", "物品不存在");
        return item;
    }
    private OrderRow requireOrder(Long id) {
        OrderRow order=orders.findById(id);
        if (order==null) throw new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "订单不存在");
        return order;
    }
    private void requireOnShelf(ItemLock item) { if (!item.status().equals("ON_SHELF")) throw conflict("物品已下架，暂不能接受新预约"); }
    private LocalDateTime now() { return LocalDateTime.now(clock); }
    private LocalDateTime earlier(LocalDateTime a, LocalDateTime b) { return a.isBefore(b)?a:b; }
    private BusinessException conflict(String message) { return new BusinessException(HttpStatus.CONFLICT, "ORDER_CONFLICT", message); }
    private OrderView view(OrderRow row) {
        return new OrderView(row.id(), row.itemId(), row.borrowerId(), row.ownerId(), row.status(),
                row.startTime().toInstant(ZoneOffset.UTC), row.endTime().toInstant(ZoneOffset.UTC), row.title(), row.owner(), row.borrower(), row.image());
    }
}
