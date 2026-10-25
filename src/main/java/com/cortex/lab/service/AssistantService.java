package com.cortex.lab.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cortex.lab.entity.AssistantConversation;
import com.cortex.lab.entity.AssistantMessage;
import com.cortex.lab.mapper.AssistantConversationMapper;
import com.cortex.lab.mapper.AssistantMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantService {

    private final AssistantConversationMapper conversationMapper;
    private final AssistantMessageMapper messageMapper;

    // 获取用户的对话列表
    public List<AssistantConversation> listConversations(String userId) {
        return conversationMapper.selectList(
            new LambdaQueryWrapper<AssistantConversation>()
                .eq(AssistantConversation::getUserId, userId != null ? userId : "anonymous")
                .eq(AssistantConversation::getStatus, "ACTIVE")
                .orderByDesc(AssistantConversation::getGmtModified)
                .last("LIMIT 20")
        );
    }

    // 获取对话的消息列表
    public List<AssistantMessage> getMessages(String conversationId) {
        return messageMapper.selectList(
            new LambdaQueryWrapper<AssistantMessage>()
                .eq(AssistantMessage::getConversationId, conversationId)
                .orderByAsc(AssistantMessage::getGmtCreate)
        );
    }

    public void createConversation(String conversationId, String userId, String firstMessage) {
        AssistantConversation conv = new AssistantConversation();
        conv.setConversationId(conversationId);
        conv.setUserId(userId != null ? userId : "anonymous");
        conv.setTitle(firstMessage.length() > 50 ? firstMessage.substring(0, 50) + "..." : firstMessage);
        conv.setMessageCount(0);
        conv.setStatus("ACTIVE");
        conv.setGmtCreate(LocalDateTime.now());
        conv.setGmtModified(LocalDateTime.now());
        conversationMapper.insert(conv);
    }

    public void saveMessage(String conversationId, String role, String content, String metadata) {
        AssistantMessage msg = new AssistantMessage();
        msg.setConversationId(conversationId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setMetadata(metadata);
        msg.setGmtCreate(LocalDateTime.now());
        messageMapper.insert(msg);
    }

    // 软删除对话
    public void deleteConversation(String conversationId) {
        try {
            AssistantConversation conv = conversationMapper.selectOne(
                new LambdaQueryWrapper<AssistantConversation>()
                    .eq(AssistantConversation::getConversationId, conversationId)
                    .last("LIMIT 1")
            );
            if (conv != null) {
                conv.setStatus("DELETED");
                conv.setGmtModified(LocalDateTime.now());
                conversationMapper.updateById(conv);
            }
        } catch (Exception e) {
            log.warn("删除对话失败: {}", e.getMessage());
        }
    }
}