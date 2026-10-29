package com.campus.share.entity;

import java.time.LocalDateTime;

public record ReservationSlot(LocalDateTime startTime, LocalDateTime endTime, String status) { }
