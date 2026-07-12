package com.lanchat.dto;

import lombok.Data;

@Data
public class GroupUpdateDTO {
    private String groupName;
    private String avatar;
    private String announcement;
    private Integer joinMode;
}
