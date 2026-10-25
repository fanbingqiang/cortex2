package com.cortex.lab.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("lab_knowledge_card")
public class KnowledgeCard {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long questionId;
    private String title;
    private String knowledgePath;   // 知识树路径，如 "Redis篇/Redis分布式锁/其他"
    private String keyPoints;
    private String detailExplanation;
    private String codeSnippet;
    private String commonPitfalls;
    private LocalDateTime gmtCreate;
    private LocalDateTime gmtModified;
}
