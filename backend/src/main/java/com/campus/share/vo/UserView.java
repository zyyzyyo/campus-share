package com.campus.share.vo;

import com.campus.share.entity.UserAccount;

public record UserView(Long id, String username, String nickname) {
    public static UserView from(UserAccount user) {
        return new UserView(user.id(), user.username(), user.nickname());
    }
}
