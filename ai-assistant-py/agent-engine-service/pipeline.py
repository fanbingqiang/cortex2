"""八股文教学流水线 — AI 自主决策"""

import time
import logging
import json
import uuid
import re
import asyncio
import random

from models import Session, SessionStep, ChatResponse
from llm_client import LlmClient
from trace_store import trace_store

logger = logging.getLogger(__name__)

# 意图关键词（子串匹配：消息中包含任一关键词即命中）
_REVEAL_KEYWORDS = [
    "给答案", "告诉我答案", "参考答案", "直接讲", "不会做", "给我答案",
    "说答案", "讲答案", "公布答案", "看答案", "做不出来", "想不出来",
    "放弃", "不猜了", "答案是什么", "啥答案", "公布一下", "直接说",
    "不知道怎么写", "不知道怎么改", "看不懂这题", "解不出来",
    "教我", "讲一下这题", "说一下答案", "直接告诉我", "我做不来",
    "想不通", "搞不懂", "不会写", "写不来", "做不了", "不会改",
]
_SKIP_KEYWORDS = [
    "再来一题", "换一个", "跳过", "下一题", "换个话题", "跳过这题",
    "不做了", "换题", "下一道", "换一道", "来个新", "别的题",
    "出个新的", "换别的", "不想做", "太难了", "太简单", "换一个题",
    "pass", "Pass", "PASS", "做题", "出题", "来一题", "再出一题",
    "出个题", "来道题", "练练手", "练练", "考考我", "测试一下",
    "做个题", "练一下", "来道", "出个题目", "测试我", "考我",
]
_LEARN_KEYWORDS = [
    "我想学", "教我", "学一下", "学习", "我想了解", "我想知道",
    "讲一下", "介绍一下", "什么是", "什么是", "怎么用", "怎么理解",
    "了解", "解释", "说说", "聊聊", "讨论", "探讨",
]
_GREET_KEYWORDS = [
    "你好", "hi", "hello", "嗨", "在吗", "在不在",
    "哈喽", "早上好", "晚上好", "下午好", "hey", "Hey",
]

# ============================================================
#  Agent Prompts 默认值（可被 trace_store 覆盖）
# ============================================================

_DEFAULT_EXAM_PROMPT = """你是一个八股文出题专家。根据知识点生成引导式代码题。

## 出题规则
- 代码可能是**正确**的（让学生分析为什么对），也可能是**错误**的（让学生找错）
- 题目难度：{difficulty}
- 出题偏好：{system_prompt}
- 用户薄弱点：{weak_points}
- 用户已掌握：{mastered}

## 当前知识模块
{message}

## 输出要求（严格按以下分隔符格式，不要多余内容）
===QUESTION===
...（引导式提问，让学生分析代码的意图和潜在问题，不要直接揭示答案）...

===CODE===
```java
...代码...
```

===CODE_TYPE===
correct 或 incorrect（只写一个单词）

===ANSWER===
...（一句话简洁答案，内部存储，不会显示给学生）...

===KNOWLEDGE===
...（知识树路径，用 / 分隔层级，如：redis/redis持久化/RDB机制）...

请严格按照以上分隔符格式输出，不要多余的话。"""

_DEFAULT_DIAGNOSE_PROMPT = """你是一个八股文教学诊断专家。评估学生对代码题的回答。

## 题目信息
题目代码：
{question_code}

代码类型：{code_type}（correct=正确代码, incorrect=错误代码）

参考答案：{reference_answer}

## 学生回答
{user_answer}

## 诊断要求
评估学生是否真正理解了代码的核心原理，判断：
1. 回答是否正确
2. 是否真正理解（不是蒙对的）
3. 知识盲区是什么

回复格式：
正确：是/否
真正理解：是/否
知识盲区：用逗号分隔
评分：0-100
诊断说明：一句话"""

_DEFAULT_TEACH_PROMPT = """你是一个八股文教学专家。根据诊断结果进行教学，尽量少用比喻，直接讲清楚技术本质。

## 知识模块
{knowledge_title}

## 诊断结果
{diagnosis}

## 教学要求（按以下结构输出，使用 Markdown）

### 1. 是什么
用一两句话简洁定义这个概念/技术是什么。

### 2. 痛点
描述在没有这个技术之前，开发者会遇到什么具体问题/场景。

### 3. 技术解决
说明这个技术是如何解决上述痛点的，核心机制是什么。

### 4. 综合代码展示
写一段涵盖该知识模块**所有关键知识点**的代码，展示这些知识点在复杂交互中如何配合使用。代码可以是错误的（标注出来），让学生分析。
代码中要体现多个知识点的交叉使用。

### 5. 知识点讲解
逐个讲解代码中涉及的知识点，结合代码中的具体行号说明。如果某个知识点在综合代码中没有体现，则单独写简单代码片段讲解。

### 6. 总结
一句话总结核心要点。

### 7. 思路来源
说明这个知识点出自哪个技术体系（如JVM、Spring、JUC等），为什么面试中会考，以及实际开发中什么场景会用到。让学习者知道这个知识点的"来龙去脉"。

### 8. 下一步学习建议
根据当前知识模块，推荐 1-2 个容易混淆的对比知识点、或进阶知识点，简要说明为什么要学它们。同时，如果用户已有已掌握的模块，随机推荐 1 个进行复习巩固。最后问用户："你想继续深入哪个方向？"

## 注意事项
- 减少比喻和类比，直接讲技术原理
- 代码要真实可运行（或明确指出错误在哪）
- 知识点讲解要结合刚才的代码，不要脱离代码空洞讲解
- 下一步建议要具体，不要泛泛地说"多练习"
- 涉及流程、架构、调用关系时，用 ASCII 字符画图（`│ ├ └ ─ → ↑ ↓` 等），不用真图片，靠空格换行对齐即可 """

