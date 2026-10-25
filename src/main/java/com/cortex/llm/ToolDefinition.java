package com.cortex.llm;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具定义（Function Calling）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolDefinition {
    private String type;
    private ToolFunction function;
}