package com.campus.share.controller;

import com.campus.share.dto.AuthRequests;
import com.campus.share.security.AuthenticatedUser;
import com.campus.share.service.AuthService;
import com.campus.share.vo.LoginResult;
import com.campus.share.vo.UserView;

import com.campus.share.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserView> register(@Valid @RequestBody AuthRequests.Register request) {
        return ApiResponse.success(auth.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResult> login(@Valid @RequestBody AuthRequests.Login request) {
        return ApiResponse.success(auth.login(request));
    }

    @GetMapping("/me")
    public ApiResponse<UserView> me(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(user.toView());
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal AuthenticatedUser user) {
        auth.logout(user);
        return ApiResponse.success(null);
    }
}
