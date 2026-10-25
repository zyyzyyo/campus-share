package com.campus.share.entity;

import java.time.LocalDateTime;

// Persistence model: never return this record from an API (it includes the hash).
public record UserAccount(Long id, String username, String passwordHash,
                          String nickname, String status, LocalDateTime createdAt) { }
