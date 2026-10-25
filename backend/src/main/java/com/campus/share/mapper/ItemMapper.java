package com.campus.share.mapper;

import com.campus.share.dto.ItemQuery;
import com.campus.share.entity.ItemEntity;
import com.campus.share.entity.ItemLock;
import com.campus.share.vo.ItemView;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ItemMapper {
    List<ItemView> findPage(@Param("query") ItemQuery query, @Param("userId") Long userId, @Param("offset") int offset);
    long count(@Param("query") ItemQuery query, @Param("userId") Long userId);
    ItemView findById(Long id);

    @Select("SELECT id, owner_id, status FROM item WHERE id = #{id} FOR UPDATE")
    ItemLock lockById(Long id);

    @Insert("""
            INSERT INTO item (owner_id, category_id, title, description, campus, pickup_location, status, created_at, updated_at)
            VALUES (#{ownerId}, #{categoryId}, #{title}, #{description}, #{campus}, #{pickupLocation}, 'ON_SHELF', #{now}, #{now})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ItemEntity item);

    @Update("""
            UPDATE item SET category_id=#{categoryId}, title=#{title}, description=#{description},
              campus=#{campus}, pickup_location=#{pickupLocation}, updated_at=#{now} WHERE id=#{id}
            """)
    int update(ItemEntity item);

    @Update("UPDATE item SET status=#{status}, updated_at=#{now} WHERE id=#{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status, @Param("now") LocalDateTime now);

    @Delete("DELETE FROM item_image WHERE item_id=#{itemId}")
    int deleteImages(Long itemId);

    @Insert("INSERT INTO item_image (item_id, image_url, sort_order) VALUES (#{itemId}, #{url}, 0)")
    int insertImage(@Param("itemId") Long itemId, @Param("url") String url);
}