_DEFAULT_INTENT_PROMPT = """根据对话历史和当前消息判断用户意图，只输出一个单词。

当前状态：
- 有活跃题目：{has_pending}
- 知识模块：{knowledge_title}

## 对话历史
{conversation_context}

规则（按优先级判断）：
1. 如果用户要答案、说"给答案"、"告诉我答案"、"参考答案是什么"、"不会做教我"、"直接讲吧"、"怎么做" → 输出 reveal_answer
2. 如果用户正在回答题目（分析代码对错、说"我觉得是对的/错的"、讨论代码逻辑、给出具体分析）→ 输出 diagnose
3. 如果用户想学新知识、出题、练习、换话题、说"再来一题"、"换一个"、"跳过"、"下一题"、"不知道"、"换个话题" → 输出 question
4. 其他情况（打招呼、闲聊、追问代码细节）→ 输出 chat

只输出一个单词：reveal_answer / diagnose / question / chat"""

_DEFAULT_CHAT_PROMPT = """你是一个编程学习助手。请根据对话历史自然地回复用户。

系统提示词：{system_prompt}
用户画像：已掌握={mastered}，薄弱点={weak_points}

要求：
- 结合对话历史理解用户意图，不要脱离上下文自说自话
- 如果对话中有出过题目，用户追问代码细节时要结合题目代码回答
- 用中文回复，使用 Markdown 格式
- 如果是打招呼，可以介绍自己并引导用户说说想学什么"""


# ============================================================
#  Prompt 获取（优先存储值，fallback 默认值）
# ============================================================

def _get_prompt(key: str, default: str) -> str:
    stored = trace_store.get_agent_prompt(key)
    return stored if stored else default


