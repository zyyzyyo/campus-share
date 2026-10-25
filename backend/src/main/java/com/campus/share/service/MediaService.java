package com.campus.share.service;

import com.campus.share.vo.MediaFile;
import org.springframework.web.multipart.MultipartFile;

public interface MediaService {
    String upload(MultipartFile file, Long userId);
    MediaFile read(String name);
}
