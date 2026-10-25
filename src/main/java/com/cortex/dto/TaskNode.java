package com.cortex.dto;

import lombok.Data;

import java.util.List;

@Data
public class TaskNode {
    private String nodeId;
    private String title;
    private String description;
    private String agentId;
    private List<String> dependencies;
    private String status;
    private String input;
    private String output;
}
