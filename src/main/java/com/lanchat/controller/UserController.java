package com.lanchat.controller;

import com.lanchat.common.Result;
import com.lanchat.dto.LoginDTO;
import com.lanchat.entity.User;
import com.lanchat.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping
@CrossOrigin
public class UserController {
    @Autowired
    private UserService userService;

    @PostMapping("/login")
    public Result<User> login(@RequestBody LoginDTO dto) {
        User user = userService.login(dto.getUsername(), dto.getPassword());

        if(user==null) {
            return Result.error("用户名或密码错误");
        }

        return Result.success(user);
    }
}
