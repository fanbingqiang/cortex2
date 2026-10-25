package com.cortex.llm;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.cortex.config.LlmConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmClient {
    
    private final LlmConfig llmConfig;
    
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();
    
    public LlmResponse chat(LlmRequest request) {
        return chat(llmConfig.getDeepseek().getBaseUrl(), llmConfig.getDeepseek().getApiKey(), request);
    }
    
    public LlmResponse chat(String baseUrl, String apiKey, LlmRequest request) {
        if (StrUtil.isBlank(apiKey) || "your-api-key-here".equals(apiKey)) {
            throw new RuntimeException("请配置有效的API Key");
        }
        
        String url = baseUrl + "/chat/completions";
        String jsonBody = JSON.toJSONString(request);
        
        Request httpRequest = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .build();
        
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                log.error("LLM API调用失败: status={}, body={}", response.code(), errorBody);
                throw new RuntimeException("LLM API调用失败: " + response.code());
            }
            
            String responseBody = response.body().string();
            log.debug("LLM API响应: {}", responseBody);
            
            return JSON.parseObject(responseBody, LlmResponse.class);
        } catch (IOException e) {
            log.error("LLM API调用异常", e);
            throw new RuntimeException("LLM API调用异常: " + e.getMessage(), e);
        }
    }
    
    public String chatSimple(String systemPrompt, String userMessage) {
        LlmRequest request = LlmRequest.create(llmConfig.getDeepseek().getModel(), systemPrompt, userMessage);
        LlmResponse response = chat(request);
        return response.getContent();
    }
    
    public String chatSimple(String userMessage) {
        LlmRequest request = LlmRequest.create(llmConfig.getDeepseek().getModel(), userMessage);
        LlmResponse response = chat(request);
        return response.getContent();
    }
}
