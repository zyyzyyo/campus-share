package com.campus.share.dto;
import jakarta.validation.constraints.Size;
public record OrderActionRequest(@Size(max=500) String reason) { }
