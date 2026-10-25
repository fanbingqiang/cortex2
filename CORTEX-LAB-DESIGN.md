# Cortex Lab — AI 导演的代码调优剧场

## 一、产品定位

**将枯燥的八股文记忆，转化为"踩坑→挣扎→顿悟"的实战训练系统。**

用户不再是背诵知识点，而是在 AI 精心布置的陷阱代码中扮演"侦探"——阅读代码、运行、看到意外结果、被追问引导、最终自己悟出原理。这种"先犯错再理解"的过程形成强烈的情绪记忆。

## 二、核心交互流程

```
用户选择/导入知识点
       ↓
AI 生成包含陷阱的 Java 代码（表面正常，暗藏对应知识点的坑）
       ↓
用户进入 Lab 页面
  ┌─────────────┬──────────────────┐
  │ 代码编辑器   │  AI 侦探对话     │
  │ (Monaco)    │  (追问式引导)    │
  ├─────────────┴──────────────────┤
  │      终端输出面板               │
  └────────────────────────────────┘
       ↓
用户阅读代码 → 运行 → 看到意外结果 😱
       ↓
AI 侦探介入（不直接揭晓答案）：
  "你注意到第二次调用的结果有什么奇怪的地方吗？"
  "试试把第 3 行改一下再运行？"
  "回忆一下 Java 中关于 equals 的知识..."
       ↓
用户不断尝试 → 自己悟出原理 → 💡 顿悟！
       ↓
AI 总结知识点，记录学习进度
```

## 三、技术架构

```
┌─────────────────────────────────────────┐
│         前端 (静态 HTML 单文件)           │
│  ┌───────────┐ ┌──────────┐ ┌────────┐ │
│  │ Monaco编辑器│ │ AI对话面板│ │ 终端面板│ │
│  └───────────┘ └──────────┘ └────────┘ │
│         蓝白简约风 · Tailwind CSS CDN     │
└─────────────────────────────────────────┘
                 │ REST API
                 ▼
┌─────────────────────────────────────────┐
│       后端 (Spring Boot 3.2 + Java 21)  │
│  ┌──────────┐ ┌──────────┐ ┌─────────┐ │
│  │ 场景引擎  │ │ AI对话引擎│ │ Java沙箱│ │
│  │(陷阱生成) │ │(引导追问)│ │(子进程)  │ │
│  └──────────┘ └──────────┘ └─────────┘ │
│         LLM API (DeepSeek)              │
│         数据存储: H2 File DB             │
└─────────────────────────────────────────┘
```

## 四、技术选型

| 层面 | 技术 | MVP 理由 |
|------|------|---------|
| 后端框架 | Spring Boot 3.2 | 复用现有基础设施 |
| 数据库 | H2 File DB | 零配置，嵌入式 |
| LLM | DeepSeek API | 低成本，128K 上下文 |
| 前端 | 静态 HTML + Monaco CDN | 零构建，直接打开 |
| 样式 | Tailwind CSS CDN | 快速蓝白简约风 |
| Java 沙箱 | ProcessBuilder | 子进程隔离执行 |

## 五、数据库设计（新增表）

```sql
-- 场景表：存储知识点和对应的陷阱代码
CREATE TABLE lab_scenario (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    knowledge_point VARCHAR(200) NOT NULL,   -- 知识点名称
    category VARCHAR(100),                    -- 分类
    trap_code TEXT NOT NULL,                  -- 带陷阱的初始代码
    expected_pitfall VARCHAR(500),            -- 预期的坑是什么
    correct_explanation TEXT,                 -- 正确解释（顿悟后展示）
    hints TEXT,                               -- 提示列表（JSON数组）
    difficulty INT DEFAULT 1,                 -- 难度 1-5
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 学习记录表
CREATE TABLE lab_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(50) NOT NULL UNIQUE,
    scenario_id BIGINT,
    user_id VARCHAR(50),
    status VARCHAR(20) DEFAULT 'IN_PROGRESS', -- IN_PROGRESS, ENLIGHTENED, GAVE_UP
    attempts INT DEFAULT 0,                   -- 尝试次数
    chat_history TEXT,                         -- 对话历史 JSON
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

## 六、API 设计

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/lab/scenarios | 获取所有场景列表 |
| GET | /api/lab/scenarios/{id} | 获取场景详情（含陷阱代码） |
| POST | /api/lab/scenarios/generate | AI 根据知识点生成场景 |
| POST | /api/lab/execute | 执行用户修改后的 Java 代码 |
| POST | /api/lab/session/start | 开始学习会话 |
| POST | /api/lab/chat | 发送消息给 AI 侦探 |
| GET | /api/lab/session/{id} | 获取会话状态 |

## 七、AI 侦探对话策略

系统扮演"苏格拉底式导师"：

1. **不直接给答案**：绝不说"这是因为 XXX"
2. **引导观察**："你比较一下两次输出的结果，有什么发现？"
3. **给出提示**："试试把参数顺序换一下再运行？"
4. **举例类比**："这和生活中排队插队的场景类似，你觉得问题出在哪？"
5. **最终确认**：用户悟出后，系统给出标准解释并确认

## 八、内置场景（MVP）

### 场景 1：Integer 缓存陷阱
- **知识点**：Integer 缓存池 -128~127
- **陷阱代码**：用 == 比较两个值相同的 Integer
- **意外结果**：小数值为 true，大数值为 false

### 场景 2：equals 与 == 混淆
- **知识点**：引用比较 vs 值比较
- **陷阱代码**：用 == 比较两个内容相同的 String
- **意外结果**：直接 new 的 String 用 == 返回 false

### 场景 3：可变对象作方法参数
- **知识点**：Java 的值传递 + 对象引用
- **陷阱代码**：方法内修改传入的 List 对象
- **意外结果**：方法外部的 List 也被修改了

## 九、MVP 限制 & 后续规划

| MVP 限制 | 后续计划 |
|----------|---------|
| 前端为单 HTML 文件 | 迁移到 React + TypeScript |
| 无 Docker 隔离 | Docker 容器沙箱 |
| 预置场景 | AI 动态生成场景 |
| 简单对话逻辑 | 基于 LLM 的智能对话 |
| 本地 H2 数据库 | MySQL + 用户系统 |

## 十、启动方式

```bash
# 1. 配置 API Key
set DEEPSEEK_API_KEY=your-key

# 2. 启动后端
mvn spring-boot:run
# 或
start.bat

# 3. 访问 Lab
http://localhost:8080/lab/index.html
```
