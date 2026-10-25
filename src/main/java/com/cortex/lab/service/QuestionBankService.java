package com.cortex.lab.service;

import com.alibaba.fastjson2.JSON;
import com.cortex.lab.dto.QuestionDto;
import com.cortex.lab.entity.KnowledgeCard;
import com.cortex.lab.entity.QuestionBank;
import com.cortex.lab.entity.QuestionProgress;
import com.cortex.lab.entity.LabScenario;
import com.cortex.lab.mapper.KnowledgeCardMapper;
import com.cortex.lab.mapper.LabScenarioMapper;
import com.cortex.lab.mapper.QuestionBankMapper;
import com.cortex.lab.mapper.QuestionProgressMapper;
import com.cortex.lab.service.PythonAIService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@org.springframework.context.annotation.DependsOn("builtinScenarioSeedService")
public class QuestionBankService {

    private final QuestionBankMapper questionBankMapper;
    private final QuestionProgressMapper progressMapper;
    private final PythonAIService pythonAIService;
    private final com.cortex.lab.mapper.QuestionOptionMapper questionOptionMapper;
    private final com.cortex.lab.mapper.GuidanceSessionMapper guidanceSessionMapper;
    private final KnowledgeCardMapper cardMapper;
    private final LabScenarioMapper labScenarioMapper;

    public QuestionBankService(QuestionBankMapper questionBankMapper,
                                QuestionProgressMapper progressMapper,
                                PythonAIService pythonAIService,
                                KnowledgeCardMapper cardMapper,
                                LabScenarioMapper labScenarioMapper,
                                com.cortex.lab.mapper.QuestionOptionMapper questionOptionMapper,
                                com.cortex.lab.mapper.GuidanceSessionMapper guidanceSessionMapper) {
        this.questionBankMapper = questionBankMapper;
        this.progressMapper = progressMapper;
        this.pythonAIService = pythonAIService;
        this.cardMapper = cardMapper;
        this.labScenarioMapper = labScenarioMapper;
        this.questionOptionMapper = questionOptionMapper;
        this.guidanceSessionMapper = guidanceSessionMapper;
    }

    // @PostConstruct  // 已禁用自动种子，数据由用户手动管理
    public void seedBuiltinQuestions() {
        try {
            long count = questionBankMapper.selectCount(null);
            if (count > 0) return;

            List<LabScenario> scenarios = labScenarioMapper.selectList(null);
            if (scenarios == null || scenarios.isEmpty()) return;

            for (LabScenario s : scenarios) {
                if (questionExists(s.getKnowledgePoint())) continue;
                insertQuestionFromScenario(s);
            }
            log.info("已从场景库同步 {} 道题目到题库", scenarios.size());
        } catch (Exception e) {
            log.warn("同步内置题目到题库失败: {}", e.getMessage());
        }
    }

    private boolean questionExists(String title) {
        if (title == null) return false;
        return questionBankMapper.selectCount(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<QuestionBank>()
                .eq(QuestionBank::getTitle, title)
        ) > 0;
    }

    private void insertQuestionFromScenario(LabScenario s) {
        QuestionBank q = new QuestionBank();
        q.setTitle(s.getKnowledgePoint());
        q.setDescription(s.getExpectedPitfall());
        q.setTrapCode(s.getTrapCode());
        q.setExpectedPitfall(s.getExpectedPitfall());
        q.setCorrectExplanation(s.getCorrectExplanation());
        q.setHints(s.getHints());
        q.setCategory(s.getCategory() != null ? s.getCategory() : "Java基础");
        q.setDifficulty(s.getDifficulty() != null ? s.getDifficulty() : 1);
        q.setStatus("ACTIVE");
        q.setGmtCreate(LocalDateTime.now());
        q.setGmtModified(LocalDateTime.now());
        questionBankMapper.insert(q);
    }

    private static final String GENERATE_PROMPT = """
你是一个编程教学专家。用户提出了一个技术问题，请生成一段包含陷阱的 Java 代码和教学材料。
返回严格的 JSON 格式（不要 markdown 标记）：

{
  "title": "题目标题",
  "description": "问题描述，说明这个陷阱考察什么",
  "trapCode": "完整的可编译运行的 Java 代码（必须包含 main 方法），代码表面正常但暗藏陷阱，通过输出让学习者发现异常",
  "expectedPitfall": "预期的意外现象（一句话）",
  "correctExplanation": "正确原理解释（200字以内）",
  "hints": ["提示1（引导性）", "提示2", "提示3"],
  "difficulty": 2
}

用户问题: %s
""";

