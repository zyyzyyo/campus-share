package com.campus.share.mapper;

import com.campus.share.security.AuthenticatedUser;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SessionMapper {
    @Insert("""
            INSERT INTO auth_session (user_id, token_hash, expires_at, created_at)
            VALUES (#{userId}, #{tokenHash}, #{expiresAt}, #{now})
            """)
    int insert(@Param("userId") Long userId, @Param("tokenHash") String tokenHash,
               @Param("expiresAt") LocalDateTime expiresAt, @Param("now") LocalDateTime now);

    @Select("""
            SELECT u.id, u.username, u.nickname, s.token_hash
            FROM auth_session s JOIN app_user u ON u.id = s.user_id
            WHERE s.token_hash = #{tokenHash} AND s.expires_at > #{now} AND u.status = 'ACTIVE'
            """)
    AuthenticatedUser findActive(@Param("tokenHash") String tokenHash, @Param("now") LocalDateTime now);

    @Delete("DELETE FROM auth_session WHERE token_hash = #{tokenHash}")
    int deleteByHash(String tokenHash);
}
