package com.campus.share.vo;

import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonFormat;

public record NotificationView(Long id, Long orderId, String content,
        @JsonFormat(pattern="yyyy-MM-dd'T'HH:mm:ss'Z'") LocalDateTime readAt,
        @JsonFormat(pattern="yyyy-MM-dd'T'HH:mm:ss'Z'") LocalDateTime createdAt) { }
