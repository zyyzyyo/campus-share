package com.campus.share.config;

import com.campus.share.service.AuthService;
import com.campus.share.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    public Clock clock() { return Clock.systemUTC(); }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, AuthService auth, ObjectMapper json) throws Exception {
        return http
                // Authentication uses an explicit Authorization header, never ambient cookies.
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(rules -> rules
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/health", "/api/categories", "/api/items", "/api/items/*", "/api/items/*/reservations", "/api/media/*").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(401);
                            response.setHeader("WWW-Authenticate", "Bearer");
                            response.setContentType("application/json;charset=UTF-8");
                            json.writeValue(response.getOutputStream(), ApiResponse.failure("UNAUTHORIZED", "请先登录，或重新登录后重试"));
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            response.setStatus(403);
                            response.setContentType("application/json;charset=UTF-8");
                            json.writeValue(response.getOutputStream(), ApiResponse.failure("FORBIDDEN", "没有操作权限"));
                        }))
                .addFilterBefore(new BearerTokenFilter(auth, json), UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
