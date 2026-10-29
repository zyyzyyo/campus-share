package com.campus.share.mapper;

import com.campus.share.vo.NotificationView;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface NotificationMapper {
    @Insert("""
        INSERT INTO notification(user_id,order_id,event_key,content,created_at)
        SELECT #{userId},#{orderId},#{event},#{content},#{now}
        WHERE NOT EXISTS (SELECT 1 FROM notification WHERE user_id=#{userId} AND event_key=#{event})
        """)
    int insert(@Param("userId") Long userId, @Param("orderId") Long orderId, @Param("event") String event,
               @Param("content") String content, @Param("now") LocalDateTime now);

    @Select("SELECT id,order_id,content,read_at,created_at FROM notification WHERE user_id=#{userId} ORDER BY created_at DESC,id DESC LIMIT #{size} OFFSET #{offset}")
    List<NotificationView> list(@Param("userId") Long userId, @Param("offset") int offset, @Param("size") int size);
    @Select("SELECT COUNT(*) FROM notification WHERE user_id=#{userId}")
    long count(Long userId);
    @Select("SELECT COUNT(*) FROM notification WHERE user_id=#{userId} AND read_at IS NULL")
    long unread(Long userId);
    @Update("UPDATE notification SET read_at=#{now} WHERE user_id=#{userId} AND id=#{id} AND read_at IS NULL")
    int read(@Param("userId") Long userId, @Param("id") Long id, @Param("now") LocalDateTime now);
    @Update("UPDATE notification SET read_at=#{now} WHERE user_id=#{userId} AND read_at IS NULL")
    int readAll(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
