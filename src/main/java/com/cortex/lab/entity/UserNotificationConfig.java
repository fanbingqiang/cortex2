package com.cortex.lab.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("user_notification_config")
public class UserNotificationConfig {
    // 用户通知配置实体
    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private Boolean emailNotifications;
    private String emailAddress;
    private Boolean pushNotifications;
    private Boolean reviewReminder;
    private Boolean reportWeekly;
    private Boolean reportMonthly;
    private Boolean aiActiveMode;  // AI 导师主动模式（默认开启）
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime gmtCreate;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime gmtModified;
}
