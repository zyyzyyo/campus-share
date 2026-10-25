package com.campus.share.security;

import com.campus.share.vo.UserView;

import java.security.Principal;

public record AuthenticatedUser(Long id, String username, String nickname, String tokenHash)
        implements Principal {
    @Override
    public String getName() { return username; }

    public UserView toView() { return new UserView(id, username, nickname); }
}
