package com.campus.share.service.impl;

import com.campus.share.mapper.HealthMapper;
import com.campus.share.service.HealthService;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class HealthServiceImpl implements HealthService {
    private final HealthMapper mapper;
    public HealthServiceImpl(HealthMapper mapper) { this.mapper = mapper; }
    public Map<String, String> check() {
        mapper.ping();
        return Map.of("status", "UP", "application", "campus-share-backend");
    }
}
