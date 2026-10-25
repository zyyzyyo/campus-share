package com.campus.share.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ItemStatusRequest(@NotBlank @Pattern(regexp = "ON_SHELF|OFF_SHELF") String status) { }
