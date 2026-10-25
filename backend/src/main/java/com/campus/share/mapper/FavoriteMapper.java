package com.campus.share.mapper;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface FavoriteMapper {
    @Select("SELECT item_id FROM favorite WHERE user_id=#{userId} ORDER BY created_at DESC")
    List<Long> findIds(Long userId);
    @Insert("INSERT INTO favorite (user_id, item_id) VALUES (#{userId}, #{itemId})")
    int insert(@Param("userId") Long userId, @Param("itemId") Long itemId);
    @Delete("DELETE FROM favorite WHERE user_id=#{userId} AND item_id=#{itemId}")
    int delete(@Param("userId") Long userId, @Param("itemId") Long itemId);
}
