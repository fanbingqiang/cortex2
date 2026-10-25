package com.cortex.lab.controller;

import com.cortex.dto.ApiResponse;
import com.cortex.lab.dto.*;
import com.cortex.lab.entity.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cortex.lab.mapper.LabSessionMapper;
import com.cortex.lab.mapper.UserMapper;
import com.cortex.lab.service.*;
import com.cortex.lab.service.PythonAIService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/lab")
@RequiredArgsConstructor
public class LabController {
    // 实验室主控制器：知识树、场景、代码执行、AI 对话、题库、社区陷阱

    private final ScenarioService scenarioService;
    private final SandboxService sandboxService;
    private final PythonAIService pythonAIService;
    private final LabSessionMapper sessionMapper;
    private final QuestionBankService questionBankService;
    private final KnowledgeCardService knowledgeCardService;
    private final DiscussionService discussionService;
    private final AssistantService assistantService;
    private final TutorService tutorService;
    private final KnowledgeTreeService knowledgeTreeService;
    private final SpringProjectService springProjectService;
    private final CommunityTrapService communityTrapService;
    private final LearningReportService learningReportService;
    private final ReportNarrativeService reportNarrativeService;
    private final UserMapper userMapper;
    private final RateLimiter rateLimiter;
    private final ChatContextBuilder chatContextBuilder;

    private final HttpClient aiHttpClient = HttpClient.newHttpClient();

    private final okhttp3.OkHttpClient okHttpClient = new okhttp3.OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build();

    private final java.util.concurrent.ExecutorService executor =
        java.util.concurrent.Executors.newFixedThreadPool(20);

    // 从 JWT token 中获取当前用户 ID（非查询参数，防止伪造）
    private String getCurrentUserId(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return (userId != null && !userId.isBlank()) ? userId : "anonymous";
    }

    // 向 Python AI 服务发送事件通知（异步，失败不影响主流程）
    private void notifyAiEvent(String type, String userId, java.util.Map<String, Object> data) {
        try {
            String json = com.alibaba.fastjson2.JSON.toJSONString(java.util.Map.of(
                "type", type, "user_id", userId, "data", data));
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8000/api/ai/event"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(java.time.Duration.ofSeconds(3))
                .build();
            aiHttpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.debug("通知AI服务失败（不影响主流程）: {}", e.getMessage());
        }
    }

    // ==================== 知识树 ====================

