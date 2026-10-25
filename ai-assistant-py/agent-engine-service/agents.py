"""八股文教学 AI — 3 个专用 Agent"""

import json
import logging
import re

from llm_client import LlmClient

logger = logging.getLogger(__name__)

# ============================================================
#  Agent 1: 出题 Agent — 生成代码 + 问"为什么"
# ============================================================

GEN_QUESTION_PROMPT = """你是一个八股文教学出题专家。根据当前知识点，生成一道"看代码回答问题"的题目。

## 当前知识点
模块：{knowledge_title}
难度：{difficulty}
标签：{tags}

## 用户画像
薄弱点：{weak_points}
学习风格：{learning_style}

## 系统提示词
{system_prompt}

## 出题要求
1. 生成一段代码（Java 优先），15-40 行，简洁但有教学价值
2. 代码类型：{code_type}（{code_type_desc}）
3. 针对代码提出一个"为什么"的问题，考察学生对原理的理解
4. 给出参考答案（解释清楚原理）
5. 题目要有迷惑性，不能一眼看出答案

## 输出格式（严格 JSON）
{{
  "code_type": "correct / incorrect",
  "question_code": "展示给学生看的代码",
  "question": "向学生提出的问题（必须包含'为什么'）",
  "reference_answer": "参考答案（详细解释原理）",
  "knowledge_points": ["涉及的知识点1", "知识点2"],
  "hint": "给学生的提示（可选）"
}}

只输出 JSON，不要多余文字。"""


# ============================================================
#  Agent 2: 诊断 Agent — 评估学生回答
# ============================================================

DIAGNOSIS_PROMPT = """你是一个代码诊断专家。评估学生对代码题的回答。

## 知识点
模块：{knowledge_title}
涉及知识点：{knowledge_points}

## 题目代码
```
{question_code}
```

## 题目问题
{question}

## 参考答案
{reference_answer}

## 学生的回答
{user_answer}

## 评估要求
1. 学生的回答正确吗？与参考答案的核心要点是否一致？
2. 学生是否真正理解了原理，还是只是表面回答？
3. 如果没有完全理解，具体缺失了哪些知识点？
4. 给出掌握度评分（0-100）

## 输出格式（严格 JSON）
{{
  "is_correct": true/false,
  "is_understood": true/false,
  "knowledge_gaps": ["盲区1", "盲区2"],
  "mastery_score": 75,
  "diagnosis_detail": "详细评估说明",
  "error_type": "concept/logic/syntax/runtime"
}}

只输出 JSON，不要多余文字。"""


# ============================================================
#  Agent 3: 讲解 Agent — 针对盲区进行教学
# ============================================================

TEACHING_PROMPT = """你是一个八股文教学讲师。根据评估结果，向学生讲解知识点。

## 知识点
模块：{knowledge_title}
标签：{tags}

## 题目代码
```
{question_code}
```

## 题目问题
{question}

## 参考答案
{reference_answer}

## 评估结果
学生回答是否正确：{is_correct}
是否真正理解：{is_understood}
知识盲区：{knowledge_gaps}
掌握度评分：{mastery_score}

## 学生的回答
{user_answer}

## 讲解要求
1. 解释**为什么**参考答案是这样的（原理层面）
2. 如果代码是错误的，指出错误在哪里、为什么错
3. 如果代码是正确的，解释为什么这样写是对的、关键点在哪
4. 如果学生有盲区，重点讲解盲区知识点
5. 补充**面试中可能怎么问**（八股文考点）
6. 语言简洁，用中文

## 输出格式（严格 JSON）
{{
  "content": "详细的讲解内容（Markdown 格式）",
  "interview_tips": "面试中可能的追问和考点",
  "summary": "一句话总结核心要点"
}}

只输出 JSON，不要多余文字。"""


# ============================================================
#  Agent 执行器
# ============================================================

class TeachingAgents:
    """八股文教学 Agent 集合"""

    def __init__(self, llm_client: LlmClient):
        self._llm = llm_client

    async def generate_question(self, knowledge_title: str, tags: list[str],
                                difficulty: str, weak_points: list[str],
                                learning_style: str, system_prompt: str = "") -> dict:
        """出题：生成代码 + 问为什么（随机选择正确/错误代码）"""
        import random
        # 简单难度偏向正确代码，困难难度偏向错误代码
        if difficulty == "easy":
            code_type = "correct" if random.random() < 0.7 else "incorrect"
        elif difficulty == "hard":
            code_type = "incorrect" if random.random() < 0.7 else "correct"
        else:
            code_type = "correct" if random.random() < 0.5 else "incorrect"

        if code_type == "correct":
            code_type_desc = "写一段正确的代码，让学生分析为什么这样写是对的"
        else:
            code_type_desc = "写一段有错误的代码，让学生找出错误并解释为什么错"

        prompt = GEN_QUESTION_PROMPT.format(
            knowledge_title=knowledge_title,
            difficulty=difficulty,
            tags=", ".join(tags) if tags else "通用",
            weak_points=", ".join(weak_points) if weak_points else "无",
            learning_style=learning_style,
            system_prompt=system_prompt or "无特殊要求",
            code_type=code_type,
            code_type_desc=code_type_desc,
        )
        resp = await self._llm.chat_simple(prompt)
        return self._parse_json(resp)

    async def diagnose(self, knowledge_title: str, knowledge_points: list[str],
                       question_code: str, question: str, reference_answer: str,
                       user_answer: str) -> dict:
        """诊断：评估学生的回答"""
        prompt = DIAGNOSIS_PROMPT.format(
            knowledge_title=knowledge_title,
            knowledge_points=", ".join(knowledge_points) if knowledge_points else "未知",
            question_code=question_code,
            question=question,
            reference_answer=reference_answer,
            user_answer=user_answer or "学生未作答",
        )
        resp = await self._llm.chat_simple(prompt)
        return self._parse_json(resp)

    async def teach(self, knowledge_title: str, tags: list[str],
                    question_code: str, question: str, reference_answer: str,
                    user_answer: str, is_correct: bool, is_understood: bool,
                    knowledge_gaps: list[str], mastery_score: int) -> dict:
        """讲解：针对盲区教学"""
        prompt = TEACHING_PROMPT.format(
            knowledge_title=knowledge_title,
            tags=", ".join(tags) if tags else "通用",
            question_code=question_code,
            question=question,
            reference_answer=reference_answer,
            user_answer=user_answer,
            is_correct=str(is_correct),
            is_understood=str(is_understood),
            knowledge_gaps=", ".join(knowledge_gaps) if knowledge_gaps else "无",
            mastery_score=str(mastery_score),
        )
        resp = await self._llm.chat_simple(prompt)
        return self._parse_json(resp)

    @staticmethod
    def _parse_json(text: str) -> dict:
        """从 LLM 响应中提取 JSON"""
        m = re.search(r"```(?:json)?\s*([\s\S]*?)```", text)
        if m:
            text = m.group(1).strip()
        m = re.search(r"\{[\s\S]*\}", text)
        if m:
            try:
                return json.loads(m.group(0))
            except json.JSONDecodeError:
                pass
        try:
            return json.loads(text)
        except json.JSONDecodeError:
            logger.warning(f"JSON 解析失败: {text[:200]}")
            return {"raw": text}