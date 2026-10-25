package com.cortex.lab.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cortex.entity.MistakeRecord;
import com.cortex.lab.dto.*;
import com.cortex.lab.entity.QuestionBank;
import com.cortex.lab.entity.User;
import com.cortex.mapper.MistakeRecordMapper;
import com.cortex.lab.mapper.QuestionBankMapper;
import com.cortex.lab.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 平台功能注册器：聚合所有项目API功能，供AI助手动态调用。
 * 每个功能暴露名称、描述、参数说明，由callFeature统一调度。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformFeatureService {

    private final CommunityTrapService communityTrapService;
    private final LearningReportService learningReportService;
    private final DiscussionService discussionService;
    private final TutorService tutorService;
    private final ScenarioService scenarioService;
    private final QuestionBankService questionBankService;
    private final KnowledgeTreeService knowledgeTreeService;
    private final QuestionBankMapper questionBankMapper;
    private final UserMapper userMapper;
    private final MistakeRecordMapper mistakeRecordMapper;
    private final ReportNarrativeService reportNarrativeService;
    private final KnowledgeCardService knowledgeCardService;

    /** 功能描述：name -> {label, description, paramInfo} */
    private record FeatureInfo(String label, String description, String paramInfo) {}
    private final Map<String, FeatureInfo> featureCatalog = new LinkedHashMap<>();

    @jakarta.annotation.PostConstruct
    public void init() {
        register("searchTraps", "搜索社区陷阱", "可按Java版本/分类/状态过滤",
            "{\"javaVersion\":\"17\",\"category\":\"Java核心\",\"status\":\"APPROVED\"}");
        register("getTrap", "查看陷阱详情", "查看指定ID的社区陷阱详情", "陷阱ID");
        register("submitTrap", "提交陷阱", "提交新的社区陷阱代码",
            "{\"title\":\"标题\",\"trapCode\":\"代码\",\"expectedPitfall\":\"坑点\",\"correctExplanation\":\"解释\",\"knowledgePoint\":\"知识点\",\"category\":\"分类\",\"javaVersion\":\"17\"}");
        register("voteTrap", "投票", "为社区陷阱投票", "陷阱ID");
        register("getLearningReport", "学习报告", "生成周报/月报（含统计、薄弱环节、建议）", "\"WEEKLY\"或\"MONTHLY\"");
        register("getLearningStats", "学习统计", "获取学习统计概览", "无参数");
        register("getDiscussions", "查看讨论", "查看题目的用户讨论", "题目ID");
        register("addDiscussion", "添加讨论", "在题目下添加评论", "{\"questionId\":ID,\"content\":\"评论\"}");
        register("tutorAssess", "导师自评", "答对给拓展知识，答错给引导", "{\"questionId\":ID,\"correct\":true/false}");
        register("getUserProfile", "用户画像", "获取统计、薄弱环节、近期错误等", "无参数");
        register("listScenarios", "场景列表", "列出所有可用陷阱场景", "无参数");
        register("generateScenarioFromKnowledge", "生成场景", "根据知识点生成新陷阱场景", "{\"knowledgePoint\":\"知识点\",\"category\":\"分类\"}");
        register("getQuestionsByCategory", "按分类查题", "按分类查看题目列表", "{\"category\":\"分类名\"}");
        register("getReportNarrative", "AI报告叙述", "生成AI学习报告叙述（有温度的分析报告）", "\"WEEKLY\"或\"MONTHLY\"");
        register("approveTrap", "审核通过", "审核通过社区陷阱（管理员）", "陷阱ID");
        register("rejectTrap", "审核拒绝", "审核拒绝社区陷阱（管理员）", "陷阱ID");
        register("integrateTrap", "导入题库", "将社区陷阱导入题库", "陷阱ID");
        // ---- 旧版助手动作（迁移到统一分发） ----
        register("searchQuestions", "搜索题目", "按关键词搜索题库", "关键词");
        register("getQuestion", "查看题目", "查看题目详情", "题目ID");
        register("generateQuestion", "生成题目", "AI生成一道新陷阱题", "知识点");
        register("createQuestion", "创建题目", "手动创建题目", "{\"title\":\"标题\",\"trapCode\":\"代码\"}");
        register("deleteQuestion", "删除题目", "删除指定题目", "题目ID");
        register("toggleQuestionMastered", "标记掌握", "标记题目掌握/未掌握", "{\"questionId\":ID,\"mastered\":true}");
        register("getReviewList", "待复习列表", "查看待复习题目", "无参数");
        register("submitReview", "提交复习", "提交复习结果", "{\"questionId\":ID,\"stillMastered\":true}");
        register("getKnowledgeTree", "知识树", "获取Java后端知识树结构", "无参数");
        register("generateScenario", "生成场景", "从知识树节点生成陷阱场景", "节点ID");
        register("toggleNodeMastered", "节点掌握", "切换知识节点掌握状态", "{\"nodeId\":\"节点ID\",\"mastered\":true}");
        register("listCards", "知识卡片", "列出所有知识卡片", "无参数");
        register("generateCard", "生成卡片", "为题目生成知识卡片", "题目ID");
        register("deleteCard", "删除卡片", "删除知识卡片", "卡片ID");
    }

    private void register(String name, String label, String description, String paramInfo) {
        featureCatalog.put(name, new FeatureInfo(label, description, paramInfo));
    }

    /** 构建功能列表提示文本，供AI助手动态注入SYSTEM_PROMPT */
    public String buildFeaturePrompt() {
        StringBuilder sb = new StringBuilder("\n## 平台功能\n以下功能可由 callFeature 调用，用户提到时直接执行：\n");

        Map<String, List<String>> groups = new LinkedHashMap<>();
        groups.put("社区陷阱", List.of("searchTraps", "getTrap", "submitTrap", "voteTrap"));
        groups.put("学习报告", List.of("getLearningReport", "getLearningStats", "getReportNarrative"));
        groups.put("讨论", List.of("getDiscussions", "addDiscussion"));
        groups.put("导师自评", List.of("tutorAssess"));
        groups.put("用户画像", List.of("getUserProfile"));
        groups.put("场景", List.of("listScenarios", "generateScenarioFromKnowledge"));
        groups.put("题库", List.of("getQuestionsByCategory"));

        for (var entry : groups.entrySet()) {
            sb.append("\n### ").append(entry.getKey()).append("\n");
            for (String name : entry.getValue()) {
                FeatureInfo info = featureCatalog.get(name);
                if (info != null) {
                    sb.append("- callFeature(\"").append(name).append("\") — ").append(info.description);
                    if (!"无参数".equals(info.paramInfo)) {
                        sb.append("\n  参数：").append(info.paramInfo);
                    }
                    sb.append("\n");
                }
            }
        }

        sb.append("""

        调用方式：
        {"type":"callFeature","payload":{"feature":"功能名","params":参数}}
        无参数时 params 可为 null。
        """);
        return sb.toString();
    }

    /** 执行功能调用，返回结果文本 */
    public String execute(String featureName, Map<String, Object> params, String userId) {
        if (featureName == null) return "功能名不能为空。";
        try {
            return switch (featureName) {
                case "searchTraps" -> execSearchTraps(params);
                case "getTrap" -> execGetTrap(extractId(params));
                case "submitTrap" -> execSubmitTrap(params);
                case "voteTrap" -> execVoteTrap(extractId(params));
                case "getLearningReport" -> execGetLearningReport(params, userId);
                case "getLearningStats" -> execGetLearningStats(userId);
                case "getDiscussions" -> execGetDiscussions(extractId(params));
                case "addDiscussion" -> execAddDiscussion(params, userId);
                case "tutorAssess" -> execTutorAssess(params);
                case "getUserProfile" -> execGetUserProfile(userId);
                case "listScenarios" -> execListScenarios();
                case "generateScenarioFromKnowledge" -> execGenerateScenarioFromKnowledge(params);
                case "getQuestionsByCategory" -> execGetQuestionsByCategory(params);
                case "getReportNarrative" -> execGetReportNarrative(params, userId);
                case "approveTrap" -> execApproveTrap(extractId(params));
                case "rejectTrap" -> execRejectTrap(extractId(params));
                case "integrateTrap" -> execIntegrateTrap(extractId(params));
                // ---- 复用 executeAction 中的功能（老功能通过 callFeature 调起） ----
                case "searchQuestions" -> execSearchQuestions(params != null ? (String) params.get("keyword") : "", userId);
                case "getQuestion" -> execGetQuestion(extractId(params), userId);
                case "getReviewList" -> execGetReviewList(userId);
                case "getKnowledgeTree" -> execGetKnowledgeTree();
                case "generateScenario" -> execGenerateScenario(params != null ? (String) params.get("nodeId") : "");
                case "listCards" -> execListCards();
                case "generateCard" -> execGenerateCard(extractId(params));
                case "deleteCard" -> execDeleteCard(extractId(params));
                case "deleteQuestion" -> execDeleteQuestion(extractId(params));
                case "updateUserProfile" -> execUpdateUserProfile(params, userId);
                default -> "未知功能：" + featureName + "，请检查功能名是否正确。";
            };
        } catch (Exception e) {
            log.warn("执行功能[{}]失败: {}", featureName, e.getMessage());
            return "操作失败: " + e.getMessage();
        }
    }

    /** 统一动作分发：兼容旧版 AssistantService 的 action 格式 */
    public String executeAction(String type, Object payload, String userId) {
        if (type == null) return null;
        try {
            return switch (type) {
                case "callFeature" -> {
                    Map<String, Object> p = mapPayload(payload);
                    yield execute(p != null ? (String) p.get("feature") : null,
                        p != null ? (Map<String, Object>) p.get("params") : null, userId);
                }
                // ---- Question bank ----
                case "searchQuestions" -> execSearchQuestions(payload != null ? payload.toString() : "", userId);
                case "getQuestion" -> execGetQuestion(toLong(payload), userId);
                case "generateQuestion" -> execGenerateQuestion(payload != null ? payload.toString() : "");
                case "createQuestion" -> execCreateQuestion(mapPayload(payload));
                case "deleteQuestion" -> execDeleteQuestion(toLong(payload));
                case "toggleQuestionMastered" -> execToggleQuestionMastered(mapPayload(payload), userId);
                case "getReviewList" -> execGetReviewList(userId);
                case "submitReview" -> execSubmitReview(mapPayload(payload), userId);
                // ---- Knowledge tree ----
                case "getKnowledgeTree" -> execGetKnowledgeTree();
                case "generateScenario" -> execGenerateScenario(payload != null ? payload.toString() : "");
                case "toggleNodeMastered" -> execToggleNodeMastered(mapPayload(payload), userId);
                // ---- Knowledge cards ----
                case "listCards" -> execListCards();
                case "generateCard" -> execGenerateCard(toLong(payload));
                case "deleteCard" -> execDeleteCard(toLong(payload));
                // ---- Redirect to execute() for new-style features ----
                default -> execute(type, mapPayload(payload), userId);
            };
        } catch (Exception e) {
            log.warn("执行动作[{}]失败: {}", type, e.getMessage());
            return "操作失败: " + e.getMessage();
        }
    }

    // ==================== 社区陷阱 ====================

    private String execSearchTraps(Map<String, Object> params) {
        String jv = params != null ? (String) params.get("javaVersion") : null;
        String cat = params != null ? (String) params.get("category") : null;
        String st = params != null ? (String) params.get("status") : null;
        List<CommunityTrapDto> list = communityTrapService.listTraps(jv, cat, st);
        if (list.isEmpty()) return "暂无社区陷阱。";
        StringBuilder sb = new StringBuilder("**社区陷阱**（共" + list.size() + "条）：\n");
        for (int i = 0; i < Math.min(list.size(), 20); i++) {
            CommunityTrapDto t = list.get(i);
            sb.append((i + 1) + ". **" + t.getTitle() + "**");
            if (t.getDifficulty() != null) sb.append(" " + "★".repeat(t.getDifficulty()));
            sb.append(" [" + t.getCategory() + "] 投票:" + (t.getVoteCount() != null ? t.getVoteCount() : 0) + "\n");
        }
        return sb.toString();
    }

    private String execGetTrap(Long id) {
        if (id == null) return "陷阱ID不能为空。";
        CommunityTrapDto t = communityTrapService.getById(id);
        if (t == null) return "陷阱不存在（ID=" + id + "）。";
        StringBuilder sb = new StringBuilder();
        sb.append("**" + t.getTitle() + "**\n");
        sb.append("分类：" + t.getCategory() + " | Java " + t.getJavaVersion()
            + " | 难度：" + "★".repeat(t.getDifficulty() != null ? t.getDifficulty() : 0) + "\n");
        sb.append("```java\n" + t.getTrapCode() + "\n```\n\n");
        sb.append("**坑点：**" + t.getExpectedPitfall() + "\n");
        sb.append("**解释：**" + t.getCorrectExplanation() + "\n");
        return sb.toString();
    }

    private String execSubmitTrap(Map<String, Object> params) {
        if (params == null) return "缺少参数。";
        CommunityTrapSubmitRequest req = new CommunityTrapSubmitRequest();
        req.setTitle((String) params.get("title"));
        req.setKnowledgePoint((String) params.get("knowledgePoint"));
        req.setCategory((String) params.get("category"));
        req.setJavaVersion((String) params.get("javaVersion"));
        req.setTrapCode((String) params.get("trapCode"));
        req.setExpectedPitfall((String) params.get("expectedPitfall"));
        req.setCorrectExplanation((String) params.get("correctExplanation"));
        req.setHints(params.get("hints") != null ? params.get("hints").toString() : null);
        if (params.get("difficulty") != null) req.setDifficulty(((Number) params.get("difficulty")).intValue());
        if (req.getTitle() == null || req.getTitle().isBlank()) return "标题不能为空。";
        if (req.getTrapCode() == null || req.getTrapCode().isBlank()) return "陷阱代码不能为空。";
        communityTrapService.submit(req);
        return "**陷阱已提交**，等待审核。";
    }

    private String execVoteTrap(Long id) {
        if (id == null) return "陷阱ID不能为空。";
        communityTrapService.vote(id);
        return "投票成功！";
    }

    private String execApproveTrap(Long id) {
        if (id == null) return "陷阱ID不能为空。";
        communityTrapService.approve(id);
        return "已审核通过。";
    }

    private String execRejectTrap(Long id) {
        if (id == null) return "陷阱ID不能为空。";
        communityTrapService.reject(id);
        return "已审核拒绝。";
    }

    private String execIntegrateTrap(Long id) {
        if (id == null) return "陷阱ID不能为空。";
        try { communityTrapService.integrateIntoQuestionBank(id); return "已导入题库。"; }
        catch (Exception e) { return "导入失败: " + e.getMessage(); }
    }

    // ==================== 学习报告 ====================

    @SuppressWarnings("unchecked")
    private String execGetLearningReport(Map<String, Object> params, String userId) {
        if (userId == null || userId.isBlank() || "anonymous".equals(userId)) return "请先登录。";
        String type = params != null ? (String) params.get("type") : "WEEKLY";
        if (type == null) type = "WEEKLY";
        LearningReportDTO report = learningReportService.generateReport(userId, type);
        StringBuilder sb = new StringBuilder();
        sb.append("**").append("WEEKLY".equals(type) ? "周报" : "月报").append("**\n");
        sb.append("周期：").append(report.getPeriodStart()).append(" ~ ").append(report.getPeriodEnd()).append("\n\n");
        sb.append("- 答题：").append(report.getTotalQuestionsAnswered()).append(" 正确：").append(report.getTotalCorrect())
            .append(" 正确率：").append(String.format("%.1f", report.getAccuracy())).append("%\n");
        sb.append("- 学习：").append(report.getTotalStudyMinutes()).append("分钟/").append(report.getStudyDays()).append("天\n");
        if (report.getWeakAreasList() != null && !report.getWeakAreasList().isEmpty()) {
            sb.append("\n**薄弱**\n");
            for (var w : report.getWeakAreasList())
                sb.append("- ").append(w.get("category")).append("：未掌握").append(w.get("notMastered")).append("/").append(w.get("total")).append("\n");
        }
        if (report.getRecommendations() != null && !report.getRecommendations().isEmpty()) {
            sb.append("\n**建议**\n");
            for (String r : report.getRecommendations()) sb.append("- ").append(r).append("\n");
        }
        return sb.toString();
    }

    private String execGetLearningStats(String userId) {
        if (userId == null || userId.isBlank() || "anonymous".equals(userId)) return "请先登录。";
        User p = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUserId, userId).last("LIMIT 1"));
        if (p == null) return "暂无学习数据。";
        int t = p.getTotalQuestionsAnswered() != null ? p.getTotalQuestionsAnswered() : 0;
        int c = p.getTotalCorrect() != null ? p.getTotalCorrect() : 0;
        int s = p.getStudyStreak() != null ? p.getStudyStreak() : 0;
        double a = t > 0 ? (c * 100.0 / t) : 0;
        return "**学习统计**\n- 等级：" + (p.getSkillLevel() != null ? p.getSkillLevel() : "未评估")
            + "\n- 答题：" + t + "，正确率：" + String.format("%.1f", a) + "%"
            + "\n- 连续学习：" + s + "天"
            + (p.getWeakAreas() != null && !p.getWeakAreas().isBlank() ? "\n- 薄弱：" + p.getWeakAreas() : "");
    }

    // ==================== 讨论 ====================

    private String execGetDiscussions(Long questionId) {
        if (questionId == null) return "题目ID不能为空。";
        List<DiscussionDto> list = discussionService.getByQuestionId(questionId);
        if (list.isEmpty()) return "暂无讨论。";
        StringBuilder sb = new StringBuilder("**讨论**（共" + list.size() + "条）：\n");
        for (DiscussionDto d : list)
            sb.append("- ").append(d.getUserId()).append("：").append(d.getContent()).append("\n");
        return sb.toString();
    }

    private String execAddDiscussion(Map<String, Object> params, String userId) {
        if (params == null) return "缺少参数。";
        Long qid = toLong(params.get("questionId"));
        String content = (String) params.get("content");
        if (qid == null) return "questionId不能为空。";
        if (content == null || content.isBlank()) return "评论内容不能为空。";
        if (userId == null || userId.isBlank() || "anonymous".equals(userId)) return "请先登录。";
        discussionService.addComment(qid, null, userId, content);
        return "评论已添加。";
    }

    // ==================== 导师自评 ====================

    private String execTutorAssess(Map<String, Object> params) {
        if (params == null) return "缺少参数。";
        Long qid = toLong(params.get("questionId"));
        boolean correct = Boolean.TRUE.equals(params.get("correct"));
        String uid = (String) params.getOrDefault("userId", "anonymous");
        if (qid == null) return "questionId不能为空。";
        TutorReviewResponse resp = tutorService.assess(qid, uid, correct);
        StringBuilder sb = new StringBuilder("**导师反馈**\n" + resp.getFeedback() + "\n");
        if (resp.getTips() != null && !resp.getTips().isEmpty())
            sb.append("\n**拓展**\n").append(resp.getTips().stream().map(t -> "- " + t).collect(Collectors.joining("\n"))).append("\n");
        if (resp.getExamples() != null && !resp.getExamples().isEmpty())
            sb.append("\n**引导**\n").append(resp.getExamples().stream().map(e -> "- " + e).collect(Collectors.joining("\n"))).append("\n");
        return sb.toString();
    }

    // ==================== 用户画像 ====================

    private String execGetUserProfile(String userId) {
        if (userId == null || userId.isBlank() || "anonymous".equals(userId)) return "请先登录。";
        StringBuilder sb = new StringBuilder("**学习画像**\n");
        User p = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUserId, userId).last("LIMIT 1"));
        if (p != null) {
            int t = p.getTotalQuestionsAnswered() != null ? p.getTotalQuestionsAnswered() : 0;
            int c = p.getTotalCorrect() != null ? p.getTotalCorrect() : 0;
            int s = p.getStudyStreak() != null ? p.getStudyStreak() : 0;
            double a = t > 0 ? (c * 100.0 / t) : 0;
            sb.append("- 等级：").append(p.getSkillLevel() != null ? p.getSkillLevel() : "未评估").append("\n");
            sb.append("- 答题：").append(t).append(" 正确率：").append(String.format("%.1f", a)).append("%\n");
            sb.append("- 连续学习：").append(s).append("天\n");
            if (p.getWeakAreas() != null && !p.getWeakAreas().isBlank()) sb.append("- 薄弱：").append(p.getWeakAreas()).append("\n");
        }
        List<MistakeRecord> m = mistakeRecordMapper.selectList(
            new LambdaQueryWrapper<MistakeRecord>().eq(MistakeRecord::getUserId, userId)
                .orderByDesc(MistakeRecord::getGmtCreate).last("LIMIT 5"));
        if (!m.isEmpty()) { sb.append("\n**近期错误**\n"); m.forEach(e -> sb.append("- ").append(e.getKeyword()).append("：").append(e.getDescription()).append("\n")); }
        try { List<QuestionDto> due = questionBankService.getReviewList(userId); if (due != null && !due.isEmpty()) sb.append("\n**待复习**：").append(due.size()).append("道\n"); } catch (Exception ignored) {}
        try { List<String> mastered = knowledgeTreeService.getMasteredNodeIds(userId); if (!mastered.isEmpty()) sb.append("\n**已掌握**：").append(String.join("、", mastered)).append("\n"); } catch (Exception ignored) {}
        return sb.toString();
    }

    // ==================== 场景 ====================

    private String execListScenarios() {
        List<ScenarioDto> list = scenarioService.listAll();
        if (list.isEmpty()) return "暂无场景。";
        StringBuilder sb = new StringBuilder("**陷阱场景**（共" + list.size() + "条）：\n");
        for (int i = 0; i < Math.min(list.size(), 20); i++) {
            ScenarioDto s = list.get(i);
            sb.append((i + 1) + ". **" + s.getKnowledgePoint() + "**");
            if (s.getDifficulty() != null) sb.append(" " + "★".repeat(s.getDifficulty()));
            if (s.getCategory() != null) sb.append(" [" + s.getCategory() + "]");
            sb.append("\n");
        }
        return sb.toString();
    }

    private String execGenerateScenarioFromKnowledge(Map<String, Object> params) {
        if (params == null) return "缺少参数。";
        String kp = (String) params.get("knowledgePoint");
        String cat = (String) params.get("category");
        if (kp == null || kp.isBlank()) return "知识点不能为空。";
        ScenarioDto dto = scenarioService.generateFromKnowledge(kp, cat);
        return "**已生成场景：" + dto.getKnowledgePoint() + "**\n```java\n" + dto.getTrapCode() + "\n```\n";
    }

    // ==================== 题库拓展 ====================

    private String execGetQuestionsByCategory(Map<String, Object> params) {
        String cat = params != null ? (String) params.get("category") : null;
        if (cat == null || cat.isBlank()) return "分类名不能为空。";
        List<QuestionBank> list = questionBankMapper.selectList(
            new LambdaQueryWrapper<QuestionBank>().eq(QuestionBank::getCategory, cat).eq(QuestionBank::getStatus, "ACTIVE"));
        if (list.isEmpty()) return "分类「" + cat + "」下暂无题目。";
        StringBuilder sb = new StringBuilder("**分类【" + cat + "】**（共" + list.size() + "道）：\n");
        for (int i = 0; i < Math.min(list.size(), 20); i++) {
            QuestionBank q = list.get(i);
            sb.append((i + 1) + ". " + q.getTitle());
            if (q.getDifficulty() != null) sb.append(" " + "★".repeat(q.getDifficulty()));
            sb.append("  ID:" + q.getId() + "\n");
        }
        return sb.toString();
    }

    // ==================== 报告叙述 ====================

    private String execGetReportNarrative(Map<String, Object> params, String userId) {
        if (userId == null || userId.isBlank() || "anonymous".equals(userId)) return "请先登录。";
        String type = params != null ? (String) params.get("type") : "WEEKLY";
        if (type == null) type = "WEEKLY";
        return reportNarrativeService.generateNarrative(userId, type);
    }

    // ==================== 旧版助手动作迁移（题库/知识树/卡片） ====================

    private String execSearchQuestions(String keyword, String userId) {
        var results = questionBankService.searchQuestions(keyword);
        if (results.isEmpty()) return "未找到相关题目。";
        StringBuilder sb = new StringBuilder("**搜索结果**（共" + results.size() + "条）：\n");
        for (int i = 0; i < Math.min(results.size(), 15); i++) {
            var q = results.get(i);
            sb.append((i + 1) + ". ID:" + q.getId() + " **" + q.getTitle() + "**");
            if (q.getDifficulty() != null && q.getDifficulty() > 0) sb.append(" " + "★".repeat(q.getDifficulty()));
            if (q.getCategory() != null) sb.append(" [" + q.getCategory() + "]");
            sb.append("\n");
        }
        return sb.toString();
    }

    private String execGetQuestion(Long id, String userId) {
        if (id == null) return "题目ID不能为空。";
        var q = questionBankService.getById(id, userId);
        if (q == null) return "题目不存在（ID=" + id + "）。";
        StringBuilder sb = new StringBuilder();
        sb.append("**题目：" + q.getTitle() + "**\n");
        if (q.getDescription() != null) sb.append("> " + q.getDescription() + "\n\n");
        if (q.getTrapCode() != null) sb.append("```java\n" + q.getTrapCode() + "\n```\n");
        if (q.getExpectedPitfall() != null) sb.append("**陷阱：**" + q.getExpectedPitfall() + "\n");
        if (q.getCorrectExplanation() != null) sb.append("**解释：**" + q.getCorrectExplanation() + "\n");
        if (q.getHints() != null && !"[]".equals(q.getHints())) sb.append("**提示：**" + q.getHints() + "\n");
        return sb.toString();
    }

    private String execGenerateQuestion(String topic) {
        if (topic == null || topic.isBlank()) return "请提供知识点或主题。";
        var q = questionBankService.generateFromQuestion(topic);
        return "**已生成题目：【" + q.getTitle() + "】**\n" + q.getDescription() + "\n```java\n" + q.getTrapCode() + "\n```\n告诉我是否要载入编辑器练习。";
    }

    @SuppressWarnings("unchecked")
    private String execCreateQuestion(Map<String, Object> payload) {
        if (payload == null) return "缺少题目数据。";
        String title = (String) payload.get("title");
        String trapCode = (String) payload.get("trapCode");
        String pitfall = (String) payload.get("expectedPitfall");
        String explanation = (String) payload.get("correctExplanation");
        if (title == null || title.isBlank()) return "题目标题不能为空。";
        var q = questionBankService.importQuestion(title, null, trapCode, pitfall, explanation, null);
        return "**题目已创建**（ID=" + q.getId() + "）：" + q.getTitle();
    }

    private String execDeleteQuestion(Long id) {
        if (id == null) return "题目ID不能为空。";
        questionBankService.deleteQuestion(id);
        return "已删除题目（ID=" + id + "）。";
    }

    @SuppressWarnings("unchecked")
    private String execToggleQuestionMastered(Map<String, Object> payload, String userId) {
        if (payload == null || userId == null) return "缺少参数。";
        Long questionId = toLong(payload.get("questionId"));
        boolean mastered = Boolean.TRUE.equals(payload.get("mastered"));
        if (questionId == null) return "questionId不能为空。";
        questionBankService.setMastered(questionId, userId, mastered);
        return mastered ? "已标记为掌握。" : "已取消掌握标记。";
    }

    private String execGetReviewList(String userId) {
        if (userId == null) return "请先登录。";
        var list = questionBankService.getReviewList(userId);
        if (list.isEmpty()) return "暂无待复习题目。";
        StringBuilder sb = new StringBuilder("**待复习题目**（共" + list.size() + "道）：\n");
        for (var q : list) {
            sb.append("- ID:" + q.getId() + " " + q.getTitle());
            if (q.getNextReviewTime() != null) sb.append("（逾期）");
            sb.append("\n");
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String execSubmitReview(Map<String, Object> payload, String userId) {
        if (payload == null || userId == null) return "缺少参数。";
        Long questionId = toLong(payload.get("questionId"));
        boolean stillMastered = Boolean.TRUE.equals(payload.get("stillMastered"));
        if (questionId == null) return "questionId不能为空。";
        questionBankService.updateReview(questionId, userId, stillMastered);
        return stillMastered ? "复习完成，已安排下次复习时间。" : "已取消掌握，将加入复习队列。";
    }

    private String execGetKnowledgeTree() {
        var tree = knowledgeTreeService.getTree();
        StringBuilder sb = new StringBuilder("**Java后端知识树**\n");
        for (var category : tree) {
            sb.append("- ").append(category.getName()).append("\n");
            if (category.getChildren() != null) {
                for (var child : category.getChildren()) {
                    sb.append("  - ").append(child.getName());
                    if (child.isLeaf()) sb.append("（可生成题目）");
                    sb.append("  `").append(child.getId()).append("`\n");
                }
            }
        }
        return sb.toString();
    }

    private String execGenerateScenario(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) return "节点ID不能为空。";
        var scenario = knowledgeTreeService.generateForNode(nodeId);
        return "**已生成场景：" + scenario.getKnowledgePoint() + "**\n```java\n" + scenario.getTrapCode() + "\n```\n告诉我是否要载入编辑器练习。";
    }

    @SuppressWarnings("unchecked")
    private String execToggleNodeMastered(Map<String, Object> payload, String userId) {
        if (payload == null || userId == null) return "缺少参数。";
        String nodeId = (String) payload.get("nodeId");
        boolean mastered = Boolean.TRUE.equals(payload.get("mastered"));
        if (nodeId == null) return "nodeId不能为空。";
        knowledgeTreeService.toggleMastered(nodeId, userId, mastered);
        return mastered ? "已标记该节点为掌握。" : "已取消掌握标记。";
    }

    private String execUpdateUserProfile(Map<String, Object> params, String userId) {
        if (userId == null || userId.isBlank() || "anonymous".equals(userId)) return "请先登录。";
        User p = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUserId, userId).last("LIMIT 1"));
        if (p == null) return "暂无学习数据，请先做题。";
        boolean updated = false;
        if (params.containsKey("weakAreas")) {
            p.setWeakAreas((String) params.get("weakAreas"));
            updated = true;
        }
        if (params.containsKey("skillLevel")) {
            p.setSkillLevel((String) params.get("skillLevel"));
            updated = true;
        }
        if (params.containsKey("learningGoal")) {
            p.setLearningGoal((String) params.get("learningGoal"));
            updated = true;
        }
        if (updated) {
            p.setGmtModified(java.time.LocalDateTime.now());
            userMapper.updateById(p);
            return "✅ 已更新学习画像。";
        }
        return "没有需要更新的内容。";
    }

    private String execListCards() {
        var cards = knowledgeCardService.listAll();
        if (cards.isEmpty()) return "暂无知识卡片。";
        StringBuilder sb = new StringBuilder("**知识卡片列表**（共" + cards.size() + "张）：\n");
        for (int i = 0; i < Math.min(cards.size(), 20); i++) {
            var c = cards.get(i);
            sb.append((i + 1) + ". " + (c.getTitle() != null ? c.getTitle() : "未命名"));
            if (c.getQuestionTitle() != null) sb.append("（题目：" + c.getQuestionTitle() + "）");
            sb.append("\n");
        }
        return sb.toString();
    }

    private String execGenerateCard(Long questionId) {
        if (questionId == null) return "题目ID不能为空。";
        var card = knowledgeCardService.generateCard(questionId);
        StringBuilder sb = new StringBuilder();
        sb.append("**已生成知识卡片：【" + (card.getTitle() != null ? card.getTitle() : "未命名") + "】**\n");
        if (card.getKeyPoints() != null) sb.append("**要点：**" + card.getKeyPoints() + "\n");
        if (card.getDetailExplanation() != null) sb.append("**详解：**" + card.getDetailExplanation() + "\n");
        if (card.getCodeSnippet() != null) sb.append("```java\n" + card.getCodeSnippet() + "\n```\n");
        if (card.getCommonPitfalls() != null) sb.append("**常见误区：**" + card.getCommonPitfalls() + "\n");
        return sb.toString();
    }

    private String execDeleteCard(Long id) {
        if (id == null) return "卡片ID不能为空。";
        knowledgeCardService.deleteCard(id);
        return "已删除卡片（ID=" + id + "）。";
    }

    // ==================== 辅助 ====================

    /** 从 params map 中提取 id 字段 */
    private Long extractId(Map<String, Object> params) {
        if (params == null) return null;
        Long id = toLong(params.get("id"));
        if (id == null) id = toLong(params.get("trapId"));
        if (id == null) id = toLong(params.get("questionId"));
        return id;
    }

    private Long toLong(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number n) return n.longValue();
        try { return Long.parseLong(obj.toString()); } catch (NumberFormatException e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapPayload(Object payload) {
        if (payload instanceof Map) return (Map<String, Object>) payload;
        return null;
    }

    // ==================== 工具定义（Function Calling） ====================

    /** 生成所有工具的 Function Calling 定义 */
    public java.util.List<com.cortex.llm.ToolDefinition> getToolDefinitions() {
        java.util.List<com.cortex.llm.ToolDefinition> tools = new java.util.ArrayList<>();

        tools.add(tool("searchQuestions", "搜索题库，按关键词查找题目", map(
            "type", "object", "properties", map(
                "keyword", map("type", "string", "description", "搜索关键词")
            ), "required", list("keyword")
        )));
        tools.add(tool("getQuestion", "查看题目详情", map(
            "type", "object", "properties", map(
                "questionId", map("type", "number", "description", "题目ID")
            ), "required", list("questionId")
        )));
        tools.add(tool("getReviewList", "查看待复习题目列表，无需参数", map(
            "type", "object", "properties", map(), "required", list()
        )));
        tools.add(tool("getKnowledgeTree", "获取知识树结构，查看所有知识点", map(
            "type", "object", "properties", map(), "required", list()
        )));
        tools.add(tool("generateScenario", "从知识树节点生成陷阱代码场景", map(
            "type", "object", "properties", map(
                "nodeId", map("type", "string", "description", "知识树节点ID")
            ), "required", list("nodeId")
        )));
        tools.add(tool("loadToEditor", "将代码文本加载到编辑器（黑板）", map(
            "type", "object", "properties", map(
                "code", map("type", "string", "description", "要加载的代码文本")
            ), "required", list("code")
        )));
        tools.add(tool("loadQuestion", "从题库加载题目到编辑器黑板", map(
            "type", "object", "properties", map(
                "questionId", map("type", "number", "description", "题目ID")
            ), "required", list("questionId")
        )));
        tools.add(tool("runCode", "运行编辑器中的代码", map(
            "type", "object", "properties", map(), "required", list()
        )));
        tools.add(tool("resetCode", "重置编辑器代码为原始代码", map(
            "type", "object", "properties", map(), "required", list()
        )));
        tools.add(tool("switchTab", "切换页面标签", map(
            "type", "object", "properties", map(
                "tab", map("type", "string", "enum", list("practice", "questions", "cards", "community", "progress"),
                    "description", "目标标签页")
            ), "required", list("tab")
        )));
        tools.add(tool("searchTraps", "搜索社区陷阱", map(
            "type", "object", "properties", map(
                "javaVersion", map("type", "string", "description", "Java版本，如 Java 17"),
                "category", map("type", "string", "description", "分类，如 Java核心"),
                "status", map("type", "string", "description", "状态，如 APPROVED")
            ), "required", list()
        )));
        tools.add(tool("getTrap", "查看社区陷阱详情", map(
            "type", "object", "properties", map(
                "trapId", map("type", "number", "description", "陷阱ID")
            ), "required", list("trapId")
        )));
        tools.add(tool("submitTrap", "提交新的社区陷阱代码", map(
            "type", "object", "properties", map(
                "title", map("type", "string", "description", "标题"),
                "trapCode", map("type", "string", "description", "陷阱代码"),
                "expectedPitfall", map("type", "string", "description", "坑点描述"),
                "correctExplanation", map("type", "string", "description", "正确解释"),
                "knowledgePoint", map("type", "string", "description", "知识点"),
                "category", map("type", "string", "description", "分类"),
                "javaVersion", map("type", "string", "description", "Java版本")
            ), "required", list("title", "trapCode")
        )));
        tools.add(tool("voteTrap", "为社区陷阱投票", map(
            "type", "object", "properties", map(
                "trapId", map("type", "number", "description", "陷阱ID")
            ), "required", list("trapId")
        )));
        tools.add(tool("getLearningReport", "生成学习报告（周报/月报）", map(
            "type", "object", "properties", map(
                "type", map("type", "string", "enum", list("WEEKLY", "MONTHLY"), "description", "报告类型")
            ), "required", list("type")
        )));
        tools.add(tool("getLearningStats", "获取学习统计概览", map(
            "type", "object", "properties", map(), "required", list()
        )));
        tools.add(tool("getReportNarrative", "生成AI学习报告叙述分析", map(
            "type", "object", "properties", map(
                "type", map("type", "string", "enum", list("WEEKLY", "MONTHLY"), "description", "报告类型")
            ), "required", list("type")
        )));
        tools.add(tool("listCards", "列出所有知识卡片", map(
            "type", "object", "properties", map(), "required", list()
        )));
        tools.add(tool("generateCard", "为题目生成知识卡片", map(
            "type", "object", "properties", map(
                "questionId", map("type", "number", "description", "题目ID")
            ), "required", list("questionId")
        )));
        tools.add(tool("tutorAssess", "导师自评：答对给拓展，答错给引导", map(
            "type", "object", "properties", map(
                "questionId", map("type", "number", "description", "题目ID"),
                "correct", map("type", "boolean", "description", "是否答对")
            ), "required", list("questionId", "correct")
        )));
        tools.add(tool("listScenarios", "列出所有可用陷阱场景", map(
            "type", "object", "properties", map(), "required", list()
        )));
        tools.add(tool("getUserProfile", "查看用户学习画像（统计、薄弱、错误）", map(
            "type", "object", "properties", map(), "required", list()
        )));
        tools.add(tool("updateUserProfile", "更新用户学习画像：修改学习目标、薄弱领域等", map(
            "type", "object", "properties", map(
                "weakAreas", map("type", "string", "description", "薄弱领域，多个用逗号分隔"),
                "skillLevel", map("type", "string", "description", "技能等级"),
                "learningGoal", map("type", "string", "description", "学习目标，如 成为Java高级工程师")
            ), "required", list()
        )));
        tools.add(tool("getDiscussions", "查看题目讨论", map(
            "type", "object", "properties", map(
                "questionId", map("type", "number", "description", "题目ID")
            ), "required", list("questionId")
        )));
        tools.add(tool("addDiscussion", "在题目下添加评论", map(
            "type", "object", "properties", map(
                "questionId", map("type", "number", "description", "题目ID"),
                "content", map("type", "string", "description", "评论内容")
            ), "required", list("questionId", "content")
        )));
        return tools;
    }

    private static com.cortex.llm.ToolDefinition tool(String name, String desc, Object params) {
        return new com.cortex.llm.ToolDefinition("function",
            new com.cortex.llm.ToolFunction(name, desc, params));
    }

    private static java.util.Map<String, Object> map(Object... kvs) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kvs.length; i += 2) m.put((String) kvs[i], kvs[i + 1]);
        return m;
    }

    private static java.util.List<String> list(String... items) {
        return java.util.List.of(items);
    }
}