    private static final String CARD_GENERATE_PROMPT = """
你是一个知识整理专家。请根据以下信息生成一张极简知识卡片。
返回严格的 JSON 格式（不要 markdown 标记）：

{
  "title": "知识点标题",
  "keyPoints": "要点1|要点2|要点3（每条10字以内）",
  "detailExplanation": "一句话原理解释（50字以内）",
  "codeSnippet": "正确代码示例（10行以内，只展示关键差异）",
  "commonPitfalls": "误区1|误区2（每条15字以内）"
}

题目: %s
陷阱: %s
正确解释: %s
""";

    public List<QuestionDto> listAll(String userId) {
        List<QuestionBank> questions = questionBankMapper.selectList(null);
        return questions.stream().map(q -> {
            QuestionDto dto = toDto(q);
            if (userId != null) {
                QuestionProgress p = progressMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<QuestionProgress>()
                        .eq(QuestionProgress::getQuestionId, q.getId())
                        .eq(QuestionProgress::getUserId, userId)
                );
                if (p != null) {
                    dto.setMastered(p.getMastered());
                    dto.setReviewCount(p.getReviewCount());
                    dto.setNextReviewTime(p.getNextReviewTime());
                }
            }
            return dto;
        }).collect(Collectors.toList());
    }

    public QuestionDto getById(Long id, String userId) {
        QuestionBank q = questionBankMapper.selectById(id);
        if (q == null) return null;
        QuestionDto dto = toDto(q);
        if (userId != null) {
            QuestionProgress p = progressMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<QuestionProgress>()
                    .eq(QuestionProgress::getQuestionId, id)
                    .eq(QuestionProgress::getUserId, userId)
            );
            if (p != null) {
                dto.setMastered(p.getMastered());
                dto.setReviewCount(p.getReviewCount());
                dto.setNextReviewTime(p.getNextReviewTime());
            }
        }
        return dto;
    }

    public QuestionDto generateFromQuestion(String userQuestion) {
        if (hasSimilarQuestion(userQuestion)) {
            throw new RuntimeException("检测到类似问题已存在，请先搜索确认不是重复问题");
        }
        String prompt = GENERATE_PROMPT.formatted(userQuestion);
        try {
            String result = pythonAIService.chatSimple(prompt);
            result = com.cortex.util.JsonUtils.cleanJson(result);
            QuestionDto dto = JSON.parseObject(result, QuestionDto.class);

            QuestionBank entity = new QuestionBank();
            entity.setTitle(dto.getTitle());
            entity.setDescription(dto.getDescription());
            entity.setTrapCode(dto.getTrapCode());
            entity.setExpectedPitfall(dto.getExpectedPitfall());
            entity.setCorrectExplanation(dto.getCorrectExplanation());
            entity.setHints(dto.getHints() != null ? JSON.toJSONString(dto.getHints()) : "[]");
            entity.setCategory("用户导入");
            entity.setDifficulty(dto.getDifficulty() != null ? dto.getDifficulty() : 2);
            entity.setStatus("ACTIVE");
            entity.setGmtCreate(LocalDateTime.now());
            entity.setGmtModified(LocalDateTime.now());
            questionBankMapper.insert(entity);

            return toDto(entity);
        } catch (Exception e) {
            log.error("AI 生成题目失败", e);
            throw new RuntimeException("AI 生成失败: " + e.getMessage());
        }
    }

    public QuestionDto importQuestion(String title, String description, String trapCode,
                                       String expectedPitfall, String correctExplanation, String knowledgePath) {
        QuestionBank entity = new QuestionBank();
        entity.setTitle(title);
        entity.setDescription(description);
        entity.setTrapCode(trapCode);
        entity.setExpectedPitfall(expectedPitfall);
        entity.setCorrectExplanation(correctExplanation);
        entity.setKnowledgePath(knowledgePath);  // 关联知识树路径
        entity.setCategory("用户自定义");
        entity.setDifficulty(2);
        entity.setStatus("ACTIVE");
        entity.setGmtCreate(LocalDateTime.now());
        entity.setGmtModified(LocalDateTime.now());
        questionBankMapper.insert(entity);
        return toDto(entity);
    }

    public int batchImport(String text, String category, String knowledgePath) {
        if (text == null || text.isBlank()) return 0;
        String[] blocks = text.split("(?m)^---\\s*$");
        int count = 0;
        for (String block : blocks) {
            String trimmed = block.trim();
            if (trimmed.isEmpty()) continue;
            try {
                String title = "";
                String description = "";
                String trapCode = "";
                String expectedPitfall = "";
                String correctExplanation = "";

                String[] lines = trimmed.split("\n");
                StringBuilder current = new StringBuilder();
                String section = "title";
                for (String line : lines) {
                    String tl = line.trim();
                    if (tl.startsWith("## ")) {
                        switch (section) {
                            case "title": title = current.toString().trim(); break;
                            case "desc": description = current.toString().trim(); break;
                            case "code": trapCode = current.toString().trim(); break;
                            case "pitfall": expectedPitfall = current.toString().trim(); break;
                        }
                        current = new StringBuilder();
                        section = tl.substring(3).toLowerCase().replace(":", "").trim();
                        if (section.equals("题目")) section = "title";
                        else if (section.contains("描述")) section = "desc";
                        else if (section.contains("代码") || section.contains("陷阱")) section = "code";
                        else if (section.contains("预期") || section.contains("现象")) section = "pitfall";
                        else if (section.contains("解释") || section.contains("原理")) section = "explain";
                    } else {
                        current.append(line).append("\n");
                    }
                }
                // 保存最后一个 section 的内容
                switch (section) {
                    case "title": title = current.toString().trim(); break;
                    case "desc": description = current.toString().trim(); break;
                    case "code": trapCode = current.toString().trim(); break;
                    case "pitfall": expectedPitfall = current.toString().trim(); break;
                    case "explain": correctExplanation = current.toString().trim(); break;
                }

                if (title.isBlank()) {
                    title = trimmed.contains("\n") ? trimmed.substring(0, trimmed.indexOf('\n')).trim() : trimmed;
                    if (title.length() > 100) title = title.substring(0, 100);
                }

                QuestionBank q = new QuestionBank();
                q.setTitle(title);
                q.setDescription(description);
                q.setTrapCode(trapCode);
                q.setExpectedPitfall(expectedPitfall);
                q.setCorrectExplanation(correctExplanation);
                q.setCategory(category != null ? category : "批量导入");
                q.setKnowledgePath(knowledgePath);  // 关联知识树路径
                q.setDifficulty(2);
                q.setStatus("ACTIVE");
                q.setGmtCreate(LocalDateTime.now());
                q.setGmtModified(LocalDateTime.now());
                questionBankMapper.insert(q);
                count++;
            } catch (Exception e) {
                log.warn("批量导入解析失败: {}", e.getMessage());
            }
        }
        return count;
    }

    public List<QuestionDto> searchQuestions(String keyword) {
        if (keyword == null || keyword.isBlank()) return listAll(null);
        List<QuestionBank> all = questionBankMapper.selectList(null);
        String lower = keyword.toLowerCase();
        return all.stream()
            .filter(q -> (q.getTitle() != null && q.getTitle().toLowerCase().contains(lower))
                || (q.getDescription() != null && q.getDescription().toLowerCase().contains(lower))
                || (q.getCategory() != null && q.getCategory().toLowerCase().contains(lower)))
            .map(this::toDto)
            .collect(Collectors.toList());
    }

    public boolean hasSimilarQuestion(String title) {
        if (title == null || title.isBlank()) return false;
        String normalized = title.replaceAll("[\\s,.!?;:，。！？；：、]", "").toLowerCase();
        List<QuestionBank> all = questionBankMapper.selectList(null);
        for (QuestionBank q : all) {
            if (q.getTitle() == null) continue;
            String qNorm = q.getTitle().replaceAll("[\\s,.!?;:，。！？；：、]", "").toLowerCase();
            if (qNorm.contains(normalized) || normalized.contains(qNorm)) return true;
            String longer = normalized.length() >= qNorm.length() ? normalized : qNorm;
            String shorter = normalized.length() >= qNorm.length() ? qNorm : normalized;
            if (longer.contains(shorter)) return true;
        }
        return false;
    }

    // ==================== 选择题选项 ====================

    public List<com.cortex.lab.entity.QuestionOption> getOptions(Long questionId) {
        return questionOptionMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.cortex.lab.entity.QuestionOption>()
                .eq(com.cortex.lab.entity.QuestionOption::getQuestionId, questionId)
                .orderByAsc(com.cortex.lab.entity.QuestionOption::getOrderNum)
        );
    }

    // ==================== 引导会话 ====================

    public com.cortex.lab.entity.GuidanceSession getGuidance(String userId, Long questionId) {
        com.cortex.lab.entity.GuidanceSession session = guidanceSessionMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.cortex.lab.entity.GuidanceSession>()
                .eq(com.cortex.lab.entity.GuidanceSession::getUserId, userId)
                .eq(com.cortex.lab.entity.GuidanceSession::getQuestionId, questionId)
                .last("LIMIT 1")
        );
        if (session == null) {
            session = new com.cortex.lab.entity.GuidanceSession();
            session.setUserId(userId);
            session.setQuestionId(questionId);
            session.setStage("QUIZ");
            session.setHintCount(0);
            session.setCorrectCount(0);
            session.setWrongCount(0);
            session.setResolved(false);
            guidanceSessionMapper.insert(session);
        }
        return session;
    }

    /** 提交答案：返回引导话术 */
    public String submitAnswer(String userId, Long questionId, String optionLabel) {
        com.cortex.lab.entity.GuidanceSession session = getGuidance(userId, questionId);
        com.cortex.lab.entity.QuestionOption selected = questionOptionMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.cortex.lab.entity.QuestionOption>()
                .eq(com.cortex.lab.entity.QuestionOption::getQuestionId, questionId)
                .eq(com.cortex.lab.entity.QuestionOption::getOptionLabel, optionLabel)
                .last("LIMIT 1")
        );
        if (selected == null) return "无效选项。";

        session.setSelectedOption(optionLabel);
        if (Boolean.TRUE.equals(selected.getIsCorrect())) {
            session.setCorrectCount(session.getCorrectCount() + 1);
            session.setStage("MASTERED");
            session.setResolved(true);
            guidanceSessionMapper.updateById(session);
            return "答对了！" + (selected.getGuidance() != null ? selected.getGuidance() : "");
        }

        session.setWrongCount(session.getWrongCount() + 1);
        int hintCount = session.getHintCount() + 1;
        session.setHintCount(hintCount);
        if (hintCount >= 2) {
            session.setStage("REVEAL");
        } else {
            session.setStage(hintCount == 1 ? "FIRST_HINT" : "SECOND_HINT");
        }
        guidanceSessionMapper.updateById(session);

        String guidance = selected.getGuidance();
        if (guidance != null) return guidance;
        return "不对，再想想。" + (hintCount >= 2 ? " 要不要看看正确答案？" : " 注意观察代码里的细节。");
    }

    public void deleteQuestion(Long id) {
        questionBankMapper.deleteById(id);
        progressMapper.delete(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<QuestionProgress>()
                .eq(QuestionProgress::getQuestionId, id)
        );
        cardMapper.delete(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeCard>()
                .eq(KnowledgeCard::getQuestionId, id)
        );
    }

    public void approveQuestion(Long id) {
        QuestionBank qb = questionBankMapper.selectById(id);
        if (qb == null) throw new RuntimeException("题目不存在");
        qb.setStatus("ACTIVE");
        qb.setGmtModified(java.time.LocalDateTime.now());
        questionBankMapper.updateById(qb);
    }

    public void rejectQuestion(Long id) {
        QuestionBank qb = questionBankMapper.selectById(id);
        if (qb == null) throw new RuntimeException("题目不存在");
        qb.setStatus("REJECTED");
        qb.setGmtModified(java.time.LocalDateTime.now());
        questionBankMapper.updateById(qb);
    }

    public void setMastered(Long questionId, String userId, boolean mastered) {
        QuestionProgress p = progressMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<QuestionProgress>()
                .eq(QuestionProgress::getQuestionId, questionId)
                .eq(QuestionProgress::getUserId, userId)
        );
        if (p == null) {
            p = new QuestionProgress();
            p.setQuestionId(questionId);
            p.setUserId(userId);
            p.setMastered(mastered);
            p.setReviewCount(0);
            p.setGmtCreate(LocalDateTime.now());
            p.setGmtModified(LocalDateTime.now());
            if (mastered) {
                p.setLastReviewTime(LocalDateTime.now());
                p.setNextReviewTime(LocalDateTime.now().plusDays(1));
            }
            progressMapper.insert(p);
        } else {
            p.setMastered(mastered);
            p.setGmtModified(LocalDateTime.now());
            if (mastered) {
                p.setLastReviewTime(LocalDateTime.now());
                p.setNextReviewTime(LocalDateTime.now().plusDays(1));
            }
            progressMapper.updateById(p);
        }
    }

    public List<QuestionDto> getReviewList(String userId) {
        List<QuestionProgress> dueProgresses = progressMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<QuestionProgress>()
                .eq(QuestionProgress::getUserId, userId)
                .eq(QuestionProgress::getMastered, true)
                .apply("next_review_time IS NOT NULL AND next_review_time <= NOW()")
        );
        return dueProgresses.stream().map(p -> {
            QuestionBank q = questionBankMapper.selectById(p.getQuestionId());
            if (q == null) return null;
            QuestionDto dto = toDto(q);
            dto.setMastered(p.getMastered());
            dto.setReviewCount(p.getReviewCount());
            dto.setNextReviewTime(p.getNextReviewTime());
            return dto;
        }).filter(Objects::nonNull).collect(Collectors.toList());
    }

    public void updateReview(Long questionId, String userId, boolean stillMastered) {
        QuestionProgress p = progressMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<QuestionProgress>()
                .eq(QuestionProgress::getQuestionId, questionId)
                .eq(QuestionProgress::getUserId, userId)
        );
        if (p != null) {
            if (stillMastered) {
                p.setReviewCount(p.getReviewCount() != null ? p.getReviewCount() + 1 : 1);
                int count = p.getReviewCount();
                int intervalDays = count <= 1 ? 1 : count <= 3 ? 3 : count <= 5 ? 7 : 15;
                p.setNextReviewTime(LocalDateTime.now().plusDays(intervalDays));
                p.setLastReviewTime(LocalDateTime.now());
                p.setMastered(true);
            } else {
                p.setMastered(false);
                p.setReviewCount(0);
                p.setNextReviewTime(null);
            }
            p.setGmtModified(LocalDateTime.now());
            progressMapper.updateById(p);
        }
    }

    public KnowledgeCard generateCard(Long questionId) {
        QuestionBank q = questionBankMapper.selectById(questionId);
        if (q == null) throw new RuntimeException("题目不存在");

        String prompt = CARD_GENERATE_PROMPT.formatted(q.getTitle(), q.getExpectedPitfall(), q.getCorrectExplanation());
        try {
            String result = pythonAIService.chatSimple(prompt);
            result = com.cortex.util.JsonUtils.cleanJson(result);
            CardGenResponse gen = JSON.parseObject(result, CardGenResponse.class);

            KnowledgeCard card = new KnowledgeCard();
            card.setQuestionId(questionId);
            card.setTitle(gen.title);
            card.setKeyPoints(gen.keyPoints);
            card.setDetailExplanation(gen.detailExplanation);
            card.setCodeSnippet(gen.codeSnippet);
            card.setCommonPitfalls(gen.commonPitfalls);
            card.setGmtCreate(LocalDateTime.now());
            card.setGmtModified(LocalDateTime.now());

            com.cortex.lab.mapper.KnowledgeCardMapper cardMapper = this.cardMapper;
            cardMapper.insert(card);
            return card;
        } catch (Exception e) {
            log.error("生成知识卡片失败", e);
            throw new RuntimeException("知识卡片生成失败: " + e.getMessage());
        }
    }

    @lombok.Data
    static class CardGenResponse {
        private String title;
        private String keyPoints;
        private String detailExplanation;
        private String codeSnippet;
        private String commonPitfalls;
    }

    private QuestionDto toDto(QuestionBank entity) {
        QuestionDto dto = new QuestionDto();
        dto.setId(entity.getId());
        dto.setTitle(entity.getTitle());
        dto.setDescription(entity.getDescription());
        dto.setTrapCode(entity.getTrapCode());
        dto.setExpectedPitfall(entity.getExpectedPitfall());
        dto.setCorrectExplanation(entity.getCorrectExplanation());
        dto.setHints(entity.getHints());
        dto.setCategory(entity.getCategory());
        dto.setDifficulty(entity.getDifficulty());
        dto.setStatus(entity.getStatus());
        dto.setGmtCreate(entity.getGmtCreate());
        return dto;
    }
}
