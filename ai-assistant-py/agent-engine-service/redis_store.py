"""Redis 共享存储 — TraceStore 的 Redis 实现（多 worker / 多副本场景）

设计原则：
- 公开方法与 trace_store.TraceStore 完全一致，main.py / pipeline.py 无感知切换
- Redis 不可用时 available=False，由 trace_store 工厂退回内存模式
- 使用同步 redis-py 客户端：本地操作微秒级，阻塞事件循环可接受
- 提示词 / 会话 / 画像全部落 Redis，天然多进程共享 + 重启不丢
"""

import json
import logging
from datetime import datetime, timezone

from models import Session, UserProfile

logger = logging.getLogger("trace_store")

# Redis Key 命名空间
K_SESSIONS = "ts:sessions"
K_PROFILES = "ts:profiles"
K_SYS_PROMPTS = "ts:sys_prompts"
K_PENDING = "ts:pending"
K_AGENT_PROMPTS = "ts:agent_prompts"
K_PROMPT_HISTORY = "ts:prompt_history"
K_FEEDBACK = "ts:feedback"


class RedisStore:
    """基于 Redis 的共享存储，接口与 TraceStore 对齐"""

    def __init__(self, url: str):
        self._redis = None
        self.available = False
        try:
            import redis as redis_lib  # 延迟导入，未安装 redis 包时优雅降级
            r = redis_lib.Redis.from_url(
                url,
                decode_responses=True,
                socket_connect_timeout=3,
                socket_timeout=3,
            )
            r.ping()
            self._redis = r
            self.available = True
            self._seed_default_prompts()
            logger.info(f"RedisStore 初始化成功: {url}")
        except Exception as e:
            logger.warning(f"Redis 不可用，退回内存模式: {e}")

    # ─── 初始化 ──────────────────────────────────────────

    def _seed_default_prompts(self):
        """把默认 Agent 提示词写入 Redis（只补缺失的，不覆盖已有修改）"""
        from trace_store import TraceStore  # 延迟导入避免循环引用
        defaults = TraceStore.DEFAULT_PROMPTS
        for key, value in defaults.items():
            self._redis.hsetnx(K_AGENT_PROMPTS, key, value)
        logger.info(f"已确保默认 Agent 提示词存在于 Redis ({len(defaults)} 个)")

    # ─── 会话 ──────────────────────────────────────────

    def create_session(self, session: Session):
        self._redis.hset(K_SESSIONS, session.session_id, session.model_dump_json())

    def update_session(self, session: Session):
        self.create_session(session)

    def get_session(self, session_id: str) -> Session | None:
        raw = self._redis.hget(K_SESSIONS, session_id)
        if not raw:
            return None
        return Session.model_validate_json(raw)

    def get_all_sessions(self) -> list[dict]:
        raw_map = self._redis.hgetall(K_SESSIONS)
        return [Session.model_validate_json(v).model_dump() for v in raw_map.values()]

    def get_step(self, session_id: str, step_id: str) -> dict | None:
        session = self.get_session(session_id)
        if not session:
            return None
        for step in session.steps:
            if step.step_id == step_id:
                return step.model_dump()
        return None

    def update_step_input(self, session_id: str, step_id: str, new_input: str) -> bool:
        session = self.get_session(session_id)
        if not session:
            return False
        for step in session.steps:
            if step.step_id == step_id:
                step.input = new_input
                self.update_session(session)
                return True
        return False

    def update_step_output(self, session_id: str, step_id: str, new_output: str) -> bool:
        session = self.get_session(session_id)
        if not session:
            return False
        for step in session.steps:
            if step.step_id == step_id:
                step.output = new_output
                self.update_session(session)
                return True
        return False

    # ─── 用户画像 ──────────────────────────────────────

    def get_or_create_profile(self, user_id: str) -> UserProfile:
        raw = self._redis.hget(K_PROFILES, user_id)
        if raw:
            return UserProfile.model_validate_json(raw)
        profile = UserProfile(user_id=user_id)
        self._redis.hset(K_PROFILES, user_id, profile.model_dump_json())
        return profile

    def update_profile(self, user_id: str, **kwargs):
        profile = self.get_or_create_profile(user_id)
        for k, v in kwargs.items():
            if hasattr(profile, k):
                setattr(profile, k, v)
        self._redis.hset(K_PROFILES, user_id, profile.model_dump_json())

    def get_profile(self, user_id: str) -> dict:
        return self.get_or_create_profile(user_id).model_dump()

    # ─── 系统提示词 ────────────────────────────────────

    def get_system_prompt(self, user_id: str) -> str:
        return self._redis.hget(K_SYS_PROMPTS, user_id) or ""

    def set_system_prompt(self, user_id: str, prompt: str):
        self._redis.hset(K_SYS_PROMPTS, user_id, prompt)

    # ─── 待回答状态 ────────────────────────────────────

    def set_pending_answer(self, user_id: str, session_id: str):
        self._redis.hset(K_PENDING, user_id, session_id)

    def get_pending_answer(self, user_id: str) -> str | None:
        return self._redis.hget(K_PENDING, user_id)

    def clear_pending_answer(self, user_id: str):
        self._redis.hdel(K_PENDING, user_id)

    # ─── Agent 提示词 ─────────────────────────────────

    def get_agent_prompts(self) -> dict[str, str]:
        return self._redis.hgetall(K_AGENT_PROMPTS)

    def get_agent_prompt(self, key: str) -> str:
        return self._redis.hget(K_AGENT_PROMPTS, key) or ""

    def _now(self) -> str:
        return datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")

    def update_agent_prompt(self, key: str, value: str, author: str = "admin"):
        """更新提示词并记录版本历史"""
        from trace_store import TraceStore
        history = self.get_prompt_history(key)
        if not history:
            # 首次：记录默认版本
            default_prompt = TraceStore.DEFAULT_PROMPTS.get(key, "")
            if default_prompt:
                history = [{
                    "version": 1,
                    "prompt": default_prompt,
                    "author": "system",
                    "timestamp": self._now(),
                    "message": "初始版本",
                }]
        next_version = (history[-1]["version"] + 1) if history else 1
        self._redis.hset(K_AGENT_PROMPTS, key, value)
        history.append({
            "version": next_version,
            "prompt": value,
            "author": author,
            "timestamp": self._now(),
            "message": f"v{next_version} 由 {author} 更新",
        })
        self._redis.hset(K_PROMPT_HISTORY, key, json.dumps(history, ensure_ascii=False))

    def get_prompt_history(self, key: str) -> list[dict]:
        raw = self._redis.hget(K_PROMPT_HISTORY, key)
        return json.loads(raw) if raw else []

    def rollback_prompt(self, key: str, version: int, author: str = "admin") -> bool:
        history = self.get_prompt_history(key)
        for h in history:
            if h["version"] == version:
                self._redis.hset(K_AGENT_PROMPTS, key, h["prompt"])
                next_version = history[-1]["version"] + 1
                history.append({
                    "version": next_version,
                    "prompt": h["prompt"],
                    "author": author,
                    "timestamp": self._now(),
                    "message": f"回滚至 v{version} (由 {author} 操作)",
                })
                self._redis.hset(K_PROMPT_HISTORY, key, json.dumps(history, ensure_ascii=False))
                return True
        return False

    # ─── 提示词优化建议 ──────────────────────────────

    def add_feedback_suggestion(self, user_id: str, conversation_id: str, context: list[dict], suggestion: str):
        record = {
            "type": "feedback",
            "user_id": user_id,
            "conversation_id": conversation_id,
            "suggestion": suggestion,
            "context_preview": context[-1]["content"][:100] if context else "",
            "timestamp": self._now(),
        }
        self._redis.rpush(K_FEEDBACK, json.dumps(record, ensure_ascii=False))
        self._redis.ltrim(K_FEEDBACK, -50, -1)  # 只保留最近 50 条

    def get_all_prompt_history(self) -> list[dict]:
        records = []
        # 管理员修改的提示词历史
        history_map = self._redis.hgetall(K_PROMPT_HISTORY)
        for key, raw in history_map.items():
            for h in json.loads(raw):
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
        for raw in self._redis.lrange(K_FEEDBACK, 0, -1):
            s = json.loads(raw)
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

    # ─── 统计 ──────────────────────────────────────────

    def count_sessions(self) -> int:
        return self._redis.hlen(K_SESSIONS)

    def count_profiles(self) -> int:
        return self._redis.hlen(K_PROFILES)

    def count_prompts(self) -> int:
        return self._redis.hlen(K_AGENT_PROMPTS)
