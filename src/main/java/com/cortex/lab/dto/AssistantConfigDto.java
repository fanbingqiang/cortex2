package com.cortex.lab.dto;

import lombok.Data;

import java.util.Map;

@Data
public class AssistantConfigDto {
    private Map<String, String> configs;
}
