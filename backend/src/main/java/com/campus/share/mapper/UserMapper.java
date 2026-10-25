package com.campus.share.mapper;

import com.campus.share.entity.UserAccount;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {
    @Select("SELECT id, username, password_hash, nickname, status, created_at FROM app_user WHERE username = #{username}")
    UserAccount findByUsername(String username);

    @Insert("""
            INSERT INTO app_user (username, password_hash, nickname, status, created_at, updated_at)
            VALUES (#{username}, #{passwordHash}, #{nickname}, 'ACTIVE', #{now}, #{now})
            """)
    int insert(@Param("username") String username, @Param("passwordHash") String passwordHash,
               @Param("nickname") String nickname, @Param("now") LocalDateTime now);
}
