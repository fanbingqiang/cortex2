package com.cortex.lab.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 通用键值配置 — 用于知识树掌握状态等
 */
@Data
@TableName("assistant_config")
public class AssistantConfig {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String configKey;
    private String configValue;
    private String configType;
    private java.time.LocalDateTime gmtCreate;
    private java.time.LocalDateTime gmtModified;
}