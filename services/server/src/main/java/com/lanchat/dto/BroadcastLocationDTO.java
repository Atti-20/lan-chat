package com.lanchat.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class BroadcastLocationDTO {

    private BigDecimal latitude;
    private BigDecimal longitude;
    private BigDecimal accuracyMeters;
    private String addressText;
    private LocalDateTime capturedAt;
}
