package com.campus.share.mapper;

import com.campus.share.vo.CategoryView;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CategoryMapper {
    @Select("SELECT id, name FROM category ORDER BY sort_order, id")
    List<CategoryView> findAll();
    @Select("SELECT COUNT(*) FROM category WHERE id = #{id}")
    int countById(Long id);
}
