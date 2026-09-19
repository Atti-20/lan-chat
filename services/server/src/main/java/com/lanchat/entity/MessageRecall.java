package com.lanchat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("message_recall")
public class MessageRecall {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String messageId;
    private Long operatorId;
    private LocalDateTime recallTime;
}
