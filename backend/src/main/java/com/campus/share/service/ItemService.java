package com.campus.share.service;

import com.campus.share.dto.*;
import com.campus.share.security.AuthenticatedUser;
import com.campus.share.vo.*;
import java.util.List;

public interface ItemService {
    List<CategoryView> categories();
    PageResult<ItemView> list(ItemQuery query, AuthenticatedUser user);
    ItemView detail(Long id);
    ItemView create(ItemRequest request, Long userId);
    ItemView update(Long id, ItemRequest request, Long userId);
    ItemView changeStatus(Long id, String status, Long userId);
    List<Long> favorites(Long userId);
    void addFavorite(Long id, Long userId);
    void removeFavorite(Long id, Long userId);
}
