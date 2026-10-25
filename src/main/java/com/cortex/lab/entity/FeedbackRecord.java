package com.cortex.lab.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("feedback_record")
public class FeedbackRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String conversationId;
    private Long messageId;
    private String userId;
    private Integer rating;
    private String feedbackType;
    private String comment;
    private LocalDateTime gmtCreate;
}
