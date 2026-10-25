package com.campus.share.vo;

import java.time.Instant;

public record LoginResult(String accessToken, String tokenType, Instant expiresAt, UserView user) { }
