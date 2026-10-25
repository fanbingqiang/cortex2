package com.cortex.llm;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具函数定义
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolFunction {
    private String name;
    private String description;
    private Object parameters;
}