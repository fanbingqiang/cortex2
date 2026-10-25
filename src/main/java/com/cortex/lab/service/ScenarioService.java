package com.cortex.lab.service;

import com.alibaba.fastjson2.JSON;
import com.cortex.lab.dto.ScenarioDto;
import com.cortex.lab.entity.LabScenario;
import com.cortex.lab.mapper.LabScenarioMapper;
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
public class ScenarioService {

    private final LabScenarioMapper scenarioMapper;
    private final PythonAIService pythonAIService;

    public List<ScenarioDto> listAll() {
        List<LabScenario> scenarios = scenarioMapper.selectList(null);
        return scenarios.stream().map(this::toDto).collect(Collectors.toList());
    }

    public ScenarioDto getById(Long id) {
        LabScenario scenario = scenarioMapper.selectById(id);
        if (scenario == null) return null;
        return toDto(scenario);
    }

    public ScenarioDto generateFromKnowledge(String knowledgePoint, String category) {
        String prompt = """
你是一个帮助学习者理解计算机知识的 AI 导师。你的任务是针对给定的知识点，生成一段带陷阱的 Java 代码，
让学习者运行后看到意外结果，从而自己悟出原理。

知识点: %s
分类: %s

请返回 JSON 格式（不要带 ```json 标记）：
{
  "trapCode": "完整的可编译运行的 Java 代码（必须包含 main 方法），代码中暗藏一个与知识点对应的陷阱。代码需要打印输出，让陷阱效果可见。",
  "expectedPitfall": "预期的陷阱是什么（一句话描述学习者会看到什么意外结果）",
  "correctExplanation": "这个陷阱的正确解释（200字以内，解释原理）",
  "hints": ["提示1（引导性，不直接说答案）", "提示2", "提示3"],
  "difficulty": 3
}

要求：
1. 代码必须是完整可运行的 Java 类，包含 main 方法
2. 代码看起来逻辑正常，初学者看不出问题
3. 陷阱效果必须通过打印输出来体现
4. 提示要循序渐进，从引导观察→引导思考→暗示方向
5. 永远不直接说出答案
""".formatted(knowledgePoint, category != null ? category : "Java基础");

        try {
            String result = pythonAIService.chatSimple(prompt);
            ScenarioDto dto = JSON.parseObject(result, ScenarioDto.class);

            LabScenario entity = new LabScenario();
            entity.setKnowledgePoint(knowledgePoint);
            entity.setCategory(category);
            entity.setTrapCode(dto.getTrapCode());
            entity.setExpectedPitfall(dto.getExpectedPitfall());
            entity.setCorrectExplanation(dto.getCorrectExplanation());
            entity.setHints(JSON.toJSONString(dto.getHints()));
            entity.setDifficulty(dto.getDifficulty() != null ? dto.getDifficulty() : 3);
            entity.setGmtCreate(LocalDateTime.now());
            scenarioMapper.insert(entity);

            dto.setId(entity.getId());
            return dto;
        } catch (Exception e) {
            log.error("Failed to generate scenario from LLM", e);
            throw new RuntimeException("场景生成失败: " + e.getMessage());
        }
    }

    private ScenarioDto toDto(LabScenario entity) {
        ScenarioDto dto = new ScenarioDto();
        dto.setId(entity.getId());
        dto.setKnowledgePoint(entity.getKnowledgePoint());
        dto.setCategory(entity.getCategory());
        dto.setTrapCode(entity.getTrapCode());
        dto.setExpectedPitfall(entity.getExpectedPitfall());
        dto.setCorrectExplanation(entity.getCorrectExplanation());
        dto.setHints(JSON.parseArray(entity.getHints(), String.class));
        dto.setDifficulty(entity.getDifficulty());
        return dto;
    }
}
