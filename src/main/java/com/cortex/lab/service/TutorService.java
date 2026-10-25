package com.cortex.lab.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.cortex.lab.dto.TutorExplainResponse;
import com.cortex.lab.dto.TutorReviewResponse;
import com.cortex.lab.entity.QuestionBank;
import com.cortex.lab.entity.QuestionProgress;
import com.cortex.lab.mapper.QuestionBankMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cortex.lab.mapper.QuestionProgressMapper;
import com.cortex.lab.service.PythonAIService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TutorService {
    // 导师点评服务：答对后推送拓展知识点，答错后给引导例子

    private final PythonAIService pythonAIService;
    private final QuestionBankMapper questionBankMapper;
    private final QuestionProgressMapper progressMapper;

    private static final String ASSESS_PROMPT = """
你是一个Java八股文导师。用户正在自评一道题，根据他答对/答错给出相应反馈。

## 题目
%s

## 要求

### 如果用户答对了（correct=true）：
- feedback: 简单确认"对"，一句话
- tips: 生成2-3条拓展知识点，每条一句话（15-30字），是他可能不知道的深度内容
- examples: 不返回
- 注意：不要直接重复题目答案，要给更深层的关联知识

### 如果用户答错了（correct=false）：
- feedback: 给一个引导性提示，引导他再思考
- examples: 给1-2个相关的例子帮助理解（但不能直接说出答案）
- tips: 不返回

### 输出格式（严格JSON）：
{"feedback":"反馈文本","examples":["例1","例2"],"tips":["知识点1","知识点2","知识点3"],"hasMoreTip":true}

""";

    private static final String EXPLAIN_PROMPT = """
你是一个Java八股文导师。用户想深入了解下面这个知识点，请给出详细的解释。

## 题目
%s

## 用户想了解的知识点
%s

## 要求
- 解释要详细但简洁，200字以内
- 可以结合代码示例说明
- 不要只重复知识点本身，要说清楚原理
""";

    // 用户自评：答对/答错
    public TutorReviewResponse assess(Long questionId, String userId, boolean correct) {
        TutorReviewResponse resp = new TutorReviewResponse();

        QuestionBank question = questionBankMapper.selectById(questionId);
        if (question == null) {
            resp.setFeedback("题目不存在");
            return resp;
        }

        String prompt = ASSESS_PROMPT.formatted(formatQuestion(question));
        String userMsg = correct ? "用户：我答对了" : "用户：我没答对";

        try {
            String result = pythonAIService.chatSimple(prompt, userMsg);
            JSONObject json = JSON.parseObject(com.cortex.util.JsonUtils.cleanJson(result));
            resp.setFeedback(json.getString("feedback"));
            if (json.containsKey("examples")) {
                resp.setExamples(json.getList("examples", String.class));
            }
            if (json.containsKey("tips")) {
                resp.setTips(json.getList("tips", String.class));
            }
            resp.setHasMoreTip(json.getBooleanValue("hasMoreTip"));
        } catch (Exception e) {
            log.error("AI点评失败: {}", e.getMessage());
            if (correct) {
                resp.setFeedback("答对了！");
                resp.setTips(java.util.List.of("继续巩固这个知识点"));
            } else {
                resp.setFeedback("再想想，看看题目中的陷阱在哪里");
                resp.setExamples(java.util.List.of("仔细对比代码中的关键差异"));
            }
        }

        // 同步掌握状态：答对标记已掌握，答错标记未掌握
        try {
            QuestionProgress prog = progressMapper.selectOne(
                new LambdaQueryWrapper<QuestionProgress>()
                    .eq(QuestionProgress::getQuestionId, questionId)
                    .eq(QuestionProgress::getUserId, userId)
                    .last("LIMIT 1")
            );
            if (prog == null) {
                prog = new QuestionProgress();
                prog.setQuestionId(questionId);
                prog.setUserId(userId);
                prog.setMastered(correct);
                prog.setReviewCount(0);
                prog.setGmtCreate(LocalDateTime.now());
                prog.setGmtModified(LocalDateTime.now());
                progressMapper.insert(prog);
            } else {
                prog.setMastered(correct);
                prog.setGmtModified(LocalDateTime.now());
                progressMapper.updateById(prog);
            }
        } catch (Exception e) {
            log.warn("同步掌握状态失败: {}", e.getMessage());
        }

        return resp;
    }

    // 解释某个拓展知识点
    public TutorExplainResponse explainTip(Long questionId, String tip, String userId) {
        TutorExplainResponse resp = new TutorExplainResponse();

        QuestionBank question = questionBankMapper.selectById(questionId);
        String questionTitle = question != null ? question.getTitle() : "未知题目";

        String prompt = EXPLAIN_PROMPT.formatted(questionTitle, tip);

        try {
            String result = pythonAIService.chatSimple(prompt, "请解释这个知识点");
            resp.setExplanation(result);
        } catch (Exception e) {
            log.error("解释知识点失败: {}", e.getMessage());
            resp.setExplanation("暂时无法解释，请稍后再试");
        }

        return resp;
    }
    // 格式化题目信息（不含答案）
    private String formatQuestion(QuestionBank q) {
        StringBuilder sb = new StringBuilder();
        sb.append("标题：").append(q.getTitle()).append("\n");
        if (q.getDescription() != null && !q.getDescription().isBlank()) {
            sb.append("描述：").append(q.getDescription()).append("\n");
        }
        if (q.getTrapCode() != null && !q.getTrapCode().isBlank()) {
            sb.append("代码：\n```java\n").append(q.getTrapCode()).append("\n```\n");
        }
        if (q.getCategory() != null) {
            sb.append("分类：").append(q.getCategory()).append("\n");
        }
        return sb.toString();
    }
}