package com.campus.share.service.impl;

import com.campus.share.common.BusinessException;
import com.campus.share.dto.*;
import com.campus.share.entity.*;
import com.campus.share.mapper.*;
import com.campus.share.security.AuthenticatedUser;
import com.campus.share.service.ItemService;
import com.campus.share.vo.*;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ItemServiceImpl implements ItemService {
    private final ItemMapper items;
    private final CategoryMapper categories;
    private final FavoriteMapper favorites;
    private final MediaMapper media;
    private final Clock clock;
    public ItemServiceImpl(ItemMapper items, CategoryMapper categories, FavoriteMapper favorites, MediaMapper media, Clock clock) {
        this.items = items; this.categories = categories; this.favorites = favorites; this.media = media; this.clock = clock;
    }
    public List<CategoryView> categories() { return categories.findAll(); }
    public PageResult<ItemView> list(ItemQuery query, AuthenticatedUser user) {
        if (!Set.of("all", "mine", "favorites").contains(query.scope()) || !Set.of("recommended", "newest").contains(query.sort())
                || query.page() < 1 || query.page() > 100000 || query.size() < 1 || query.size() > 50
                || (query.q() != null && query.q().length() > 100)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "筛选或分页参数不合法");
        }
        if (!"all".equals(query.scope()) && user == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "请先登录");
        }
        Long userId = user == null ? null : user.id();
        return new PageResult<>(items.findPage(query, userId, query.getOffset()), items.count(query, userId), query.page(), query.size());
    }
    public ItemView detail(Long id) {
        ItemView item = items.findById(id);
        if (item == null) throw new BusinessException(HttpStatus.NOT_FOUND, "ITEM_NOT_FOUND", "物品不存在");
        return item;
    }
    @Transactional
    public ItemView create(ItemRequest request, Long userId) {
        validateReferences(request, userId);
        ItemEntity item = entity(request, userId);
        items.insert(item);
        saveImage(item.getId(), request.imageUrl());
        return detail(item.getId());
    }
    @Transactional
    public ItemView update(Long id, ItemRequest request, Long userId) {
        requireOwner(id, userId);
        validateReferences(request, userId);
        ItemEntity item = entity(request, userId); item.setId(id);
        items.update(item);
        items.deleteImages(id); saveImage(id, request.imageUrl());
        return detail(id);
    }
    @Transactional
    public ItemView changeStatus(Long id, String status, Long userId) {
        requireOwner(id, userId);
        items.updateStatus(id, status, LocalDateTime.now(clock));
        return detail(id);
    }
    public List<Long> favorites(Long userId) { return favorites.findIds(userId); }
    public void addFavorite(Long id, Long userId) {
        detail(id);
        try { favorites.insert(userId, id); } catch (DuplicateKeyException ignored) { /* PUT is idempotent. */ }
    }
    public void removeFavorite(Long id, Long userId) { favorites.delete(userId, id); }
    private void requireOwner(Long id, Long userId) {
        ItemLock item = items.lockById(id);
        if (item == null) throw new BusinessException(HttpStatus.NOT_FOUND, "ITEM_NOT_FOUND", "物品不存在");
        if (!item.ownerId().equals(userId)) throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "只能管理自己发布的物品");
    }
    private void validateReferences(ItemRequest request, Long userId) {
        if (categories.countById(request.categoryId()) == 0) throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "分类不存在");
        if (request.imageUrl() != null && !request.imageUrl().isBlank()) {
            String prefix = "/api/media/";
            UploadedImage image = request.imageUrl().startsWith(prefix) ? media.find(request.imageUrl().substring(prefix.length())) : null;
            if (image == null || !image.userId().equals(userId)) throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE", "只能使用自己上传的图片");
        }
    }
    private void saveImage(Long id, String url) { if (url != null && !url.isBlank()) items.insertImage(id, url); }
    private ItemEntity entity(ItemRequest request, Long userId) {
        ItemEntity item = new ItemEntity();
        item.setOwnerId(userId); item.setCategoryId(request.categoryId()); item.setTitle(request.title().strip());
        item.setDescription(request.description().strip()); item.setCampus(request.campus());
        item.setPickupLocation(request.pickupLocation().strip()); item.setNow(LocalDateTime.now(clock));
        return item;
    }
}
