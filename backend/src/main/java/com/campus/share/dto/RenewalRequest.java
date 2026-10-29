package com.campus.share.dto;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
public record RenewalRequest(@NotNull Instant endTime) { }
