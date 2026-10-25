"""内存追踪存储 — 记录教学会话 + 用户画像 + 系统提示词 + 版本历史（带 JSON 持久化）"""

import json
import logging
import os
from typing import Any
from datetime import datetime, timezone, timedelta

from models import Session, UserProfile
from config import settings

PERSIST_FILE = os.path.join(os.path.dirname(__file__), "prompt_state.json")

logger = logging.getLogger("trace_store")


class TraceStore:
    """内存追踪存储（单例）"""
    _instance = None

    DEFAULT_PROMPTS = {
        "exam": """你是一个八股文出题专家。根据知识点生成引导式代码题。

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

请严格按照以上分隔符格式输出，不要多余的话。""",
        "diagnose": """你是一个八股文教学诊断专家。评估学生对代码题的回答。

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
诊断说明：一句话""",
        "teach": """你是一个八股文教学专家。根据诊断结果进行教学，尽量少用比喻，直接讲清楚技术本质。

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
- 涉及流程、架构、调用关系时，用 ASCII 字符画图（`│ ├ └ ─ → ↑ ↓` 等），不用真图片，靠空格换行对齐即可""",
        "intent": """根据对话历史和当前消息判断用户意图，只输出一个单词。

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

只输出一个单词：reveal_answer / diagnose / question / chat""",
        "chat": """你是一个编程学习助手。请根据对话历史自然地回复用户。

系统提示词：{system_prompt}
用户画像：已掌握={mastered}，薄弱点={weak_points}

要求：
- 结合对话历史理解用户意图，不要脱离上下文自说自话
- 如果对话中有出过题目，用户追问代码细节时要结合题目代码回答
- 用中文回复，使用 Markdown 格式
- 如果是打招呼，可以介绍自己并引导用户说说想学什么""",
    }

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._sessions: dict[str, Session] = {}
            cls._instance._profiles: dict[str, UserProfile] = {}
            cls._instance._system_prompts: dict[str, str] = {}
            cls._instance._pending_answer: dict[str, str] = {}  # user_id -> session_id
            cls._instance._agent_prompts: dict[str, str] = dict(cls.DEFAULT_PROMPTS)  # 各 Agent 提示词
            cls._instance._prompt_history: dict[str, list[dict]] = {}
            cls._instance._feedback_suggestions: list[dict] = []  # AI 自动生成的优化建议
            cls._instance._load()
        return cls._instance

    def _save(self):
        """持久化提示词状态到 JSON 文件"""
        try:
            data = {
                "agent_prompts": self._agent_prompts,
                "prompt_history": self._prompt_history,
                "feedback_suggestions": self._feedback_suggestions[-50:],  # 保留最近50条
            }
            with open(PERSIST_FILE, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
        except Exception as e:
            logging.getLogger("trace_store").warning(f"持久化失败: {e}")

    def _load(self):
        """从 JSON 文件恢复提示词状态"""
        try:
            if os.path.exists(PERSIST_FILE):
                with open(PERSIST_FILE, "r", encoding="utf-8") as f:
                    data = json.load(f)
                if "agent_prompts" in data:
                    self._agent_prompts.update(data["agent_prompts"])
                if "prompt_history" in data:
                    self._prompt_history = data["prompt_history"]
                if "feedback_suggestions" in data:
                    self._feedback_suggestions = data["feedback_suggestions"]
                logging.getLogger("trace_store").info(f"已加载持久化提示词状态 ({len(self._prompt_history)} 个 Agent 有历史, {len(self._feedback_suggestions)} 条反馈建议)")
        except Exception as e:
            logging.getLogger("trace_store").warning(f"加载持久化数据失败: {e}")

    # ─── 会话 ──────────────────────────────────────────

    def create_session(self, session: Session):
        self._sessions[session.session_id] = session

    def update_session(self, session: Session):
        self._sessions[session.session_id] = session

    def get_session(self, session_id: str) -> Session | None:
        return self._sessions.get(session_id)

    def get_all_sessions(self) -> list[dict]:
        return [s.model_dump() for s in self._sessions.values()]

    def get_step(self, session_id: str, step_id: str) -> dict | None:
        session = self._sessions.get(session_id)
        if not session:
            return None
        for step in session.steps:
            if step.step_id == step_id:
                return step.model_dump()
        return None

    def update_step_input(self, session_id: str, step_id: str, new_input: str) -> bool:
        session = self._sessions.get(session_id)
        if not session:
            return False
        for step in session.steps:
            if step.step_id == step_id:
                step.input = new_input
                return True
        return False

    def update_step_output(self, session_id: str, step_id: str, new_output: str) -> bool:
        session = self._sessions.get(session_id)
        if not session:
            return False
        for step in session.steps:
            if step.step_id == step_id:
                step.output = new_output
                return True
        return False

    # ─── 用户画像 ──────────────────────────────────────

    def get_or_create_profile(self, user_id: str) -> UserProfile:
        if user_id not in self._profiles:
            self._profiles[user_id] = UserProfile(user_id=user_id)
        return self._profiles[user_id]

    def update_profile(self, user_id: str, **kwargs):
        profile = self.get_or_create_profile(user_id)
        for k, v in kwargs.items():
            if hasattr(profile, k):
                setattr(profile, k, v)
        self._profiles[user_id] = profile

    def get_profile(self, user_id: str) -> dict:
        return self.get_or_create_profile(user_id).model_dump()

    # ─── 系统提示词 ────────────────────────────────────

    def get_system_prompt(self, user_id: str) -> str:
        return self._system_prompts.get(user_id, "")

    def set_system_prompt(self, user_id: str, prompt: str):
        self._system_prompts[user_id] = prompt

    # ─── 待回答状态 ────────────────────────────────────

    def set_pending_answer(self, user_id: str, session_id: str):
        self._pending_answer[user_id] = session_id

    def get_pending_answer(self, user_id: str) -> str | None:
        return self._pending_answer.get(user_id)

    def clear_pending_answer(self, user_id: str):
        self._pending_answer.pop(user_id, None)

    # ─── Agent 提示词 ─────────────────────────────────

    def get_agent_prompts(self) -> dict[str, str]:
        return dict(self._agent_prompts)

    def update_agent_prompt(self, key: str, value: str, author: str = "admin"):
        """更新提示词并记录版本历史"""
        if key not in self._prompt_history:
            # 首次：记录默认版本
            if key in self.DEFAULT_PROMPTS:
                self._prompt_history[key] = [{
                    "version": 1,
                    "prompt": self.DEFAULT_PROMPTS[key],
                    "author": "system",
                    "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S"),
                    "message": "初始版本",
                }]
            else:
                self._prompt_history[key] = []
        history = self._prompt_history[key]
        next_version = (history[-1]["version"] + 1) if history else 1
        self._agent_prompts[key] = value
        history.append({
            "version": next_version,
            "prompt": value,
            "author": author,
            "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S"),
            "message": f"v{next_version} 由 {author} 更新",
        })
        self._save()

    def get_prompt_history(self, key: str) -> list[dict]:
        """获取指定提示词的版本历史"""
        return self._prompt_history.get(key, [])

    def rollback_prompt(self, key: str, version: int, author: str = "admin") -> bool:
        """回滚到指定版本"""
        history = self._prompt_history.get(key, [])
        for h in history:
            if h["version"] == version:
                self._agent_prompts[key] = h["prompt"]
                next_version = history[-1]["version"] + 1
                history.append({
                    "version": next_version,
                    "prompt": h["prompt"],
                    "author": author,
                    "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S"),
                    "message": f"回滚至 v{version} (由 {author} 操作)",
                })
                self._save()
                return True
        return False

    # ─── 提示词优化建议 ──────────────────────────────

    def add_feedback_suggestion(self, user_id: str, conversation_id: str, context: list[dict], suggestion: str):
        """用户不满意反馈 → AI 生成优化建议 → 存入变更日志"""
        record = {
            "type": "feedback",
            "user_id": user_id,
            "conversation_id": conversation_id,
            "suggestion": suggestion,
            "context_preview": context[-1]["content"][:100] if context else "",
            "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S"),
        }
        self._feedback_suggestions.append(record)
        self._save()

    def get_all_prompt_history(self) -> list[dict]:
        """聚合所有变更日志（管理员修改 + AI 自动优化建议），按时间倒序"""
        records = []
        # 管理员修改的提示词历史
        for key, history in self._prompt_history.items():
            for h in history:
                records.append({
                    "type": "prompt_change",
                    "agent_key": key,
                    "version": h["version"],
                    "author": h["author"],
                    "timestamp": h["timestamp"],
                    "message": h.get("message", f"更新到 v{h['version']}"),
                    "prompt_preview": h.get("prompt", "")[:100],
                })
        # AI 自动生成的优化建议
        for s in self._feedback_suggestions:
            records.append({
                "type": "feedback",
                "agent_key": "AI自动优化",
                "version": 0,
                "author": f"用户 {s.get('user_id', 'anonymous')[:8]}",
                "timestamp": s["timestamp"],
                "message": s["suggestion"],
                "prompt_preview": s.get("context_preview", ""),
            })
        records.sort(key=lambda r: r["timestamp"], reverse=True)
        return records

    def get_agent_prompt(self, key: str) -> str:
        return self._agent_prompts.get(key, self.DEFAULT_PROMPTS.get(key, ""))

    # ─── 统计 ──────────────────────────────────────────

    def count_sessions(self) -> int:
        return len(self._sessions)

    def count_profiles(self) -> int:
        return len(self._profiles)

    def count_prompts(self) -> int:
        return len(self._agent_prompts)


def _create_store():
    """根据配置选择存储后端：Redis（多 worker 共享）或内存（默认）

    - 未配置 REDIS_URL → 内存模式（本地开发，行为与之前完全一致）
    - 配置了但连不上 → 自动退回内存模式，保证服务可启动
    """
    redis_url = settings.redis_url
    if not redis_url:
        return TraceStore()
    try:
        from redis_store import RedisStore
        store = RedisStore(redis_url)
        if store.available:
            return store
    except ImportError:
        logger.info("未安装 redis 包，使用内存模式")
    except Exception as e:
        logger.warning(f"RedisStore 初始化失败，使用内存模式: {e}")
    return TraceStore()


trace_store = _create_store()