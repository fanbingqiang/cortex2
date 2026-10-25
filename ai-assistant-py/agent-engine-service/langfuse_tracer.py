"""
LangFuse 外部追踪集成（可选）

启用方式：
  设置环境变量：
    LANGFUSE_ENABLED=true
    LANGFUSE_PUBLIC_KEY=pk-xxx
    LANGFUSE_SECRET_KEY=sk-xxx
    LANGFUSE_HOST=https://cloud.langfuse.com  （自托管则改为你的地址）

功能：
  - 自动追踪 LLM 调用（token 用量、延迟、模型）
  - 追踪教学会话的完整链路（出题 → 诊断 → 讲解 → 画像更新）
  - 在 LangFuse UI 中查看会话回放和评分

注意：
  1. 需要安装 langfuse SDK：pip install langfuse>=2.0.0
  2. 启用后，内置 trace_store 仍会继续工作，两者并行运行
  3. LangFuse 提供持久化存储，内置 trace_store 是内存存储（重启丢失）
"""

import logging
from typing import Optional

logger = logging.getLogger(__name__)

# 尝试导入 langfuse，如果未安装则优雅降级
try:
    from langfuse import Langfuse
    HAS_LANGFUSE = True
except ImportError:
    HAS_LANGFUSE = False
    Langfuse = None


class LangFuseTracer:
    """LangFuse 追踪器（单例）"""

    _instance: Optional["LangFuseTracer"] = None

    def __init__(self):
        from config import settings

        self.enabled = settings.langfuse_enabled and HAS_LANGFUSE
        self.client = None

        if not HAS_LANGFUSE and settings.langfuse_enabled:
            logger.warning(
                "LangFuse 已启用但未安装 SDK。请运行: pip install langfuse>=2.0.0"
            )

        if self.enabled:
            try:
                self.client = Langfuse(
                    public_key=settings.langfuse_public_key,
                    secret_key=settings.langfuse_secret_key,
                    host=settings.langfuse_host,
                )
                logger.info(f"LangFuse 追踪已启用: {settings.langfuse_host}")
            except Exception as e:
                logger.error(f"LangFuse 初始化失败: {e}")
                self.enabled = False

    @classmethod
    def get(cls) -> "LangFuseTracer":
        if cls._instance is None:
            cls._instance = cls()
        return cls._instance

    def trace_llm_call(
        self,
        name: str,
        model: str,
        prompt: str,
        completion: str,
        usage: dict = None,
        metadata: dict = None,
    ):
        """记录一次 LLM 调用"""
        if not self.enabled or not self.client:
            return

        try:
            trace = self.client.trace(name=name, metadata=metadata)
            trace.generation(
                name=f"{name}-gen",
                model=model,
                input=prompt,
                output=completion,
                usage=usage,
            )
        except Exception as e:
            logger.warning(f"LangFuse 记录 LLM 调用失败: {e}")

    def trace_session(
        self,
        session_id: str,
        user_id: str,
        name: str,
        metadata: dict = None,
    ):
        """创建教学会话追踪"""
        if not self.enabled or not self.client:
            return None

        try:
            trace = self.client.trace(
                id=session_id,
                name=name,
                user_id=user_id,
                metadata=metadata,
            )
            return trace
        except Exception as e:
            logger.warning(f"LangFuse 创建会话追踪失败: {e}")
            return None

    def trace_step(
        self,
        trace,
        step_name: str,
        input_data: str = None,
        output_data: str = None,
        metadata: dict = None,
    ):
        """记录会话中的步骤"""
        if not self.enabled or not self.client or trace is None:
            return

        try:
            trace.span(
                name=step_name,
                input=input_data,
                output=output_data,
                metadata=metadata,
            )
        except Exception as e:
            logger.warning(f"LangFuse 记录步骤失败: {e}")

    def flush(self):
        """确保所有数据已发送"""
        if self.enabled and self.client:
            try:
                self.client.flush()
            except Exception:
                pass


# 全局单例
langfuse_tracer = LangFuseTracer.get()