package com.campus.share.controller;

import com.campus.share.common.ApiResponse;
import com.campus.share.security.AuthenticatedUser;
import com.campus.share.service.NotificationService;
import com.campus.share.vo.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService service;
    public NotificationController(NotificationService service) { this.service=service; }
    @GetMapping
    public ApiResponse<PageResult<NotificationView>> list(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="12") int size) {
        return ApiResponse.success(service.list(user.id(),page,size));
    }
    @GetMapping("/unread-count")
    public ApiResponse<Long> unread(@AuthenticationPrincipal AuthenticatedUser user) { return ApiResponse.success(service.unread(user.id())); }
    @PatchMapping("/{id}/read")
    public ApiResponse<Void> read(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        service.read(user.id(),id); return ApiResponse.success(null);
    }
    @PatchMapping("/read-all")
    public ApiResponse<Void> readAll(@AuthenticationPrincipal AuthenticatedUser user) {
        service.readAll(user.id()); return ApiResponse.success(null);
    }
}
