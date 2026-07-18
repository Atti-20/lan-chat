package com.lanchat.dto;

import lombok.Data;

import java.util.List;

@Data
public class BroadcastCompleteDTO {

    private List<Long> imageFileIds;
    private BroadcastLocationDTO location;
}
