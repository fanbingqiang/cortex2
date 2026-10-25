package com.cortex.lab.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("`user`")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private String username;
    private String passwordHash;
    private String email;
    private String avatar;
    private String role;
    private String status;
    private LocalDateTime lastLoginTime;
    private String personalityTags;
    private String workHabits;
    private String preferences;
    private Double totalStudyHours;
    private Integer totalQuestionsAnswered;
    private Integer totalCorrect;
    private Integer studyStreak;
    private LocalDate lastStudyDate;
    private String weakAreas;
    private String preferredDirection;
    private String learningGoal;
    private String skillLevel;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime gmtCreate;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime gmtModified;
}
