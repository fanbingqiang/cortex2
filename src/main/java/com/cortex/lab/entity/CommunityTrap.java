package com.cortex.lab.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("lab_community_trap")
public class CommunityTrap {
    // 社区陷阱代码实体
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String knowledgePoint;
    private String knowledgePath;   // 知识树路径，如 "Redis篇/Redis分布式锁/其他"
    private String category;
    private String javaVersion;
    private String trapCode;
    private String expectedPitfall;
    private String correctExplanation;
    private String hints;
    private Integer difficulty;
    private String submitter;
    private String status;
    private Integer voteCount;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime gmtCreate;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime gmtModified;
}