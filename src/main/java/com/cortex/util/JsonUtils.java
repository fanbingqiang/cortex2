package com.cortex.util;

/**
 * JSON 工具类 - 清理 LLM 返回的 JSON 字符串
 */
public class JsonUtils {

    /**
     * 清理 LLM 返回的 JSON，去除 markdown 代码块标记和前后多余文本
     */
    public static String cleanJson(String raw) {
        if (raw == null || raw.isBlank()) return raw;

        String trimmed = raw.trim();

        // 移除 markdown 代码块标记 ```json 或 ```
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf("\n");
            if (start > 0) {
                trimmed = trimmed.substring(start + 1);
            } else {
                // 只有 ``` 没有换行的情况
                trimmed = trimmed.substring(3);
            }
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
        }

        // 尝试找到 JSON 的起始位置
        int jsonStart = trimmed.indexOf("{");
        int jsonEnd = trimmed.lastIndexOf("}");
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            trimmed = trimmed.substring(jsonStart, jsonEnd + 1);
        }

        return trimmed;
    }
}