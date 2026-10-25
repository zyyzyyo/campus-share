package com.campus.share.service;

import com.campus.share.dto.OrderRequest;
import com.campus.share.vo.*;

public interface OrderService {
    PageResult<OrderView> list(Long userId, String role, int page, int size);
    OrderView create(OrderRequest request, Long userId);
    OrderView act(Long id, String action, Long userId);
    void expireOverdue();
}
