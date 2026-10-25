package com.campus.share.dto;

import jakarta.validation.constraints.*;

public record ItemRequest(
        @NotBlank @Size(max = 100) String title,
        @NotBlank @Size(max = 500) String description,
        @NotNull @Positive Long categoryId,
        @NotBlank @Pattern(regexp = "东校区|南校区|西校区") String campus,
        @NotBlank @Size(max = 150) String pickupLocation,
        @Size(max = 200) String imageUrl) { }
