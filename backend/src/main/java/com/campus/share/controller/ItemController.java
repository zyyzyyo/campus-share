package com.campus.share.controller;

import com.campus.share.common.ApiResponse;
import com.campus.share.dto.*;
import com.campus.share.security.AuthenticatedUser;
import com.campus.share.service.ItemService;
import com.campus.share.vo.*;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class ItemController {
    private final ItemService service;
    public ItemController(ItemService service) { this.service = service; }
    @GetMapping("/api/categories")
    public ApiResponse<List<CategoryView>> categories() { return ApiResponse.success(service.categories()); }
    @GetMapping("/api/items")
    public ApiResponse<PageResult<ItemView>> list(
            @RequestParam(defaultValue="all") String scope, @RequestParam(defaultValue="") String q,
            @RequestParam(required=false) Long categoryId, @RequestParam(defaultValue="") String campus,
            @RequestParam(defaultValue="false") boolean availableOnly, @RequestParam(defaultValue="recommended") String sort,
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="12") int size,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.list(new ItemQuery(scope, q.strip(), categoryId, campus, availableOnly, sort, page, size), user));
    }
    @GetMapping("/api/items/{id}")
    public ApiResponse<ItemView> detail(@PathVariable Long id) { return ApiResponse.success(service.detail(id)); }
    @PostMapping("/api/items") @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ItemView> create(@Valid @RequestBody ItemRequest request, @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.create(request, user.id()));
    }
    @PutMapping("/api/items/{id}")
    public ApiResponse<ItemView> update(@PathVariable Long id, @Valid @RequestBody ItemRequest request, @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.update(id, request, user.id()));
    }
    @PatchMapping("/api/items/{id}/status")
    public ApiResponse<ItemView> status(@PathVariable Long id, @Valid @RequestBody ItemStatusRequest request, @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.changeStatus(id, request.status(), user.id()));
    }
    @GetMapping("/api/favorites")
    public ApiResponse<List<Long>> favorites(@AuthenticationPrincipal AuthenticatedUser user) { return ApiResponse.success(service.favorites(user.id())); }
    @PutMapping("/api/favorites/{id}")
    public ApiResponse<Void> favorite(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        service.addFavorite(id, user.id()); return ApiResponse.success(null);
    }
    @DeleteMapping("/api/favorites/{id}")
    public ApiResponse<Void> unfavorite(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        service.removeFavorite(id, user.id()); return ApiResponse.success(null);
    }
}
