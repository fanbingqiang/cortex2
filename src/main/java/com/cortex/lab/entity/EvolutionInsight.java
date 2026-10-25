package com.cortex.lab.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("evolution_insight")
public class EvolutionInsight {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String insightKey;
    private String insightContent;
    private String category;
    private Double confidence;
    private Integer sourceCount;
    private LocalDateTime gmtCreate;
    private LocalDateTime gmtModified;
}
