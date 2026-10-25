package com.cortex.lab.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cortex.lab.dto.LearningReportDTO;
import com.cortex.lab.entity.*;
import com.cortex.lab.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LearningReportService {
    // 学习报表服务：生成周报/月报，记录日常学习活动

    private final DailyLearningMapper dailyLearningMapper;
    private final QuestionProgressMapper questionProgressMapper;
    private final QuestionBankMapper questionBankMapper;
    private final UserMapper profileMapper;

    // 生成学习报表（周报/月报）
    public LearningReportDTO generateReport(String userId, String reportType) {
        LocalDate today = LocalDate.now();
        LocalDate periodStart;
        LocalDate periodEnd = today;

        if ("WEEKLY".equalsIgnoreCase(reportType)) {
            periodStart = today.minusDays(7);
        } else if ("MONTHLY".equalsIgnoreCase(reportType)) {
            periodStart = today.minusDays(30);
        } else {
            throw new RuntimeException("不支持的报表类型: " + reportType);
        }

        // Build report data
        LearningReportDTO dto = new LearningReportDTO();
        dto.setReportType(reportType);
        dto.setPeriodStart(periodStart.format(DateTimeFormatter.ISO_LOCAL_DATE));
        dto.setPeriodEnd(periodEnd.format(DateTimeFormatter.ISO_LOCAL_DATE));

        // Daily logs
        List<DailyLearning> dailyLogs = dailyLearningMapper.selectList(
            new LambdaQueryWrapper<DailyLearning>()
                .eq(DailyLearning::getUserId, userId)
                .ge(DailyLearning::getLogDate, periodStart)
                .le(DailyLearning::getLogDate, periodEnd)
                .orderByAsc(DailyLearning::getLogDate)
        );

        int totalAnswered = dailyLogs.stream().mapToInt(l -> l.getQuestionsAnswered() != null ? l.getQuestionsAnswered() : 0).sum();
        int totalCorrect = dailyLogs.stream().mapToInt(l -> l.getCorrectCount() != null ? l.getCorrectCount() : 0).sum();
        int totalMinutes = dailyLogs.stream().mapToInt(l -> l.getStudyMinutes() != null ? l.getStudyMinutes() : 0).sum();

        dto.setTotalQuestionsAnswered(totalAnswered);
        dto.setTotalCorrect(totalCorrect);
        dto.setAccuracy(totalAnswered > 0 ? (double) totalCorrect / totalAnswered * 100 : 0);
        dto.setTotalStudyMinutes(totalMinutes);
        dto.setStudyDays((int) dailyLogs.stream().filter(l -> l.getQuestionsAnswered() != null && l.getQuestionsAnswered() > 0).count());

        // Daily stats
        List<Map<String, Object>> dailyStats = dailyLogs.stream().map(l -> {
            Map<String, Object> m = new HashMap<>();
            m.put("date", l.getLogDate().toString());
            m.put("questionsAnswered", l.getQuestionsAnswered());
            m.put("correctCount", l.getCorrectCount());
            m.put("studyMinutes", l.getStudyMinutes());
            return m;
        }).collect(Collectors.toList());
        dto.setDailyStats(dailyStats);

        // Category breakdown
        List<Map<String, Object>> categoryBreakdown = buildCategoryBreakdown(userId);
        dto.setCategoryBreakdown(categoryBreakdown);

        // Weak areas
        List<Map<String, Object>> weakAreasList = buildWeakAreas(userId);
        dto.setWeakAreasList(weakAreasList);

        // Progress trend (by day accuracy)
        List<Map<String, Object>> progressTrend = dailyLogs.stream()
            .filter(l -> l.getQuestionsAnswered() != null && l.getQuestionsAnswered() > 0)
            .map(l -> {
                Map<String, Object> m = new HashMap<>();
                m.put("date", l.getLogDate().toString());
                m.put("accuracy", (double) l.getCorrectCount() / l.getQuestionsAnswered() * 100);
                return m;
            }).collect(Collectors.toList());
        dto.setProgressTrend(progressTrend);

        // Recommendations
        dto.setRecommendations(generateRecommendations(dto));
        dto.setFocusAreas(generateFocusAreas(weakAreasList));

        return dto;
    }

    private List<Map<String, Object>> buildCategoryBreakdown(String userId) {
        try {
            List<QuestionBank> allQuestions = questionBankMapper.selectList(null);
            Map<String, Integer> categoryTotal = new HashMap<>();
            Map<String, Integer> categoryMastered = new HashMap<>();

            for (QuestionBank q : allQuestions) {
                String cat = q.getCategory() != null ? q.getCategory() : "未分类";
                categoryTotal.merge(cat, 1, Integer::sum);

                if (userId != null) {
                    QuestionProgress p = questionProgressMapper.selectOne(
                        new LambdaQueryWrapper<QuestionProgress>()
                            .eq(QuestionProgress::getQuestionId, q.getId())
                            .eq(QuestionProgress::getUserId, userId)
                    );
                    if (p != null && Boolean.TRUE.equals(p.getMastered())) {
                        categoryMastered.merge(cat, 1, Integer::sum);
                    }
                }
            }

            return categoryTotal.entrySet().stream().map(e -> {
                Map<String, Object> m = new HashMap<>();
                m.put("category", e.getKey());
                m.put("total", e.getValue());
                m.put("mastered", categoryMastered.getOrDefault(e.getKey(), 0));
                m.put("masteryRate", e.getValue() > 0 ? (double) categoryMastered.getOrDefault(e.getKey(), 0) / e.getValue() * 100 : 0);
                return m;
            }).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("构建分类统计失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<Map<String, Object>> buildWeakAreas(String userId) {
        try {
            List<QuestionBank> allQuestions = questionBankMapper.selectList(null);
            Map<String, Integer> categoryNotMastered = new HashMap<>();
            Map<String, Integer> categoryTotal = new HashMap<>();

            for (QuestionBank q : allQuestions) {
                String cat = q.getCategory() != null ? q.getCategory() : "未分类";
                categoryTotal.merge(cat, 1, Integer::sum);

                if (userId != null) {
                    QuestionProgress p = questionProgressMapper.selectOne(
                        new LambdaQueryWrapper<QuestionProgress>()
                            .eq(QuestionProgress::getQuestionId, q.getId())
                            .eq(QuestionProgress::getUserId, userId)
                    );
                    if (p == null || !Boolean.TRUE.equals(p.getMastered())) {
                        categoryNotMastered.merge(cat, 1, Integer::sum);
                    }
                }
            }

            return categoryNotMastered.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .map(e -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("category", e.getKey());
                    m.put("notMastered", e.getValue());
                    m.put("total", categoryTotal.getOrDefault(e.getKey(), 0));
                    m.put("weaknessScore", categoryTotal.getOrDefault(e.getKey(), 0) > 0
                        ? (double) e.getValue() / categoryTotal.get(e.getKey()) * 100 : 0);
                    return m;
                }).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("构建薄弱知识点失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<String> generateRecommendations(LearningReportDTO dto) {
        List<String> recs = new ArrayList<>();
        if (dto.getStudyDays() == 0) {
            recs.add("开始你的学习之旅吧！建议每天至少练习2-3道题目。");
            return recs;
        }
        if (dto.getAccuracy() < 50) {
            recs.add("正确率偏低，建议复习基础知识，多看看知识卡片。");
        } else if (dto.getAccuracy() < 70) {
            recs.add("正确率还有提升空间，重点关注薄弱知识点。");
        } else {
            recs.add("正确率不错！挑战一些更难的题目来提升自己。");
        }
        if (dto.getStudyDays() < 3) {
            recs.add("保持学习的连续性效果更好，建议每天抽15分钟练习。");
        }
        if (dto.getTotalQuestionsAnswered() < 10) {
            recs.add("题目练习量较少，建议多做练习巩固知识。");
        }
        if (dto.getCategoryBreakdown() != null) {
            dto.getCategoryBreakdown().stream()
                .filter(c -> ((Number) c.getOrDefault("masteryRate", 100)).doubleValue() < 40)
                .findFirst()
                .ifPresent(c -> recs.add("在「" + c.get("category") + "」方面需要加强练习。"));
        }
        if (recs.isEmpty()) {
            recs.add("学习状态良好！可以尝试生成新的知识点进行学习。");
        }
        return recs;
    }

    private List<String> generateFocusAreas(List<Map<String, Object>> weakAreasList) {
        return weakAreasList.stream()
            .map(w -> (String) w.get("category"))
            .collect(Collectors.toList());
    }

    // 记录日常学习活动
    public void recordDailyActivity(String userId, int questionsAnswered, int correctCount, int studyMinutes, String knowledgePoints) {
        LocalDate today = LocalDate.now();
        try {
            DailyLearning log = dailyLearningMapper.selectOne(
                new LambdaQueryWrapper<DailyLearning>()
                    .eq(DailyLearning::getUserId, userId)
                    .eq(DailyLearning::getLogDate, today)
            );
            if (log == null) {
                log = new DailyLearning();
                log.setUserId(userId);
                log.setLogDate(today);
                log.setQuestionsAnswered(questionsAnswered);
                log.setCorrectCount(correctCount);
                log.setStudyMinutes(studyMinutes);
                log.setKnowledgePointsStudied(knowledgePoints);
                log.setGmtCreate(java.time.LocalDateTime.now());
                dailyLearningMapper.insert(log);
            } else {
                log.setQuestionsAnswered((log.getQuestionsAnswered() != null ? log.getQuestionsAnswered() : 0) + questionsAnswered);
                log.setCorrectCount((log.getCorrectCount() != null ? log.getCorrectCount() : 0) + correctCount);
                log.setStudyMinutes((log.getStudyMinutes() != null ? log.getStudyMinutes() : 0) + studyMinutes);
                if (knowledgePoints != null) {
                    String existing = log.getKnowledgePointsStudied();
                    log.setKnowledgePointsStudied(existing != null ? existing + "," + knowledgePoints : knowledgePoints);
                }
                dailyLearningMapper.updateById(log);
            }
        } catch (Exception e) {
            log.error("记录日常活动失败: userId={}", userId, e);
        }
    }

    // 记录答题结果，更新学习画像
    public void recordQuestionAnswered(String userId, boolean correct) {
        try {
            User profile = profileMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUserId, userId)
            );
            if (profile == null) {
                profile = new User();
                profile.setUserId(userId);
                profile.setTotalStudyHours(0.0);
                profile.setTotalQuestionsAnswered(1);
                profile.setTotalCorrect(correct ? 1 : 0);
                profile.setStudyStreak(1);
                profile.setLastStudyDate(LocalDate.now());
                profile.setSkillLevel("BEGINNER");
                profile.setGmtCreate(java.time.LocalDateTime.now());
                profile.setGmtModified(java.time.LocalDateTime.now());
                profileMapper.insert(profile);
            } else {
                profile.setTotalQuestionsAnswered((profile.getTotalQuestionsAnswered() != null ? profile.getTotalQuestionsAnswered() : 0) + 1);
                profile.setTotalCorrect((profile.getTotalCorrect() != null ? profile.getTotalCorrect() : 0) + (correct ? 1 : 0));
                if (profile.getLastStudyDate() != null && profile.getLastStudyDate().equals(LocalDate.now().minusDays(1))) {
                    profile.setStudyStreak((profile.getStudyStreak() != null ? profile.getStudyStreak() : 0) + 1);
                } else if (profile.getLastStudyDate() == null || !profile.getLastStudyDate().equals(LocalDate.now())) {
                    profile.setStudyStreak(1);
                }
                profile.setLastStudyDate(LocalDate.now());
                profile.setGmtModified(java.time.LocalDateTime.now());
                profileMapper.updateById(profile);
            }

            recordDailyActivity(userId, 1, correct ? 1 : 0, 0, null);
        } catch (Exception e) {
            log.error("记录答题失败: userId={}, correct={}", userId, correct, e);
        }
    }
}
