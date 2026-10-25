package com.cortex.lab.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("planning_session")
public class PlanningSession {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private String goal;
    private Integer currentStep;
    private Integer totalSteps;
    private String stepsJson;
    private Integer completedSteps;
    private String status;
    private LocalDateTime gmtCreate;
    private LocalDateTime gmtModified;
}
