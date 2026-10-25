package com.cortex.lab.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileDTO {
    // 基础信息
    private String userId;
    private String username;
    private String email;
    private String role;
    private String skillLevel;

    // 学习统计
    private Integer totalQuestionsAnswered;
    private Integer totalCorrect;
    private Integer studyStreak;
    private Double accuracy;

    // 薄弱点（自动识别 + 手动编辑）
    private List<String> weakPoints;

    // 掌握点（自动识别 + 手动编辑）
    private List<String> masteredPoints;

    // 自定义画像（用户自由填写）
    private String customProfile;

    // 个性标签
    private List<String> personalityTags;

    // 学习目标
    private String learningGoal;

    // 偏好方向
    private String preferredDirection;
}