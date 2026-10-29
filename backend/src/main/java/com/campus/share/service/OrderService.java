package com.campus.share.service;

import com.campus.share.dto.OrderRequest;
import com.campus.share.vo.*;

public interface OrderService {
    java.util.List<ReservationView> reservations(Long itemId);
    PageResult<OrderView> list(Long userId, String role, int page, int size);
    OrderView create(OrderRequest request, Long userId);
    OrderView act(Long id, String action, Long userId);
    OrderView act(Long id, String action, Long userId, String reason);
    OrderView requestRenewal(Long id, java.time.Instant endTime, Long userId);
    OrderView reviewRenewal(Long id, boolean approve, Long userId);
    OrderView detail(Long id, Long userId);
    void expireOverdue();
}
