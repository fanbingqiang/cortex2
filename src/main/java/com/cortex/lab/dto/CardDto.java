package com.cortex.lab.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class CardDto {
    private Long id;
    private Long questionId;
    private String questionTitle;
    private String title;
    private String knowledgePath;
    private String keyPoints;
    private String detailExplanation;
    private String codeSnippet;
    private String commonPitfalls;
    private LocalDateTime gmtCreate;
}
