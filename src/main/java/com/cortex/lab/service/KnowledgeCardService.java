package com.cortex.lab.service;

import com.alibaba.fastjson2.JSON;
import com.cortex.lab.dto.CardDto;
import com.cortex.lab.entity.KnowledgeCard;
import com.cortex.lab.entity.QuestionBank;
import com.cortex.lab.mapper.KnowledgeCardMapper;
import com.cortex.lab.mapper.QuestionBankMapper;
import com.cortex.lab.service.PythonAIService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeCardService {

    private final KnowledgeCardMapper cardMapper;
    private final QuestionBankMapper questionBankMapper;
    private final PythonAIService pythonAIService;

    private static final String CARD_PROMPT = """
    你是一个知识整理专家。请根据以下信息生成一张知识卡片。
    返回严格的 JSON 格式（不要 markdown 标记）：
    {
      "title": "知识点标题（不超过50字）",
      "keyPoints": "要点1|要点2|要点3（每条不超过30字）",
      "detailExplanation": "详细解释（200字以内）",
      "codeSnippet": "正确代码示例（10行以内，只展示关键差异）",
      "commonPitfalls": "误区1|误区2（每条不超过30字）"
    }
    题目: %s
    陷阱: %s
    正确解释: %s
    """;

    public CardDto getByQuestionId(Long questionId) {
        KnowledgeCard card = cardMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeCard>()
                .eq(KnowledgeCard::getQuestionId, questionId)
        );
        return card != null ? toDto(card) : null;
    }

    public List<CardDto> listAll() {
        List<KnowledgeCard> cards = cardMapper.selectList(null);
        return cards.stream().map(c -> {
            CardDto dto = toDto(c);
            QuestionBank q = questionBankMapper.selectById(c.getQuestionId());
            if (q != null) dto.setQuestionTitle(q.getTitle());
            return dto;
        }).collect(Collectors.toList());
    }

    public CardDto generateCard(Long questionId) {
        CardDto existing = getByQuestionId(questionId);
        if (existing != null) return existing;

        QuestionBank q = questionBankMapper.selectById(questionId);
        if (q == null) throw new RuntimeException("题目不存在");

        String prompt = CARD_PROMPT.formatted(q.getTitle(), q.getExpectedPitfall(), q.getCorrectExplanation());
        try {
            String result = pythonAIService.chatSimple(prompt);
            result = com.cortex.util.JsonUtils.cleanJson(result);
            CardGen gen = JSON.parseObject(result, CardGen.class);

            KnowledgeCard card = new KnowledgeCard();
            card.setQuestionId(questionId);
            card.setTitle(gen.title != null ? gen.title : q.getTitle());
            card.setKeyPoints(gen.keyPoints);
            card.setDetailExplanation(gen.detailExplanation);
            card.setCodeSnippet(gen.codeSnippet);
            card.setCommonPitfalls(gen.commonPitfalls);
            card.setGmtCreate(LocalDateTime.now());
            card.setGmtModified(LocalDateTime.now());
            cardMapper.insert(card);

            return toDto(card);
        } catch (Exception e) {
            log.error("生成知识卡片失败", e);
            KnowledgeCard card = new KnowledgeCard();
            card.setQuestionId(questionId);
            card.setTitle(q.getTitle());
            card.setKeyPoints("读代码|猜输出|看结果");
            card.setDetailExplanation(q.getCorrectExplanation() != null ? q.getCorrectExplanation() : q.getExpectedPitfall());
            card.setCodeSnippet(q.getTrapCode());
            card.setCommonPitfalls("别想当然|动手验证");
            card.setGmtCreate(LocalDateTime.now());
            card.setGmtModified(LocalDateTime.now());
            cardMapper.insert(card);
            return toDto(card);
        }
    }

    public void deleteCard(Long id) {
        KnowledgeCard card = cardMapper.selectById(id);
        if (card == null) throw new RuntimeException("知识卡片不存在");
        cardMapper.deleteById(id);
    }

    /** 用户自建知识卡片（题目 + 简易答案） */
    public CardDto createCard(CardDto dto) {
        if (dto.getTitle() == null || dto.getTitle().isBlank()) {
            throw new RuntimeException("请输入卡片标题");
        }
        KnowledgeCard card = new KnowledgeCard();
        card.setQuestionId(0L);  // 自建卡片无关联题目
        card.setTitle(dto.getTitle().trim());
        card.setKnowledgePath(dto.getKnowledgePath());
        card.setDetailExplanation(dto.getDetailExplanation());
        card.setKeyPoints(dto.getKeyPoints());
        card.setCodeSnippet(dto.getCodeSnippet());
        card.setCommonPitfalls(dto.getCommonPitfalls());
        card.setGmtCreate(LocalDateTime.now());
        card.setGmtModified(LocalDateTime.now());
        cardMapper.insert(card);
        return toDto(card);
    }

    /** AI 简易回答生成知识卡片（用户提问 → AI 简单作答） */
    public CardDto generateCardFromQuestion(String question) {
        if (question == null || question.isBlank()) {
            throw new RuntimeException("请输入你想了解的问题");
        }
        String prompt = "你是一个知识卡片生成助手。请用最通俗、最简洁的语言回答下面的问题，生成一张「题目+简易答案」卡片，方便学习者快速回忆。\n"
            + "返回严格 JSON（不要 markdown 标记）：\n"
            + "{\"title\": \"问题标题（不超过25字）\", \"answer\": \"简易答案（2~3句话，50~100字，用大白话讲清楚核心）\"}\n"
            + "问题: " + question.trim();
        try {
            String result = pythonAIService.chatSimple(prompt);
            result = com.cortex.util.JsonUtils.cleanJson(result);
            CardGen gen = JSON.parseObject(result, CardGen.class);

            KnowledgeCard card = new KnowledgeCard();
            card.setQuestionId(0L);
            card.setTitle(gen.title != null ? gen.title : question.trim().substring(0, Math.min(25, question.trim().length())));
            card.setDetailExplanation(gen.answer != null ? gen.answer : gen.detailExplanation);
            card.setGmtCreate(LocalDateTime.now());
            card.setGmtModified(LocalDateTime.now());
            cardMapper.insert(card);
            return toDto(card);
        } catch (Exception e) {
            log.error("AI 生成知识卡片失败", e);
            throw new RuntimeException("AI 生成失败，请稍后再试");
        }
    }

    public CardDto updateCard(Long id, CardDto dto) {
        KnowledgeCard card = cardMapper.selectById(id);
        if (card == null) throw new RuntimeException("知识卡片不存在");
        if (dto.getTitle() != null) card.setTitle(dto.getTitle());
        if (dto.getKnowledgePath() != null) card.setKnowledgePath(dto.getKnowledgePath());
        if (dto.getKeyPoints() != null) card.setKeyPoints(dto.getKeyPoints());
        if (dto.getDetailExplanation() != null) card.setDetailExplanation(dto.getDetailExplanation());
        if (dto.getCodeSnippet() != null) card.setCodeSnippet(dto.getCodeSnippet());
        if (dto.getCommonPitfalls() != null) card.setCommonPitfalls(dto.getCommonPitfalls());
        card.setGmtModified(LocalDateTime.now());
        cardMapper.updateById(card);
        return toDto(card);
    }

    @lombok.Data
    static class CardGen {
        private String title;
        private String answer;       // AI 简易回答（生成卡片用）
        private String keyPoints;
        private String detailExplanation;
        private String codeSnippet;
        private String commonPitfalls;
    }

    private CardDto toDto(KnowledgeCard card) {
        CardDto dto = new CardDto();
        dto.setId(card.getId());
        dto.setQuestionId(card.getQuestionId());
        dto.setTitle(card.getTitle());
        dto.setKnowledgePath(card.getKnowledgePath());
        dto.setKeyPoints(card.getKeyPoints());
        dto.setDetailExplanation(card.getDetailExplanation());
        dto.setCodeSnippet(card.getCodeSnippet());
        dto.setCommonPitfalls(card.getCommonPitfalls());
        dto.setGmtCreate(card.getGmtCreate());
        return dto;
    }

}
