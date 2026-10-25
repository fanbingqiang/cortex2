# Agent Engine: Java 版 → Python 重写记录

## 概述

将 Java 多 Agent 任务编排引擎从 Spring Boot 拆出，用 Python 独立实现（FastAPI）。支持：
- 用户需求 → LLM 分解为 DAG 任务图
- 每个子任务匹配最合适的 Agent
- 按依赖拓扑逐层并行执行
- 失败自动重规划

## 技术选型

| 组件 | 选择 |
|------|------|
| Web 框架 | FastAPI |
| ASGI | uvicorn |
| 数据库 ORM | SQLAlchemy 2.0 + aiomysql |
| LLM 客户端 | httpx (异步, 支持 SSE) |
| 数据校验 | Pydantic v2 |

## 项目结构

agent-engine-service/
├── main.py            # FastAPI 入口 + 路由
├── models.py          # 所有 Pydantic 数据模型
├── llm_client.py      # LLM API 调用封装
├── agent_registry.py  # Agent 注册中心
├── task_decomposer.py # 任务分解器
├── task_executor.py   # DAG 任务执行引擎
├── event_service.py   # SSE 事件推送
├── memory_service.py  # 用户画像管理
├── database.py        # SQLAlchemy 异步引擎
├── config.py          # 配置管理
└── requirements.txt   # 依赖

## 数据库表（与 Java 共用）

| 表名 | 用途 |
|------|------|
| agent_metadata | Agent 元数据（含 capabilities JSON） |
| task | 任务（含 task_graph JSON） |
| task_execution_log | 执行日志 |
| user_profile | 用户画像 |
| mistake_record | 错误记录 |

## API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/task/create | 创建任务（分解为 DAG，存 DB） |
| POST | /api/task/execute | 执行已有任务 |
| POST | /api/task/run | 一步创建+执行 |
| GET | /api/task/stream/{task_id} | SSE 事件流 |
| GET | /api/task/{task_id} | 获取任务详情 |
| GET | /api/agents | 列出所有 Agent |
| GET | /api/profile/{user_id} | 获取用户画像 |
| POST | /api/profile/{user_id}/tags | 添加性格标签 |
| POST | /api/profile/{user_id}/habits | 添加工作习惯 |
| POST | /api/profile/{user_id}/mistakes | 添加错误记录 |
| GET | /api/health | 健康检查 |

## 架构要点

- Task ID: uuid4().hex[:16]
- JSON 字段: capabilities / personality_tags / task_graph 存为 JSON 字符串
- 并行执行: asyncio.gather 同层节点
- 重试: 401/402/403/429 直接抛，其他重试 2 次
- SSE: FastAPI StreamingResponse + asyncio.Queue