package com.lanchat.dto;

import lombok.Data;

import java.util.List;

@Data
public class BroadcastTargetUpdateDTO {
    private List<Long> addUserIds;
    private List<Long> removeUserIds;
}
