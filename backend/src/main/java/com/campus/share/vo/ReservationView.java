package com.campus.share.vo;

import java.time.Instant;

public record ReservationView(Instant startTime, Instant endTime, String status) { }
