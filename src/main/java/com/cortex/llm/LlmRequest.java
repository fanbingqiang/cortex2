package com.cortex.llm;

import lombok.Data;

import java.util.List;

@Data
public class LlmRequest {
    private String model;
    private List<Message> messages;
    private Double temperature;
    private Integer maxTokens;
    
    @Data
    public static class Message {
        private String role;
        private String content;
        
        public Message() {}
        
        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }
    
    public static LlmRequest create(String model, String systemPrompt, String userMessage) {
        LlmRequest request = new LlmRequest();
        request.setModel(model);
        request.setMessages(List.of(
            new Message("system", systemPrompt),
            new Message("user", userMessage)
        ));
        request.setTemperature(0.7);
        request.setMaxTokens(4096);
        return request;
    }
    
    public static LlmRequest create(String model, String userMessage) {
        LlmRequest request = new LlmRequest();
        request.setModel(model);
        request.setMessages(List.of(
            new Message("user", userMessage)
        ));
        request.setTemperature(0.7);
        request.setMaxTokens(4096);
        return request;
    }
}
