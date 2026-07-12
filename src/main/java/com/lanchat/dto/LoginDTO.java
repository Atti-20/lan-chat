package com.lanchat.dto;

import lombok.Data;

@Data
public class LoginDTO {
    private String username;
    private String password;
    /** 设备类型：web/android/ios */
    private String deviceType;
    /** 设备名称 */
    private String deviceName;
}
