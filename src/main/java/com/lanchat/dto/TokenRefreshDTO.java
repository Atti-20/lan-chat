package com.lanchat.dto;

import lombok.Data;

@Data
public class TokenRefreshDTO {
    private String refreshToken;
    private String deviceType;
    private String deviceName;
}
