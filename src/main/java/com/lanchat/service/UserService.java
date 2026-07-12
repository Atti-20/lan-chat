package com.lanchat.service;

import com.lanchat.entity.User;

public interface UserService {
    User login(String username, String password);
}
