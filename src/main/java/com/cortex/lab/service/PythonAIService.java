package com.cortex.lab.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Python AI 服务代理 — 所有 LLM 调用统一走 Python 8001 端口
 */
@Slf4j
@Service
public class PythonAIService {

    private static final String PYTHON_SIMPLE_URL = "http://" + System.getenv().getOrDefault("PYTHON_HOST", "localhost") + ":8001/api/chat/simple";
    // 强制 HTTP/1.1 + 绕过系统代理直连 Python（HTTP/2 升级请求会导致 body 丢失）
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .proxy(ProxySelector.of(null))
            .build();

    /**
     * 简单对话 — 发 system_prompt + message 给 Python，返回文本
     */
    public String chatSimple(String systemPrompt, String userMessage) {
        try {
            Map<String, String> body = new java.util.HashMap<>();
            body.put("system_prompt", systemPrompt != null ? systemPrompt : "");
            body.put("message", userMessage != null ? userMessage : "");
            String jsonBody = JSON.toJSONString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(PYTHON_SIMPLE_URL))
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.error("Python AI 返回 {}: {}", resp.statusCode(), resp.body());
                throw new RuntimeException("Python AI 服务异常: " + resp.statusCode());
            }

            JSONObject result = JSON.parseObject(resp.body());
            JSONObject data = result.getJSONObject("data");
            if (data != null) {
                return data.getString("content");
            }
            return result.getString("content");
        } catch (Exception e) {
            log.error("Python AI 调用失败: {}", e.getMessage());
            throw new RuntimeException("AI 服务调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 简单对话 — 只有 user 消息
     */
    public String chatSimple(String userMessage) {
        return chatSimple(null, userMessage);
    }
}