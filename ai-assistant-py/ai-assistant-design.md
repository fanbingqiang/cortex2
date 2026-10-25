# AI 导师服务设计文档

## 1. 架构总览

```
┌─────────────────────────────────────────────────────────┐
│                   前端 (Vue 3)                           │
│  ┌──────────────────────┐ ┌────────────────┐           │
│  │  代码编辑器 (CodeEditor) │ │  聊天面板       │           │
│  │  （AI 写代码+中文注释）  │ │  (GlobalChat)   │           │
│  └──────────┬───────────┘ └───────┬────────┘           │
│             │                     │                     │
│    WebSocket (ws://localhost:8000/ws/ai)                │
└─────────────┼─────────────────────┼─────────────────────┘
              │                     │
       ┌──────┴─────────────────────┴──────┐
       │            Spring Boot (8080)       │
       │  ┌──────────────┐  ┌─────────────┐ │
       │  │  业务 API      │  │  事件通知     │ │
       │  │  /api/lab/*   │  │  POST /api/  │ │
       │  │  /api/auth/*  │  │  ai/event    │ │
       │  └──────────────┘  └──────┬──────┘ │
       └──────────────────────────┼─────────┘
                                  │ HTTP (localhost)
                                  ▼
┌─────────────────────────────────────────────────────────┐
│           Python AI Service (端口 8000)                   │
│                                                         │
│  ┌───────────────────────────────────────────────────┐  │
│  │  Agent 核心 (LangGraph)                            │  │
│  │  ┌──────┐  ┌──────┐  ┌──────┐  ┌──────┐         │  │
│  │  │事件感知│→│LLM决策│→│工具执行│→│结果推送│         │  │
│  │  └──────┘  └──────┘  └──────┘  └──────┘         │  │
│  │   Tools: 写代码(+注释)/搜题/跳转/执行/推荐/画像     │  │
│  └───────────────────────────────────────────────────┘  │
│                                                         │
│  ┌──────────────────┐  ┌───────────────────────────┐   │
│  │  教学状态管理器     │  │  WebSocket 连接管理器      │   │
│  │  (内存 dict)      │  │  (连接池 + 广播)           │   │
│  └──────────────────┘  └───────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

### 通信方式

| 方向 | 协议 | 用途 |
|------|------|------|
| Python → 前端 | WebSocket | AI 主动推送（黑板内容、消息、页面跳转指令） |
| 前端 → Python | WebSocket | 用户主动调 AI（聊天、请求帮助） |
| Spring Boot → Python | HTTP | 事件通知（用户答题、运行代码、犯错） |
| Python → Spring Boot | HTTP | 调 Java API 获取数据（题库、知识树、用户画像） |

### 不删现有代码

- 现有 `AssistantService.java`、`LlmClient.java` **保留不动**
- Python 服务新开 `/api/ai/*` 端点，前端用新 WebSocket 连 Python
- 现有前端 GlobalChat 组件保留，但 AI 导师走新的 WebSocket 连接

---

## 2. 注释教学机制（替代黑板）

### 核心原则

AI **不单独占用代码编辑器**，而是跟用户**共用代码区**。AI 的教学行为是：
- 在代码区**写入陷阱代码**（可运行）
- 在代码中以 **中文注释形式写提示/问题**，不写答案
- 这些注释**不落库、不缓存**，刷新即消失
- 教学风格：**苏格拉底式** — 只提问不回答，引导用户自己发现

### 工作方式

AI 收到事件后，通过 `write_code_with_hints` 工具**覆盖代码区内容**：

```
// 用户原来的代码（或被 AI 覆盖后的新代码）

// ──────── 思考一下 ────────
// String a = "hello";
// String b = "hello";
// 问：a == b 的结果是 true 还是 false？为什么？
// ─────────────────────────

public class Test {
    public static void main(String[] args) {
        String a = "hello";
        String b = "hello";
        String c = new String("hello");

        System.out.println(a == b);  // ??? 想一想再运行
        System.out.println(a == c);  // ???
    }
}

// ❓ 如果 a 和 c 指向不同的内存区域，用 equals 呢？
// 🔗 做这道题检验一下：intern 方法的特点
```

注释即教学：
- `// ──── 思考一下 ────` 引导注意
- `// 问：...` 苏格拉底式提问
- `// ??? 想一想再运行` 制造认知冲突
- `// ❓ 如果...` 延伸思考
- `// 🔗 做这道题...` 行动召唤

### 教学阶段流转

```
INIT（AI 决定教什么）
  → TRAP（写陷阱代码 + 中文注释提问）
    → OBSERVE（等待用户运行代码）
      → GUIDE（更新注释，加引导性提示）
        → SOLVE（用户答对，注释改为小结+推荐）
          → VERIFY（推荐相似题检验）
            → NEXT（根据画像推荐下一知识点）
```

### AI 写代码的约束

AI 通过 `write_code_with_hints` 工具操作代码区，工具定义：

```python
{
    "name": "write_code_with_hints",
    "description": "在代码编辑器中写入代码和中文注释提示（苏格拉底式，只提问不写答案）。"
                   "会覆盖编辑器当前内容。注释必须用 // 开头。",
    "parameters": {
        "type": "object",
        "properties": {
            "code": {
                "type": "string",
                "description": "完整的 Java 代码（包含陷阱代码 + 中文注释提示）"
            },
            "topic": {
                "type": "string",
                "description": "当前教学主题"
            }
        },
        "required": ["code", "topic"]
    }
}
```

> **注释不落库**：Python 服务维护的教学状态只存内存（每个 user_id 一个 dict）。
> 用户刷新页面 → 代码区恢复初始状态 → AI 重新开始教学。

---

## 3. Agent 设计（LangGraph）

### 状态定义

```python
class AgentState(TypedDict):
    user_id: str
    conversation: list[dict]         # 对话历史
    blackboard: Blackboard           # 黑板当前内容
    user_portrait: dict              # 用户画像缓存
    pending_event: dict | None       # 待处理的用户事件
    active_tools: list[str]          # 当前可用的工具
    last_action: str                 # AI 上一步做了什么
```

### 节点图

```
                      ┌──────────────┐
                      │  事件入口节点   │
                      │ (接收/过滤事件) │
                      └──────┬───────┘
                             │
                             ▼
                      ┌──────────────┐
                      │  分析感知节点   │ ◄── 调 Java API 获取：
                      │              │     - 用户画像
                      │  "用户现在    │     - 当前题目/代码
                      │   是什么状态?"│     - 历史错题
                      └──────┬───────┘
                             │
                             ▼
                      ┌──────────────┐
                      │  LLM 决策节点  │ ◄── DeepSeek 推理：
                      │              │     - 应不应该介入？
                      │  "下一步做什么"│     - 写黑板？推题？跳转？
                      └──────┬───────┘
                             │
                    ┌────────┴────────┐
                    │                 │
                    ▼                 ▼
            ┌────────────┐   ┌──────────────┐
            │ 工具执行节点  │   │  对话回复节点   │
            │            │   │              │
            │ 调 Java API │   │ 直接回消息     │
            │ 写黑板      │   │ 不操作黑板     │
            │ 推题/跳转   │   │              │
            └──────┬─────┘   └──────┬───────┘
                   │                │
                   └───────┬────────┘
                           ▼
                    ┌──────────────┐
                    │  输出推送节点   │
                    │              │
                    │ WebSocket 推  │
                    │ 前端          │
                    └──────────────┘
```

### 决策逻辑示例（伪代码）

```
1. 事件入口：
   收到事件 = { type: "answer", correct: false, questionId: 5 }

2. 分析感知：
   调 GET /api/lab/questions/5?userId=demo001 → 获取题目详情
   调 GET /api/auth/userinfo?userId=demo001 → 获取画像
   发现：String 相关题错了3次，掌握率 30%

3. LLM 决策（Prompt 简版）：
   输入：用户答错了题 #5，String 相关错误累计3次，画像显示薄弱
   输出：{
     action: "write_blackboard",
     topic: "String常量池",
     phase: "TRAP",
     content: "写出对比代码 + 关键问题",
     send_message: "看看黑板上的代码，你觉得结果是什么？"
   }

4. 工具执行：
   - 调 Java API 获取 String 相关题列表
   - 写黑板内容
   - 记录教学状态

5. 输出推送：
   WebSocket → 前端：
   { type: "blackboard_update", content: "..." }
   { type: "message", content: "看看黑板上的代码..." }
```

---

## 4. 工具定义（Tools）

所有工具通过 Python 调用 Spring Boot REST API 执行。

### 教学工具（核心）

| 工具名 | 功能 | 说明 |
|--------|------|------|
| `write_code_with_hints` | 写代码到编辑器 | 陷阱代码 + `//` 中文注释提示，覆盖当前内容 |
| `recommend_question` | 推荐相似题目 | 根据分类推荐，AI 引导用户去题库做 |
| `switch_tab` | 跳转页面 | 跳到题库/练习/学习中心等页面 |
| `load_question_to_editor` | 加载题目到编辑器 | 从题库加载一道题的代码到编辑器 |

### 数据获取工具

| 工具名 | 功能 | 调用的 Java API |
|--------|------|----------------|
| `search_questions` | 搜题库 | `GET /api/lab/questions/search?keyword=` |
| `get_question_detail` | 题目详情 | `GET /api/lab/questions/{id}?userId=` |
| `get_knowledge_tree` | 知识树 | `GET /api/lab/knowledge-tree` |
| `get_review_list` | 待复习 | `GET /api/lab/questions/review/due?userId=` |
| `get_user_profile` | 用户画像 | `GET /api/auth/userinfo?userId=` |
| `get_learning_report` | 学习报告 | `GET /api/auth/report/ai-narrative?userId=` |
| `execute_code` | 运行代码 | `POST /api/lab/execute` |

### Tool Schema 示例

```python
tools = [
    {
        "name": "write_code_with_hints",
        "description": "在代码编辑器中写入代码和中文注释提示。"
                       "苏格拉底式教学，只写陷阱代码+提问，不写答案。"
                       "注释用 // 开头，覆盖编辑器当前内容。",
        "parameters": {
            "type": "object",
            "properties": {
                "code": {
                    "type": "string",
                    "description": "完整的 Java 代码（含陷阱 + // 中文注释提示）"
                },
                "topic": {
                    "type": "string",
                    "description": "当前教学主题"
                }
            },
            "required": ["code", "topic"]
        }
    },
    {
        "name": "switch_tab",
        "description": "跳转到指定页面",
        "parameters": {
            "type": "object",
            "properties": {
                "tab": {
                    "type": "string",
                    "enum": ["practice", "questions", "cards", "progress", "community"]
                }
            },
            "required": ["tab"]
        }
    },
    {
        "name": "recommend_question",
        "description": "根据知识点推荐一道相似题给用户检验学习效果",
        "parameters": {
            "type": "object",
            "properties": {
                "category": {
                    "type": "string",
                    "description": "知识点分类"
                },
                "exclude_question_id": {
                    "type": "integer",
                    "description": "排除当前题目ID"
                }
            },
            "required": ["category"]
        }
    },
    {
        "name": "search_questions",
        "description": "搜索题库中的题目",
        "parameters": {
            "type": "object",
            "properties": {
                "keyword": {"type": "string", "description": "关键词"}
            },
            "required": ["keyword"]
        }
    },
    {
        "name": "get_user_profile",
        "description": "获取用户学习画像（答题统计、薄弱领域、学习目标等）",
        "parameters": {"type": "object", "properties": {}}
    }
]
```

---

## 5. 事件系统

### 事件类型

Spring Boot 在用户操作的关键节点发 HTTP 通知到 Python：

```json
{
  "type": "question_answered",
  "userId": "demo001",
  "data": {
    "questionId": 5,
    "correct": false,
    "category": "String",
    "title": "String常量池"
  }
}
```

| 事件类型 | 触发时机 | 数据 |
|---------|---------|------|
| `question_answered` | 用户答完一题 | questionId, correct, category |
| `code_executed` | 用户运行了代码 | code, output, hasError |
| `node_selected` | 用户选了知识树节点 | nodeId, nodeName |
| `code_modified` | 用户改了代码 | diff 摘要 |
| `review_completed` | 用户完成复习 | questionId, stillMastered |
| `user_idle` | 用户停顿/切换页面 | currentPage |

### AI 主动推送

WebSocket 消息格式（Python → 前端）：

```json
{
  "type": "code_update",
  "data": {
    "code": "// ──── 思考一下 ────\n// String a = \"hello\"...\n// 问：a == b 的结果？\n// ─────────────────\n\npublic class Test {\n    ...\n}",
    "topic": "String常量池陷阱"
  }
}

{
  "type": "message",
  "data": {
    "content": "看看编辑器里的代码，你觉得输出是什么？"
  }
}

{
  "type": "action",
  "data": {
    "action": {
      "type": "switchTab",
      "payload": "questions"
    }
  }
}

{
  "type": "suggestions",
  "data": {
    "suggestions": ["运行看看结果", "换个题目"]
  }
}
```

---

## 6. 学习闭环流程完整示例

```
用户正在做 String 相关题
    │
    ▼
用户答错 "String s = new String('hello') 创建了几个对象"
    │
    ▼
Spring Boot → POST /api/ai/event → Python 收到 { type: "question_answered", correct: false }
    │
    ▼
感知节点：查用户画像 → String 错误累计3次，掌握率低于40%
    │
    ▼
推理节点（DeepSeek）：
  "用户对 String 常量池理解不够，需要从基础讲起"
  决策：write_code_with_hints + send_message
    │
    ▼
工具执行：
  write_code_with_hints(code="带中文注释的陷阱代码", topic="String常量池")
    │
    ▼
WebSocket 推送：
  → code_update (代码区展示陷阱代码 + 中文注释提问)
  → message ("看看编辑器里的代码，想想 a == b 的结果")
    │
    ▼
用户看代码，看到 // 注释里的提问，思考后运行验证
    │
    ▼
用户运行代码看结果，回复 AI "我懂了，一个在常量池一个在堆"
    │
    ▼
AI 确认理解正确 → 推荐相似题检验：
  recommend_question(category="String", exclude=5)
  → 发消息 "做做这道题检验一下掌握得怎么样"
    │
    ▼
用户去题库做推荐题，做对了
    │
    ▼
AI 查画像 → String 掌握率上升到60%
  决策：next_topic → 检查下一个薄弱领域"JVM调优"
  → 发消息 "String 掌握不错了，接下来我们看看 JVM 调优？"
```

---

## 7. 项目结构

```
ai-assistant/
├── main.py                    # 入口：FastAPI 应用
├── agent/
│   ├── graph.py               # LangGraph 图定义
│   ├── nodes.py               # 各节点逻辑
│   ├── state.py               # Agent 状态定义
│   └── prompts.py             # LLM Prompt 模板
├── tools/
│   ├── platform.py            # 平台操作工具（调 Java API）
│   ├── blackboard.py          # 黑板工具
│   └── teaching.py            # 教学工具
├── blackboard/
│   ├── manager.py             # 黑板状态管理
│   └── templates.py           # Markdown 模板
├── bridge/
│   ├── java_api.py            # Spring Boot REST 客户端
│   └── ws_handler.py          # WebSocket 处理器
├── models/
│   ├── schemas.py             # Pydantic 模型
│   └── events.py              # 事件类型定义
├── requirements.txt
└── Dockerfile
```

---

## 8. 关键依赖

```
fastapi
uvicorn[standard]
websockets
httpx                    # 调 Java API
langgraph                # Agent 图
langchain-core           # Tool 定义
pydantic
```

---

## 9. 部署

同机不同端口，Python 跑 8000 端口：

```bash
# 开发
cd ai-assistant
pip install -r requirements.txt
uvicorn main:app --port 8000 --reload

# 生产（跟 Spring Boot 同 Docker Compose）
docker-compose up -d    # 同时启动 java:8080 + python:8000
```

前端 Vite 配置加代理：

```ts
// vite.config.ts
proxy: {
  '/api/ai': 'http://localhost:8000',     // AI 服务
  '/ws/ai': { target: 'ws://localhost:8000', ws: true } // WebSocket
}
```

---

## 10. AI 主动模式开关

### 设计

在现有通知设置面板加一个开关：

```
┌──────────────────────────────┐
│  AI 导师主动模式               │
│  [■ 已开启]  开启后 AI 会主动  │
│              观察你的学习状态   │
│              并给出教学建议     │
│  [立即关闭]                   │
└──────────────────────────────┘
```

### 存储

- 存在 `user_notification_config` 表，新增字段 `ai_active_mode`（tinyint, 默认 1）
- Python 服务每次决策前先查用户是否开启了主动模式
- 关闭后 AI 只回复不主动（用户发消息才回复）
- 开关实时生效，不需要重启

### 前端

- 在通知设置弹窗（MyPage.vue 已有）加一行开关
- 调 `PUT /api/auth/notification/config` 保存配置

| 现有组件 | 变化 |
|---------|------|
| `AssistantService.java` | **保留不动**。Python 服务不依赖它 |
| `LlmClient.java` | **保留不动**。`ReportNarrativeService` 等仍用 Java LLM |
| `GlobalChat.vue` | **保留不动**。新 AI 走 WebSocket，不替代现有聊天 |
| 前端所有页面 | 加一个 WebSocket 连接 `ws://localhost:8000/ws/ai` 接收 AI 主动事件 |
| CodeEditor | 增加 `setCode(code)` 方法供外部调用。AI 通过 WebSocket 发 `code_update` 事件写入代码+注释 |
| 通知设置面板 | 加一行 "AI 导师主动模式" 开关 |
