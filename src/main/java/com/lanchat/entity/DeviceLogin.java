package com.lanchat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;

@Data
@TableName("device_login")
public class DeviceLogin {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String deviceType;
    private String deviceName;
    @JsonIgnore
    private String token;

    @JsonIgnore
    private String refreshToken;
    private LocalDateTime loginTime;
    private LocalDateTime expireTime;
    private Integer status;
}
