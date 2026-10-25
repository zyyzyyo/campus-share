package com.campus.share.vo;

import java.time.Instant;

public record OrderView(Long id, Long itemId, Long borrowerId, Long ownerId, String status,
                        Instant startTime, Instant endTime, String title, String owner, String borrower, String image) { }
