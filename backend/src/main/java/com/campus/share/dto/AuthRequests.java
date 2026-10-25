package com.campus.share.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthRequests {
    private AuthRequests() { }

    public record Register(
            @NotBlank(message = "账号不能为空")
            @Pattern(regexp = "[A-Za-z0-9_]{4,32}", message = "账号需为 4–32 位字母、数字或下划线")
            String username,
            @NotBlank(message = "密码不能为空")
            @Size(min = 8, max = 64, message = "密码长度需为 8–64 个字符")
            String password,
            @NotBlank(message = "昵称不能为空")
            @Size(max = 32, message = "昵称不能超过 32 个字符")
            String nickname) { }

    public record Login(
            @NotBlank(message = "账号不能为空")
            @Pattern(regexp = "[A-Za-z0-9_]{4,32}", message = "账号格式不正确")
            String username,
            @NotBlank(message = "密码不能为空")
            @Size(min = 8, max = 64, message = "密码长度需为 8–64 个字符")
            String password) { }
}
