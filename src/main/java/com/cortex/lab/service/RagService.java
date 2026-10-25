package com.cortex.lab.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cortex.lab.entity.KnowledgeCard;
import com.cortex.lab.entity.KnowledgeChunk;
import com.cortex.lab.entity.QuestionBank;
import com.cortex.lab.entity.EvolutionInsight;
import com.cortex.lab.mapper.KnowledgeChunkMapper;
import com.cortex.lab.mapper.KnowledgeCardMapper;
import com.cortex.lab.mapper.QuestionBankMapper;
import com.cortex.lab.mapper.EvolutionInsightMapper;
import com.cortex.llm.LlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final KnowledgeCardMapper knowledgeCardMapper;
    private final QuestionBankMapper questionBankMapper;
    private final EvolutionInsightMapper evolutionInsightMapper;
    private final LlmClient llmClient;

    public List<String> searchRelevantContext(String query, int limit) {
        List<String> results = new ArrayList<>();

        List<String> keywords = extractKeywords(query);
        if (keywords.isEmpty()) return results;

        Set<Long> matchedIds = new HashSet<>();

        for (String keyword : keywords) {
            String kw = "%" + keyword + "%";

            List<KnowledgeChunk> chunks = knowledgeChunkMapper.selectList(
                new LambdaQueryWrapper<KnowledgeChunk>()
                    .like(KnowledgeChunk::getKeywords, kw)
                    .or()
                    .like(KnowledgeChunk::getContent, kw)
                    .or()
                    .like(KnowledgeChunk::getChunkKey, kw)
                    .last("LIMIT " + limit)
            );

            for (KnowledgeChunk chunk : chunks) {
                if (matchedIds.add(chunk.getId())) {
                    results.add("【" + chunk.getChunkKey() + "】" + chunk.getContent());
                }
            }
        }

        List<KnowledgeCard> cards = knowledgeCardMapper.selectList(
            new LambdaQueryWrapper<KnowledgeCard>()
                .like(KnowledgeCard::getTitle, keywords.get(0))
                .or()
                .like(KnowledgeCard::getKeyPoints, keywords.get(0))
                .last("LIMIT " + limit)
        );
        for (KnowledgeCard card : cards) {
            String text = "【" + card.getTitle() + "】" +
                (card.getKeyPoints() != null ? card.getKeyPoints() : "") +
                (card.getDetailExplanation() != null ? card.getDetailExplanation() : "");
            if (!results.contains(text)) {
                results.add(text);
            }
        }

        List<QuestionBank> questions = questionBankMapper.selectList(
            new LambdaQueryWrapper<QuestionBank>()
                .like(QuestionBank::getTitle, keywords.get(0))
                .or()
                .like(QuestionBank::getExpectedPitfall, keywords.get(0))
                .or()
                .like(QuestionBank::getCorrectExplanation, keywords.get(0))
                .last("LIMIT " + limit)
        );
        for (QuestionBank q : questions) {
            String text = "【题:" + q.getTitle() + "】陷阱:" +
                (q.getExpectedPitfall() != null ? q.getExpectedPitfall() : "") +
                " 原理:" + (q.getCorrectExplanation() != null ? q.getCorrectExplanation() : "");
            if (!results.contains(text)) {
                results.add(text);
            }
        }

        List<EvolutionInsight> insights = evolutionInsightMapper.selectList(
            new LambdaQueryWrapper<EvolutionInsight>()
                .like(EvolutionInsight::getCategory, keywords.get(0))
                .or()
                .like(EvolutionInsight::getInsightContent, keywords.get(0))
                .last("LIMIT " + limit)
        );
        for (EvolutionInsight insight : insights) {
            String text = "【洞察:" + insight.getInsightKey() + "】" + insight.getInsightContent();
            if (!results.contains(text)) {
                results.add(text);
            }
        }

        return results.stream().distinct().limit(limit).collect(Collectors.toList());
    }

    public List<String> extractKeywords(String text) {
        if (text == null || text.isBlank()) return List.of();

        try {
            String prompt = "从以下问题中提取3-5个最关键的技术关键词，每个关键词2-4个字，用逗号分隔。只输出关键词，不要其他内容。\n\n问题: " + text;
            String result = llmClient.chatSimple(prompt);
            if (result != null && !result.isBlank()) {
                return Arrays.stream(result.split("[，,]"))
                    .map(String::trim)
                    .filter(s -> s.length() >= 2)
                    .limit(5)
                    .collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.warn("AI关键词提取失败，使用基础分词: {}", e.getMessage());
        }

        return basicExtractKeywords(text);
    }

    private List<String> basicExtractKeywords(String text) {
        List<String> keywords = new ArrayList<>();

        String[] commonTerms = {
            "Integer", "String", "HashMap", "ArrayList", "LinkedList", "Thread", "Exception",
            "finally", "static", "final", "volatile", "synchronized", "transient",
            "缓存", "池", "引用", "内存", "线程", "锁", "泛型", "反射", "注解",
            "equals", "hashCode", "clone", "compare", "compareTo",
            "JVM", "GC", "堆", "栈", "方法区", "常量池",
            "设计模式", "单例", "工厂", "代理", "观察者",
            "Spring", "MyBatis", "MySQL", "Redis", "Kafka",
            "索引", "事务", "隔离级别", "锁机制"
        };

        String lowerText = text.toLowerCase();
        for (String term : commonTerms) {
            if (lowerText.contains(term.toLowerCase())) {
                keywords.add(term);
            }
        }

        String[] words = text.split("[\\s,，。？?、：:；;！!()（）\\[\\]【】{}]");
        for (String word : words) {
            word = word.trim();
            if (word.length() >= 2 && word.length() <= 10 && !keywords.contains(word)) {
                keywords.add(word);
            }
        }

        return keywords.stream().limit(5).collect(Collectors.toList());
    }

    public void indexKnowledge(String chunkKey, String content, String keywords, String source, Long sourceId) {
        try {
            List<KnowledgeChunk> existing = knowledgeChunkMapper.selectList(
                new LambdaQueryWrapper<KnowledgeChunk>()
                    .eq(KnowledgeChunk::getChunkKey, chunkKey)
                    .eq(KnowledgeChunk::getSource, source)
                    .eq(KnowledgeChunk::getSourceId, sourceId)
                    .last("LIMIT 1")
            );

            if (!existing.isEmpty()) {
                KnowledgeChunk existingChunk = existing.get(0);
                existingChunk.setContent(content);
                existingChunk.setKeywords(keywords);
                existingChunk.setGmtModified(java.time.LocalDateTime.now());
                knowledgeChunkMapper.updateById(existingChunk);
                return;
            }

            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setChunkKey(chunkKey);
            chunk.setContent(content);
            chunk.setKeywords(keywords);
            chunk.setSource(source);
            chunk.setSourceId(sourceId);
            chunk.setWeight(1);
            knowledgeChunkMapper.insert(chunk);
        } catch (Exception e) {
            log.warn("索引知识块失败: {}", e.getMessage());
        }
    }
}
