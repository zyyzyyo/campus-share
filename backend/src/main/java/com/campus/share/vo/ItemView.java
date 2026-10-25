package com.campus.share.vo;

import java.time.LocalDateTime;

public record ItemView(Long id, Long ownerId, String owner, Long categoryId, String category,
                       String title, String description, String campus, String pickupLocation,
                       String status, String image, LocalDateTime createdAt) { }
