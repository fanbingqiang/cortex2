package com.cortex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_profile")
public class UserProfile {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private String username;
    private String personalityTags;
    private String workHabits;
    private String preferences;
    private LocalDateTime gmtCreate;
    private LocalDateTime gmtModified;
}
