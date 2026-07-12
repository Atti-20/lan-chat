package com.lanchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanchat.entity.User;
import com.lanchat.mapper.UserMapper;
import com.lanchat.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl implements UserService {
     @Autowired
    private UserMapper userMapper;

     @Override
    public User login(String username, String password) {
         LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
         wrapper.eq(User::getUsername, username)
                 .eq(User::getPassword, password);

         return userMapper.selectOne(wrapper);
     }
}
