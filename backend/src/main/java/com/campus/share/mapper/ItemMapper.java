package com.campus.share.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.share.dto.ItemQuery;
import com.campus.share.entity.ItemEntity;
import com.campus.share.entity.ItemLock;
import com.campus.share.vo.ItemView;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ItemMapper extends BaseMapper<ItemEntity> {
    List<ItemView> findPage(@Param("query") ItemQuery query, @Param("userId") Long userId, @Param("offset") int offset);
    long count(@Param("query") ItemQuery query, @Param("userId") Long userId);
    ItemView findById(Long id);
    List<ItemView> findPublishedByIds(@Param("ids") List<Long> ids);

    @Select("SELECT id, owner_id, status FROM item WHERE id = #{id} FOR UPDATE")
    ItemLock lockById(Long id);

    @Delete("DELETE FROM item_image WHERE item_id=#{itemId}")
    int deleteImages(Long itemId);

    @Insert("INSERT INTO item_image (item_id, image_url, sort_order) VALUES (#{itemId}, #{url}, 0)")
    int insertImage(@Param("itemId") Long itemId, @Param("url") String url);
}
