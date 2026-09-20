package com.lanchat.control.device;

import lombok.Data;

import java.util.List;

@Data
public class DeviceRegistrationRequest {
    private String deviceKey;
    private String platform;
    private String displayName;
    private String appVersion;
    private List<String> capabilities;
    private String algorithm;
    private String publicKey;
}
