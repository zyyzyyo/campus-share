package com.campus.share.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import com.campus.share.common.BusinessException;
import org.springframework.http.HttpStatus;

import java.time.Duration;

@Component
public class BookingSubmitGuard {

    private final StringRedisTemplate redis;
    private final String prefix;
    private final Duration cooldown;

    public BookingSubmitGuard(
            StringRedisTemplate redis,
            @Value("${app.redis.key-prefix}") String prefix,
            @Value("${app.booking.submit-cooldown:5s}") Duration cooldown) {

        if (cooldown.toMillis() <= 0) {
            throw new IllegalArgumentException("重复提交拦截时间必须大于 0");
        }

        this.redis = redis;
        this.prefix = prefix + ":booking:submit:";
        this.cooldown = cooldown;
    }
    public void check(Long userId, Long itemId) {
        String key = prefix + userId + ":" + itemId;
        Boolean accepted;
        try {
            accepted=redis.opsForValue()
                    .setIfAbsent(key, "1", cooldown);
        } catch (DataAccessException exception) {
            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "BOOKING_GUARD_UNAVAILABLE",
                    "借用申请服务暂时不可用，请稍后重试");
        }
        if (!Boolean.TRUE.equals(accepted)) {
            throw new BusinessException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "DUPLICATE_SUBMISSION",
                    "请勿重复提交，请稍后再试");
        }

    }
}
