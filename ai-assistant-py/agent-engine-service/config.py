"""配置管理 — 从环境变量读取"""

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    # 多 API Key 支持：逗号分隔，如 "sk-xxx,sk-yyy,sk-zzz"
    llm_api_key: str = "sk-8e70d5c6be1e4c18b1f6731e39cd0dd9"
    llm_base_url: str = "https://api.deepseek.com"
    llm_model: str = "deepseek-chat"
    service_host: str = "0.0.0.0"
    service_port: int = 8001

    # 限流配置
    key_pool_rpm: int = 50       # 每个 Key 每分钟最大请求数
    queue_max_size: int = 200    # 队列最大长度
    queue_timeout_s: int = 60    # 队列超时秒数

    # 压缩配置
    compress_threshold: int = 10  # 历史消息超过此数触发压缩
    compress_keep_recent: int = 5 # 压缩后保留最近 N 条

    # Redis 共享存储（可选）：配置后会话/画像/Key池计数跨 worker 共享
    # 为空或连不上时自动退回内存模式（本地开发不受影响）
    redis_url: str = ""
    workers: int = 1  # uvicorn worker 数（Docker 中通过 CMD 设为 2 利用多核）

    # LangFuse 外部追踪（可选）
    langfuse_enabled: bool = False
    langfuse_public_key: str = ""
    langfuse_secret_key: str = ""
    langfuse_host: str = "https://cloud.langfuse.com"

    @property
    def api_keys(self) -> list[str]:
        """解析逗号分隔的多个 API Key"""
        return [k.strip() for k in self.llm_api_key.split(",") if k.strip()]

    model_config = {"env_prefix": "", "case_sensitive": False}


settings = Settings()