class TeachingPipeline:
    """八股文教学流水线 — 两步式 AI 决策"""

    def __init__(self, llm_client: LlmClient):
        self._llm = llm_client
        self._history: dict[str, list[dict]] = {}

    # ─── 聊天入口 ──────────────────────────────────────

    async def chat(self, user_id: str, message: str,
                   system_prompt: str = "") -> ChatResponse:
        # ... 原有逻辑不变 ...
        return await self._chat_internal(user_id, message, system_prompt)

    async def chat_stream(self, user_id: str, message: str,
                          system_prompt: str = ""):
        """流式聊天，逐 token yield SSE 事件"""
        if system_prompt:
            trace_store.set_system_prompt(user_id, system_prompt)
        else:
            system_prompt = trace_store.get_system_prompt(user_id)

        profile = trace_store.get_profile(user_id)
        history = self._get_history(user_id)
        history.append({"role": "user", "content": message})

        # 判断 pending
        pending_sid = trace_store.get_pending_answer(user_id)
        has_pending = False
        knowledge_title = ""
        pending_session = None
        if pending_sid:
            pending_session = trace_store.get_session(pending_sid)
            if pending_session and pending_session.status == "RUNNING":
                has_pending = True
                knowledge_title = pending_session.knowledge_title

        # 混合意图分类：先关键词，再 LLM
        intent = self._classify_intent_hybrid(message, has_pending)
        if intent is None:
            # 关键词未命中，走 LLM 分类
            recent_history = history[-6:]
            conv_lines = []
            for h in recent_history:
                role = "用户" if h["role"] == "user" else "AI"
                conv_lines.append(f"{role}：{h['content'][:200]}")
            conversation_context = "\n".join(conv_lines)
            intent_prompt = _get_prompt("intent", _DEFAULT_INTENT_PROMPT).format(
                has_pending="是" if has_pending else "否",
                knowledge_title=knowledge_title,
                conversation_context=conversation_context,
            )
            try:
                intent_resp = await self._llm.chat(
                    messages=[{"role": "user", "content": message}],
                    system_prompt=intent_prompt,
                )
                intent = intent_resp.strip().lower()
                if "reveal" in intent:
                    intent = "reveal_answer"
                elif "diagnose" in intent:
                    intent = "diagnose"
                elif "question" in intent:
                    intent = "question"
                else:
                    intent = "chat"
            except Exception:
                intent = "chat"

            # 硬编码兜底
            if has_pending and any(kw in message for kw in _REVEAL_KEYWORDS):
                intent = "reveal_answer"

        logger.info(f"流式意图: {intent} | 消息: {message[:50]}")

        # 发送意图事件
        yield f"data: {json.dumps({'type': 'intent', 'intent': intent}, ensure_ascii=False)}\n\n"

        # 执行对应 Agent 并流式输出
        full_reply = ""
        action = intent
        session_id = ""

        try:
            if intent == "reveal_answer" and has_pending:
                async for chunk in self._stream_reveal_answer(pending_sid, user_id, profile, history):
                    if isinstance(chunk, dict):
                        action = chunk.get("action", "reveal_answer")
                        session_id = chunk.get("session_id", "")
                    else:
                        yield f"data: {json.dumps({'type': 'token', 'content': chunk}, ensure_ascii=False)}\n\n"
                        full_reply += chunk
            elif intent == "diagnose" and has_pending:
                async for chunk in self._stream_diagnose_teach(pending_sid, user_id, profile, history):
                    if isinstance(chunk, dict):
                        action = chunk.get("action", "diagnose_teach")
                        session_id = chunk.get("session_id", "")
                    else:
                        yield f"data: {json.dumps({'type': 'token', 'content': chunk}, ensure_ascii=False)}\n\n"
                        full_reply += chunk
            elif intent == "question":
                if has_pending:
                    if pending_session:
                        pending_session.status = "CANCELLED"
                        trace_store.update_session(pending_session)
                    trace_store.clear_pending_answer(user_id)
                async for chunk in self._stream_question(message, user_id, system_prompt, profile, history):
                    if isinstance(chunk, dict):
                        action = chunk.get("action", "question")
                        session_id = chunk.get("session_id", "")
                    elif isinstance(chunk, str) and chunk.startswith("data: "):
                        # _stream_question 已生成完整 SSE 事件（code/knowledge_path/token），直接透传
                        yield chunk
                    else:
                        yield f"data: {json.dumps({'type': 'token', 'content': chunk}, ensure_ascii=False)}\n\n"
                        full_reply += chunk
            else:
                async for chunk in self._stream_chat(message, user_id, system_prompt, profile, history):
                    if isinstance(chunk, dict):
                        action = chunk.get("action", "chat")
                    else:
                        yield f"data: {json.dumps({'type': 'token', 'content': chunk}, ensure_ascii=False)}\n\n"
                        full_reply += chunk
        except Exception as e:
            logger.error(f"流式生成失败: {e}")
            err = f"\n\n抱歉，生成失败：{e}"
            yield f"data: {json.dumps({'type': 'token', 'content': err}, ensure_ascii=False)}\n\n"
            full_reply += err

        # 突击复习：答完题后随机抽一个已掌握模块，直接问问题
        if action in ("diagnose_teach", "reveal_answer"):
            profile = trace_store.get_profile(user_id)
            mastered = profile.get("mastered", [])
            if mastered:
                review_topic = random.choice(mastered)
                quiz_text = f"\n\n---\n\n**⚡ 突击检查！** 还记得 **{review_topic}** 吗？\n\n说说它的核心机制是什么？答不上来也没关系，想复习的话告诉我。"
                full_reply += quiz_text
                for ch in quiz_text:
                    yield f"data: {json.dumps({'type': 'token', 'content': ch}, ensure_ascii=False)}\n\n"
                    await asyncio.sleep(0.003)

        # 清理
        full_reply = re.sub(r'【[^】]*】\s*', '', full_reply)
        history = self._get_history(user_id)
        history.append({"role": "assistant", "content": full_reply})

        # 解析代码块 → 发送 code 事件
        code_blocks = self._parse_code_blocks(full_reply)
        for cb in code_blocks:
            yield f"data: {json.dumps({'type': 'code', 'language': cb['language'], 'content': cb['content']}, ensure_ascii=False)}\n\n"
            await asyncio.sleep(0.01)

        # 所有教学回复（reveal_answer / diagnose_teach）都写入笔记本
        if action in ("reveal_answer", "diagnose_teach") and session_id:
            session = trace_store.get_session(session_id)
            if session:
                # 笔记内容：简易知识点摘要（标题 + 参考答案 + 关键知识点）
                parts = [f"## {session.knowledge_title or '未知知识点'}"]
                if session.reference_answer:
                    parts.append(f"**答案：**{session.reference_answer}")
                if session.knowledge_points:
                    parts.append(f"**要点：**{'、'.join(session.knowledge_points)}")
                note_content = "\n\n".join(parts)
                note = {
                    "knowledge": session.knowledge_title or "未知知识点",
                    "answer": note_content,
                    "time": datetime.now().isoformat()
                }
                yield f"data: {json.dumps({'type': 'note', 'knowledge': note['knowledge'], 'content': note['answer']}, ensure_ascii=False)}\n\n"

        # 异步分析用户情绪（不阻塞对话流）
        if action in ("reveal_answer", "diagnose_teach"):
            asyncio.create_task(self._analyze_sentiment(user_id, history, action))
        self._set_history(user_id, history)

        # 生成建议问题
        suggestions = self._generate_suggestions(action, full_reply, message, user_id)

        # 发送完成事件
        yield f"data: {json.dumps({'type': 'done', 'action': action, 'session_id': session_id}, ensure_ascii=False)}\n\n"
        yield f"data: {json.dumps({'type': 'suggestions', 'items': suggestions}, ensure_ascii=False)}\n\n"

    async def _chat_internal(self, user_id: str, message: str,
                             system_prompt: str = "") -> ChatResponse:

        if system_prompt:
            trace_store.set_system_prompt(user_id, system_prompt)
        else:
            system_prompt = trace_store.get_system_prompt(user_id)

        profile = trace_store.get_profile(user_id)

        # 更新对话历史（先加当前消息）
        history = self._get_history(user_id)
        history.append({"role": "user", "content": message})

        # 判断是否有待回答的题目（只有 RUNNING 状态的才算）
        pending_sid = trace_store.get_pending_answer(user_id)
        has_pending = False
        knowledge_title = ""
        pending_session = None
        if pending_sid:
            pending_session = trace_store.get_session(pending_sid)
            if pending_session and pending_session.status == "RUNNING":
                has_pending = True
                knowledge_title = pending_session.knowledge_title

        # ── 第一步：意图分类（带完整对话上下文） ──
        # 构建对话上下文字符串
        recent_history = history[-6:]  # 最近 6 条
        conv_lines = []
        for h in recent_history:
            role = "用户" if h["role"] == "user" else "AI"
            conv_lines.append(f"{role}：{h['content'][:200]}")
        conversation_context = "\n".join(conv_lines)

        intent_prompt = _get_prompt("intent", _DEFAULT_INTENT_PROMPT).format(
            has_pending="是" if has_pending else "否",
            knowledge_title=knowledge_title,
            conversation_context=conversation_context,
        )
        try:
            # 只传当前消息，意图 prompt 里已经包含了对话上下文
            intent_resp = await self._llm.chat(
                messages=[{"role": "user", "content": message}],
                system_prompt=intent_prompt,
            )
            intent = intent_resp.strip().lower()
            if "reveal" in intent:
                intent = "reveal_answer"
            elif "diagnose" in intent:
                intent = "diagnose"
            elif "question" in intent:
                intent = "question"
            else:
                intent = "chat"

            # 硬编码兜底：明确要答案的关键词
            if has_pending and any(kw in message for kw in [
                "给答案", "告诉我答案", "参考答案", "直接讲", "不会做",
                "给我答案", "说答案", "讲答案", "公布答案", "看答案"
            ]):
                intent = "reveal_answer"
        except Exception as e:
            logger.error(f"意图分类失败: {e}")
            intent = "chat"

        logger.info(f"意图: {intent} | 消息: {message[:50]}")

        # ── 第二步：根据意图执行 ──
        if intent == "reveal_answer" and has_pending:
            return await self._run_reveal_answer(pending_sid, user_id, profile, history)
        elif intent == "diagnose" and has_pending:
            return await self._run_diagnose_teach(pending_sid, user_id, profile, history)
        elif intent == "question":
            if has_pending:
                if pending_session:
                    pending_session.status = "CANCELLED"
                    trace_store.update_session(pending_session)
                trace_store.clear_pending_answer(user_id)
            return await self._run_question(message, user_id, system_prompt, profile, history)
        else:
            # chat 意图：不清除 pending，让用户可以继续追问或回答
            return await self._run_chat(message, user_id, system_prompt, profile, history)

    # ─── 出题 ──────────────────────────────────────────

    async def _run_question(self, message: str, user_id: str,
                            system_prompt: str, profile: dict,
                            history: list[dict]) -> ChatResponse:
        prompt = _get_prompt("exam", _DEFAULT_EXAM_PROMPT).format(
            difficulty=profile.get("preferred_difficulty", "medium"),
            system_prompt=system_prompt or "无",
            weak_points=", ".join(profile.get("weak_points", [])) or "无",
            mastered=", ".join(profile.get("mastered", [])) or "无",
            message=message,
        )
        try:
            resp = await self._llm.chat(
                messages=history[-6:].copy(),
                system_prompt=prompt,
            )
        except Exception as e:
            logger.error(f"出题失败: {e}")
            return ChatResponse(reply="抱歉，出题失败，请重试。", action="chat", profile=profile)

        # 解析出题结果
        parsed = self._parse_exam_output(resp)
        session_id = uuid.uuid4().hex[:12]
        session = Session(
            session_id=session_id,
            user_id=user_id,
            knowledge_node_id=(parsed.get("knowledge_title", "") or "unknown").replace(" ", "-").lower()[:30],
            knowledge_title=parsed.get("knowledge_title", "未命名"),
            system_prompt=system_prompt,
            status="RUNNING",
            code_type=parsed.get("code_type", "incorrect"),
            question_code=parsed.get("question_code", ""),
            question=parsed.get("question", ""),
            reference_answer=parsed.get("reference_answer", ""),
            hint="",
            knowledge_points=parsed.get("knowledge_points", []),
        )
        step = self._add_step(session, "question", "出题")
        step.status = "SUCCESS"
        step.output = json.dumps(parsed, ensure_ascii=False, indent=2)
        step.input = json.dumps({
            "knowledge_title": parsed.get("knowledge_title", ""),
            "user_message": message,
            "profile": {"difficulty": profile.get("preferred_difficulty"), "weak_points": profile.get("weak_points", [])},
        }, ensure_ascii=False, indent=2)
        trace_store.create_session(session)
        trace_store.set_pending_answer(user_id, session_id)

        # 构建友好的回复（不显示答案，引导式）
        code_type_text = "正确代码" if session.code_type == "correct" else "错误代码"
        reply = f"""## {parsed.get('knowledge_title', '题目')}

**类型：{code_type_text}**

{parsed.get('question_code', '')}

{parsed.get('question', '')}

---
请先尝试分析，说说你的想法。如果需要提示或答案，随时告诉我。"""
        # 清理 【】 标记
        reply = re.sub(r'【[^】]*】\s*', '', reply)

        history = self._get_history(user_id)
        history.append({"role": "assistant", "content": reply})
        self._set_history(user_id, history)

        return ChatResponse(
            reply=reply,
            action="question",
            session_id=session_id,
            session=session.model_dump(),
            profile=profile,
        )

    # ─── 诊断+讲解 ──────────────────────────────────────

    async def _run_diagnose_teach(self, session_id: str, user_id: str,
                                   profile: dict, history: list[dict]) -> ChatResponse:
        session = trace_store.get_session(session_id)
        if not session:
            return ChatResponse(reply="会话不存在", action="chat", profile=profile)

        user_answer = self._get_last_user_message(user_id)
        session.user_answer = user_answer

        # 检测是否用户没有真正回答（如直接要答案）
        no_real_answer = not user_answer or any(
            kw in user_answer for kw in ["给答案", "告诉我答案", "参考答案", "给我答案"]
        )

        if no_real_answer:
            # 跳过诊断，直接讲解
            diag_resp = "正确：未作答\n真正理解：否\n知识盲区：未知\n评分：0\n诊断说明：用户未作答，直接查看答案"
            diag = {"is_correct": "未作答", "is_understood": "否", "knowledge_gaps": [], "score": 0, "detail": "直接查看答案"}
            score = 0
        else:
            # 诊断
            diag_prompt = _get_prompt("diagnose", _DEFAULT_DIAGNOSE_PROMPT).format(
                question_code=session.question_code or "",
                code_type=session.code_type or "incorrect",
                reference_answer=session.reference_answer or "",
                user_answer=user_answer,
            )
            try:
                diag_resp = await self._llm.chat(
                    messages=history[-6:].copy(),
                    system_prompt=diag_prompt,
                )
            except Exception as e:
                logger.error(f"诊断失败: {e}")
                diag_resp = "正确：否\n真正理解：否\n知识盲区：未知\n评分：0\n诊断说明：诊断失败"

            diag = self._parse_diagnosis(diag_resp)

        # 讲解
        teach_prompt = _get_prompt("teach", _DEFAULT_TEACH_PROMPT).format(
            knowledge_title=session.knowledge_title or "未知",
            diagnosis=diag_resp,
        )
        try:
            teach_resp = await self._llm.chat(
                messages=history[-6:].copy(),
                system_prompt=teach_prompt,
            )
        except Exception as e:
            logger.error(f"讲解失败: {e}")
            teach_resp = "讲解生成失败，请重试。"

        # 记录步骤
        d_step = self._add_step(session, "diagnosis", "诊断")
        d_step.status = "SUCCESS"
        d_step.input = json.dumps({"user_answer": user_answer, "question_code": session.question_code}, ensure_ascii=False)
        d_step.output = diag_resp

        t_step = self._add_step(session, "teaching", "讲解")
        t_step.status = "SUCCESS"
        t_step.input = json.dumps({"knowledge_gaps": diag.get("knowledge_gaps", "")}, ensure_ascii=False)
        t_step.output = teach_resp

        p_step = self._add_step(session, "profile", "更新画像")
        p_step.status = "SUCCESS"

        session.knowledge_gaps = diag.get("knowledge_gaps", [])
        session.mastery_score = diag.get("score", 0)
        session.teaching_content = teach_resp
        session.status = "COMPLETED"
        trace_store.update_session(session)

        # 更新画像
        score = diag.get("score", 0)
        if score >= 80:
            trace_store.update_profile(user_id, mastered=profile.get("mastered", []) + [session.knowledge_title])
        else:
            trace_store.update_profile(user_id, weak_points=profile.get("weak_points", []) + [session.knowledge_title])

        profile = trace_store.get_profile(user_id)
        p_step.output = json.dumps({"mastered": profile.get("mastered", []), "weak_points": profile.get("weak_points", [])}, ensure_ascii=False)

        if no_real_answer:
            reply = f"""## 参考答案

{teach_resp}"""
        else:
            reply = f"""## 诊断结果

正确：{diag.get('is_correct', '?')}
理解程度：{diag.get('is_understood', '?')}
评分：{score}/100

{teach_resp}"""
        # 清理 【】 标记
        reply = re.sub(r'【[^】]*】\s*', '', reply)

        history = self._get_history(user_id)
        history.append({"role": "assistant", "content": reply})
        self._set_history(user_id, history)

        # 清除待回答状态，允许继续对话
        trace_store.clear_pending_answer(user_id)

        return ChatResponse(
            reply=reply,
            action="diagnose_teach",
            session_id=session_id,
            session=session.model_dump(),
            profile=profile,
        )

    # ─── 通用聊天 ──────────────────────────────────────

    async def _run_chat(self, message: str, user_id: str,
                        system_prompt: str, profile: dict,
                        history: list[dict]) -> ChatResponse:
        chat_prompt = _get_prompt("chat", _DEFAULT_CHAT_PROMPT).format(
            system_prompt=system_prompt or "无",
            mastered=", ".join(profile.get("mastered", [])) or "无",
            weak_points=", ".join(profile.get("weak_points", [])) or "无",
        )
        # 最近 10 条对话历史
        msgs = history[-10:].copy()
        try:
            reply = await self._llm.chat(messages=msgs, system_prompt=chat_prompt)
        except Exception:
            reply = "你好！有什么我可以帮你的吗？"

        # 清理 【】 标记
        reply = re.sub(r'【[^】]*】\s*', '', reply)

        history.append({"role": "assistant", "content": reply})
        self._set_history(user_id, history)
        return ChatResponse(reply=reply, action="chat", profile=profile)

    # ─── 引导式给答案 ──────────────────────────────────

    async def _run_reveal_answer(self, session_id: str, user_id: str,
                                  profile: dict, history: list[dict]) -> ChatResponse:
        """用户要答案时，先引导尝试，不给直接答案"""
        session = trace_store.get_session(session_id)
        if not session:
            return ChatResponse(reply="会话不存在", action="chat", profile=profile)

        # 如果用户已经尝试过（有 user_answer），直接走诊断+讲解
        if session.user_answer and session.user_answer.strip():
            return await self._run_diagnose_teach(session_id, user_id, profile, history)

        # 第二次要答案：用户坚持要，直接走诊断+讲解
        if session.hint == "reveal_attempted":
            return await self._run_diagnose_teach(session_id, user_id, profile, history)

        # 第一次要答案：引导用户先尝试分析
        session.hint = "reveal_attempted"
        trace_store.update_session(session)

        reply = f"""别急着看答案，先试试自己分析一下？

**题目回顾：**

{session.question_code or ''}

{session.question or '请分析这段代码'}

---

提示：你可以说说你觉得这段代码是**正确还是错误**，以及你的**理由**。即使说错了也没关系，学习的过程比答案更重要。

如果你实在想不出来，再说一次"给答案"，我会给你详细讲解。"""

        reply = re.sub(r'【[^】]*】\s*', '', reply)
        history.append({"role": "assistant", "content": reply})
        self._set_history(user_id, history)

        return ChatResponse(
            reply=reply,
            action="reveal_answer",
            session_id=session_id,
            session=session.model_dump(),
            profile=profile,
        )

    # ─── 解析方法 ──────────────────────────────────────

    @staticmethod
    def _parse_exam_output(text: str) -> dict:
        """解析出题 Agent 的输出（支持新分隔符格式 ===SECTION===）"""
        def _extract(section: str, text: str) -> str:
            m = re.search(rf'==={section}===\s*\n(.*?)(?===|$)', text, re.DOTALL)
            return m.group(1).strip() if m else ""

        result = {
            "knowledge_title": "",
            "knowledge_path": "",
            "code_type": "incorrect",
            "question_code": "",
            "question": "",
            "reference_answer": "",
            "knowledge_points": [],
        }

        # 新格式 ===SECTION===
        q = _extract("QUESTION", text)
        code = _extract("CODE", text)
        code_type_raw = _extract("CODE_TYPE", text)
        answer = _extract("ANSWER", text)
        kp = _extract("KNOWLEDGE", text)

        if q or code:
            # 新格式解析成功
            result["question"] = q
            result["question_code"] = re.sub(r'```\w*\n?|```', '', code).strip()
            result["reference_answer"] = answer
            result["knowledge_path"] = kp
            result["knowledge_points"] = [p.strip() for p in kp.replace('/', ',').split(',') if p.strip()]
            if code_type_raw:
                ct = code_type_raw.lower()
                if "incorrect" in ct or "wrong" in ct or "false" in ct:
                    result["code_type"] = "incorrect"
                elif "correct" in ct or "right" in ct or "true" in ct:
                    result["code_type"] = "correct"
            title = kp.split("/")[-1] if kp else q[:30]
            result["knowledge_title"] = title.strip() or "编程知识"
            return result

        # 兼容旧格式 【...】
        m = re.search(r'【代码类型】\s*(.*)', text)
        if m:
            code_type = m.group(1).strip()
            result["code_type"] = "correct" if "correct" in code_type.lower() else "incorrect"
        m = re.search(r'【代码】\s*\n(.*?)(?=【问题】|$)', text, re.DOTALL)
        if m:
            result["question_code"] = re.sub(r'【[^】]*】', '', m.group(1)).strip()
        m = re.search(r'【问题】\s*\n(.*?)(?=【代码类型】|$)', text, re.DOTALL)
        if m:
            result["question"] = re.sub(r'【[^】]*】', '', m.group(1)).strip()
        m = re.search(r'【参考答案】\s*\n(.*?)(?=【知识点】|$)', text, re.DOTALL)
        if m:
            result["reference_answer"] = re.sub(r'【[^】]*】', '', m.group(1)).strip()
        m = re.search(r'【知识点】\s*\n(.*?)$', text, re.DOTALL)
        if m:
            result["knowledge_points"] = [p.strip() for p in m.group(1).strip().split(',') if p.strip()]

        code = result["question_code"]
        if "ThreadPool" in code or "Executor" in code:
            result["knowledge_title"] = "Java 线程池"
        elif "synchronized" in code:
            result["knowledge_title"] = "Java 并发同步"
        elif "String" in code and "==" in code:
            result["knowledge_title"] = "Java String 比较"
        elif "HashMap" in code or "ConcurrentHashMap" in code:
            result["knowledge_title"] = "Java 集合"
        elif "volatile" in code:
            result["knowledge_title"] = "Java volatile"
        elif "class" in code or "interface" in code:
            result["knowledge_title"] = "Java 面向对象"
        else:
            result["knowledge_title"] = "编程知识"

        return result

    @staticmethod
    def _parse_diagnosis(text: str) -> dict:
        """解析诊断结果"""
        result = {
            "is_correct": "否",
            "is_understood": "否",
            "knowledge_gaps": [],
            "score": 0,
            "detail": "",
        }
        for line in text.split("\n"):
            line = line.strip()
            if "正确" in line and "：" in line:
                result["is_correct"] = "是" if "是" in line.split("：")[1] else "否"
            elif "理解" in line and "：" in line:
                result["is_understood"] = "是" if "是" in line.split("：")[1] else "否"
            elif "知识盲区" in line and "：" in line:
                gaps = line.split("：", 1)[1].strip()
                result["knowledge_gaps"] = [g.strip() for g in gaps.split(",") if g.strip() and g.strip() != "未知"]
            elif "评分" in line and "：" in line:
                try:
                    result["score"] = int(line.split("：")[1].strip().split("/")[0])
                except ValueError:
                    pass
            elif "诊断说明" in line and "：" in line:
                result["detail"] = line.split("：", 1)[1].strip()
        return result

    # ─── 对话历史 ──────────────────────────────────────

    def _get_history(self, user_id: str) -> list[dict]:
        if user_id not in self._history:
            self._history[user_id] = []
        return self._history[user_id]

    def _set_history(self, user_id: str, history: list[dict]):
        self._history[user_id] = history
        # 触发压缩检查
        asyncio.create_task(self._compress_history(user_id))

    async def _compress_history(self, user_id: str):
        """上下文压缩：参考 Claude Code 的压缩策略，将旧消息压缩为摘要而非删除。

        当历史消息超过 threshold 时：
        1. 将前 N 条消息用 LLM 总结为一段摘要
        2. 保留：摘要 + 最近 keep_recent 条消息
        3. 上下文总量大幅减少，但信息不丢失
        """
        from config import settings
        threshold = settings.compress_threshold
        keep_recent = settings.compress_keep_recent

        history = self._get_history(user_id)
        if len(history) <= threshold:
            return

        # 要压缩的消息（旧的部分）
        to_compress = history[:-keep_recent]
        recent = history[-keep_recent:]

        # 构建压缩提示词
        compress_prompt = "将以下教学对话总结为一段简洁的摘要，保留关键知识点和学员的薄弱点：\n\n"
        for m in to_compress:
            role = "学员" if m["role"] == "user" else "导师"
            text = m["content"][:300]  # 每条最多 300 字
            compress_prompt += f"{role}：{text}\n"

        try:
            summary = await self._llm.chat_simple_with_system(
                "你是一个对话摘要助手。用一两句话总结对话，保留核心信息。",
                compress_prompt
            )
            summary = f"[历史摘要] {summary.strip()}"
            logger.info(f"历史压缩: {len(to_compress)} 条 → {len(summary)} 字摘要")
        except Exception as e:
            logger.warning(f"历史压缩失败，使用简单截断: {e}")
            # 兜底：取最后一条 user 消息作为摘要
            last_user = next((m["content"][:200] for m in reversed(to_compress) if m["role"] == "user"), "")
            summary = f"[历史摘要] 学员曾讨论：{last_user}"

        # 新历史：摘要 + 最近消息
        new_history = [{"role": "system", "content": summary}] + recent
        self._history[user_id] = new_history
        logger.info(f"压缩完成: {len(history)} → {len(new_history)} 条 ({len(summary)} 字摘要)")

    def _get_last_user_message(self, user_id: str) -> str:
        h = self._get_history(user_id)
        for m in reversed(h):
            if m["role"] == "user":
                return m["content"]
        return ""

    # ─── 辅助方法 ──────────────────────────────────────────

    def _classify_intent_hybrid(self, message: str, has_pending: bool) -> str | None:
        """混合意图分类：关键词优先，命中返回意图，未命中返回 None"""
        msg = message.strip()

        # 明确要答案
        if has_pending and any(kw in msg for kw in _REVEAL_KEYWORDS):
            return "reveal_answer"

        # 明确要换题/跳过
        if any(kw in msg for kw in _SKIP_KEYWORDS):
            return "question"

        # 明确要学新知识（排除太短的消息）
        if len(msg) >= 4 and any(kw in msg for kw in _LEARN_KEYWORDS):
            return "question"

        # 打招呼
        if msg in _GREET_KEYWORDS:
            return "chat"

        return None  # 走 LLM 分类

    def _generate_suggestions(self, action: str, reply: str, user_msg: str, user_id: str = "") -> list[str]:
        """根据上下文生成快捷回复建议，包含随机复习已掌握模块"""
        if action == "question":
            return ["给答案", "这段代码什么意思", "换个题", "太难了换个简单的"]
        elif action == "diagnose_teach" or action == "reveal_answer":
            suggestions = ["再来一题", "换个知识点", "我想学别的", "讲一下核心概念"]
            # 随机抽 1-2 个已掌握模块作为复习建议
            if user_id:
                profile = trace_store.get_profile(user_id)
                mastered = profile.get("mastered", [])
                if mastered:
                    pick = random.sample(mastered, min(2, len(mastered)))
                    for item in pick:
                        suggestions.append(f"复习一下{item}")
            return suggestions
        elif action == "chat":
            if any(kw in user_msg for kw in _LEARN_KEYWORDS + _SKIP_KEYWORDS):
                return ["出个题练练", "给我讲一下基础概念", "来个简单点的"]
            return ["我想学Java", "出个题练练", "给我讲一下原理", "Java线程池怎么用"]
        return []

    # ─── 流式 Agent 方法 ────────────────────────────────

    async def _stream_question(self, message: str, user_id: str,
                                system_prompt: str, profile: dict,
                                history: list[dict]):
        """流式出题 — 只显示问题文本，代码→编辑器，知识点→知识树，答案不显示"""
        prompt = _get_prompt("exam", _DEFAULT_EXAM_PROMPT).format(
            difficulty=profile.get("preferred_difficulty", "medium"),
            system_prompt=system_prompt or "无",
            weak_points=", ".join(profile.get("weak_points", [])) or "无",
            mastered=", ".join(profile.get("mastered", [])) or "无",
            message=message,
        )
        # 收集完整响应（不逐个流式输出，因为要解析后分发）
        full_resp = ""
        async for token in self._llm.chat_stream(
            messages=history[-6:].copy(),
            system_prompt=prompt,
        ):
            full_resp += token

        parsed = self._parse_exam_output(full_resp)
        logger.info(f"[出题] 解析结果: title={parsed.get('knowledge_title','')!r} code_len={len(parsed.get('question_code',''))} kp={parsed.get('knowledge_path','')!r} q_len={len(parsed.get('question',''))}")
        session_id = uuid.uuid4().hex[:12]
        session = Session(
            session_id=session_id,
            user_id=user_id,
            knowledge_node_id=(parsed.get("knowledge_title", "") or "unknown").replace(" ", "-").lower()[:30],
            knowledge_title=parsed.get("knowledge_title", "未命名"),
            system_prompt=system_prompt,
            status="RUNNING",
            code_type=parsed.get("code_type", "incorrect"),
            question_code=parsed.get("question_code", ""),
            question=parsed.get("question", ""),
            reference_answer=parsed.get("reference_answer", ""),
            hint="",
            knowledge_points=parsed.get("knowledge_points", []),
        )
        step = self._add_step(session, "question", "出题")
        step.status = "SUCCESS"
        step.output = json.dumps(parsed, ensure_ascii=False, indent=2)
        trace_store.create_session(session)
        trace_store.set_pending_answer(user_id, session_id)

        # 1. 发送代码到编辑器（干净的代码，去掉 markdown 包裹）
        code = parsed.get("question_code", "")
        if code:
            yield f"data: {json.dumps({'type': 'code', 'language': 'java', 'content': code}, ensure_ascii=False)}\n\n"
            await asyncio.sleep(0.01)

        # 2. 发送知识路径到知识树区域
        kp = parsed.get("knowledge_path", "")
        if kp:
            yield f"data: {json.dumps({'type': 'knowledge_path', 'path': kp}, ensure_ascii=False)}\n\n"
            await asyncio.sleep(0.01)

        # 3. 流式输出问题文本
        question_text = parsed.get("question", "")
        code_type_text = "正确代码" if session.code_type == "correct" else "错误代码"
        title = parsed.get("knowledge_title", "题目")
        display = f"**{title}** (类型：{code_type_text})\n\n{question_text}"
        for ch in display:
            yield f"data: {json.dumps({'type': 'token', 'content': ch}, ensure_ascii=False)}\n\n"
            await asyncio.sleep(0.01)

        yield {"action": "question", "session_id": session_id}

    async def _stream_chat(self, message: str, user_id: str,
                            system_prompt: str, profile: dict,
                            history: list[dict]):
        """流式聊天"""
        chat_prompt = _get_prompt("chat", _DEFAULT_CHAT_PROMPT).format(
            system_prompt=system_prompt or "无",
            mastered=", ".join(profile.get("mastered", [])) or "无",
            weak_points=", ".join(profile.get("weak_points", [])) or "无",
        )
        async for token in self._llm.chat_stream(
            messages=history[-10:].copy(),
            system_prompt=chat_prompt,
        ):
            yield token
        yield {"action": "chat"}

    async def _stream_reveal_answer(self, session_id: str, user_id: str,
                                     profile: dict, history: list[dict]):
        """流式引导/给答案"""
        session = trace_store.get_session(session_id)
        if not session:
            yield "会话不存在"
            yield {"action": "chat"}
            return

        if session.user_answer and session.user_answer.strip():
            async for chunk in self._stream_diagnose_teach(session_id, user_id, profile, history):
                yield chunk
            return

        if session.hint == "reveal_attempted":
            # 第二次点"看答案"：直接显示存储的参考答案，一次性输出
            answer = session.reference_answer or "暂无参考答案"
            reply = f"**参考答案：**\n\n{answer}\n\n---\n\n如果你还想深入了解，可以继续问我。"
            yield reply
            session.status = "COMPLETED"
            trace_store.update_session(session)
            trace_store.clear_pending_answer(user_id)
            trace_store.update_profile(user_id, weak_points=profile.get("weak_points", []) + [session.knowledge_title])
            yield {"action": "reveal_answer", "session_id": session_id}
            return

        session.hint = "reveal_attempted"
        trace_store.update_session(session)

        reply = f"""别急着看答案，先试试自己分析一下？

**题目回顾：**

{session.question_code or ''}

{session.question or '请分析这段代码'}

---

提示：你可以说说你觉得这段代码是**正确还是错误**，以及你的**理由**。即使说错了也没关系，学习的过程比答案更重要。

如果你实在想不出来，再说一次"给答案"，我会给你详细讲解。"""

        yield reply
        yield {"action": "reveal_answer"}  # 第一次引导，不写入笔记

    async def _stream_diagnose_teach(self, session_id: str, user_id: str,
                                      profile: dict, history: list[dict]):
        """流式诊断+教学"""
        session = trace_store.get_session(session_id)
        if not session:
            yield "会话不存在"
            yield {"action": "chat"}
            return

        user_answer = self._get_last_user_message(user_id)
        session.user_answer = user_answer

        no_real_answer = not user_answer or any(
            kw in user_answer for kw in ["给答案", "告诉我答案", "参考答案", "给我答案"]
        )

        if no_real_answer:
            diag_resp = "正确：未作答\n真正理解：否\n知识盲区：未知\n评分：0\n诊断说明：用户未作答，直接查看答案"
            diag = {"is_correct": "未作答", "is_understood": "否", "knowledge_gaps": [], "score": 0, "detail": "直接查看答案"}
            score = 0
        else:
            diag_prompt = _get_prompt("diagnose", _DEFAULT_DIAGNOSE_PROMPT).format(
                question_code=session.question_code or "",
                code_type=session.code_type or "incorrect",
                reference_answer=session.reference_answer or "",
                user_answer=user_answer,
            )
            try:
                diag_resp = await self._llm.chat(
                    messages=history[-6:].copy(),
                    system_prompt=diag_prompt,
                )
            except Exception:
                diag_resp = "正确：否\n真正理解：否\n知识盲区：未知\n评分：0\n诊断说明：诊断失败"
            diag = self._parse_diagnosis(diag_resp)
            score = diag.get("score", 0)

        # 流式讲解
        teach_prompt = _get_prompt("teach", _DEFAULT_TEACH_PROMPT).format(
            knowledge_title=session.knowledge_title or "未知",
            diagnosis=diag_resp,
        )
        teach_full = ""
        async for token in self._llm.chat_stream(
            messages=history[-6:].copy(),
            system_prompt=teach_prompt,
        ):
            teach_full += token
            yield token

        session.knowledge_gaps = diag.get("knowledge_gaps", [])
        session.mastery_score = score
        session.teaching_content = teach_full
        session.status = "COMPLETED"
        trace_store.update_session(session)
        trace_store.clear_pending_answer(user_id)

        if score >= 80:
            trace_store.update_profile(user_id, mastered=profile.get("mastered", []) + [session.knowledge_title])
        else:
            trace_store.update_profile(user_id, weak_points=profile.get("weak_points", []) + [session.knowledge_title])

        yield {"action": "diagnose_teach", "session_id": session_id}

    def _add_step(self, session: Session, step_id: str, step_name: str) -> SessionStep:
        step = SessionStep(
            step_id=step_id, step_name=step_name,
            status="PENDING", timestamp=time.time(),
        )
        session.steps.append(step)
        return step

    # ─── 保留的旧接口（兼容） ──────────────────────────────

    def _build_session_info(self, user_id: str) -> str:
        return "无活跃会话"

    async def _on_question(self, result, user_id, profile):
        pass

    async def _on_diagnose_teach(self, result, user_id, profile):
        pass

    async def _classify_intent(self, message, profile):
        return "chat"

    async def _chat_reply(self, message, user_id, system_prompt, profile):
        return ChatResponse(reply="", action="chat", profile=profile)

    # ─── 代码块解析 + 情绪分析 ──────────────────────────

    def _parse_code_blocks(self, text: str) -> list[dict]:
        """从文本中提取代码块，返回 [{'language': 'java', 'content': '...'}, ...]"""
        import re
        blocks = []
        # 匹配 ```language\n...\n``` 格式
        pattern = r'```(\w*)\n(.*?)```'
        for m in re.finditer(pattern, text, re.DOTALL):
            lang = m.group(1).strip() or "text"
            content = m.group(2).strip()
            if content and len(content) > 10:  # 过滤太短的
                blocks.append({"language": lang, "content": content})
        return blocks

    async def _analyze_sentiment(self, user_id: str, history: list[dict], action: str):
        """异步分析用户情绪，存储提示词优化建议"""
        try:
            # 取最近 5 条用户消息
            user_msgs = [h["content"] for h in history[-10:] if h["role"] == "user"]
            if len(user_msgs) < 3:
                return

            user_text = "\n".join(user_msgs[-5:])
            analysis_prompt = (
                "分析以下学员的对话，判断其情绪状态和学习情况，给出提示词优化建议。\n\n"
                f"最近动作：{action}\n"
                f"学员消息：\n{user_text}\n\n"
                "输出 JSON 格式：\n"
                '{"emotion": "满意/困惑/不耐烦/正常", "issues": ["问题1", "问题2"], '
                '"suggestion": "提示词优化建议"}'
            )

            resp = await self._llm.chat(
                messages=[{"role": "user", "content": analysis_prompt}],
                system_prompt="你是一个教学分析专家。只输出 JSON，不要其他内容。"
            )

            import json
            result = json.loads(resp.strip())
            result["user_id"] = user_id
            result["action"] = action
            result["time"] = datetime.now().isoformat()

            logger.info(f"情绪分析: user={user_id}, emotion={result.get('emotion')}")

        except Exception as e:
            logger.debug(f"情绪分析失败（不影响主流程）: {e}")