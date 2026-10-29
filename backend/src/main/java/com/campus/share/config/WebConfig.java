package com.campus.share.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final RateLimitInterceptor limiter;
    public WebConfig(RateLimitInterceptor limiter) { this.limiter = limiter; }
    @Override
    public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(limiter); }
}
