package com.campus.share.task;

import com.campus.share.service.OrderService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="app.orders.expiry-enabled", havingValue="true", matchIfMissing=true)
public class OrderExpiryTask {
    private final OrderService service;
    public OrderExpiryTask(OrderService service) { this.service=service; }
    @Scheduled(fixedDelay=60000, initialDelay=60000)
    public void expire() { service.expireOverdue(); }
}
