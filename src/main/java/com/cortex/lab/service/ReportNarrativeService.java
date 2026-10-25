package com.cortex.lab.service;

import com.alibaba.fastjson2.JSON;
import com.cortex.lab.dto.LearningReportDTO;
import com.cortex.lab.dto.UserInfoDTO;
import com.cortex.lab.service.PythonAIService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportNarrativeService {

    private final PythonAIService pythonAIService;
    private final AuthService authService;
    private final LearningReportService learningReportService;

    private static final String NARRATIVE_PROMPT = """
你是一名专业的学习分析师。请根据用户的学习数据，生成一份有温度、有洞察的 AI 学习报告。

请输出严格的 JSON 格式（不要包含 ```markdown 标记），包含以下字段：
{
  "summary": "一句话总结用户本周/本月学习情况",
  "performance": "详细分析学习表现，包括答题量、正确率、学习时长等",
  "trends": "学习趋势分析，是否有进步或退步",
  "strengths": ["优势1", "优势2", "优势3"],
  "weaknesses": ["待改进1", "待改进2"],
  "recommendations": ["具体建议1", "具体建议2", "具体建议3"],
  "nextSteps": "下阶段学习计划建议"
}

用户基础信息：
- 用户名: %s
- 技能等级: %s
- 总答题数: %d
- 总正确率: %.1f%%
- 学习连续天数: %d
- 薄弱领域: %s
- 学习目标: %s

%s 学习数据：
- 报告周期: %s 至 %s
- 答题数: %d
- 正确数: %d
- 正确率: %.1f%%
- 学习分钟: %d
- 学习天数: %d

分类掌握情况：
%s

薄弱环节：
%s
""";

    public String generateNarrative(String userId, String reportType) {
        try {
            LearningReportDTO report = learningReportService.generateReport(userId, reportType);
            UserInfoDTO userInfo = authService.getUserInfo(userId);

            String reportTypeCn = "WEEKLY".equalsIgnoreCase(reportType) ? "周报" : "月报";
            String categoryStr = formatCategoryBreakdown(report.getCategoryBreakdown());
            String weakStr = formatWeakAreas(report.getWeakAreasList());

            String prompt = String.format(NARRATIVE_PROMPT,
                userInfo.getUsername(),
                userInfo.getSkillLevel() != null ? userInfo.getSkillLevel() : "未评估",
                userInfo.getTotalQuestionsAnswered() != null ? userInfo.getTotalQuestionsAnswered() : 0,
                userInfo.getAccuracy() != null ? userInfo.getAccuracy() : 0,
                userInfo.getStudyStreak() != null ? userInfo.getStudyStreak() : 0,
                userInfo.getWeakAreas() != null ? String.join(", ", userInfo.getWeakAreas()) : "暂无",
                userInfo.getLearningGoal() != null ? userInfo.getLearningGoal() : "未设置",
                reportTypeCn,
                report.getPeriodStart(),
                report.getPeriodEnd(),
                report.getTotalQuestionsAnswered(),
                report.getTotalCorrect(),
                report.getAccuracy(),
                report.getTotalStudyMinutes(),
                report.getStudyDays(),
                categoryStr,
                weakStr
            );

            return com.cortex.util.JsonUtils.cleanJson(pythonAIService.chatSimple("你是一名学习分析师", prompt));
        } catch (Exception e) {
            log.error("生成AI叙事报告失败", e);
            return buildFallbackNarrativeJson(userId, reportType, e.getMessage());
        }
    }

    private String buildFallbackNarrativeJson(String userId, String reportType, String reason) {
        try {
            UserInfoDTO userInfo = authService.getUserInfo(userId);
            LearningReportDTO report = learningReportService.generateReport(userId, reportType);
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("summary", userInfo.getUsername() + " 的" + ("WEEKLY".equalsIgnoreCase(reportType) ? "周" : "月") + "学习概况");
            fallback.put("performance", String.format(
                "本周期答题 %d 道，正确 %d 道，正确率 %.1f%%，学习 %d 分钟。%s",
                report.getTotalQuestionsAnswered(),
                report.getTotalCorrect(),
                report.getAccuracy(),
                report.getTotalStudyMinutes(),
                reason != null && reason.contains("API") ? "（AI 分析暂不可用，请配置 LLM_API_KEY）" : ""
            ));
            fallback.put("recommendations", List.of(
                "继续巩固薄弱知识点",
                "保持每日练习习惯",
                "复习待做题目"
            ));
            return JSON.toJSONString(fallback);
        } catch (Exception ex) {
            Map<String, Object> minimal = Map.of(
                "summary", "学习报告",
                "performance", "暂无足够学习数据，请先完成一些练习后再生成报告。" + (reason != null ? "（" + reason + "）" : ""),
                "recommendations", List.of("从知识树选择知识点开始练习")
            );
            return JSON.toJSONString(minimal);
        }
    }

    private String formatCategoryBreakdown(List<Map<String, Object>> breakdown) {
        if (breakdown == null || breakdown.isEmpty()) return "暂无分类数据";
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> m : breakdown) {
            sb.append("  - ").append(m.get("category"))
              .append(": ").append(m.get("mastered")).append("/").append(m.get("total"))
              .append(" (").append(String.format("%.1f", m.getOrDefault("masteryRate", 0))).append("%)\n");
        }
        return sb.toString();
    }

    private String formatWeakAreas(List<Map<String, Object>> weakAreas) {
        if (weakAreas == null || weakAreas.isEmpty()) return "暂无薄弱环节";
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> m : weakAreas) {
            sb.append("  - ").append(m.get("category"))
              .append(": ").append(m.get("notMastered")).append("/").append(m.get("total"))
              .append(" 未掌握\n");
        }
        return sb.toString();
    }
}
