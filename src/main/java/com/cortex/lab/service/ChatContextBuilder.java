package com.cortex.lab.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cortex.lab.entity.AssistantConversation;
import com.cortex.lab.entity.AssistantMessage;
import com.cortex.lab.entity.User;
import com.cortex.lab.mapper.AssistantConversationMapper;
import com.cortex.lab.mapper.AssistantMessageMapper;
import com.cortex.lab.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 聊天上下文构建器
 * - 从 DB 加载用户画像，构建 system prompt
 * - 加载对话历史（Python 端会做压缩，Java 只负责传递）
 * - 控制每条消息长度，不删除历史
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatContextBuilder {

    private final UserMapper userMapper;
    private final AssistantMessageMapper messageMapper;
    private final AssistantConversationMapper conversationMapper;

    private static final int MAX_HISTORY_MESSAGES = 20;   // 最多带 20 条历史（Python 会压缩）
    private static final int MAX_MSG_CHARS = 500;          // 每条消息最多 500 字

    /**
     * 构建发送给 Python AI 的上下文
     * @return { systemPrompt, history, profile }
     */
    public Map<String, Object> buildContext(String userId, String conversationId) {
        // 1. 加载用户画像
        User user = userMapper.selectOne(
            new LambdaQueryWrapper<User>().eq(User::getUserId, userId).last("LIMIT 1"));

        // 2. 构建系统提示词
        String systemPrompt = buildSystemPrompt(user);

        // 3. 构建结构化画像（传给 Python trace_store）
        Map<String, Object> profile = buildProfile(user);

        // 4. 加载对话历史
        List<Map<String, String>> history = loadHistory(conversationId);

        return Map.of("systemPrompt", systemPrompt, "history", history, "profile", profile);
    }

    private String buildSystemPrompt(User user) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个Java后端面试教学AI导师。你的职责是帮助学员准备面试。\n\n");

        sb.append("## 学员信息\n");
        if (user != null) {
            if (user.getSkillLevel() != null && !user.getSkillLevel().isBlank()) {
                sb.append("- 技能等级：").append(user.getSkillLevel()).append("\n");
            }
            if (user.getWeakAreas() != null && !user.getWeakAreas().isBlank()) {
                sb.append("- 薄弱点：").append(user.getWeakAreas()).append("\n");
            }
            if (user.getLearningGoal() != null && !user.getLearningGoal().isBlank()) {
                sb.append("- 学习目标：").append(user.getLearningGoal()).append("\n");
            }
            if (user.getPreferences() != null && !user.getPreferences().isBlank()) {
                sb.append("- 自定义画像：").append(user.getPreferences()).append("\n");
            }
            if (user.getPersonalityTags() != null && !user.getPersonalityTags().isBlank()) {
                sb.append("- 个性标签：").append(user.getPersonalityTags()).append("\n");
            }
        } else {
            sb.append("- 新学员，尚无画像数据\n");
        }

        sb.append("\n## 教学规则\n");
        sb.append("- 优先针对学员的薄弱点进行讲解和出题\n");
        sb.append("- 对已掌握的知识点，进行突击复习\n");
        sb.append("- 根据学员技能等级调整难度\n");
        sb.append("- 回答要简洁，重点突出\n");

        return sb.toString();
    }

    /**
     * 构建结构化用户画像（传给 Python trace_store，用于 pipeline 各 Agent）
     * 注意：只传静态偏好字段，mastered/weak_points 由 Python pipeline 动态维护
     */
    private Map<String, Object> buildProfile(User user) {
        Map<String, Object> profile = new java.util.HashMap<>();
        if (user == null) return profile;

        // 技能等级 → 出题难度
        if (user.getSkillLevel() != null && !user.getSkillLevel().isBlank()) {
            String level = user.getSkillLevel().toLowerCase();
            if (level.contains("初级") || level.contains("beginner")) {
                profile.put("preferred_difficulty", "easy");
            } else if (level.contains("高级") || level.contains("advanced")) {
                profile.put("preferred_difficulty", "hard");
            } else {
                profile.put("preferred_difficulty", "medium");
            }
        }

        // 学习目标
        if (user.getLearningGoal() != null && !user.getLearningGoal().isBlank()) {
            profile.put("learning_style", user.getLearningGoal());
        }

        if (user.getUsername() != null) {
            profile.put("username", user.getUsername());
        }

        return profile;
    }

    private List<Map<String, String>> loadHistory(String conversationId) {
        List<Map<String, String>> result = new ArrayList<>();
        if (conversationId == null || conversationId.isBlank()) return result;

        List<AssistantMessage> messages = messageMapper.selectList(
            new LambdaQueryWrapper<AssistantMessage>()
                .eq(AssistantMessage::getConversationId, conversationId)
                .orderByDesc(AssistantMessage::getGmtCreate)
                .last("LIMIT " + (MAX_HISTORY_MESSAGES + 1)));

        // 反转回时间顺序
        for (int i = messages.size() - 1; i >= 0 && result.size() < MAX_HISTORY_MESSAGES; i--) {
            AssistantMessage msg = messages.get(i);
            String content = msg.getContent();
            if (content != null && content.length() > MAX_MSG_CHARS) {
                content = content.substring(0, MAX_MSG_CHARS) + "...";
            }
            result.add(Map.of("role", msg.getRole(), "content", content));
        }
        return result;
    }
}