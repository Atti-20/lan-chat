package com.lanchat.control.device;

import lombok.Data;

@Data
public class DeviceRevocationRequest {
    private String confirmationPhrase;
    private String reason;
}
