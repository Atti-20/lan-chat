package com.lanchat.dto;

import lombok.Data;

import java.util.List;

@Data
public class GroupCreateDTO {
    private String groupName;
    private String avatar;
    private String announcement;
    private List<Long> memberIds;
}
