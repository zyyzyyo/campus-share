package com.campus.share.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface HealthMapper {
    @Select("SELECT 1")
    int ping();
}
