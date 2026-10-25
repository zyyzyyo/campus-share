package com.campus.share.controller;

import com.campus.share.common.ApiResponse;
import java.util.Map;
import com.campus.share.service.HealthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final HealthService health;

    public HealthController(HealthService health) { this.health = health; }

    @GetMapping("/api/health")
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.success(health.check());
    }
}
