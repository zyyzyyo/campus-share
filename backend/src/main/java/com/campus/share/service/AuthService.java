package com.campus.share.service;

import com.campus.share.dto.AuthRequests;
import com.campus.share.security.AuthenticatedUser;
import com.campus.share.vo.LoginResult;
import com.campus.share.vo.UserView;

public interface AuthService {
    UserView register(AuthRequests.Register request);
    LoginResult login(AuthRequests.Login request);
    AuthenticatedUser authenticate(String token);
    void logout(AuthenticatedUser user);
}
