package com.campus.share.mapper;

import com.campus.share.entity.UploadedImage;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MediaMapper {
    @Select("SELECT storage_name, user_id, content_type FROM uploaded_image WHERE storage_name=#{name}")
    UploadedImage find(String name);
    @Insert("INSERT INTO uploaded_image (storage_name, user_id, content_type) VALUES (#{storageName}, #{userId}, #{contentType})")
    int insert(UploadedImage image);
}
