"""多 API Key 池 + 请求队列 + RPM 追踪

参考 DeepSeek 网页版的多 Key 分发策略：
- 每个 Key 独立追踪 RPM（每分钟请求数）
- 选择负载最低的 Key 发送请求
- 所有 Key 都满时，请求进入队列等待
- 队列超时或满时，返回 429 让前端重试

Redis 共享模式（REDIS_URL 配置后）：
- RPM 计数存 Redis zset，多 worker 共享，避免超限
- Key 列表存 Redis，多 worker 增删一致
- 排队用轮询感知其他 worker 释放的 Key
- Redis 不可用时自动退回进程内模式（本地开发不受影响）
"""

import time
import json
import uuid
import asyncio
import logging
import threading
from collections import defaultdict

from config import settings

logger = logging.getLogger(__name__)

K_KEYS = "kpool:keys"
K_RPM = "kpool:rpm:"


class KeyPool:
    """多 API Key 池，带 RPM 限流和请求队列"""

    def __init__(self):
        self._keys = settings.api_keys
        self._rpm_limit = settings.key_pool_rpm
        self._queue_max = settings.queue_max_size
        self._queue_timeout = settings.queue_timeout_s

        # 进程内 RPM 计数器（内存模式）：key -> [(timestamp, count), ...]
        self._rpm_counters: dict[str, list[tuple[float, int]]] = defaultdict(list)
        self._lock = threading.Lock()

        # 等待队列：asyncio.Event 列表（仅进程内唤醒加速，Redis 模式靠轮询兜底）
        self._waiters: list[asyncio.Event] = []

        # Redis 共享模式
        self._redis = None
        self._use_redis = False
        try:
            if settings.redis_url:
                import redis as redis_lib
                r = redis_lib.Redis.from_url(
                    settings.redis_url,
                    decode_responses=True,
                    socket_connect_timeout=3,
                    socket_timeout=3,
                )
                r.ping()
                self._redis = r
                self._use_redis = True
                self._load_keys_from_redis()
                logger.info(f"KeyPool 使用 Redis 共享模式: {settings.redis_url}")
        except Exception as e:
            logger.warning(f"KeyPool Redis 不可用，使用进程内模式: {e}")

        logger.info(f"KeyPool 初始化: {len(self._keys)} 个 Key, RPM={self._rpm_limit}")

    def _load_keys_from_redis(self):
        """从 Redis 加载 Key 列表；不存在则把当前 env Key 写入"""
        raw = self._redis.get(K_KEYS)
        if raw:
            try:
                stored = json.loads(raw)
                if stored:
                    self._keys = stored
                    settings.llm_api_key = ",".join(stored)
                    return
            except Exception:
                pass
        self._redis.set(K_KEYS, json.dumps(self._keys, ensure_ascii=False))

    # ─── RPM 计数 ──────────────────────────────────────

    def _current_rpm(self, key: str) -> int:
        """计算某个 Key 当前窗口内的请求数"""
        if self._use_redis:
            rkey = K_RPM + key
            self._redis.zremrangebyscore(rkey, 0, time.time() - 60)
            return self._redis.zcard(rkey)
        now = time.time()
        window_start = now - 60
        with self._lock:
            counters = self._rpm_counters[key]
            # 清理过期记录
            self._rpm_counters[key] = [c for c in counters if c[0] > window_start]
            return sum(c[1] for c in self._rpm_counters[key])

    def _record(self, key: str):
        """记录一次请求"""
        if self._use_redis:
            rkey = K_RPM + key
            member = f"{time.time_ns()}-{uuid.uuid4().hex[:8]}"
            self._redis.zadd(rkey, {member: time.time()})
            self._redis.expire(rkey, 120)
            return
        now = time.time()
        with self._lock:
            self._rpm_counters[key].append((now, 1))

    # ─── Key 选择 ──────────────────────────────────────

    def _pick_key(self) -> str | None:
        """选择负载最低的 Key，所有 Key 都满返回 None"""
        best_key = None
        best_rpm = float("inf")
        for key in self._keys:
            rpm = self._current_rpm(key)
            if rpm < self._rpm_limit and rpm < best_rpm:
                best_rpm = rpm
                best_key = key
        if best_key:
            logger.debug(f"选中 Key: {best_key[:12]}..., RPM={best_rpm}/{self._rpm_limit}")
        return best_key

    # ─── 请求获取 ──────────────────────────────────────

    async def acquire(self) -> str:
        """获取一个可用的 Key，必要时排队等待"""
        # 快速路径：直接获取
        key = self._pick_key()
        if key:
            self._record(key)
            return key

        # 慢路径：排队
        if len(self._waiters) >= self._queue_max:
            raise RuntimeError(f"请求队列已满 ({self._queue_max})，请稍后重试")

        event = asyncio.Event()
        self._waiters.append(event)
        logger.info(f"排队等待: 队列长度={len(self._waiters)}/{self._queue_max}")

        key = None
        deadline = time.time() + self._queue_timeout
        try:
            while time.time() < deadline:
                key = self._pick_key()
                if key:
                    break
                # 等本地 release 唤醒；2 秒兜底轮询（Redis 模式可感知其他 worker 释放）
                try:
                    await asyncio.wait_for(event.wait(), timeout=2.0)
                except asyncio.TimeoutError:
                    pass
        finally:
            if event in self._waiters:
                self._waiters.remove(event)

        if key:
            self._record(key)
            return key
        raise RuntimeError(f"排队超时 ({self._queue_timeout}s)，请稍后重试")

    def release(self, key: str):
        """释放 Key（不减少计数，但唤醒下一个等待者）"""
        if self._waiters:
            waiter = self._waiters.pop(0)
            waiter.set()

    # ─── Key 管理（同步 Redis） ─────────────────────────

    def add_key(self, new_key: str) -> list[str]:
        """添加 API Key"""
        if new_key in self._keys:
            return self._keys
        self._keys = self._keys + [new_key]
        settings.llm_api_key = ",".join(self._keys)
        if self._use_redis:
            self._redis.set(K_KEYS, json.dumps(self._keys, ensure_ascii=False))
        return self._keys

    def remove_key(self, index: int) -> str | None:
        """删除指定索引的 API Key，返回被删除的 Key"""
        if index < 0 or index >= len(self._keys):
            return None
        removed = self._keys.pop(index)
        settings.llm_api_key = ",".join(self._keys)
        if self._use_redis:
            self._redis.set(K_KEYS, json.dumps(self._keys, ensure_ascii=False))
        return removed

    def stats(self) -> dict:
        """返回当前池状态"""
        return {
            "keys": len(self._keys),
            "rpm_limit": self._rpm_limit,
            "queue_length": len(self._waiters),
            "key_rpms": {k[:12]: self._current_rpm(k) for k in self._keys},
            "key_list": self._keys,
        }


# 全局单例
key_pool = KeyPool()