    @GetMapping("/knowledge-tree")
    public ApiResponse<List<KnowledgeNodeDTO>> getKnowledgeTree() {
        // 获取知识树
        try {
            return ApiResponse.success(knowledgeTreeService.getTree());
        } catch (Exception e) {
            log.error("获取知识树失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/knowledge-tree/generate")
    public ApiResponse<ScenarioDto> generateFromTree(@RequestBody Map<String, String> body) {
        // 根据知识点生成陷阱代码
        try {
            String nodeId = body.get("nodeId");
            if (nodeId == null || nodeId.isBlank()) {
                return ApiResponse.error("请选择知识点");
            }
            ScenarioDto dto = knowledgeTreeService.generateForNode(nodeId);
            return ApiResponse.success("代码生成成功", dto);
        } catch (Exception e) {
            log.error("知识树代码生成失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/knowledge-tree/generate/{nodeId}/stream")
    public SseEmitter generateFromTreeStream(@PathVariable String nodeId,
                                              @RequestParam(required = false) String userId) {
        SseEmitter emitter = new SseEmitter(120000L);
        try {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    emitter.send(SseEmitter.event().name("status").data("正在准备内容..."));
                    ScenarioDto dto = knowledgeTreeService.generateForNode(nodeId, userId);
                    emitter.send(SseEmitter.event().name("result").data(
                        com.alibaba.fastjson2.JSON.toJSONString(dto)));
                    emitter.complete();
                } catch (Exception e) {
                    try { emitter.send(SseEmitter.event().name("error").data(e.getMessage())); } catch (Exception ignored) {}
                    emitter.complete();
                }
            });
        } catch (Exception e) {
            try { emitter.send(SseEmitter.event().name("error").data(e.getMessage())); } catch (Exception ignored) {}
            emitter.complete();
        }
        return emitter;
    }

    @PostMapping("/knowledge-tree/master")
    public ApiResponse<Void> toggleMasterNode(@RequestBody Map<String, String> body) {
        // 切换知识节点掌握状态
        try {
            String nodeId = body.get("nodeId");
            boolean mastered = Boolean.parseBoolean(body.getOrDefault("mastered", "false"));
            String userId = body.getOrDefault("userId", "anonymous");
            knowledgeTreeService.toggleMastered(nodeId, userId, mastered);
            return ApiResponse.success(null);
        } catch (Exception e) {
            log.error("更新知识节点状态失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/knowledge-tree/project")
    public ApiResponse<ProjectInfoDTO> generateProject(@RequestBody Map<String, String> body) {
        // 根据知识点生成 Spring Boot 项目
        try {
            String nodeId = body.get("nodeId");
            if (nodeId == null || nodeId.isBlank()) {
                return ApiResponse.error("请选择知识点");
            }
            ProjectInfoDTO project = knowledgeTreeService.generateProject(nodeId);
            return ApiResponse.success("项目生成成功", project);
        } catch (Exception e) {
            log.error("生成 Spring Boot 项目失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/project/build")
    public ApiResponse<Map<String, Object>> buildProject(@RequestBody Map<String, Object> body) {
        // 构建 Maven 项目
        try {
            String projectDir = (String) body.get("projectDir");
            if (projectDir == null) {
                return ApiResponse.error("项目目录不能为空");
            }
            // Re-build from files in the project directory
            @SuppressWarnings("unchecked")
            List<Map<String, String>> fileMaps = (List<Map<String, String>>) body.get("files");
            if (fileMaps == null || fileMaps.isEmpty()) {
                return ApiResponse.error("项目文件不能为空");
            }
            List<ProjectFileDTO> files = fileMaps.stream()
                .map(m -> new ProjectFileDTO(m.get("path"), m.get("content")))
                .toList();
            var result = sandboxService.buildMavenProject(files);
            return ApiResponse.success(Map.of(
                "success", result.isSuccess(),
                "output", result.getOutput(),
                "exitCode", result.getExitCode(),
                "elapsedMs", result.getElapsedMs(),
                "projectDir", result.getProjectDir()
            ));
        } catch (Exception e) {
            log.error("构建项目失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/knowledge-tree/mastered")
    public ApiResponse<List<String>> getMasteredNodeIds(@RequestParam(defaultValue = "anonymous") String userId) {
        // 获取已掌握节点ID列表
        try {
            return ApiResponse.success(knowledgeTreeService.getMasteredNodeIds(userId));
        } catch (Exception e) {
            log.error("获取掌握节点失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    // ==================== 场景相关 (原有) ====================

    @GetMapping("/scenarios")
    public ApiResponse<List<ScenarioDto>> listScenarios() {
        // 获取场景列表
        try {
            return ApiResponse.success(scenarioService.listAll());
        } catch (Exception e) {
            log.error("获取场景列表失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/scenarios/{id}")
    public ApiResponse<ScenarioDto> getScenario(@PathVariable Long id) {
        // 获取场景详情
        try {
            ScenarioDto dto = scenarioService.getById(id);
            if (dto == null) return ApiResponse.error("场景不存在");
            return ApiResponse.success(dto);
        } catch (Exception e) {
            log.error("获取场景详情失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/scenarios/generate")
    public ApiResponse<ScenarioDto> generateScenario(@RequestBody Map<String, String> body) {
        // 根据知识点生成新场景
        try {
            String knowledgePoint = body.get("knowledgePoint");
            String category = body.get("category");
            if (knowledgePoint == null || knowledgePoint.isBlank()) {
                return ApiResponse.error("请输入知识点描述");
            }
            ScenarioDto dto = scenarioService.generateFromKnowledge(knowledgePoint, category);
            return ApiResponse.success("场景生成成功", dto);
        } catch (Exception e) {
            log.error("生成场景失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    // ==================== 代码执行 ====================

    @PostMapping("/execute")
    public ApiResponse<ExecuteResponse> execute(@RequestBody ExecuteRequest request) {
        // 执行 Java 代码
        try {
            if (request.getCode() == null || request.getCode().isBlank()) {
                return ApiResponse.error("代码不能为空");
            }
            ExecuteResponse result = sandboxService.execute(request.getCode());
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("执行代码失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    // ==================== 学习会话 & AI 对话 ====================

    @PostMapping("/session/start")
    public ApiResponse<Map<String, Object>> startSession(@RequestBody Map<String, Object> body) {
        // 开始学习会话
        try {
            Long scenarioId = Long.valueOf(body.get("scenarioId").toString());
            String userId = (String) body.getOrDefault("userId", "anonymous");

            ScenarioDto scenario = scenarioService.getById(scenarioId);
            if (scenario == null) return ApiResponse.error("场景不存在");

            LabSession session = new LabSession();
            session.setSessionId(java.util.UUID.randomUUID().toString());
            session.setScenarioId(scenarioId);
            session.setUserId(userId);
            session.setStatus("IN_PROGRESS");
            session.setAttempts(0);
            session.setChatHistory("[]");
            session.setGmtCreate(java.time.LocalDateTime.now());
            session.setGmtModified(java.time.LocalDateTime.now());
            sessionMapper.insert(session);

            return ApiResponse.success(Map.of(
                "sessionId", session.getSessionId(),
                "scenarioId", scenarioId,
                "trapCode", scenario.getTrapCode(),
                "knowledgePoint", scenario.getKnowledgePoint()
            ));
        } catch (Exception e) {
            log.error("开始会话失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/chat")
    public ApiResponse<ChatResponse> chat(@RequestBody ChatRequest request) {
        // AI 对话（场景模式）
        try {
            if (request.getSessionId() == null) {
                return ApiResponse.error("请先开始一个学习会话");
            }

            LabSession session = sessionMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LabSession>()
                    .eq(LabSession::getSessionId, request.getSessionId())
            );
            if (session == null) return ApiResponse.error("会话不存在");

            ScenarioDto scenario = scenarioService.getById(session.getScenarioId());
            if (scenario == null) return ApiResponse.error("场景不存在");

            session.setAttempts(session.getAttempts() + 1);
            session.setGmtModified(java.time.LocalDateTime.now());

            String systemPrompt = String.format("""
你是一个苏格拉底式编程导师"小 C"。引导学习者发现代码陷阱，**回复控制在 1~3 句话**，简短有力。

## 原则
1. **绝不直接说答案**，用反问或类比
2. **循序渐进**：第1轮"你发现了什么？" → 第2轮"试试改个值？" → 第3轮"回忆一下XX知识"
3. **用类比引导**，保持轻松语气

## 场景
- 知识点：%s
- 预期陷阱：%s
- 正确解释（你不能直接说）：%s

## 对话历史
%s

## 上次输出
%s

返回 JSON：
{
  "reply": "1~3句话",
  "hintLevel": "OBSERVE|HINT|STRONG_HINT|NEAR_ANSWER",
  "enlightened": false,
  "suggestions": ["建议1", "建议2"]
}

学习者准确说出原理才设 enlightened=true。
""",
                scenario.getKnowledgePoint() != null ? scenario.getKnowledgePoint() : "",
                scenario.getExpectedPitfall() != null ? scenario.getExpectedPitfall() : "",
                scenario.getCorrectExplanation() != null ? scenario.getCorrectExplanation() : "",
                session.getChatHistory() != null ? session.getChatHistory() : "[]",
                request.getLastOutput() != null ? request.getLastOutput() : "尚未运行过代码"
            );

            String userContent = "用户当前代码:\n```java\n" + (request.getCurrentCode() != null ? request.getCurrentCode() : "") + "\n```\n\n用户说: " + (request.getMessage() != null ? request.getMessage() : "我运行了代码，但还没发现什么特别的。");

            String result = pythonAIService.chatSimple(systemPrompt, userContent);
            ChatResponse response = com.alibaba.fastjson2.JSON.parseObject(result, ChatResponse.class);
            if (response == null) {
                response = new ChatResponse();
                response.setReply(result);
                response.setHintLevel("HINT");
                response.setEnlightened(false);
                response.setSuggestions(new java.util.ArrayList<>());
            }

            if (response.isEnlightened()) {
                session.setStatus("ENLIGHTENED");
            }

            java.util.List<Object> history = com.alibaba.fastjson2.JSON.parseArray(session.getChatHistory(), Object.class);
            if (history == null) history = new java.util.ArrayList<>();
            history.add(java.util.Map.of(
                "role", "user",
                "content", request.getMessage(),
                "code", request.getCurrentCode(),
                "output", request.getLastOutput()
            ));
            history.add(java.util.Map.of(
                "role", "assistant",
                "content", response.getReply()
            ));
            session.setChatHistory(com.alibaba.fastjson2.JSON.toJSONString(history));
            sessionMapper.updateById(session);

            return ApiResponse.success(response);
        } catch (Exception e) {
            log.error("AI对话失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    // ==================== 题库系统 ====================

    @GetMapping("/questions/search")
    public ApiResponse<List<QuestionDto>> searchQuestions(@RequestParam String keyword) {
        // 搜索题目
        try {
            return ApiResponse.success(questionBankService.searchQuestions(keyword));
        } catch (Exception e) {
            log.error("搜索题目失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/questions")
    public ApiResponse<List<QuestionDto>> listQuestions(@RequestParam(required = false) String userId) {
        // 获取题库列表
        try {
            return ApiResponse.success(questionBankService.listAll(userId));
        } catch (Exception e) {
            log.error("获取题库失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/questions/{id}")
    public ApiResponse<QuestionDto> getQuestion(@PathVariable Long id,
                                                 @RequestParam(required = false) String userId) {
        // 获取题目详情
        try {
            QuestionDto dto = questionBankService.getById(id, userId);
            if (dto == null) return ApiResponse.error("题目不存在");
            return ApiResponse.success(dto);
        } catch (Exception e) {
            log.error("获取题目失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/questions/generate")
    public ApiResponse<QuestionDto> generateQuestion(@RequestBody Map<String, String> body) {
        // AI 生成题目
        try {
            String userQuestion = body.get("question");
            if (userQuestion == null || userQuestion.isBlank()) {
                return ApiResponse.error("请输入你要学习的问题");
            }
            QuestionDto dto = questionBankService.generateFromQuestion(userQuestion);
            return ApiResponse.success("题目生成成功，开始学习吧！", dto);
        } catch (Exception e) {
            log.error("生成题目失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/questions/import")
    public ApiResponse<QuestionDto> importQuestion(@RequestBody Map<String, String> body) {
        // 手动导入题目
        try {
            String title = body.get("title");
            String description = body.get("description");
            String trapCode = body.get("trapCode");
            String expectedPitfall = body.get("expectedPitfall");
            String correctExplanation = body.get("correctExplanation");
            String knowledgePath = body.get("knowledgePath");
            if (title == null || title.isBlank()) return ApiResponse.error("请输入标题");
            QuestionDto dto = questionBankService.importQuestion(title, description, trapCode, expectedPitfall, correctExplanation, knowledgePath);
            return ApiResponse.success("导入成功", dto);
        } catch (Exception e) {
            log.error("导入题目失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/questions/batch-import")
    public ApiResponse<Map<String, Object>> batchImport(@RequestBody Map<String, String> body) {
        // 批量导入题目
        try {
            String text = body.get("text");
            String category = body.getOrDefault("category", "批量导入");
            String knowledgePath = body.get("knowledgePath");
            if (text == null || text.isBlank()) return ApiResponse.error("请输入题目内容");
            int count = questionBankService.batchImport(text, category, knowledgePath);
            return ApiResponse.success(Map.of("count", count));
        } catch (Exception e) {
            log.error("批量导入失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @DeleteMapping("/questions/{id}")
    public ApiResponse<Void> deleteQuestion(@PathVariable Long id) {
        // 删除题目
        try {
            questionBankService.deleteQuestion(id);
            return ApiResponse.success(null);
        } catch (Exception e) {
            log.error("删除题目失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/questions/{id}/options")
    public ApiResponse<List<com.cortex.lab.entity.QuestionOption>> getQuestionOptions(@PathVariable Long id) {
        try {
            return ApiResponse.success(questionBankService.getOptions(id));
        } catch (Exception e) {
            log.error("获取选项失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/questions/{id}/guidance")
    public ApiResponse<com.cortex.lab.entity.GuidanceSession> getGuidance(
            @PathVariable Long id, @RequestParam String userId) {
        try {
            return ApiResponse.success(questionBankService.getGuidance(userId, id));
        } catch (Exception e) {
            log.error("获取引导状态失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/questions/{id}/answer")
    public ApiResponse<Map<String, Object>> submitAnswer(
            @PathVariable Long id, @RequestBody Map<String, String> body) {
        try {
            String userId = body.get("userId");
            String optionLabel = body.get("optionLabel");
            String guidance = questionBankService.submitAnswer(userId, id, optionLabel);
            com.cortex.lab.entity.GuidanceSession session = questionBankService.getGuidance(userId, id);
            learningReportService.recordQuestionAnswered(userId, Boolean.TRUE.equals(session.getResolved()));

            // 通知 AI 服务
            var aiData = new java.util.LinkedHashMap<String, Object>();
            aiData.put("questionId", id);
            aiData.put("correct", Boolean.TRUE.equals(session.getResolved()));
            notifyAiEvent("question_answered", userId, aiData);

            return ApiResponse.success(Map.of(
                "guidance", guidance,
                "stage", session.getStage(),
                "resolved", session.getResolved()
            ));
        } catch (Exception e) {
            log.error("提交答案失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/questions/{id}/approve")
    public ApiResponse<Void> approveQuestion(@PathVariable Long id) {
        // 审核通过题目
        try {
            questionBankService.approveQuestion(id);
            return ApiResponse.success("审核通过", null);
        } catch (Exception e) {
            log.error("审核题目失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/questions/{id}/reject")
    public ApiResponse<Void> rejectQuestion(@PathVariable Long id) {
        // 审核拒绝题目
        try {
            questionBankService.rejectQuestion(id);
            return ApiResponse.success("已拒绝", null);
        } catch (Exception e) {
            log.error("拒绝题目失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    // ==================== 进度管理 ====================

    @PostMapping("/questions/{id}/mastered")
    public ApiResponse<Void> setMastered(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        // 标记题目掌握状态
        try {
            String userId = (String) body.getOrDefault("userId", "anonymous");
            boolean mastered = Boolean.TRUE.equals(body.get("mastered"));
            questionBankService.setMastered(id, userId, mastered);
            return ApiResponse.success(null);
        } catch (Exception e) {
            log.error("更新状态失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/questions/review/due")
    public ApiResponse<List<QuestionDto>> getDueReviews(@RequestParam String userId) {
        // 获取待复习题目
        try {
            return ApiResponse.success(questionBankService.getReviewList(userId));
        } catch (Exception e) {
            log.error("获取复习列表失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/questions/review")
    public ApiResponse<Void> updateReview(@RequestBody Map<String, Object> body) {
        // 更新复习状态
        try {
            Long questionId = Long.valueOf(body.get("questionId").toString());
            String userId = (String) body.getOrDefault("userId", "anonymous");
            boolean stillMastered = Boolean.TRUE.equals(body.get("stillMastered"));
            questionBankService.updateReview(questionId, userId, stillMastered);
            return ApiResponse.success(null);
        } catch (Exception e) {
            log.error("更新复习状态失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    // ==================== 知识卡片 ====================

    @GetMapping("/cards")
    public ApiResponse<List<CardDto>> listCards() {
        // 获取知识卡片列表
        try {
            return ApiResponse.success(knowledgeCardService.listAll());
        } catch (Exception e) {
            log.error("获取知识卡片列表失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/cards")
    public ApiResponse<CardDto> createCard(@RequestBody CardDto dto) {
        // 用户自建知识卡片（题目 + 简易答案）
        try {
            return ApiResponse.success("知识卡片创建成功！", knowledgeCardService.createCard(dto));
        } catch (Exception e) {
            log.error("创建知识卡片失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/cards/generate")
    public ApiResponse<CardDto> generateCardFromQuestion(@RequestBody Map<String, String> body) {
        // AI 简易回答生成知识卡片（用户提问）
        try {
            String question = body.getOrDefault("question", "");
            return ApiResponse.success("AI 知识卡片生成成功！", knowledgeCardService.generateCardFromQuestion(question));
        } catch (Exception e) {
            log.error("AI 生成知识卡片失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/questions/{id}/card")
    public ApiResponse<CardDto> getCard(@PathVariable Long id) {
        // 获取题目的知识卡片
        try {
            CardDto card = knowledgeCardService.getByQuestionId(id);
            if (card == null) return ApiResponse.error("知识卡片尚未生成");
            return ApiResponse.success(card);
        } catch (Exception e) {
            log.error("获取知识卡片失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @DeleteMapping("/cards/{id}")
    public ApiResponse<Void> deleteCard(@PathVariable Long id) {
        // 删除知识卡片
        try {
            knowledgeCardService.deleteCard(id);
            return ApiResponse.success(null);
        } catch (Exception e) {
            log.error("删除知识卡片失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @PutMapping("/cards/{id}")
    public ApiResponse<CardDto> updateCard(@PathVariable Long id, @RequestBody CardDto dto) {
        // 更新知识卡片
        try {
            CardDto card = knowledgeCardService.updateCard(id, dto);
            return ApiResponse.success("知识卡片更新成功！", card);
        } catch (Exception e) {
            log.error("更新知识卡片失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/questions/{id}/card/generate")
    public ApiResponse<CardDto> generateCard(@PathVariable Long id) {
        // AI 生成知识卡片
        try {
            CardDto card = knowledgeCardService.generateCard(id);
            return ApiResponse.success("知识卡片生成成功！", card);
        } catch (Exception e) {
            log.error("生成知识卡片失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    // ==================== 讨论区 ====================

    @GetMapping("/questions/{id}/discussions")
    public ApiResponse<List<DiscussionDto>> getDiscussions(@PathVariable Long id) {
        // 获取题目讨论列表
        try {
            return ApiResponse.success(discussionService.getByQuestionId(id));
        } catch (Exception e) {
            log.error("获取讨论失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/questions/{id}/discussions")
    public ApiResponse<DiscussionDto> addDiscussion(@PathVariable Long id, @RequestBody Map<String, String> body) {
        // 添加评论
        try {
            String content = body.get("content");
            String userId = body.getOrDefault("userId", "anonymous");
            Long parentId = body.containsKey("parentId") && body.get("parentId") != null
                ? Long.valueOf(body.get("parentId")) : null;
            if (content == null || content.isBlank()) return ApiResponse.error("请输入评论内容");
            DiscussionDto dto = discussionService.addComment(id, parentId, userId, content);
            return ApiResponse.success("评论成功", dto);
        } catch (Exception e) {
            log.error("添加讨论失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @DeleteMapping("/discussions/{id}")
    public ApiResponse<Void> deleteDiscussion(@PathVariable Long id) {
        // 删除评论
        try {
            discussionService.deleteComment(id);
            return ApiResponse.success(null);
        } catch (Exception e) {
            log.error("删除讨论失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    // ==================== 全局AI助手 ====================

    @GetMapping("/assistant/welcome")
    public ApiResponse<GlobalChatResponse> assistantWelcome(HttpServletRequest req) {
        try {
            GlobalChatResponse resp = new GlobalChatResponse();
            resp.setReply("你好！我是小C助手，可以帮你学习 Java 编程。\n\n选择一个知识点开始学习吧！");
            resp.setSuggestions(List.of("开始学习", "出一道题", "解释概念"));
            return ApiResponse.success(resp);
        } catch (Exception e) {
            log.error("获取欢迎信息失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/assistant/conversations")
    public ApiResponse<List<AssistantConversation>> listConversations(HttpServletRequest req) {
        // 从 JWT token 获取用户 ID，防止伪造
        try {
            String userId = getCurrentUserId(req);
            return ApiResponse.success(assistantService.listConversations(userId));
        } catch (Exception e) {
            log.error("获取对话列表失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/assistant/conversations")
    public ApiResponse<Map<String, String>> createConversation(@RequestBody Map<String, String> body, HttpServletRequest req) {
        try {
            String userId = getCurrentUserId(req);
            String conversationId = body.get("conversationId");
            String firstMessage = body.getOrDefault("firstMessage", "新对话");
            assistantService.createConversation(conversationId, userId, firstMessage);
            return ApiResponse.success(Map.of("conversationId", conversationId));
        } catch (Exception e) {
            log.error("创建对话失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/assistant/conversations/{conversationId}/messages")
    public ApiResponse<Void> saveMessage(@PathVariable String conversationId, @RequestBody Map<String, String> body, HttpServletRequest req) {
        try {
            String userId = getCurrentUserId(req);
            List<AssistantConversation> convs = assistantService.listConversations(userId);
            boolean owned = convs.stream().anyMatch(c -> c.getConversationId().equals(conversationId));
            if (!owned) return ApiResponse.error("无权操作此对话");
            assistantService.saveMessage(conversationId, body.get("role"), body.get("content"), body.get("metadata"));
            return ApiResponse.success(null);
        } catch (Exception e) {
            log.error("保存消息失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/assistant/conversations/{conversationId}/messages")
    public ApiResponse<List<AssistantMessage>> getConversationMessages(@PathVariable String conversationId, HttpServletRequest req) {
        // 校验对话所有权
        try {
            String userId = getCurrentUserId(req);
            List<AssistantConversation> convs = assistantService.listConversations(userId);
            boolean owned = convs.stream().anyMatch(c -> c.getConversationId().equals(conversationId));
            if (!owned) return ApiResponse.error("无权访问此对话");
            return ApiResponse.success(assistantService.getMessages(conversationId));
        } catch (Exception e) {
            log.error("获取消息失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @DeleteMapping("/assistant/conversations/{conversationId}")
    public ApiResponse<Void> deleteConversation(@PathVariable String conversationId, HttpServletRequest req) {
        // 校验对话所有权
        try {
            String userId = getCurrentUserId(req);
            List<AssistantConversation> convs = assistantService.listConversations(userId);
            boolean owned = convs.stream().anyMatch(c -> c.getConversationId().equals(conversationId));
            if (!owned) return ApiResponse.error("无权删除此对话");
            assistantService.deleteConversation(conversationId);
            return ApiResponse.success(null);
        } catch (Exception e) {
            log.error("删除对话失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    // ==================== 导师自评 ====================

    @PostMapping("/tutor/assess")
    public ApiResponse<TutorReviewResponse> tutorAssess(@RequestBody TutorReviewRequest request) {
        // 用户自评：答对/答错，AI返回点评+拓展知识点
        try {
            if (request.getQuestionId() == null) {
                return ApiResponse.error("题目ID不能为空");
            }
            TutorReviewResponse resp = tutorService.assess(
                request.getQuestionId(),
                request.getUserId(),
                request.isCorrect()
            );
            learningReportService.recordQuestionAnswered(request.getUserId(), request.isCorrect());

            // 通知 AI 服务
            var aiData = new java.util.LinkedHashMap<String, Object>();
            aiData.put("questionId", request.getQuestionId());
            aiData.put("correct", request.isCorrect());
            notifyAiEvent("question_answered", request.getUserId(), aiData);

            return ApiResponse.success(resp);
        } catch (Exception e) {
            log.error("导师点评失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/tutor/explain-tip")
    public ApiResponse<TutorExplainResponse> tutorExplainTip(@RequestBody TutorExplainRequest request) {
        // 解释某个拓展知识点
        try {
            if (request.getQuestionId() == null || request.getTip() == null) {
                return ApiResponse.error("参数不完整");
            }
            TutorExplainResponse resp = tutorService.explainTip(
                request.getQuestionId(),
                request.getTip(),
                request.getUserId()
            );
            return ApiResponse.success(resp);
        } catch (Exception e) {
            log.error("解释知识点失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    // ==================== 社区陷阱众包 ====================

    @PostMapping("/community/submit")
    public ApiResponse<CommunityTrapDto> submitCommunityTrap(@RequestBody CommunityTrapSubmitRequest req) {
        // 提交社区陷阱代码
        try {
            if (req.getTitle() == null || req.getTitle().isBlank()) {
                return ApiResponse.error("请输入标题");
            }
            // 简化提交：用户只提交问题，代码可选（不强制写错误代码）
            if ((req.getTrapCode() == null || req.getTrapCode().isBlank())
                    && (req.getExpectedPitfall() == null || req.getExpectedPitfall().isBlank())) {
                return ApiResponse.error("请描述你的问题或粘贴相关代码");
            }
            CommunityTrapDto dto = communityTrapService.submit(req);
            return ApiResponse.success("提交成功，等待审核", dto);
        } catch (Exception e) {
            log.error("提交社区陷阱失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/community/traps")
    public ApiResponse<List<CommunityTrapDto>> listCommunityTraps(
            @RequestParam(required = false, defaultValue = "all") String javaVersion,
            @RequestParam(required = false, defaultValue = "all") String category,
            @RequestParam(required = false, defaultValue = "all") String status) {
        // 获取社区陷阱列表
        try {
            return ApiResponse.success(communityTrapService.listTraps(javaVersion, category, status));
        } catch (Exception e) {
            log.error("获取社区陷阱列表失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/community/traps/{id}")
    public ApiResponse<CommunityTrapDto> getCommunityTrap(@PathVariable Long id) {
        // 获取陷阱详情
        try {
            CommunityTrapDto dto = communityTrapService.getById(id);
            if (dto == null) return ApiResponse.error("陷阱不存在");
            return ApiResponse.success(dto);
        } catch (Exception e) {
            log.error("获取社区陷阱详情失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/community/traps/{id}/vote")
    public ApiResponse<Void> voteCommunityTrap(@PathVariable Long id) {
        // 投票
        try {
            communityTrapService.vote(id);
            return ApiResponse.success(null);
        } catch (Exception e) {
            log.error("投票失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/community/traps/{id}/approve")
    public ApiResponse<CommunityTrapDto> approveCommunityTrap(@PathVariable Long id) {
        // 审核通过
        try {
            return ApiResponse.success("已通过", communityTrapService.approve(id));
        } catch (Exception e) {
            log.error("审核通过失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/community/traps/{id}/reject")
    public ApiResponse<CommunityTrapDto> rejectCommunityTrap(@PathVariable Long id) {
        // 审核拒绝
        try {
            return ApiResponse.success("已拒绝", communityTrapService.reject(id));
        } catch (Exception e) {
            log.error("审核拒绝失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/community/traps/{id}/delete")
    public ApiResponse<Void> deleteCommunityTrap(@PathVariable Long id) {
        // 删除陷阱
        try {
            communityTrapService.delete(id);
            return ApiResponse.success("已删除", null);
        } catch (Exception e) {
            log.error("删除陷阱失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/community/traps/{id}/integrate")
    public ApiResponse<Void> integrateCommunityTrap(@PathVariable Long id) {
        // 导入题库
        try {
            communityTrapService.integrateIntoQuestionBank(id);
            return ApiResponse.success("已导入题库", null);
        } catch (Exception e) {
            log.error("导入题库失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    // ==================== 用户画像 ====================

    @GetMapping("/user/profile")
    public ApiResponse<UserProfileDTO> getUserProfile(HttpServletRequest req) {
        try {
            String userId = getCurrentUserId(req);
            User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUserId, userId).last("LIMIT 1"));

            UserProfileDTO dto = new UserProfileDTO();
            dto.setUserId(userId);

            if (user != null) {
                dto.setUsername(user.getUsername());
                dto.setEmail(user.getEmail());
                dto.setRole(user.getRole());
                dto.setSkillLevel(user.getSkillLevel());
                dto.setTotalQuestionsAnswered(user.getTotalQuestionsAnswered());
                dto.setTotalCorrect(user.getTotalCorrect());
                dto.setStudyStreak(user.getStudyStreak());
                dto.setLearningGoal(user.getLearningGoal());
                dto.setPreferredDirection(user.getPreferredDirection());

                int total = user.getTotalQuestionsAnswered() != null ? user.getTotalQuestionsAnswered() : 0;
                int correct = user.getTotalCorrect() != null ? user.getTotalCorrect() : 0;
                dto.setAccuracy(total > 0 ? Math.round(correct * 10000.0 / total) / 100.0 : 0.0);

                dto.setWeakPoints(parseList(user.getWeakAreas()));
                dto.setPersonalityTags(parseList(user.getPersonalityTags()));
                dto.setCustomProfile(user.getPreferences());

                // 自动从知识树掌握状态推断掌握点
                List<String> masteredIds = knowledgeTreeService.getMasteredNodeIds(userId);
                List<String> masteredNames = new java.util.ArrayList<>();
                for (KnowledgeNodeDTO node : knowledgeTreeService.getTree()) {
                    collectMasteredNames(node, masteredIds, masteredNames);
                }
                dto.setMasteredPoints(masteredNames);
            } else {
                dto.setWeakPoints(List.of());
                dto.setMasteredPoints(List.of());
                dto.setPersonalityTags(List.of());
                dto.setAccuracy(0.0);
            }

            return ApiResponse.success(dto);
        } catch (Exception e) {
            log.error("获取用户画像失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @PutMapping("/user/profile")
    public ApiResponse<Void> updateUserProfile(@RequestBody UserProfileDTO body, HttpServletRequest req) {
        try {
            String userId = getCurrentUserId(req);
            User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUserId, userId).last("LIMIT 1"));

            if (user == null) {
                user = new User();
                user.setUserId(userId);
                user.setUsername(userId);
                user.setPasswordHash("");
                user.setRole("USER");
                user.setGmtCreate(java.time.LocalDateTime.now());
                user.setGmtModified(java.time.LocalDateTime.now());
                userMapper.insert(user);
            }

            if (body.getSkillLevel() != null) user.setSkillLevel(body.getSkillLevel());
            if (body.getLearningGoal() != null) user.setLearningGoal(body.getLearningGoal());
            if (body.getPreferredDirection() != null) user.setPreferredDirection(body.getPreferredDirection());
            if (body.getWeakPoints() != null) user.setWeakAreas(String.join(",", body.getWeakPoints()));
            if (body.getPersonalityTags() != null) user.setPersonalityTags(String.join(",", body.getPersonalityTags()));
            if (body.getCustomProfile() != null) user.setPreferences(body.getCustomProfile());

            user.setGmtModified(java.time.LocalDateTime.now());
            userMapper.updateById(user);

            // 实时同步到 Python trace_store（后台静默，失败不影响主流程）
            try {
                Map<String, Object> syncBody = new java.util.HashMap<>();
                if (body.getSkillLevel() != null) {
                    String level = body.getSkillLevel().toLowerCase();
                    if (level.contains("初级") || level.contains("beginner")) syncBody.put("preferred_difficulty", "easy");
                    else if (level.contains("高级") || level.contains("advanced")) syncBody.put("preferred_difficulty", "hard");
                    else syncBody.put("preferred_difficulty", "medium");
                }
                if (body.getLearningGoal() != null) syncBody.put("learning_style", body.getLearningGoal());
                if (body.getWeakPoints() != null) syncBody.put("weak_points", body.getWeakPoints());
                if (!syncBody.isEmpty()) {
                    String reqBody = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(syncBody);
                    proxyToPython("/api/profile/" + userId, "PUT", reqBody);
                    log.info("画像已同步到Python: user={}", userId);
                }
            } catch (Exception e) {
                log.warn("画像同步Python失败(不影响主流程): {}", e.getMessage());
            }

            return ApiResponse.success(null);
        } catch (Exception e) {
            log.error("更新用户画像失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    private List<String> parseList(String str) {
        if (str == null || str.isBlank()) return List.of();
        return java.util.Arrays.asList(str.split(","));
    }

    private void collectMasteredNames(KnowledgeNodeDTO node, List<String> masteredIds, List<String> result) {
        if (masteredIds.contains(node.getId())) {
            result.add(node.getName());
        }
        if (node.getChildren() != null) {
            for (KnowledgeNodeDTO child : node.getChildren()) {
                collectMasteredNames(child, masteredIds, result);
            }
        }
    }

    // ==================== AI 聊天后自动分析画像 ====================

    @PostMapping("/user/profile/analyze-chat")
    public ApiResponse<Map<String, Object>> analyzeChatAndUpdateProfile(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        try {
            String userId = getCurrentUserId(req);
            @SuppressWarnings("unchecked")
            List<Map<String, String>> messages = (List<Map<String, String>>) body.get("messages");
            if (messages == null || messages.isEmpty()) {
                return ApiResponse.error("对话内容为空");
            }

            // 构建分析 prompt
            StringBuilder conversation = new StringBuilder();
            for (Map<String, String> m : messages) {
                String role = "user".equals(m.get("role")) ? "学员" : "AI导师";
                conversation.append(role).append("：").append(m.get("content")).append("\n");
            }

            String systemPrompt = "你是一个学习分析专家。分析以下AI教学对话，提取学员的学习情况。\n" +
                "请严格按JSON格式返回，不要有任何其他文字：\n" +
                "{\"weakPoints\":[\"学员不熟悉的知识点\"],\"masteredPoints\":[\"学员已掌握的知识点\"],\"summary\":\"一句话总结学习情况\"}\n" +
                "注意：weakPoints和masteredPoints只从以下领域选择：Redis、MySQL、Spring、微服务、消息中间件、Java集合、并发编程、JVM、设计模式";

            String analysis = pythonAIService.chatSimple(systemPrompt, conversation.toString());
            String cleaned = com.cortex.util.JsonUtils.cleanJson(analysis);
            Map<String, Object> result = com.alibaba.fastjson2.JSON.parseObject(cleaned);

            // 更新用户画像
            User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUserId, userId).last("LIMIT 1"));
            if (user == null) {
                user = new User();
                user.setUserId(userId);
                user.setUsername(userId);
                user.setPasswordHash("");
                user.setRole("USER");
                user.setGmtCreate(java.time.LocalDateTime.now());
                user.setGmtModified(java.time.LocalDateTime.now());
                userMapper.insert(user);
            }

            // 合并薄弱点
            @SuppressWarnings("unchecked")
            List<String> newWeak = (List<String>) result.getOrDefault("weakPoints", List.of());
            String existingWeak = user.getWeakAreas() != null ? user.getWeakAreas() : "";
            java.util.Set<String> weakSet = new java.util.LinkedHashSet<>();
            if (!existingWeak.isBlank()) weakSet.addAll(java.util.Arrays.asList(existingWeak.split(",")));
            weakSet.addAll(newWeak);
            user.setWeakAreas(String.join(",", weakSet));

            // 合并个性标签（掌握点也存到这里，前端会展示在掌握点区域）
            @SuppressWarnings("unchecked")
            List<String> newMastered = (List<String>) result.getOrDefault("masteredPoints", List.of());
            String existingTags = user.getPersonalityTags() != null ? user.getPersonalityTags() : "";
            java.util.Set<String> tagSet = new java.util.LinkedHashSet<>();
            if (!existingTags.isBlank()) tagSet.addAll(java.util.Arrays.asList(existingTags.split(",")));
            tagSet.addAll(newMastered);
            user.setPersonalityTags(String.join(",", tagSet));

            user.setGmtModified(java.time.LocalDateTime.now());
            userMapper.updateById(user);

            log.info("AI分析画像更新: userId={}, weakPoints={}, masteredPoints={}", userId, weakSet, tagSet);
            return ApiResponse.success(Map.of(
                "weakPoints", new java.util.ArrayList<>(weakSet),
                "masteredPoints", new java.util.ArrayList<>(tagSet),
                "summary", result.getOrDefault("summary", "")
            ));
        } catch (Exception e) {
            log.error("AI分析画像失败", e);
            return ApiResponse.error("分析失败: " + e.getMessage());
        }
    }

    // ==================== AI 聊天网关（SSE 代理） ====================

    private static final String PYTHON_AI_URL = "http://localhost:8001/api/chat/stream";
    private static final String PYTHON_BASE_URL = "http://localhost:8001";

    private final HttpClient pythonHttpClient = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(5))
        .build();

    // ─── 管理员校验 ──────────────────────────────────────
    private boolean isAdmin(HttpServletRequest request) {
        String role = (String) request.getAttribute("role");
        return "ROOT".equals(role) || "ADMIN".equals(role);
    }

    private void requireAdmin(HttpServletRequest request) {
        if (!isAdmin(request)) {
            throw new RuntimeException("无权限：仅管理员可访问");
        }
    }

    // ─── 代理 Python 请求 ────────────────────────────────
    private String proxyToPython(String path, String method, String body) throws Exception {
        java.net.URL url = new java.net.URL(PYTHON_BASE_URL + path);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(body != null);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(30000);
        if (body != null) {
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                os.flush();
            }
        }
        int status = conn.getResponseCode();
        java.io.InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String respBody = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (status >= 400) {
            throw new RuntimeException("Python " + status + ": " + respBody);
        }
        return respBody;
    }

    private void logProxy(String path, String method, String body) {
        try {
            java.io.FileWriter fw = new java.io.FileWriter("proxy_debug.log", true);
            fw.write("[" + java.time.LocalDateTime.now() + "] " + method + " " + path + " body=" + body + "\n");
            fw.close();
        } catch (Exception ignored) {}
    }

    @PostMapping("/assistant/chat/stream")
    public void chatStream(@RequestBody Map<String, String> body,
                           HttpServletRequest req,
                           HttpServletResponse response) throws IOException {
        String userId = getCurrentUserId(req);
        String message = body.getOrDefault("message", "");
        String conversationId = body.getOrDefault("conversationId", "");

        // 1. 限流检查
        if (!rateLimiter.tryAcquire(userId)) {
            response.setStatus(429);
            response.setContentType("text/event-stream;charset=UTF-8");
            response.getWriter().write("data: {\"type\":\"error\",\"content\":\"请求过于频繁，请稍后再试\"}\n\n");
            response.getWriter().flush();
            return;
        }

        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        java.io.PrintWriter writer = response.getWriter();

        try {
            // 2. 构建上下文（从 DB 加载用户画像，截断历史）
            Map<String, Object> ctx = chatContextBuilder.buildContext(userId, conversationId);
            String systemPrompt = (String) ctx.get("systemPrompt");
            @SuppressWarnings("unchecked")
            Map<String, Object> profile = (Map<String, Object>) ctx.get("profile");

            // 3. 构建请求体发给 Python
            Map<String, Object> pythonBody = new java.util.HashMap<>();
            pythonBody.put("user_id", userId);
            pythonBody.put("message", message);
            pythonBody.put("system_prompt", systemPrompt);
            pythonBody.put("profile", profile);
            String jsonBody = com.alibaba.fastjson2.JSON.toJSONString(pythonBody);
            log.info("SSE 代理: userId={}, messageLen={}, profile={}", userId, message.length(), profile);

            // 4. 代理 SSE 流：使用 OkHttp 发送请求
            okhttp3.RequestBody requestBody = okhttp3.RequestBody.create(
                jsonBody, okhttp3.MediaType.parse("application/json;charset=UTF-8"));
            okhttp3.Request okReq = new okhttp3.Request.Builder()
                .url(PYTHON_AI_URL)
                .post(requestBody)
                .build();

            try (okhttp3.Response okResp = okHttpClient.newCall(okReq).execute()) {
                if (okResp.code() != 200) {
                    String errorBody = okResp.body() != null ? okResp.body().string() : "";
                    log.error("Python AI 返回 {}: {}", okResp.code(), errorBody);
                    writer.write("data: {\"type\":\"error\",\"content\":\"AI 服务异常: " + okResp.code() + "\"}\n\n");
                    writer.flush();
                    return;
                }

                // 逐行读取 SSE 流，原始转发
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(okResp.body().byteStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        writer.write(line + "\n");
                        writer.flush();
                    }
                }
            }
        } catch (Exception e) {
            log.error("SSE 代理异常: userId={}", userId, e);
            writer.write("data: {\"type\":\"error\",\"content\":\"AI 服务连接失败: " + e.getMessage() + "\"}\n\n");
            writer.flush();
        } finally {
            rateLimiter.release();
            writer.close();
        }
    }

    /** 用户反馈不满意 → AI 分析上下文生成优化建议 → 存入变更日志 */
    @PostMapping("/assistant/feedback")
    public ApiResponse<Map<String, Object>> submitFeedback(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        try {
            String userId = getCurrentUserId(req);
            body.put("user_id", userId);
            String reqBody = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
            String json = proxyToPython("/api/chat/feedback", "POST", reqBody);
            return ApiResponse.success(com.alibaba.fastjson2.JSON.parseObject(json));
        } catch (Exception e) {
            log.error("提交反馈失败", e);
            return ApiResponse.error("反馈提交失败: " + e.getMessage());
        }
    }

    // ==================== 后台管理 API（仅管理员） ====================

    @GetMapping("/admin/prompts")
    public ApiResponse<Map<String, Object>> getAdminPrompts(HttpServletRequest req) {
        try {
            requireAdmin(req);
            String json = proxyToPython("/api/prompts", "GET", null);
            return ApiResponse.success(com.alibaba.fastjson2.JSON.parseObject(json));
        } catch (Exception e) {
            return ApiResponse.error("Python 服务连接失败: " + e.getMessage());
        }
    }

    @PutMapping("/admin/prompts")
    public ApiResponse<Map<String, Object>> updateAdminPrompts(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        try {
            requireAdmin(req);
            String reqBody = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
            String json = proxyToPython("/api/prompts", "PUT", reqBody);
            return ApiResponse.success(com.alibaba.fastjson2.JSON.parseObject(json));
        } catch (Exception e) {
            return ApiResponse.error("Python 服务连接失败: " + e.getMessage());
        }
    }

    @GetMapping("/admin/prompts/{key}/history")
    public ApiResponse<Map<String, Object>> getPromptHistory(@PathVariable String key, HttpServletRequest req) {
        try {
            requireAdmin(req);
            String json = proxyToPython("/api/prompts/" + key + "/history", "GET", null);
            return ApiResponse.success(com.alibaba.fastjson2.JSON.parseObject(json));
        } catch (Exception e) {
            return ApiResponse.error("Python 服务连接失败: " + e.getMessage());
        }
    }

    @PutMapping("/admin/prompts/{key}/rollback")
    public ApiResponse<Map<String, Object>> rollbackPrompt(@PathVariable String key, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        try {
            requireAdmin(req);
            // 使用 Jackson 序列化，避免 fastjson 兼容性问题
            String reqBody = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
            logProxy("/api/prompts/" + key + "/rollback", "PUT", reqBody);
            String json = proxyToPython("/api/prompts/" + key + "/rollback", "PUT", reqBody);
            return ApiResponse.success(com.alibaba.fastjson2.JSON.parseObject(json));
        } catch (Exception e) {
            return ApiResponse.error("Python 服务连接失败: " + e.getMessage());
        }
    }

    @GetMapping("/admin/prompt-changes")
    public ApiResponse<Map<String, Object>> getPromptChanges(@RequestParam(defaultValue = "200") int limit, HttpServletRequest req) {
        try {
            requireAdmin(req);
            String json = proxyToPython("/api/admin/prompt-changes?limit=" + limit, "GET", null);
            return ApiResponse.success(com.alibaba.fastjson2.JSON.parseObject(json));
        } catch (Exception e) {
            return ApiResponse.error("Python 服务连接失败: " + e.getMessage());
        }
    }

    /** AI 总结变更记录并生成优化提示词 */
    @PostMapping("/admin/prompt-changes/summarize")
    public ApiResponse<Map<String, Object>> summarizeChanges(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        try {
            requireAdmin(req);
            String reqBody = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
            String json = proxyToPython("/api/admin/prompt-changes/summarize", "POST", reqBody);
            return ApiResponse.success(com.alibaba.fastjson2.JSON.parseObject(json));
        } catch (Exception e) {
            return ApiResponse.error("AI 总结失败: " + e.getMessage());
        }
    }

    @PostMapping("/admin/key-pool/add")
    public ApiResponse<Map<String, Object>> addKey(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        try {
            requireAdmin(req);
            String json = proxyToPython("/api/admin/key-pool/add", "POST", com.alibaba.fastjson2.JSON.toJSONString(body));
            return ApiResponse.success(com.alibaba.fastjson2.JSON.parseObject(json));
        } catch (Exception e) {
            return ApiResponse.error("Python 服务连接失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/admin/key-pool/{keyIndex}")
    public ApiResponse<Map<String, Object>> removeKey(@PathVariable int keyIndex, HttpServletRequest req) {
        try {
            requireAdmin(req);
            String json = proxyToPython("/api/admin/key-pool/" + keyIndex, "DELETE", null);
            return ApiResponse.success(com.alibaba.fastjson2.JSON.parseObject(json));
        } catch (Exception e) {
            return ApiResponse.error("Python 服务连接失败: " + e.getMessage());
        }
    }

    @GetMapping("/admin/sessions")
    public ApiResponse<Map<String, Object>> getAdminSessions(HttpServletRequest req) {
        try {
            requireAdmin(req);
            String json = proxyToPython("/api/sessions", "GET", null);
            return ApiResponse.success(com.alibaba.fastjson2.JSON.parseObject(json));
        } catch (Exception e) {
            return ApiResponse.error("Python 服务连接失败: " + e.getMessage());
        }
    }

    @GetMapping("/admin/sessions/{sessionId}")
    public ApiResponse<Map<String, Object>> getAdminSession(@PathVariable String sessionId, HttpServletRequest req) {
        try {
            requireAdmin(req);
            String json = proxyToPython("/api/session/" + sessionId, "GET", null);
            return ApiResponse.success(com.alibaba.fastjson2.JSON.parseObject(json));
        } catch (Exception e) {
            return ApiResponse.error("Python 服务连接失败: " + e.getMessage());
        }
    }

    @GetMapping("/admin/key-pool")
    public ApiResponse<Map<String, Object>> getAdminKeyPool(HttpServletRequest req) {
        try {
            requireAdmin(req);
            String json = proxyToPython("/api/admin/key-pool", "GET", null);
            return ApiResponse.success(com.alibaba.fastjson2.JSON.parseObject(json));
        } catch (Exception e) {
            return ApiResponse.error("Python 服务连接失败: " + e.getMessage());
        }
    }

    @GetMapping("/admin/stats")
    public ApiResponse<Map<String, Object>> getAdminStats(HttpServletRequest req) {
        try {
            requireAdmin(req);
            // Python 统计
            String pyJson = proxyToPython("/api/admin/stats", "GET", null);
            Map<String, Object> pyStats = com.alibaba.fastjson2.JSON.parseObject(pyJson);
            Map<String, Object> data = (Map<String, Object>) pyStats.get("data");

            // Java DB 统计
            long totalUsers = userMapper.selectCount(null);
            long totalConversations = sessionMapper.selectCount(null);

            Map<String, Object> result = new java.util.LinkedHashMap<>();
            if (data != null) result.putAll(data);
            result.put("total_users", totalUsers);
            result.put("total_conversations", totalConversations);

            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.error("Python 服务连接失败: " + e.getMessage());
        }
    }

    @GetMapping("/admin/users")
    public ApiResponse<List<Map<String, Object>>> getAdminUsers(HttpServletRequest req) {
        try {
            requireAdmin(req);
            List<User> users = userMapper.selectList(null);
            // 按等级排序（ROOT > ADMIN > USER），同等级按首字母排序
            Map<String, Integer> roleOrder = Map.of("ROOT", 0, "ADMIN", 1, "USER", 2);
            users.sort((a, b) -> {
                int ra = roleOrder.getOrDefault(a.getRole(), 99);
                int rb = roleOrder.getOrDefault(b.getRole(), 99);
                if (ra != rb) return Integer.compare(ra, rb);
                String na = a.getUsername() != null ? a.getUsername() : "";
                String nb = b.getUsername() != null ? b.getUsername() : "";
                return na.compareToIgnoreCase(nb);
            });
            List<Map<String, Object>> list = new java.util.ArrayList<>();
            for (User u : users) {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("user_id", u.getUserId());
                m.put("username", u.getUsername());
                m.put("email", u.getEmail());
                m.put("role", u.getRole());
                m.put("status", u.getStatus() != null ? u.getStatus() : "ACTIVE");
                m.put("skill_level", u.getSkillLevel());
                m.put("total_questions", u.getTotalQuestionsAnswered() != null ? u.getTotalQuestionsAnswered() : 0);
                m.put("total_correct", u.getTotalCorrect() != null ? u.getTotalCorrect() : 0);
                m.put("study_streak", u.getStudyStreak() != null ? u.getStudyStreak() : 0);
                m.put("learning_goal", u.getLearningGoal());
                m.put("weak_areas", u.getWeakAreas());
                m.put("last_login", u.getLastLoginTime() != null ? u.getLastLoginTime().toString() : null);
                list.add(m);
            }
            return ApiResponse.success(list);
        } catch (Exception e) {
            return ApiResponse.error("查询用户失败: " + e.getMessage());
        }
    }

    @PutMapping("/admin/users/{userId}/ban")
    public ApiResponse<?> banUser(@PathVariable String userId, HttpServletRequest req) {
        try {
            requireAdmin(req);
            User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUserId, userId).last("LIMIT 1"));
            if (user == null) return ApiResponse.error("用户不存在");
            if ("ROOT".equals(user.getRole())) return ApiResponse.error("不能封禁 ROOT 管理员");
            user.setStatus("BANNED");
            user.setGmtModified(java.time.LocalDateTime.now());
            userMapper.updateById(user);
            return ApiResponse.success(Map.of("user_id", userId, "status", "BANNED"));
        } catch (Exception e) {
            return ApiResponse.error("封禁失败: " + e.getMessage());
        }
    }

    @PutMapping("/admin/users/{userId}/unban")
    public ApiResponse<?> unbanUser(@PathVariable String userId, HttpServletRequest req) {
        try {
            requireAdmin(req);
            User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUserId, userId).last("LIMIT 1"));
            if (user == null) return ApiResponse.error("用户不存在");
            user.setStatus("ACTIVE");
            user.setGmtModified(java.time.LocalDateTime.now());
            userMapper.updateById(user);
            return ApiResponse.success(Map.of("user_id", userId, "status", "ACTIVE"));
        } catch (Exception e) {
            return ApiResponse.error("解封失败: " + e.getMessage());
        }
    }

    // ======================== AI 学习报告 ========================

    @GetMapping("/report/ai-narrative")
    public ApiResponse<String> generateAiNarrative(@RequestParam(required = false) String userId,
                                                    @RequestParam(defaultValue = "WEEKLY") String reportType,
                                                    HttpServletRequest req) {
        try {
            String uid = (userId != null && !userId.isBlank()) ? userId : getCurrentUserId(req);
            String result = reportNarrativeService.generateNarrative(uid, reportType);
            return ApiResponse.success("报告生成成功", result);
        } catch (Exception e) {
            log.error("AI报告生成失败", e);
            return ApiResponse.error("报告生成失败: " + e.getMessage());
        }
    }
}
