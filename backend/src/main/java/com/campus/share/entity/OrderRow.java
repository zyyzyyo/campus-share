package com.campus.share.entity;

import java.time.LocalDateTime;

public record OrderRow(Long id, Long itemId, Long borrowerId, Long ownerId, String status,
                       LocalDateTime startTime, LocalDateTime endTime, LocalDateTime reviewExpiresAt,
                       LocalDateTime pickupExpiresAt, String title, String owner, String borrower, String image,
                       LocalDateTime pickedUpAt, LocalDateTime renewalEndTime, String renewalStatus, String cancelReason, int renewalSequence) { }
