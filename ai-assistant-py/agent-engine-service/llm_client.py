"""LLM API 调用层 — 封装 DeepSeek / OpenAI 兼容接口，支持多 Key 池"""

import json
import asyncio
import logging
from typing import Any

import httpx

from config import settings
from key_pool import key_pool
from langfuse_tracer import langfuse_tracer

logger = logging.getLogger(__name__)


class LlmClient:
    """LLM API 调用封装，支持流式和非流式，自动重试 + 多 Key 池"""

    def __init__(self):
        self._base_url = settings.llm_base_url.rstrip("/")
        self._model = settings.llm_model
        self._http = httpx.AsyncClient(timeout=120)

    def _headers(self, api_key: str) -> dict:
        return {
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        }

    async def _acquire_key(self) -> str:
        """从 Key 池获取一个可用 Key"""
        return await key_pool.acquire()

    async def chat(self, messages: list[dict], tools: list | None = None,
                   system_prompt: str | None = None) -> str:
        """非流式聊天，返回 content 文本"""
        import time
        start_time = time.time()
        api_key = await self._acquire_key()
        msgs = []
        if system_prompt:
            msgs.append({"role": "system", "content": system_prompt})
        msgs.extend(messages)

        body = {
            "model": self._model,
            "messages": msgs,
            "temperature": 0.7,
        }
        if tools:
            body["tools"] = tools

        for attempt in range(3):
            try:
                resp = await self._http.post(
                    f"{self._base_url}/chat/completions",
                    headers=self._headers(api_key),
                    json=body,
                )
                if resp.status_code == 429:
                    logger.warning(f"Key {api_key[:12]}... 触发限流, 重试 {attempt+1}/3")
                    key_pool.release(api_key)
                    api_key = await self._acquire_key()
                    await asyncio.sleep((attempt + 1) * 2)
                    continue
                if resp.status_code in (401, 402, 403):
                    logger.error(f"Key {api_key[:12]}... 鉴权失败: {resp.status_code}")
                    raise RuntimeError(f"LLM API auth error: {resp.status_code}")
                if resp.status_code != 200:
                    logger.warning(f"LLM API 返回 {resp.status_code}, 重试 {attempt+1}/3")
                    await asyncio.sleep((attempt + 1) * 1)
                    continue
                data = resp.json()
                completion = data["choices"][0]["message"]["content"] or ""
                # LangFuse 追踪
                latency = int((time.time() - start_time) * 1000)
                usage = data.get("usage", {})
                langfuse_tracer.trace_llm_call(
                    name="chat",
                    model=self._model,
                    prompt=json.dumps(msgs, ensure_ascii=False),
                    completion=completion,
                    usage={
                        "prompt_tokens": usage.get("prompt_tokens", 0),
                        "completion_tokens": usage.get("completion_tokens", 0),
                        "total_tokens": usage.get("total_tokens", 0),
                    },
                    metadata={"latency_ms": latency, "api_key": api_key[:12] + "..."},
                )
                return completion
            except httpx.TimeoutException:
                logger.warning(f"LLM 请求超时, 重试 {attempt+1}/3")
                await asyncio.sleep((attempt + 1) * 1)
        raise RuntimeError("LLM API 请求失败，已重试 3 次")

    async def chat_with_tools(self, messages: list[dict], tools: list) -> dict:
        """支持 tool_calls 的调用，返回完整的 choice message"""
        api_key = await self._acquire_key()
        body = {
            "model": self._model,
            "messages": messages,
            "temperature": 0.7,
            "tools": tools,
        }
        for attempt in range(3):
            try:
                resp = await self._http.post(
                    f"{self._base_url}/chat/completions",
                    headers=self._headers(api_key),
                    json=body,
                )
                if resp.status_code == 429:
                    key_pool.release(api_key)
                    api_key = await self._acquire_key()
                    await asyncio.sleep((attempt + 1) * 2)
                    continue
                if resp.status_code in (401, 402, 403):
                    raise RuntimeError(f"LLM API auth error: {resp.status_code}")
                if resp.status_code != 200:
                    logger.warning(f"LLM API {resp.status_code}, 重试 {attempt+1}/3")
                    await asyncio.sleep((attempt + 1) * 1)
                    continue
                data = resp.json()
                return data["choices"][0]["message"]
            except httpx.TimeoutException:
                await asyncio.sleep((attempt + 1) * 1)
        raise RuntimeError("LLM API 请求失败")

    async def chat_simple(self, user_message: str) -> str:
        """只有 user 消息的快捷调用"""
        return await self.chat(messages=[{"role": "user", "content": user_message}])

    async def chat_simple_with_system(self, system_prompt: str, user_message: str) -> str:
        """带 system 消息的快捷调用"""
        return await self.chat(
            messages=[{"role": "user", "content": user_message}],
            system_prompt=system_prompt,
        )

    async def chat_stream(self, messages: list[dict], system_prompt: str | None = None):
        """流式聊天，逐段 yield 文本"""
        api_key = await self._acquire_key()
        msgs = []
        if system_prompt:
            msgs.append({"role": "system", "content": system_prompt})
        msgs.extend(messages)
        body = {
            "model": self._model,
            "messages": msgs,
            "temperature": 0.7,
            "stream": True,
        }
        async with self._http.stream(
            "POST", f"{self._base_url}/chat/completions",
            headers=self._headers(api_key), json=body,
        ) as resp:
            if resp.status_code == 429:
                key_pool.release(api_key)
                raise RuntimeError("LLM API 限流 (429)，请稍后重试")
            if resp.status_code != 200:
                raise RuntimeError(f"LLM API error: {resp.status_code}")
            async for line in resp.aiter_lines():
                if line.startswith("data: "):
                    content = line[6:]
                    if content == "[DONE]":
                        break
                    try:
                        chunk = json.loads(content)
                        delta = chunk["choices"][0].get("delta", {})
                        text = delta.get("content", "")
                        if text:
                            yield text
                    except json.JSONDecodeError:
                        continue
