package com.cortex.agent;

import com.cortex.entity.AgentMetadata;
import com.cortex.mapper.AgentMetadataMapper;
import com.cortex.mapper.MistakeRecordMapper;
import com.cortex.mapper.UserProfileMapper;
import com.cortex.dto.UserProfileDto;
import com.cortex.entity.MistakeRecord;
import com.cortex.entity.UserProfile;
import com.cortex.llm.LlmClient;
import com.cortex.llm.LlmRequest;
import com.cortex.llm.LlmResponse;
import com.alibaba.fastjson2.JSON;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRegistry {
    
    private final AgentMetadataMapper agentMetadataMapper;
    private final LlmClient llmClient;
    private final UserProfileMapper userProfileMapper;
    private final MistakeRecordMapper mistakeRecordMapper;
    
    private final Map<String, AgentMetadata> agentCache = new ConcurrentHashMap<>();
    
    @PostConstruct
    public void init() {
        loadAgents();
    }
    
    public void loadAgents() {
        List<AgentMetadata> agents = agentMetadataMapper.selectList(null);
        agents.forEach(agent -> agentCache.put(agent.getAgentId(), agent));
        log.info("已加载{}个Agent", agents.size());
    }
    
    public AgentMetadata getAgent(String agentId) {
        return agentCache.get(agentId);
    }
    
    public List<AgentMetadata> getAllAgents() {
        return new ArrayList<>(agentCache.values());
    }
    
    public List<AgentMetadata> findAgentsByCapability(String capability) {
        return agentCache.values().stream()
                .filter(agent -> {
                    String capabilities = agent.getCapabilities();
                    return capabilities != null && capabilities.contains(capability);
                })
                .sorted((a, b) -> b.getPriority().compareTo(a.getPriority()))
                .toList();
    }
    
    public String executeAgent(String agentId, String input, String userId) {
        AgentMetadata agent = getAgent(agentId);
        if (agent == null) {
            throw new RuntimeException("Agent不存在: " + agentId);
        }
        
        String systemPrompt = buildSystemPrompt(agent, userId);
        String userPrompt = buildUserPrompt(agent, input);
        
        LlmRequest request = LlmRequest.create("deepseek-chat", systemPrompt, userPrompt);
        LlmResponse response = llmClient.chat(request);
        
        return response.getContent();
    }
    
    private String buildSystemPrompt(AgentMetadata agent, String userId) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(agent.getPromptTemplate());
        
        if (StrUtil.isNotBlank(userId)) {
            UserProfileDto profile = getUserProfile(userId);
            if (profile != null && (profile.getPersonalityTags() != null || profile.getWorkHabits() != null)) {
                prompt.append("\n\n【用户画像】\n");
                if (profile.getPersonalityTags() != null && !profile.getPersonalityTags().isEmpty()) {
                    prompt.append("- 性格特点：").append(String.join("、", profile.getPersonalityTags())).append("\n");
                }
                if (profile.getWorkHabits() != null && !profile.getWorkHabits().isEmpty()) {
                    prompt.append("- 工作习惯：").append(String.join("、", profile.getWorkHabits())).append("\n");
                }
                if (profile.getMistakes() != null && !profile.getMistakes().isEmpty()) {
                    prompt.append("- 注意事项：\n");
                    for (UserProfileDto.MistakeDto mistake : profile.getMistakes()) {
                        prompt.append("  * ").append(mistake.getKeyword()).append(": ").append(mistake.getDescription()).append("\n");
                    }
                }
                prompt.append("\n请根据用户画像调整你的回答风格。");
            }
        }
        
        return prompt.toString();
    }
    
    private String buildUserPrompt(AgentMetadata agent, String input) {
        return input;
    }
    
    private UserProfileDto getUserProfile(String userId) {
        UserProfile profile = userProfileMapper.selectById(userId);
        if (profile == null) {
            return null;
        }
        
        UserProfileDto dto = new UserProfileDto();
        dto.setUserId(profile.getUserId());
        dto.setUsername(profile.getUsername());
        
        if (StrUtil.isNotBlank(profile.getPersonalityTags())) {
            dto.setPersonalityTags(JSON.parseArray(profile.getPersonalityTags(), String.class));
        }
        if (StrUtil.isNotBlank(profile.getWorkHabits())) {
            dto.setWorkHabits(JSON.parseArray(profile.getWorkHabits(), String.class));
        }
        
        List<MistakeRecord> mistakes = mistakeRecordMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MistakeRecord>()
                .eq(MistakeRecord::getUserId, userId)
                .orderByDesc(MistakeRecord::getGmtCreate)
                .last("LIMIT 10")
        );
        
        if (!mistakes.isEmpty()) {
            List<UserProfileDto.MistakeDto> mistakeDtos = mistakes.stream()
                .map(m -> {
                    UserProfileDto.MistakeDto md = new UserProfileDto.MistakeDto();
                    md.setKeyword(m.getKeyword());
                    md.setDescription(m.getDescription());
                    return md;
                })
                .toList();
            dto.setMistakes(mistakeDtos);
        }
        
        return dto;
    }
}
