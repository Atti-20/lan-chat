package com.lanchat.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

/**
 * 登录返回结果
 */
@Data
public class LoginVO {
    private Long userId;
    private String username;
    private String nickname;
    private String avatar;
    private String token;
    @JsonIgnore
    private String refreshToken;
    private Long expiresIn;
}
