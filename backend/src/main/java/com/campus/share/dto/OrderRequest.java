package com.campus.share.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

public record OrderRequest(@NotNull @Positive Long itemId, @NotNull Instant startTime, @NotNull Instant endTime) { }
