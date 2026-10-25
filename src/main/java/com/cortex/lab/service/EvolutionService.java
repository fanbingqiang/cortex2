package com.cortex.lab.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cortex.lab.entity.EvolutionInsight;
import com.cortex.lab.entity.FeedbackRecord;
import com.cortex.lab.mapper.EvolutionInsightMapper;
import com.cortex.lab.mapper.FeedbackRecordMapper;
import com.cortex.llm.LlmClient;
import com.cortex.llm.LlmRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvolutionService {

    private final EvolutionInsightMapper insightMapper;
    private final FeedbackRecordMapper feedbackMapper;
    private final LlmClient llmClient;

    public void recordFeedback(String conversationId, Long messageId, String userId, int rating, String comment) {
        FeedbackRecord record = new FeedbackRecord();
        record.setConversationId(conversationId);
        record.setMessageId(messageId);
        record.setUserId(userId != null ? userId : "anonymous");
        record.setRating(rating);
        record.setFeedbackType(rating >= 4 ? "positive" : rating <= 2 ? "negative" : "neutral");
        record.setComment(comment);
        record.setGmtCreate(LocalDateTime.now());
        feedbackMapper.insert(record);

        if (rating <= 2 && comment != null && !comment.isBlank()) {
            extractInsightFromFeedback(comment, userId);
        }
    }

    public List<EvolutionInsight> getRelevantInsights(String question) {
        return insightMapper.selectList(
            new LambdaQueryWrapper<EvolutionInsight>()
                .like(EvolutionInsight::getInsightContent, question)
                .or()
                .like(EvolutionInsight::getCategory, question)
                .last("LIMIT 5")
        );
    }

    public void extractInsightFromConversation(String conversationId, List<String> messages) {
        try {
            String conversationText = String.join("\n", messages);
            String prompt = "以下是一段编程学习对话。请提炼出3个关键洞察（用户常犯的错误、理解误区、有价值的知识点）。" +
                "每个洞察用一句话概括。格式：洞察1||洞察2||洞察3\n\n对话:\n" + conversationText;

            String result = llmClient.chatSimple(prompt);

            if (result != null && !result.isBlank()) {
                String[] insights = result.split("\\|\\|");
                for (String insight : insights) {
                    String trimmed = insight.trim();
                    if (trimmed.length() > 10) {
                        saveInsight("conversation_insight", trimmed, "conversation", trimmed);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("对话洞察提取失败: {}", e.getMessage());
        }
    }

    private void extractInsightFromFeedback(String feedback, String userId) {
        try {
            String prompt = "基于以下用户反馈，总结一个用户可能存在的知识薄弱点或学习需求。一句话概括。\n\n反馈: " + feedback;
            String result = llmClient.chatSimple(prompt);

            if (result != null && !result.isBlank()) {
                saveInsight("feedback_insight_" + userId, result.trim(), "feedback", feedback);
            }
        } catch (Exception e) {
            log.warn("反馈洞察提取失败: {}", e.getMessage());
        }
    }

    public void saveInsight(String key, String content, String category, String source) {
        try {
            List<EvolutionInsight> existing = insightMapper.selectList(
                new LambdaQueryWrapper<EvolutionInsight>()
                    .eq(EvolutionInsight::getInsightKey, key)
                    .last("LIMIT 1")
            );

            if (!existing.isEmpty()) {
                EvolutionInsight ei = existing.get(0);
                ei.setSourceCount(ei.getSourceCount() + 1);
                ei.setConfidence(Math.min(1.0, ei.getConfidence() + 0.1));
                ei.setInsightContent(content);
                ei.setGmtModified(LocalDateTime.now());
                insightMapper.updateById(ei);
                return;
            }

            EvolutionInsight insight = new EvolutionInsight();
            insight.setInsightKey(key);
            insight.setInsightContent(content);
            insight.setCategory(category);
            insight.setConfidence(0.5);
            insight.setSourceCount(1);
            insight.setGmtCreate(LocalDateTime.now());
            insight.setGmtModified(LocalDateTime.now());
            insightMapper.insert(insight);
        } catch (Exception e) {
            log.warn("保存洞察失败: {}", e.getMessage());
        }
    }

    public void indexKnowledgeCards(String conversationId, String question, String answer) {
        try {
            String prompt = "基于以下问答对，提取1个核心知识点，用一句话概括。只输出知识点本身。\n\n问: " + question + "\n答: " + answer;
            String knowledge = llmClient.chatSimple(prompt);
            if (knowledge != null && !knowledge.isBlank() && knowledge.length() > 5) {
                saveInsight("knowledge_" + conversationId, knowledge.trim(), "knowledge", question);
            }
        } catch (Exception e) {
            log.warn("知识卡片索引失败: {}", e.getMessage());
        }
    }

    public String buildEvolutionContext() {
        try {
            List<EvolutionInsight> topInsights = insightMapper.selectList(
                new LambdaQueryWrapper<EvolutionInsight>()
                    .orderByDesc(EvolutionInsight::getConfidence)
                    .last("LIMIT 5")
            );

            if (topInsights.isEmpty()) return "";

            StringBuilder sb = new StringBuilder("\n【自我进化知识库】\n");
            sb.append("基于过往对话，AI总结出以下值得注意的学习洞察：\n");
            for (int i = 0; i < topInsights.size(); i++) {
                EvolutionInsight ei = topInsights.get(i);
                sb.append(i + 1).append(". ").append(ei.getInsightContent()).append("\n");
            }
            sb.append("在回答时可参考这些洞察，提供更有针对性的指导。\n");
            return sb.toString();
        } catch (Exception e) {
            log.warn("构建进化上下文失败: {}", e.getMessage());
            return "";
        }
    }
}
