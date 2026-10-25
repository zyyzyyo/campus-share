package com.campus.share.controller;

import com.campus.share.common.ApiResponse;
import com.campus.share.dto.OrderRequest;
import com.campus.share.security.AuthenticatedUser;
import com.campus.share.service.OrderService;
import com.campus.share.vo.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService service;
    public OrderController(OrderService service) { this.service=service; }
    @GetMapping
    public ApiResponse<PageResult<OrderView>> list(@RequestParam(defaultValue="borrowed") String role,
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="12") int size, @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.list(user.id(), role, page, size));
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrderView> create(@Valid @RequestBody OrderRequest request, @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.create(request, user.id()));
    }
    @PatchMapping("/{id}/actions/{action}")
    public ApiResponse<OrderView> act(@PathVariable Long id, @PathVariable String action, @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.act(id, action, user.id()));
    }
}
