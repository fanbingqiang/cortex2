package com.cortex.lab.dto;

import lombok.Data;

@Data
public class CommunityTrapSubmitRequest {
    // 社区陷阱提交请求
    private String title;
    private String knowledgePoint;
    private String category;
    private String javaVersion;
    private String trapCode;
    private String expectedPitfall;
    private String correctExplanation;
    private String hints;       // JSON格式的提示列表
    private Integer difficulty;
    private String submitter;
    private String knowledgePath;  // 知识树路径，如 "Redis篇/Redis分布式锁/其他"
}